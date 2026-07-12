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
        renderer.surfaceTexture?.let { queueStart(it) }
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

    /** Called from the GL thread when the SurfaceTexture exists; hop to main to wire up MediaPlayer. */
    private fun onSurfaceTextureReady(st: SurfaceTexture) {
        post { queueStart(st) }
    }

    private fun queueStart(st: SurfaceTexture) {
        val uri = pendingUri ?: return
        if (started) return
        started = true
        player?.let { runCatching { it.release() } }
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
        st.setOnFrameAvailableListener { requestRender() }
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
            oesTex = createOesTexture()
            program.init()
            val st = SurfaceTexture(oesTex)
            surfaceTexture = st
            Matrix.setIdentityM(texMatrix, 0)
            onSurfaceTextureReady(st)
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
