package com.davidsm.milkfilter

import android.content.ContentResolver
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.Matrix
import com.davidsm.milkfilter.gl.CodecInputSurface
import com.davidsm.milkfilter.gl.DecoderOutputSurface
import com.davidsm.milkfilter.gl.VideoFilterProgram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class VideoFilterRenderer(
    private val context: Context,
    private val resolver: ContentResolver
) {
    /**
     * Fully GPU pipeline: decode (MediaCodec) -> SurfaceTexture -> filter shader ([VideoFilterProgram],
     * the SAME shader as the live preview) -> encode (H.264 via GL input surface) -> mux (MP4) with
     * the original audio copied through. No per-frame seeks and no CPU per-pixel work, so it runs
     * close to real time and the export looks exactly like the preview.
     *
     * Returns true on success. onProgress reports 0..100. Throws on failure (except when
     * cancelled) so the caller gets the real reason instead of a bare `false` -- e.g. an
     * unsupported source video codec raises [UnsupportedOperationException] with the mime type.
     */
    suspend fun render(
        uri: Uri,
        info: VideoInfo,
        filter: String,
        dither: FilterProcessor.DitherState,
        milk: FilterProcessor.MilkState,
        outFile: File,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.Default) {
        // MediaCodec decodes frames in their coded orientation (rotation metadata is NOT applied),
        // so we rotate in the shader and size the output to the upright dimensions.
        val swap = info.rotationDeg == 90 || info.rotationDeg == 270
        val uprightW = if (swap) info.height else info.width
        val uprightH = if (swap) info.width else info.height
        val capped = fitWithinCap(uprightW, uprightH, 720)
        val outW = (capped.first / 2) * 2      // H.264 needs even dimensions
        val outH = (capped.second / 2) * 2
        val durUs = info.durationMs * 1000
        val fps = info.fps.coerceIn(1, 60)

        var encoder: MediaCodec? = null
        var inputSurface: CodecInputSurface? = null
        var program: VideoFilterProgram? = null
        var decoder: MediaCodec? = null
        var decoderOutput: DecoderOutputSurface? = null
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var audio: AudioTrack? = null
        var transcodedAudio: TranscodedAudio? = null

        try {
            // --- Video extractor / decoder source track ---
            val ex = MediaExtractor()
            extractor = ex
            resolver.openFileDescriptor(uri, "r")!!.use { pfd -> ex.setDataSource(pfd.fileDescriptor) }
            var videoTrackIdx = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    videoTrackIdx = i; inFormat = fmt; break
                }
            }
            if (videoTrackIdx < 0 || inFormat == null) return@withContext false
            ex.selectTrack(videoTrackIdx)
            val inMime = inFormat.getString(MediaFormat.KEY_MIME)!!
            // Fail fast with a clear reason instead of letting MediaCodec.createDecoderByType()
            // below throw an opaque error: some sources (e.g. AV1 video from yt-dlp/WebM
            // downloads) have no decoder on many devices.
            if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(inFormat) == null) {
                throw UnsupportedOperationException("no decoder available for $inMime")
            }

            // --- Encoder (H.264) + its GL context ---
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(outW, outH, fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            inputSurface = CodecInputSurface(surface)
            encoder.start()

            inputSurface.makeCurrent()
            val prog = VideoFilterProgram().also { it.init() }
            program = prog
            // Use outW/outH (the actual render-target size draw() below uses for glViewport),
            // not uprightW/uprightH: fitWithinCap rounds width and height independently, so the
            // two can end up with a very slightly different aspect ratio. Sizing the pixelation
            // grid off the pre-rounding dimensions then stretches each "square" block to fit the
            // differently-proportioned viewport, rendering as visibly rectangular pixels.
            prog.setParams(filter, dither, milk, outW, outH)

            // --- Decoder renders into an OES texture on the encoder's GL context ---
            val decOut = DecoderOutputSurface()
            decoderOutput = decOut
            decoder = MediaCodec.createDecoderByType(inMime)
            decoder.configure(inFormat, decOut.surface, null, 0)
            decoder.start()

            // Rotate sampled tex coords so coded frames come out upright.
            val rot = FloatArray(16)
            Matrix.setIdentityM(rot, 0)
            if (info.rotationDeg != 0) {
                Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
                Matrix.rotateM(rot, 0, info.rotationDeg.toFloat(), 0f, 0f, 1f)
                Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
            }
            val stMatrix = FloatArray(16)
            val combined = FloatArray(16)

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // --- Audio ---
            // Try a direct copy first (fast, no quality loss) -- most sources are already
            // AAC. If the MP4 muxer rejects the format (e.g. Opus, common in yt-dlp/WebM
            // downloads: MediaMuxer's MP4 output has never universally accepted it), transcode
            // to AAC instead of dropping audio outright.
            audio = openAudioTrack(uri)
            var audioOutIndex = -1
            audio?.let { a ->
                audioOutIndex = try {
                    muxer.addTrack(a.format)
                } catch (e: Exception) {
                    android.util.Log.w(
                        "VideoFilterRenderer",
                        "audio track (${a.format.getString(MediaFormat.KEY_MIME)}) rejected by MP4 muxer, transcoding to AAC instead", e
                    )
                    -1
                }
            }
            if (audioOutIndex < 0) {
                runCatching { audio?.extractor?.release() }
                audio = null
                transcodedAudio = runCatching { transcodeAudioToAac(uri) }
                    .onFailure { android.util.Log.w("VideoFilterRenderer", "audio transcode to AAC failed, continuing without audio", it) }
                    .getOrNull()
                transcodedAudio?.let { t -> audioOutIndex = muxer.addTrack(t.format) }
            }

            val encBufferInfo = MediaCodec.BufferInfo()
            val decBufferInfo = MediaCodec.BufferInfo()
            var videoOutIndex = -1
            var muxerStarted = false
            val timeoutUs = 10_000L

            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                ensureActive()

                // Feed the decoder.
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val ib = decoder.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                // Drain the decoder, filtering + encoding each frame.
                val outIdx = decoder.dequeueOutputBuffer(decBufferInfo, timeoutUs)
                if (outIdx >= 0) {
                    val doRender = decBufferInfo.size != 0
                    val eos = decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outIdx, doRender)
                    if (doRender && decOut.awaitNewImage()) {
                        decOut.getTransformMatrix(stMatrix)
                        Matrix.multiplyMM(combined, 0, stMatrix, 0, rot, 0)
                        prog.draw(decOut.textureId, combined, outW, outH)
                        inputSurface.setPresentationTime(decBufferInfo.presentationTimeUs * 1000)
                        inputSurface.swapBuffers()
                        videoOutIndex = drainEncoder(
                            encoder, muxer, encBufferInfo, videoOutIndex, endOfStream = false,
                            muxerStartedRef = { muxerStarted = it }, audioAddedTrack = audioOutIndex,
                            isMuxerStarted = { muxerStarted }, onMuxerStart = { muxer!!.start() }
                        )
                        if (durUs > 0) {
                            onProgress((decBufferInfo.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 99))
                        }
                    }
                    if (eos) outputDone = true
                }
            }

            // Flush the encoder.
            encoder.signalEndOfInputStream()
            videoOutIndex = drainEncoder(
                encoder, muxer, encBufferInfo, videoOutIndex, endOfStream = true,
                muxerStartedRef = { muxerStarted = it }, audioAddedTrack = audioOutIndex,
                isMuxerStarted = { muxerStarted }, onMuxerStart = { muxer!!.start() }
            )

            // --- Write audio samples (direct copy or transcoded, whichever succeeded above) ---
            if (audioOutIndex >= 0) {
                if (!muxerStarted) { muxer.start(); muxerStarted = true }
                audio?.let { a -> copyAudio(a, muxer, audioOutIndex) }
                transcodedAudio?.let { t -> writeTranscodedAudio(t, muxer, audioOutIndex) }
            }

            onProgress(100)
            true
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { decoderOutput?.release() }
            runCatching { extractor?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { program?.release() }
            runCatching { inputSurface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { audio?.extractor?.release() }
        }
    }

    private fun estimateBitrate(w: Int, h: Int, fps: Int): Int =
        (w * h * fps * 0.12f).toInt().coerceIn(2_000_000, 12_000_000)

    /** Drains available encoder output into the muxer. Returns the video track index. */
    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        currentVideoTrack: Int,
        endOfStream: Boolean,
        muxerStartedRef: (Boolean) -> Unit,
        audioAddedTrack: Int,
        isMuxerStarted: () -> Boolean,
        onMuxerStart: () -> Unit
    ): Int {
        var videoTrack = currentVideoTrack
        val timeoutUs = 10_000L
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return videoTrack else continue
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    // The audio track (if any -- see audioAddedTrack) was already added
                    // to the muxer up front, so the video format becoming known means
                    // all tracks are now registered and the muxer can start.
                    onMuxerStart(); muxerStartedRef(true)
                }
                outIndex >= 0 -> {
                    val encoded: ByteBuffer = encoder.getOutputBuffer(outIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && isMuxerStarted()) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrack, encoded, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return videoTrack
                }
            }
        }
    }

    private class AudioTrack(
        val extractor: MediaExtractor,
        val trackIndex: Int,
        val format: MediaFormat
    )

    private fun openAudioTrack(uri: Uri): AudioTrack? {
        val ex = MediaExtractor()
        resolver.openFileDescriptor(uri, "r")!!.use { pfd -> ex.setDataSource(pfd.fileDescriptor) }
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                ex.selectTrack(i)
                return AudioTrack(ex, i, fmt)
            }
        }
        ex.release(); return null
    }

    private fun copyAudio(audio: AudioTrack, muxer: MediaMuxer, outTrack: Int) {
        val maxSize = 256 * 1024
        val buffer = ByteBuffer.allocate(maxSize)
        val info = MediaCodec.BufferInfo()
        val ex = audio.extractor
        while (true) {
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ex.sampleTime
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(outTrack, buffer, info)
            ex.advance()
        }
    }

    private class AacSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)
    private class TranscodedAudio(val format: MediaFormat, val samples: List<AacSample>)

    /**
     * Decodes the source's audio track fully to PCM then re-encodes it as AAC, which the MP4
     * muxer always accepts (unlike some source codecs -- e.g. Opus from yt-dlp/WebM downloads,
     * which [render] tries as a direct copy first and falls back to this on failure).
     * Returns null if there's no audio track, or it can't be decoded/encoded either.
     */
    private fun CoroutineScope.transcodeAudioToAac(uri: Uri): TranscodedAudio? {
        val ex = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            resolver.openFileDescriptor(uri, "r")!!.use { pfd -> ex.setDataSource(pfd.fileDescriptor) }
            var trackIdx = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { trackIdx = i; inFormat = fmt; break }
            }
            if (trackIdx < 0 || inFormat == null) return null
            ex.selectTrack(trackIdx)
            val inMime = inFormat.getString(MediaFormat.KEY_MIME)!!
            if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(inFormat) == null) return null

            val sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val dec = MediaCodec.createDecoderByType(inMime)
            decoder = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = enc
            enc.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val samples = mutableListOf<AacSample>()
            var outFormat: MediaFormat? = null
            val timeoutUs = 10_000L
            var decInputDone = false
            var decOutputDone = false
            var encOutputDone = false

            while (!encOutputDone) {
                ensureActive()

                // Feed the decoder from the extractor.
                if (!decInputDone) {
                    val inIdx = dec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                // Drain the decoder's PCM straight into the encoder.
                if (!decOutputDone) {
                    val decInfo = MediaCodec.BufferInfo()
                    val outIdx = dec.dequeueOutputBuffer(decInfo, timeoutUs)
                    if (outIdx >= 0) {
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (decInfo.size > 0) {
                            val pcm = dec.getOutputBuffer(outIdx)!!
                            pcm.position(decInfo.offset); pcm.limit(decInfo.offset + decInfo.size)
                            var remaining = decInfo.size
                            while (remaining > 0) {
                                ensureActive()
                                val encInIdx = enc.dequeueInputBuffer(timeoutUs)
                                if (encInIdx < 0) continue
                                val encBuf = enc.getInputBuffer(encInIdx)!!
                                val chunk = minOf(remaining, encBuf.capacity())
                                val savedLimit = pcm.limit()
                                pcm.limit(pcm.position() + chunk)
                                encBuf.clear(); encBuf.put(pcm)
                                pcm.limit(savedLimit)
                                enc.queueInputBuffer(encInIdx, 0, chunk, decInfo.presentationTimeUs, 0)
                                remaining -= chunk
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (eos) {
                            decOutputDone = true
                            var encInIdx = -1
                            while (encInIdx < 0) { ensureActive(); encInIdx = enc.dequeueInputBuffer(timeoutUs) }
                            enc.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }

                // Drain the encoder's AAC output.
                val encInfo = MediaCodec.BufferInfo()
                val encOutIdx = enc.dequeueOutputBuffer(encInfo, timeoutUs)
                when {
                    encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = enc.outputFormat
                    encOutIdx >= 0 -> {
                        if (encInfo.size > 0 && encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buf = enc.getOutputBuffer(encOutIdx)!!
                            buf.position(encInfo.offset); buf.limit(encInfo.offset + encInfo.size)
                            val copy = ByteArray(encInfo.size)
                            buf.get(copy)
                            samples.add(AacSample(copy, encInfo.presentationTimeUs, encInfo.flags))
                        }
                        enc.releaseOutputBuffer(encOutIdx, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encOutputDone = true
                    }
                }
            }

            return outFormat?.let { TranscodedAudio(it, samples) }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { ex.release() }
        }
    }

    private fun writeTranscodedAudio(transcoded: TranscodedAudio, muxer: MediaMuxer, outTrack: Int) {
        val info = MediaCodec.BufferInfo()
        for (s in transcoded.samples) {
            info.set(0, s.data.size, s.presentationTimeUs, s.flags)
            muxer.writeSampleData(outTrack, ByteBuffer.wrap(s.data), info)
        }
    }
}
