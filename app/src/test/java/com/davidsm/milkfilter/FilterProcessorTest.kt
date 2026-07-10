package com.davidsm.milkfilter

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterProcessorTest {

    @Test
    fun buildExpandedPalette_returnsBaseWhenCountMatches() {
        val base = listOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255))
        val out = FilterProcessor.buildExpandedPalette(base, 2)
        assertEquals(2, out.size)
        assertEquals(0, out[0][0])
        assertEquals(255, out[1][0])
    }

    @Test
    fun buildExpandedPalette_interpolatesMidpoint() {
        val base = listOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255))
        val out = FilterProcessor.buildExpandedPalette(base, 3)
        assertEquals(3, out.size)
        // middle entry ~127/128 (rounding) grey
        assertEquals(128, out[1][0])
    }

    @Test
    fun buildExpandedPalette_clampsCountToMinimumTwo() {
        val base = listOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255))
        val out = FilterProcessor.buildExpandedPalette(base, 1)
        assertEquals(2, out.size)
    }

    @Test
    fun milkNoise_isDeterministicForSameCoords() {
        val a = FilterProcessor.milkNoise(10, 20, 0)
        val b = FilterProcessor.milkNoise(10, 20, 0)
        assertEquals(a, b, 0.0f)
    }

    @Test
    fun milkNoise_inUnitRange() {
        for (i in 0 until 500) {
            val v = FilterProcessor.milkNoise(i, i * 7, i % 3)
            assert(v in 0f..1f) { "noise out of range: $v" }
        }
    }

    @Test
    fun milkNoise_variesAcrossPixels() {
        val v0 = FilterProcessor.milkNoise(0, 0, 0)
        val v1 = FilterProcessor.milkNoise(1, 0, 0)
        assert(v0 != v1) { "noise identical for neighbouring pixels" }
    }
}
