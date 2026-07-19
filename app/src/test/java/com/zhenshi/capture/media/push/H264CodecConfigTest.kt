package com.zhenshi.capture.media.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class H264CodecConfigTest {
    @Test
    fun parseAnnexB_extractsSpsAndPps() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val data = startCode + sps + startCode + pps
        val parsed = H264CodecConfig.parseSpsPps(data)
        assertNotNull(parsed)
        assertEquals(sps.toList(), parsed!!.first.array().toList())
        assertEquals(pps.toList(), parsed.second.array().toList())
    }

    @Test
    fun parseAvcC_extractsSpsAndPps() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val data = byteArrayOf(
            1, 0x42, 0x00, 0x1E, 0xFF.toByte(),
            0xE1.toByte(),
            0x00, sps.size.toByte(),
        ) + sps + byteArrayOf(1, 0x00, pps.size.toByte()) + pps
        val parsed = H264CodecConfig.parseSpsPps(data)
        assertNotNull(parsed)
        assertEquals(sps.toList(), parsed!!.first.array().toList())
        assertEquals(pps.toList(), parsed.second.array().toList())
    }

    @Test
    fun parseEmpty_returnsNull() {
        assertNull(H264CodecConfig.parseSpsPps(byteArrayOf()))
    }
}
