package com.davidsm.milkfilter

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Live filtered preview of a video.
 *
 * getFrameAtTime does a full seek+decode per call, far too slow to run per displayed frame.
 * So decoding and playback are DECOUPLED:
 *  - a decoder job fills a bounded cache of low-res RAW frames (spread across the clip) as fast as
 *    it can, back-to-back;
 *  - a player loop displays, at a constant fps, only frames already in the cache, re-applying the
 *    current filter each time.
 * The player therefore never waits on a decode, so playback is smooth from the first cached frame
 * and gets richer as decoding completes. Moving a slider re-filters cached frames instantly.
 */
class VideoPreviewController(
    private val uri: Uri,
    private val info: VideoInfo,
    private val resolver: ContentResolver,
    private val previewMaxDim: Int = 288,
    private val previewFps: Int = 20,
    private val maxFrames: Int = 64
) {
    @Volatile private var filter = "dither"
    @Volatile private var dither = FilterProcessor.DitherState()
    @Volatile private var milk = FilterProcessor.MilkState()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    /** Copies the filter state so slider mutations can't tear a frame mid-render. */
    fun updateFilter(filter: String, dither: FilterProcessor.DitherState, milk: FilterProcessor.MilkState) {
        this.filter = filter
        this.dither = dither.copy()
        this.milk = milk.copy()
    }

    fun start(onFrame: (Bitmap) -> Unit, onError: () -> Unit = {}) {
        loop?.cancel()
        loop = scope.launch {
            val mmr = MediaMetadataRetriever()
            val durUs = info.durationMs * 1000
            val n = if (durUs <= 0L) 1
                    else min(maxFrames, max(1, (info.durationMs * previewFps / 1000L).toInt()))
            val stepUs = if (n > 1) durUs / (n - 1) else 0L
            val cache = arrayOfNulls<Bitmap>(n)     // raw, low-res frames, reused across playback loops
            val cached = AtomicInteger(0)           // how many leading cache slots are populated & safe to read
            val frameDelayMs = 1000L / previewFps.coerceAtLeast(1)
            var decoder: Job? = null
            try {
                withContext(Dispatchers.IO) { mmr.setDataSource2(resolver, uri) }

                // Decoder: fill the cache as fast as possible, publishing count after each write.
                decoder = launch(Dispatchers.IO) {
                    for (i in 0 until n) {
                        if (!isActive) break
                        val decoded = runCatching {
                            mmr.getFrameAtTime(i * stepUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        }.getOrNull()
                        if (decoded != null) {
                            val scaled = scaleForPreview(decoded)
                            if (scaled !== decoded) decoded.recycle()
                            cache[i] = scaled
                        }
                        cached.set(i + 1)   // publish (even if null) so the player advances past gaps
                    }
                }

                // Player: display cached frames at a constant fps.
                var idx = 0
                while (isActive) {
                    val startedAt = System.currentTimeMillis()
                    val count = cached.get()
                    if (count > 0) {
                        val raw = cache[idx % count]
                        if (raw != null && !raw.isRecycled) {
                            val filtered = if (filter == "dither")
                                FilterProcessor.processDither(raw, dither)
                            else FilterProcessor.processMilk(raw, milk, idx)
                            withContext(Dispatchers.Main) { onFrame(filtered) }
                        }
                        idx++
                    }
                    val wait = frameDelayMs - (System.currentTimeMillis() - startedAt)
                    delay(if (wait > 0) wait else 1L)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VideoPreviewController", "preview failed", e)
                withContext(NonCancellable + Dispatchers.Main) { onError() }
            } finally {
                // Stop the decoder before releasing the retriever it uses.
                withContext(NonCancellable) { decoder?.cancelAndJoin() }
                withContext(NonCancellable + Dispatchers.IO) { runCatching { mmr.release() } }
                for (b in cache) runCatching { b?.recycle() }
            }
        }
    }

    private fun scaleForPreview(bmp: Bitmap): Bitmap {
        val longer = maxOf(bmp.width, bmp.height)
        if (longer <= previewMaxDim) return bmp
        val scale = previewMaxDim.toFloat() / longer
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    fun stop() { loop?.cancel(); loop = null }
}

// setDataSource overload that works with content Uris via ContentResolver.
private fun MediaMetadataRetriever.setDataSource2(resolver: ContentResolver, uri: Uri) {
    resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
        setDataSource(pfd.fileDescriptor)
    }
}
