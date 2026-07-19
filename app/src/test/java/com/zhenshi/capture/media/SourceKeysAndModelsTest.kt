package com.zhenshi.capture.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不依赖 android.net.Uri 的 JVM 可测部分；encode/decode 闭环见 androidTest。 */
class SourceKeysEncodeTest {

    @Test
    fun encode_usbAndNetwork() {
        assertEquals(
            "usb:1002|Capture|21325|8457",
            SourceKeys.encode(
                SignalSource.UsbDevice(1002, "Capture", 0x534d, 0x2109),
            ),
        )
        assertEquals(
            "rtmp://192.168.1.1/live",
            SourceKeys.encode(SignalSource.RtmpUrl("rtmp://192.168.1.1/live")),
        )
        assertEquals(
            "rtsp://10.0.0.2/stream",
            SourceKeys.encode(SignalSource.RtspUrl("rtsp://10.0.0.2/stream")),
        )
    }

    @Test
    fun encodeProfile() {
        assertEquals("1920x1080@30", SourceKeys.encodeProfile(VideoProfile(1920, 1080, 30)))
        assertEquals("", SourceKeys.encodeProfile(null))
    }
}

class ParseNetworkSourceTest {

    @Test
    fun acceptsRtmpAndRtspSchemes() {
        assertEquals(
            SignalSource.RtmpUrl("rtmp://host/app"),
            parseNetworkSource("  rtmp://host/app  "),
        )
        assertEquals(
            SignalSource.RtmpUrl("rtmps://host/app"),
            parseNetworkSource("rtmps://host/app"),
        )
        assertEquals(
            SignalSource.RtspUrl("rtsp://host/stream"),
            parseNetworkSource("rtsp://host/stream"),
        )
        assertEquals(
            SignalSource.RtspUrl("rtsps://host/stream"),
            parseNetworkSource("rtsps://host/stream"),
        )
    }

    @Test
    fun rejectsUnsupported() {
        assertNull(parseNetworkSource(""))
        assertNull(parseNetworkSource("   "))
        assertNull(parseNetworkSource("http://host/live"))
        assertNull(parseNetworkSource("srt://host/live"))
    }
}

class VideoProfileTest {

    @Test
    fun fromResolutions_expandsFpsAndSortsByArea() {
        val profiles = VideoProfile.fromResolutions(
            listOf(1280 to 720, 1920 to 1080, 1280 to 720),
        )
        assertEquals(
            listOf(
                VideoProfile(1920, 1080, 30),
                VideoProfile(1920, 1080, 60),
                VideoProfile(1280, 720, 30),
                VideoProfile(1280, 720, 60),
            ),
            profiles,
        )
    }

    @Test
    fun openFallbacks_containsDefaultPreview() {
        assertTrue(VideoProfile.openFallbacks().contains(VideoProfile.DEFAULT_PREVIEW))
    }
}

class PushSessionStateTest {

    @Test
    fun isStreaming_derivedFromReadyOnly() {
        assertFalse(PushSessionState().isStreaming)
        assertFalse(PushSessionState(connection = ConnectionState.Connecting).isStreaming)
        assertFalse(PushSessionState(connection = ConnectionState.Error("x")).isStreaming)
        assertTrue(PushSessionState(connection = ConnectionState.Ready).isStreaming)
    }
}

class BitratePresetTest {

    @Test
    fun forResolution_matchesPixelTiers() {
        assertEquals(BitratePreset.HIGH, BitratePreset.forResolution(1920, 1080))
        assertEquals(BitratePreset.MID, BitratePreset.forResolution(1280, 720))
        assertEquals(BitratePreset.LOW, BitratePreset.forResolution(640, 480))
    }

    @Test
    fun atLeast_raisesBelowFloorOnly() {
        assertEquals(BitratePreset.HIGH, BitratePreset.atLeast(BitratePreset.LOW, BitratePreset.HIGH))
        assertEquals(BitratePreset.HIGH, BitratePreset.atLeast(BitratePreset.HIGH, BitratePreset.MID))
        assertEquals(BitratePreset.MID, BitratePreset.atLeast(BitratePreset.MID, BitratePreset.MID))
    }
}
