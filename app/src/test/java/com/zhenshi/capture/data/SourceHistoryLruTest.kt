package com.zhenshi.capture.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceHistoryLruTest {
    @Test
    fun add_movesExistingToFront() {
        val next = SourceHistoryLru.add(listOf("a", "b", "c"), "b")
        assertEquals(listOf("b", "a", "c"), next)
    }

    @Test
    fun add_capsLength() {
        val existing = (1..20).map { "u$it" }
        val next = SourceHistoryLru.add(existing, "new", max = 20)
        assertEquals(20, next.size)
        assertEquals("new", next.first())
        assertEquals(false, next.contains("u20"))
    }

    @Test
    fun networkOnly_filtersUsbKeys() {
        val items = listOf(
            "rtmp://192.168.1.1/live",
            "usb:1|Cam|1|2",
            "rtsp://host/stream",
        )
        assertEquals(
            listOf("rtmp://192.168.1.1/live", "rtsp://host/stream"),
            SourceHistoryLru.networkOnly(items),
        )
    }

    @Test
    fun encodeDecode_roundTrip() {
        val list = listOf("a", "b")
        assertEquals(list, SourceHistoryLru.decode(SourceHistoryLru.encode(list)))
    }
}
