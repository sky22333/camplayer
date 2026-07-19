package com.zhenshi.capture.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcDescriptorParserTest {

    @Test
    fun parseFrameSizes_empty_returnsEmpty() {
        assertTrue(UvcDescriptorParser.parseFrameSizes(ByteArray(0)).isEmpty())
    }

    @Test
    fun parseFrameSizes_extractsMjpegAndUncompressedFrames() {
        val raw = buildList {
            addAll(csInterfaceFrame(subtype = 0x07, width = 1920, height = 1080).asList())
            addAll(csInterfaceFrame(subtype = 0x05, width = 1280, height = 720).asList())
            // 过小分辨率应被过滤
            addAll(csInterfaceFrame(subtype = 0x07, width = 80, height = 60).asList())
            // 非 CS_INTERFACE
            addAll(byteArrayOf(4, 0x09, 0, 0).asList())
        }.toByteArray()

        assertEquals(
            listOf(1920 to 1080, 1280 to 720),
            UvcDescriptorParser.parseFrameSizes(raw),
        )
    }

    @Test
    fun parseFrameSizes_dedupesSameResolution() {
        val raw = buildList {
            addAll(csInterfaceFrame(subtype = 0x07, width = 1280, height = 720).asList())
            addAll(csInterfaceFrame(subtype = 0x0B, width = 1280, height = 720).asList())
        }.toByteArray()

        assertEquals(listOf(1280 to 720), UvcDescriptorParser.parseFrameSizes(raw))
    }

    private fun csInterfaceFrame(subtype: Int, width: Int, height: Int): ByteArray {
        val bytes = ByteArray(26)
        bytes[0] = 26
        bytes[1] = 0x24 // CS_INTERFACE
        bytes[2] = subtype.toByte()
        bytes[5] = (width and 0xFF).toByte()
        bytes[6] = ((width shr 8) and 0xFF).toByte()
        bytes[7] = (height and 0xFF).toByte()
        bytes[8] = ((height shr 8) and 0xFF).toByte()
        return bytes
    }
}
