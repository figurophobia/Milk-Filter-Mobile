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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Live filtered preview of a video.
 *
 * getFrameAtTime does a full seek+decode per call, so decoding every displayed frame live is far
 * too slow (janky). Instead we sample a bounded set of low-res RAW frames spread across the clip,
 * decoding each ONCE (lazily) and caching it. The playback loop just re-applies the current filter
 * to the cached raw frames, so after the first pass playback is smooth, and moving a slider
 * re-filters the cached frames instantly.
 */
class VideoPreviewController(
    private val uri: Uri,
    private val info: VideoInfo,
    private val resolver: ContentResolver,
    private val previewMaxDim: Int = 288,
    private val previewFps: Int = 15,
    private val maxFrames: Int = 72
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
            // How many distinct frames to sample across the whole clip, and their timestamps.
            val n = if (durUs <= 0L) 1
                    else min(maxFrames, max(1, (info.durationMs * previewFps / 1000L).toInt()))
            val stepUs = if (n > 1) durUs / (n - 1) else 0L
            val cache = arrayOfNulls<Bitmap>(n)   // raw, low-res frames, reused across playback loops
            val frameDelayMs = 1000L / previewFps.coerceAtLeast(1)
            try {
                withContext(Dispatchers.IO) { mmr.setDataSource2(resolver, uri) }
                var idx = 0
                while (isActive) {
                    val startedAt = System.currentTimeMillis()
                    var raw = cache[idx]
                    if (raw == null || raw.isRecycled) {
                        val decoded = withContext(Dispatchers.IO) {
                            mmr.getFrameAtTime(idx * stepUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        }
                        if (decoded != null) {
                            raw = scaleForPreview(decoded)
                            if (raw !== decoded) decoded.recycle()
                            cache[idx] = raw
                        }
                    }
                    val current = raw
                    if (current != null && !current.isRecycled) {
                        val filtered = if (filter == "dither")
                            FilterProcessor.processDither(current, dither)
                        else FilterProcessor.processMilk(current, milk, idx)
                        withContext(Dispatchers.Main) { onFrame(filtered) }
                    }
                    idx = (idx + 1) % n
                    val wait = frameDelayMs - (System.currentTimeMillis() - startedAt)
                    if (wait > 0) delay(wait)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VideoPreviewController", "preview failed", e)
                withContext(NonCancellable + Dispatchers.Main) { onError() }
            } finally {
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
