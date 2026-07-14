package com.davidsm.milkfilter

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.Surface
import com.davidsm.milkfilter.gl.VideoFilterProgram
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Real-time filtered video preview.
 *
 * Plays the source video with [MediaPlayer] onto a [SurfaceTexture] and draws it every frame with a
 * lightweight GLES2 fragment shader ([VideoFilterProgram]) that *approximates* the dither / milk
 * filters on the GPU. This runs at native frame rate (the exact CPU filter would not), so the
 * preview is smooth. The export ([VideoFilterRenderer]) uses the SAME [VideoFilterProgram], so the
 * exported MP4 looks exactly like this preview.
 *
 * The view letterboxes to the video aspect ratio, like [AspectRatioVideoView].
 */
class VideoPreviewGLView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GLSurfaceView(context, attrs) {

    private val renderer = PreviewRenderer()
    private var player: MediaPlayer? = null
    private var pendingUri: Uri? = null
    private var started = false

    /** Aspect-ratio letterboxing (rotation-adjusted video dimensions). */
    private var videoW = 0
    private var videoH = 0

    /** Fired on the main thread once the first frame is ready to show. */
    var onReady: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Loads a video; playback starts (looping, muted) as soon as the GL surface + player are ready. */
    fun setVideo(uri: Uri, info: VideoInfo) {
        val swap = info.rotationDeg == 90 || info.rotationDeg == 270
        videoW = if (swap) info.height else info.width
        videoH = if (swap) info.width else info.height
        requestLayout()
        pendingUri = uri
        started = false
        renderer.surfaceTexture?.let { queueStart(null, it) }
    }

    fun updateFilter(filter: String, dither: FilterProcessor.DitherState, milk: FilterProcessor.MilkState) {
        renderer.program.setParams(filter, dither, milk, videoW, videoH)
        requestRender()
    }

    fun onResumePreview() {
        onResume()
        player?.let { runCatching { if (!it.isPlaying) it.start() } }
    }

    fun onPausePreview() {
        player?.let { runCatching { if (it.isPlaying) it.pause() } }
        onPause()
    }

    fun releasePreview() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        pendingUri = null
        started = false
    }

    /** Called from the GL thread when a new SurfaceTexture exists; hop to main to wire up MediaPlayer. */
    private fun onSurfaceTextureReady(old: SurfaceTexture?, st: SurfaceTexture) {
        post { queueStart(old, st) }
    }

    private fun queueStart(old: SurfaceTexture?, st: SurfaceTexture) {
        val uri = pendingUri ?: return
        st.setOnFrameAvailableListener { requestRender() }
        val existing = player
        if (started && existing != null) {
            // The GL surface was torn down and recreated (screen lock, app switch, etc.) while
            // a player was already attached: the old SurfaceTexture it was drawing into is gone,
            // so the new one never receives a frame and the preview looks permanently black.
            // Rebind the SAME player to the fresh SurfaceTexture instead of dropping it. Only
            // release the old SurfaceTexture once the rebind has actually happened, so the
            // decoder is never left mid-frame with no consumer attached at all.
            val rebound = runCatching { existing.setSurface(Surface(st)) }.isSuccess
            if (rebound) {
                old?.let { runCatching { it.release() } }
                requestRender()
                return
            }
            // The player was in a transient state (e.g. still preparing) and rejected the new
            // surface: fall through and rebuild it from scratch instead of leaving the preview
            // permanently black with nothing to recover it.
            runCatching { existing.release() }
            player = null
            started = false
        }
        started = true
        old?.let { runCatching { it.release() } }
        existing?.let { runCatching { it.release() } } // previous video's player (e.g. setVideo() with a new uri), if any
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setSurface(Surface(st))
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                runCatching { it.start() }
                onReady?.invoke()
            }
            mp.setOnErrorListener { _, _, _ -> onError?.invoke(); true }
            mp.prepareAsync()
        }.onFailure { onError?.invoke() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = getDefaultSize(videoW, widthMeasureSpec)
        var height = getDefaultSize(videoH, heightMeasureSpec)
        if (videoW > 0 && videoH > 0) {
            val boxW = MeasureSpec.getSize(widthMeasureSpec)
            val boxH = MeasureSpec.getSize(heightMeasureSpec)
            val videoRatio = videoW.toFloat() / videoH
            val boxRatio = boxW.toFloat() / boxH
            if (videoRatio > boxRatio) { width = boxW; height = (boxW / videoRatio).toInt() }
            else { height = boxH; width = (boxH * videoRatio).toInt() }
        }
        setMeasuredDimension(width, height)
    }

    /** GL renderer: owns the OES texture, feeds MediaPlayer frames through [VideoFilterProgram]. */
    private inner class PreviewRenderer : Renderer {
        @Volatile var surfaceTexture: SurfaceTexture? = null
            private set

        val program = VideoFilterProgram()
        private var oesTex = 0
        private val texMatrix = FloatArray(16)
        private var viewW = 1
        private var viewH = 1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // The previous EGL context (if any) is already gone at this point, along with the
            // OES texture it owned; only the SurfaceTexture's own native buffer queue survives
            // and needs explicit release so it doesn't leak. Don't release it here though: the
            // player may still be attached to it until queueStart (main thread) rebinds it to
            // the new one below, so hand it off and let queueStart release it once that's done.
            val old = surfaceTexture
            oesTex = createOesTexture()
            program.init()
            val st = SurfaceTexture(oesTex)
            surfaceTexture = st
            Matrix.setIdentityM(texMatrix, 0)
            onSurfaceTextureReady(old, st)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width; viewH = height
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return
            runCatching {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
            }
            program.draw(oesTex, texMatrix, viewW, viewH)
        }

        private fun createOesTexture(): Int {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return t[0]
        }
    }
}
