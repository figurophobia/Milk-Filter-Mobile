package com.davidsm.milkfilter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object FilterProcessor {

    private val BAYER8 = intArrayOf(
        0, 48, 12, 60, 3, 51, 15, 63,
        32, 16, 44, 28, 35, 19, 47, 31,
        8, 56, 4, 52, 11, 59, 7, 55,
        40, 24, 36, 20, 43, 27, 39, 23,
        2, 50, 14, 62, 1, 49, 13, 61,
        34, 18, 46, 30, 33, 17, 45, 29,
        10, 58, 6, 54, 9, 57, 5, 53,
        42, 26, 38, 22, 41, 25, 37, 21
    )

    val DITHER_PALETTE_KEYS = listOf("purple", "milk1", "milk2", "earth", "dark-green", "olive", "greyscale")
    val DITHER_PALETTE_LABELS = mapOf(
        "purple" to "Purple", "milk1" to "Milk 1", "milk2" to "Milk 2",
        "earth" to "Earth", "dark-green" to "Green", "olive" to "Olive", "greyscale" to "Grey"
    )
    val DITHER_PALETTES: Map<String, List<IntArray>> = mapOf(
        "purple"     to listOf(intArrayOf(0, 0, 0), intArrayOf(31, 0, 102), intArrayOf(146, 0, 137)),
        "milk1"      to listOf(intArrayOf(0, 0, 0), intArrayOf(102, 0, 31), intArrayOf(137, 0, 146)),
        "milk2"      to listOf(intArrayOf(0, 0, 0), intArrayOf(92, 36, 60), intArrayOf(203, 43, 43)),
        "earth"      to listOf(intArrayOf(0, 0, 0), intArrayOf(25, 105, 44), intArrayOf(224, 110, 22), intArrayOf(247, 219, 126)),
        "dark-green" to listOf(intArrayOf(29, 1, 16), intArrayOf(26, 23, 28), intArrayOf(40, 83, 67), intArrayOf(150, 215, 173)),
        "olive"      to listOf(intArrayOf(16, 1, 29), intArrayOf(28, 23, 26), intArrayOf(67, 83, 40), intArrayOf(173, 215, 150)),
        "greyscale"  to listOf(intArrayOf(0, 0, 0), intArrayOf(85, 85, 85), intArrayOf(170, 170, 170), intArrayOf(255, 255, 255))
    )
    val DITHER_LEVELS = mapOf(
        "brightness"    to floatArrayOf(0.4f, 0.6f, 0.8f, 1.0f, 1.3f, 1.6f, 2.0f),
        "contrast"      to floatArrayOf(0.5f, 0.8f, 1.1f, 1.5f, 1.9f, 2.3f, 2.8f),
        "ditherStrength" to floatArrayOf(0.0f, 0.3f, 0.55f, 0.8f, 1.1f, 1.5f, 2.0f),
        "pixelScale"    to floatArrayOf(1f, 2f, 3f, 4f, 6f, 8f, 10f, 12f, 16f, 20f, 24f, 32f),
        "paletteColors" to floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 10f, 12f, 14f, 16f)
    )

    val MILK_PALETTE_KEYS = listOf("milk1", "milk2")
    val MILK_PALETTE_LABELS = mapOf("milk1" to "Milk 1", "milk2" to "Milk 2")
    val MILK_PALETTES: Map<String, List<IntArray>> = mapOf(
        "milk1" to listOf(intArrayOf(0, 0, 0), intArrayOf(102, 0, 31), intArrayOf(137, 0, 146)),
        "milk2" to listOf(intArrayOf(0, 0, 0), intArrayOf(92, 36, 60), intArrayOf(203, 43, 43))
    )
    val MILK_LEVELS = mapOf(
        "brightness" to floatArrayOf(0.4f, 0.6f, 0.8f, 1.0f, 1.3f, 1.6f, 2.0f),
        "contrast"   to floatArrayOf(0.4f, 0.6f, 0.8f, 1.0f, 1.3f, 1.6f, 2.0f)
    )
    val MILK_COMPRESSION_LEVELS = intArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)

    data class DitherState(
        var paletteIdx: Int = 1,
        var brightnessIdx: Int = 3,
        var contrastIdx: Int = 3,
        var ditherStrengthIdx: Int = 3,
        var pixelScaleIdx: Int = 3,
        var paletteColorsIdx: Int = 1
    )

    data class MilkState(
        var paletteIdx: Int = 0,
        var brightnessIdx: Int = 3,
        var contrastIdx: Int = 3,
        var pointillism: Boolean = false,
        var compression: Boolean = false,
        var compressionLevelIdx: Int = 4
    )

    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    internal fun buildExpandedPalette(base: List<IntArray>, count: Int): List<IntArray> {
        val n = max(2, count)
        if (n == base.size) return base
        val segs = base.size - 1
        return (0 until n).map { i ->
            val norm = if (n == 1) 0f else i.toFloat() / (n - 1)
            val pos = norm * segs
            val seg = min(segs - 1, pos.toInt())
            val lt = pos - seg
            val from = base[seg]; val to = base[min(seg + 1, base.size - 1)]
            intArrayOf(
                lerp(from[0].toFloat(), to[0].toFloat(), lt).roundToInt(),
                lerp(from[1].toFloat(), to[1].toFloat(), lt).roundToInt(),
                lerp(from[2].toFloat(), to[2].toFloat(), lt).roundToInt()
            )
        }
    }

    fun processDither(source: Bitmap, state: DitherState): Bitmap {
        val brightness = DITHER_LEVELS["brightness"]!![state.brightnessIdx]
        val contrast   = DITHER_LEVELS["contrast"]!![state.contrastIdx]
        val strength   = DITHER_LEVELS["ditherStrength"]!![state.ditherStrengthIdx]
        val pixelScale = DITHER_LEVELS["pixelScale"]!![state.pixelScaleIdx].toInt()
        val numColors  = DITHER_LEVELS["paletteColors"]!![state.paletteColorsIdx].toInt()
        val paletteName = DITHER_PALETTE_KEYS[state.paletteIdx]

        val w = source.width; val h = source.height
        val outW = max(1, (w.toFloat() / pixelScale).roundToInt())
        val outH = max(1, (h.toFloat() / pixelScale).roundToInt())

        val small = Bitmap.createScaledBitmap(source, outW, outH, true)
        val pixels = IntArray(outW * outH)
        small.getPixels(pixels, 0, outW, 0, 0, outW, outH)
        if (small !== source) small.recycle()

        val palette = buildExpandedPalette(DITHER_PALETTES[paletteName]!!, numColors)
        val last = palette.size - 1

        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val idx = y * outW + x
                val color = pixels[idx]
                if (Color.alpha(color) == 0) { pixels[idx] = 0; continue }

                val r = Color.red(color) / 255f
                val g = Color.green(color) / 255f
                val b = Color.blue(color) / 255f
                val grey = r * 0.3f + g * 0.59f + b * 0.11f
                val c = (grey - 0.5f) * contrast + 0.5f
                val br = clamp(c, 0f, 1f).pow(brightness)
                val bayer = BAYER8[(y % 8) * 8 + (x % 8)]
                val filtered = clamp(br + (bayer - 32) * strength / 255f, 0f, 1f)
                val qi = (filtered * last).roundToInt()
                val p = palette[qi]
                pixels[idx] = Color.rgb(p[0], p[1], p[2])
            }
        }

        val smallResult = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        smallResult.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        // No smoothing: pixel art must stay crisp
        val result = Bitmap.createScaledBitmap(smallResult, w, h, false)
        smallResult.recycle()
        return result
    }

    fun processMilk(source: Bitmap, state: MilkState): Bitmap {
        val mBri = MILK_LEVELS["brightness"]!![state.brightnessIdx]
        val mCon = MILK_LEVELS["contrast"]!![state.contrastIdx]
        val key = MILK_PALETTE_KEYS[state.paletteIdx]
        val colors = MILK_PALETTES[key]!!
        val punt = if (state.pointillism) 0.7f else 1.0f
        val mid1 = if (key == "milk1") 120 else 90
        val mid2 = if (key == "milk1") 200 else 150
        val adjustBC = mBri != 1.0f || mCon != 1.0f

        val w = source.width; val h = source.height

        val work: Bitmap
        if (state.compression) {
            val level = MILK_COMPRESSION_LEVELS[state.compressionLevelIdx]
            val factor = max(0.05f, 1f - level / 110f)
            val sw = max(1, (w * factor).roundToInt())
            val sh = max(1, (h * factor).roundToInt())
            val tmp = Bitmap.createScaledBitmap(source, sw, sh, true)
            work = Bitmap.createScaledBitmap(tmp, w, h, false)
            tmp.recycle()
        } else {
            work = source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val pixels = IntArray(w * h)
        work.getPixels(pixels, 0, w, 0, 0, w, h)
        if (work !== source) work.recycle()

        val rng = java.util.Random()
        for (i in pixels.indices) {
            val color = pixels[i]
            if (Color.alpha(color) == 0) continue

            var lum = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3f
            if (adjustBC) {
                lum = ((lum / 255f - 0.5f) * mCon + 0.5f) * 255f
                lum = clamp(lum / 255f, 0f, 1f).pow(mBri) * 255f
            }

            val c = when {
                lum <= 25  -> colors[0]
                lum <= 70  -> if (rng.nextFloat() < punt) colors[0] else colors[1]
                lum < mid1 -> if (rng.nextFloat() < punt) colors[1] else colors[0]
                lum < mid2 -> colors[1]
                lum < 230  -> if (rng.nextFloat() < punt) colors[2] else colors[1]
                else       -> colors[2]
            }
            pixels[i] = Color.rgb(c[0], c[1], c[2])
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }
}
