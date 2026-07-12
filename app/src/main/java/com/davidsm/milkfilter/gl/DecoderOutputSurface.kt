package com.davidsm.milkfilter.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Receives decoded video frames from a MediaCodec decoder into an external-OES GL texture.
 *
 * The decoder renders into [surface]; each frame fires a callback (on a private Handler thread) that
 * [awaitNewImage] blocks on, then pulls the frame into the OES texture with updateTexImage. The GL
 * texture lives in whatever EGL context is current when this is constructed, so it must be created
 * on the render thread after that context is made current.
 */
class DecoderOutputSurface {

    val textureId: Int = createOesTexture()
    private val surfaceTexture = SurfaceTexture(textureId)
    val surface: Surface

    private val frameLock = Object()
    private var frameAvailable = false
    private val callbackThread = HandlerThread("dec-frame").apply { start() }

    init {
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(frameLock) { frameAvailable = true; frameLock.notifyAll() }
        }, Handler(callbackThread.looper))
        surface = Surface(surfaceTexture)
    }

    /** Blocks until the next decoded frame arrives, then latches it into the OES texture. */
    fun awaitNewImage(timeoutMs: Long = 2500): Boolean {
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) return false
                frameLock.wait(remain)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        return true
    }

    fun getTransformMatrix(m: FloatArray) = surfaceTexture.getTransformMatrix(m)

    fun release() {
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        runCatching { callbackThread.quitSafely() }
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
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
