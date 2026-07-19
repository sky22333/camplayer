package com.zhenshi.capture.screens.usb

import com.zhenshi.capture.media.VideoProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileOptionsTest {

    private val profiles = listOf(
        VideoProfile(1920, 1080, 30),
        VideoProfile(1920, 1080, 60),
        VideoProfile(1280, 720, 30),
        VideoProfile(1280, 720, 60),
        VideoProfile(640, 480, 30),
    )

    @Test
    fun resolutionOptions_dedupesAndSortsDescending() {
        assertEquals(
            listOf(
                ResolutionOption(1920, 1080),
                ResolutionOption(1280, 720),
                ResolutionOption(640, 480),
            ),
            profiles.resolutionOptions(),
        )
    }

    @Test
    fun fpsOptionsFor_knownResolution_returnsStandardFps() {
        assertEquals(VideoProfile.STANDARD_FPS_OPTIONS, profiles.fpsOptionsFor(1280, 720))
        assertTrue(profiles.fpsOptionsFor(800, 600).isEmpty())
    }

    @Test
    fun findProfile_exactOrNormalizedFps() {
        assertEquals(
            VideoProfile(1920, 1080, 60),
            profiles.findProfile(1920, 1080, 60),
        )
        assertEquals(
            VideoProfile(1280, 720, 30),
            profiles.findProfile(1280, 720, 24),
        )
        assertNull(profiles.findProfile(800, 600, 30))
    }
}
