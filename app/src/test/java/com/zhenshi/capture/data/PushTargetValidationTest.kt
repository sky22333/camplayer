package com.zhenshi.capture.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTargetValidationTest {

    @Test
    fun validatePushTargetName_rejectsBlank() {
        assertNull(validatePushTargetName(""))
        assertNull(validatePushTargetName("   "))
    }

    @Test
    fun validatePushTargetName_trimsValue() {
        assertEquals("生产", validatePushTargetName("  生产  "))
    }

    @Test
    fun validatePushTargetUrl_acceptsRtmpSchemes() {
        assertEquals(
            "rtmp://127.0.0.1/live/test",
            validatePushTargetUrl("  rtmp://127.0.0.1/live/test  "),
        )
        assertEquals(
            "rtmps://example.com/app/key",
            validatePushTargetUrl("rtmps://example.com/app/key"),
        )
    }

    @Test
    fun validatePushTargetUrl_rejectsInvalid() {
        assertNull(validatePushTargetUrl(""))
        assertNull(validatePushTargetUrl("http://example.com/live"))
        assertNull(validatePushTargetUrl("rtsp://example.com/stream"))
        assertNull(validatePushTargetUrl("ftp://example.com/live"))
    }

    @Test
    fun create_trimsNameAndUrl() {
        val target = PushTarget.create("  生产  ", "  rtmp://127.0.0.1/live  ")
        assertEquals("生产", target.name)
        assertEquals("rtmp://127.0.0.1/live", target.url)
        assertTrue(target.id.isNotBlank())
    }
}
