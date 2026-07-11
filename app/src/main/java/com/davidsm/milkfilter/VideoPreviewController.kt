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

/** Live, low-res, low-fps filtered preview of a video (jank accepted). */
class VideoPreviewController(
    private val uri: Uri,
    private val info: VideoInfo,
    private val resolver: ContentResolver,
    private val previewMaxDim: Int = 360,
    private val previewFps: Int = 12
) {
    private var filter = "dither"
    private var dither = FilterProcessor.DitherState()
    private var milk = FilterProcessor.MilkState()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    fun updateFilter(filter: String, dither: FilterProcessor.DitherState, milk: FilterProcessor.MilkState) {
        this.filter = filter; this.dither = dither; this.milk = milk
    }

    fun start(onFrame: (Bitmap) -> Unit, onError: () -> Unit = {}) {
        loop?.cancel()
        loop = scope.launch {
            val mmr = MediaMetadataRetriever()
            try {
                withContext(Dispatchers.IO) { mmr.setDataSource2(resolver, uri) }
                val durUs = info.durationMs * 1000
                val frameIntervalUs = 1_000_000L / previewFps.coerceAtLeast(1)
                var frameIndex = 0
                try {
                    while (isActive) {
                        var tUs = 0L
                        while (isActive && tUs < maxOf(durUs, frameIntervalUs)) {
                            val raw = withContext(Dispatchers.IO) {
                                mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                            }
                            if (raw != null) {
                                val scaled = scaleForPreview(raw)
                                if (scaled !== raw) raw.recycle()
                                val filtered = if (filter == "dither")
                                    FilterProcessor.processDither(scaled, dither)
                                else FilterProcessor.processMilk(scaled, milk, frameIndex)
                                if (filtered !== scaled) scaled.recycle()
                                withContext(Dispatchers.Main) { onFrame(filtered) }
                                frameIndex++
                            }
                            tUs += frameIntervalUs
                            delay(1000L / previewFps)
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { mmr.release() } }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VideoPreviewController", "preview failed", e)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { onError() }
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
