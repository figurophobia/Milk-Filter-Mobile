package com.davidsm.milkfilter

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.davidsm.milkfilter.gl.BitmapRenderer
import com.davidsm.milkfilter.gl.CodecInputSurface
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
     * Decode (MediaMetadataRetriever) -> filter (FilterProcessor) -> encode (H.264
     * via GL input surface) -> mux (MP4) with the original audio copied through.
     * Returns true on success. onProgress reports 0..100.
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
        // Derive output dimensions from a real decoded frame: getFrameAtTime returns
        // rotation-corrected bitmaps, so using info.width/height (coded, pre-rotation)
        // would squash portrait video into a landscape canvas.
        val probeMmr = MediaMetadataRetriever()
        val (outW, outH) = try {
            resolver.openFileDescriptor(uri, "r")!!.use { pfd -> probeMmr.setDataSource(pfd.fileDescriptor) }
            val pf = probeMmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: probeMmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            val dims = if (pf != null) fitWithinCap(pf.width, pf.height, 720)
                       else fitWithinCap(info.width, info.height, 720)
            pf?.recycle()
            dims
        } catch (e: Exception) {
            fitWithinCap(info.width, info.height, 720)
        } finally {
            runCatching { probeMmr.release() }
        }
        val fps = info.fps.coerceIn(1, 60)
        val durUs = info.durationMs * 1000
        val frameCount = ((info.durationMs / 1000.0) * fps).toInt().coerceAtLeast(1)
        val frameIntervalUs = 1_000_000L / fps

        var encoder: MediaCodec? = null
        var inputSurface: CodecInputSurface? = null
        var renderer: BitmapRenderer? = null
        var muxer: MediaMuxer? = null
        var audio: AudioTrack? = null
        val mmr = MediaMetadataRetriever()

        try {
            resolver.openFileDescriptor(uri, "r")!!.use { pfd -> mmr.setDataSource(pfd.fileDescriptor) }

            // --- Encoder setup (H.264) ---
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
            renderer = BitmapRenderer().also { it.surfaceCreated() }

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // --- Audio passthrough setup ---
            audio = openAudioTrack(uri)
            var audioOutIndex = -1
            if (audio != null) audioOutIndex = muxer.addTrack(audio.format)

            val bufferInfo = MediaCodec.BufferInfo()
            var videoOutIndex = -1
            var muxerStarted = false

            // --- Frame loop ---
            for (f in 0 until frameCount) {
                ensureActive()
                val tUs = f * frameIntervalUs
                // OPTION_CLOSEST returns the actual frame at tUs. OPTION_CLOSEST_SYNC would
                // snap to the nearest keyframe, so clips with a single leading keyframe would
                // render N copies of the first frame (video "frozen" on frame 0).
                val raw = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue
                val scaled = if (raw.width != outW || raw.height != outH)
                    Bitmap.createScaledBitmap(raw, outW, outH, true) else raw
                if (scaled !== raw) raw.recycle()

                val filtered = if (filter == "dither")
                    FilterProcessor.processDither(scaled, dither)
                else FilterProcessor.processMilk(scaled, milk, f)
                if (filtered !== scaled) scaled.recycle()

                inputSurface.makeCurrent()
                renderer.drawBitmap(filtered, outW, outH)
                inputSurface.setPresentationTime(tUs * 1000)
                inputSurface.swapBuffers()
                filtered.recycle()

                // Drain encoder
                videoOutIndex = drainEncoder(encoder, muxer, bufferInfo, videoOutIndex,
                    endOfStream = false, muxerStartedRef = { muxerStarted = it },
                    audioAddedTrack = audioOutIndex, isMuxerStarted = { muxerStarted },
                    onMuxerStart = { muxer!!.start() })

                onProgress(((f + 1) * 100) / frameCount)
            }

            // Signal EOS + final drain
            encoder.signalEndOfInputStream()
            videoOutIndex = drainEncoder(encoder, muxer, bufferInfo, videoOutIndex,
                endOfStream = true, muxerStartedRef = { muxerStarted = it },
                audioAddedTrack = audioOutIndex, isMuxerStarted = { muxerStarted },
                onMuxerStart = { muxer!!.start() })

            // --- Copy audio samples ---
            if (audio != null && audioOutIndex >= 0) {
                if (!muxerStarted) { muxer.start(); muxerStarted = true }
                copyAudio(audio, muxer, audioOutIndex)
            }

            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("VideoFilterRenderer", "render failed", e)
            false
        } finally {
            runCatching { mmr.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { renderer?.release() }
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
}
