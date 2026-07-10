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
}
