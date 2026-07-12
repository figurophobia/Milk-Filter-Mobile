package com.davidsm.milkfilter.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.davidsm.milkfilter.FilterProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * The dither / milk filter as GLES2 fragment shaders, sampling an external-OES video texture.
 *
 * Single source of truth shared by the live preview ([com.davidsm.milkfilter.VideoPreviewGLView])
 * and the exported render ([com.davidsm.milkfilter.VideoFilterRenderer]) so the exported MP4 looks
 * exactly like the preview. All GL calls must run on the thread holding the GL context.
 *
 * [setParams] may be called from another thread (the preview sets it from the UI thread); it is
 * guarded so it can't tear a frame mid-draw.
 */
class VideoFilterProgram {

    private var ditherProgram = 0
    private var milkProgram = 0
    private var bayerTex = 0

    private val lock = Any()
    private var pFilter = 0                 // 0 = dither, 1 = milk
    private var videoResX = 1f
    private var videoResY = 1f
    // dither
    private val palette = FloatArray(16 * 3)
    private var numColors = 3
    private var dBrightness = 1f
    private var dContrast = 1f
    private var dStrength = 0f
    private var dPixelScale = 1f
    // milk
    private var mBri = 1f
    private var mCon = 1f
    private var mPunt = 1f
    private var mid1 = 120f
    private var mid2 = 200f
    private var mComp = 0f
    private val mC0 = FloatArray(3)
    private val mC1 = FloatArray(3)
    private val mC2 = FloatArray(3)

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).also { it.position(0) }
    private val quadTex: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).also { it.position(0) }

    /** Compiles programs and uploads the Bayer texture. GL context must be current. */
    fun init() {
        ditherProgram = buildProgram(VSH, DITHER_FSH)
        milkProgram = buildProgram(VSH, MILK_FSH)
        bayerTex = createBayerTexture()
    }

    fun setParams(
        filter: String,
        dither: FilterProcessor.DitherState,
        milk: FilterProcessor.MilkState,
        videoW: Int,
        videoH: Int
    ) {
        synchronized(lock) {
            pFilter = if (filter == "milk") 1 else 0
            videoResX = max(1, videoW).toFloat()
            videoResY = max(1, videoH).toFloat()
            if (pFilter == 0) {
                dBrightness = FilterProcessor.DITHER_LEVELS["brightness"]!![dither.brightnessIdx]
                dContrast = FilterProcessor.DITHER_LEVELS["contrast"]!![dither.contrastIdx]
                dStrength = FilterProcessor.DITHER_LEVELS["ditherStrength"]!![dither.ditherStrengthIdx]
                dPixelScale = FilterProcessor.DITHER_LEVELS["pixelScale"]!![dither.pixelScaleIdx]
                val n = FilterProcessor.DITHER_LEVELS["paletteColors"]!![dither.paletteColorsIdx].toInt()
                val key = FilterProcessor.DITHER_PALETTE_KEYS[dither.paletteIdx]
                val expanded = FilterProcessor.buildExpandedPalette(FilterProcessor.DITHER_PALETTES[key]!!, n)
                numColors = expanded.size.coerceIn(2, 16)
                for (i in 0 until numColors) {
                    palette[i * 3] = expanded[i][0] / 255f
                    palette[i * 3 + 1] = expanded[i][1] / 255f
                    palette[i * 3 + 2] = expanded[i][2] / 255f
                }
            } else {
                mBri = FilterProcessor.MILK_LEVELS["brightness"]!![milk.brightnessIdx]
                mCon = FilterProcessor.MILK_LEVELS["contrast"]!![milk.contrastIdx]
                mPunt = if (milk.pointillism) 0.7f else 1.0f
                val key = FilterProcessor.MILK_PALETTE_KEYS[milk.paletteIdx]
                mid1 = if (key == "milk1") 120f else 90f
                mid2 = if (key == "milk1") 200f else 150f
                mComp = if (milk.compression) {
                    val level = FilterProcessor.MILK_COMPRESSION_LEVELS[milk.compressionLevelIdx]
                    max(0.05f, 1f - level / 110f)
                } else 0f
                val c = FilterProcessor.MILK_PALETTES[key]!!
                for (j in 0..2) { mC0[j] = c[0][j] / 255f; mC1[j] = c[1][j] / 255f; mC2[j] = c[2][j] / 255f }
            }
        }
    }

    /** Draws the OES video texture, filtered, onto the current framebuffer. */
    fun draw(oesTextureId: Int, texMatrix: FloatArray, viewportW: Int, viewportH: Int) {
        GLES20.glViewport(0, 0, viewportW, viewportH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val filter: Int
        synchronized(lock) { filter = pFilter }
        if (filter == 0) drawDither(oesTextureId, texMatrix) else drawMilk(oesTextureId, texMatrix)
    }

    private fun drawDither(oesTextureId: Int, texMatrix: FloatArray) {
        GLES20.glUseProgram(ditherProgram)
        bindGeometry(ditherProgram, texMatrix)
        bindOes(ditherProgram, oesTextureId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bayerTex)
        GLES20.glUniform1i(loc(ditherProgram, "uBayer"), 1)
        synchronized(lock) {
            GLES20.glUniform2f(loc(ditherProgram, "uVideoRes"), videoResX, videoResY)
            GLES20.glUniform1f(loc(ditherProgram, "uPixelScale"), dPixelScale)
            GLES20.glUniform1f(loc(ditherProgram, "uBrightness"), dBrightness)
            GLES20.glUniform1f(loc(ditherProgram, "uContrast"), dContrast)
            GLES20.glUniform1f(loc(ditherProgram, "uStrength"), dStrength)
            GLES20.glUniform1i(loc(ditherProgram, "uNumColors"), numColors)
            GLES20.glUniform3fv(loc(ditherProgram, "uPalette"), 16, palette, 0)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawMilk(oesTextureId: Int, texMatrix: FloatArray) {
        GLES20.glUseProgram(milkProgram)
        bindGeometry(milkProgram, texMatrix)
        bindOes(milkProgram, oesTextureId)
        synchronized(lock) {
            GLES20.glUniform2f(loc(milkProgram, "uVideoRes"), videoResX, videoResY)
            GLES20.glUniform1f(loc(milkProgram, "uMBri"), mBri)
            GLES20.glUniform1f(loc(milkProgram, "uMCon"), mCon)
            GLES20.glUniform1f(loc(milkProgram, "uPunt"), mPunt)
            GLES20.glUniform1f(loc(milkProgram, "uMid1"), mid1)
            GLES20.glUniform1f(loc(milkProgram, "uMid2"), mid2)
            GLES20.glUniform1f(loc(milkProgram, "uComp"), mComp)
            GLES20.glUniform3f(loc(milkProgram, "uC0"), mC0[0], mC0[1], mC0[2])
            GLES20.glUniform3f(loc(milkProgram, "uC1"), mC1[0], mC1[1], mC1[2])
            GLES20.glUniform3f(loc(milkProgram, "uC2"), mC2[0], mC2[1], mC2[2])
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindGeometry(program: Int, texMatrix: FloatArray) {
        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glUniformMatrix4fv(loc(program, "uTexMatrix"), 1, false, texMatrix, 0)
    }

    private fun bindOes(program: Int, oesTextureId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(loc(program, "uTex"), 0)
    }

    private fun loc(program: Int, name: String) = GLES20.glGetUniformLocation(program, name)

    fun release() {
        if (ditherProgram != 0) GLES20.glDeleteProgram(ditherProgram)
        if (milkProgram != 0) GLES20.glDeleteProgram(milkProgram)
        if (bayerTex != 0) GLES20.glDeleteTextures(1, intArrayOf(bayerTex), 0)
        ditherProgram = 0; milkProgram = 0; bayerTex = 0
    }

    private fun createBayerTexture(): Int {
        val src = intArrayOf(
            0, 48, 12, 60, 3, 51, 15, 63,
            32, 16, 44, 28, 35, 19, 47, 31,
            8, 56, 4, 52, 11, 59, 7, 55,
            40, 24, 36, 20, 43, 27, 39, 23,
            2, 50, 14, 62, 1, 49, 13, 61,
            34, 18, 46, 30, 33, 17, 45, 29,
            10, 58, 6, 54, 9, 57, 5, 53,
            42, 26, 38, 22, 41, 25, 37, 21
        )
        val buf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        for (v in src) buf.put((v * 255 / 63).toByte())
        buf.position(0)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 8, 8, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        return t[0]
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    companion object {
        private val VSH = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                vTex = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        // Dither approximation: pixelate -> luminance -> contrast/brightness -> 8x8 Bayer -> palette quantize.
        private val DITHER_FSH = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            uniform sampler2D uBayer;
            uniform vec2 uVideoRes;
            uniform float uPixelScale;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uStrength;
            uniform int uNumColors;
            uniform vec3 uPalette[16];
            void main() {
                vec2 blocks = max(vec2(1.0), floor(uVideoRes / uPixelScale));
                vec2 cell = floor(vTex * blocks);
                vec2 suv = (cell + 0.5) / blocks;
                vec3 col = texture2D(uTex, suv).rgb;
                float grey = dot(col, vec3(0.3, 0.59, 0.11));
                float c = clamp((grey - 0.5) * uContrast + 0.5, 0.0, 1.0);
                float br = pow(c, uBrightness);
                float bayer = texture2D(uBayer, fract(cell / 8.0)).r * 63.0;
                float f = clamp(br + (bayer - 32.0) * uStrength / 255.0, 0.0, 1.0);
                int qi = int(f * float(uNumColors - 1) + 0.5);
                vec3 outc = uPalette[0];
                for (int k = 0; k < 16; k++) { if (k == qi) { outc = uPalette[k]; } }
                gl_FragColor = vec4(outc, 1.0);
            }
        """.trimIndent()

        // Milk approximation: luminance thresholds -> palette, hash noise for pointillism, optional pixelate.
        private val MILK_FSH = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            uniform vec2 uVideoRes;
            uniform float uMBri;
            uniform float uMCon;
            uniform float uPunt;
            uniform float uMid1;
            uniform float uMid2;
            uniform float uComp;
            uniform vec3 uC0;
            uniform vec3 uC1;
            uniform vec3 uC2;
            // Stable per-pixel hash (no sin(): sin banding gets ugly at large coords).
            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            void main() {
                vec2 suv = vTex;
                if (uComp > 0.0) {
                    vec2 blocks = max(vec2(1.0), floor(uVideoRes * uComp));
                    vec2 cell = floor(vTex * blocks);
                    suv = (cell + 0.5) / blocks;
                }
                vec3 col = texture2D(uTex, suv).rgb * 255.0;
                float lum = (col.r + col.g + col.b) / 3.0;
                lum = ((lum / 255.0 - 0.5) * uMCon + 0.5) * 255.0;
                lum = pow(clamp(lum / 255.0, 0.0, 1.0), uMBri) * 255.0;
                // Noise is always per source pixel (fine grain), independent of the color grid.
                float noise = hash(floor(vTex * uVideoRes));
                vec3 c;
                if (lum <= 25.0) c = uC0;
                else if (lum <= 70.0) c = (noise < uPunt) ? uC0 : uC1;
                else if (lum < uMid1) c = (noise < uPunt) ? uC1 : uC0;
                else if (lum < uMid2) c = uC1;
                else if (lum < 230.0) c = (noise < uPunt) ? uC2 : uC1;
                else c = uC2;
                gl_FragColor = vec4(c, 1.0);
            }
        """.trimIndent()
    }
}
