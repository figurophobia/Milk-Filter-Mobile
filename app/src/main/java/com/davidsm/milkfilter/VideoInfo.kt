package com.davidsm.milkfilter

import kotlin.math.roundToInt

data class VideoInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val rotationDeg: Int,
    val fps: Int,
    val hasAudio: Boolean
)

/**
 * Scale (width,height) so the longer side fits within [cap] (i.e. inside a
 * cap*16/9 x cap box for 720 -> 1280x720), preserving aspect ratio. Never
 * upscales. Both returned dimensions are rounded to even numbers (H.264 needs
 * even dimensions).
 */
fun fitWithinCap(width: Int, height: Int, cap: Int = 720): Pair<Int, Int> {
    val longer = maxOf(width, height)
    val boxLong = (cap * 16) / 9            // 1280 for cap 720
    val boxShort = cap                       // 720
    // Scale so neither the long side exceeds boxLong nor the short side exceeds boxShort.
    val shorter = minOf(width, height)
    val scale = minOf(
        1f,
        boxLong.toFloat() / longer,
        boxShort.toFloat() / shorter
    )
    fun even(v: Int) = v - (v % 2)
    val w = even(maxOf(2, (width * scale).roundToInt()))
    val h = even(maxOf(2, (height * scale).roundToInt()))
    return w to h
}
