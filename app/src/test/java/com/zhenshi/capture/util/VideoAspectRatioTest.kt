package com.zhenshi.capture.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAspectRatioTest {

    @Test
    fun videoAspectRatio_1920x1080_returns16by9() {
        assertEquals(1920f / 1080f, videoAspectRatio(1920, 1080), 0.001f)
    }

    @Test
    fun videoAspectRatio_invalid_returnsDefault16by9() {
        assertEquals(16f / 9f, videoAspectRatio(0, 1080), 0.001f)
        assertEquals(16f / 9f, videoAspectRatio(1920, 0), 0.001f)
    }
}
