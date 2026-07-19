package com.zhenshi.capture.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** SourceKeys.decode 依赖 android.net.Uri，放在仪器化测试中验证闭环。 */
@RunWith(AndroidJUnit4::class)
class SourceKeysInstrumentedTest {

    @Test
    fun encodeDecode_usb_roundTrip() {
        val source = SignalSource.UsbDevice(
            deviceId = 1002,
            name = "Capture",
            vendorId = 0x534d,
            productId = 0x2109,
        )
        assertEquals(source, SourceKeys.decode(SourceKeys.encode(source)))
    }

    @Test
    fun decode_usb_legacyFormats() {
        assertEquals(
            SignalSource.UsbDevice(7, "USB 7"),
            SourceKeys.decode("usb:7"),
        )
        assertEquals(
            SignalSource.UsbDevice(7, "Cam"),
            SourceKeys.decode("usb:7|Cam"),
        )
    }

    @Test
    fun encodeDecode_networkUrls() {
        assertEquals(
            SignalSource.RtmpUrl("rtmp://192.168.1.1/live"),
            SourceKeys.decode(SourceKeys.encode(SignalSource.RtmpUrl("rtmp://192.168.1.1/live"))),
        )
        assertEquals(
            SignalSource.RtspUrl("rtsp://10.0.0.2/stream"),
            SourceKeys.decode(SourceKeys.encode(SignalSource.RtspUrl("rtsp://10.0.0.2/stream"))),
        )
    }

    @Test
    fun decode_percentEncodedUsbName() {
        assertEquals(
            SignalSource.UsbDevice(1, "Cam A", 0, 0),
            SourceKeys.decode("usb:1|Cam%20A|0|0"),
        )
    }

    @Test
    fun decode_invalid_returnsNull() {
        assertNull(SourceKeys.decode(""))
        assertNull(SourceKeys.decode("http://example.com"))
        assertNull(SourceKeys.decode("usb:abc"))
    }

    @Test
    fun encodeDecode_profile_roundTrip() {
        val profile = VideoProfile(1920, 1080, 30)
        assertEquals(profile, SourceKeys.decodeProfile(SourceKeys.encodeProfile(profile)))
        assertNull(SourceKeys.decodeProfile(""))
        assertNull(SourceKeys.decodeProfile("bad"))
    }
}
