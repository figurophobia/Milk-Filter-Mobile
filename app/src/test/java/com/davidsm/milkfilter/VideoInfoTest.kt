package com.davidsm.milkfilter

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoInfoTest {

    @Test
    fun fitWithinCap_downscalesLandscape1080pTo720p() {
        val (w, h) = fitWithinCap(1920, 1080, 720)
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun fitWithinCap_downscalesPortrait() {
        val (w, h) = fitWithinCap(1080, 1920, 720)
        assertEquals(720, w)
        assertEquals(1280, h)
    }

    @Test
    fun fitWithinCap_neverUpscales() {
        val (w, h) = fitWithinCap(640, 480, 720)
        assertEquals(640, w)
        assertEquals(480, h)
    }

    @Test
    fun fitWithinCap_dimensionsAreEven() {
        val (w, h) = fitWithinCap(1001, 667, 720)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }
}
