package com.zhenshi.capture.util

import com.zhenshi.capture.media.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencySamplerTest {

    @Test
    fun recordReadClear_lifecycle() {
        val sampler = LatencySampler()
        assertNull(sampler.read())

        sampler.record(0)
        assertNull(sampler.read())

        sampler.record(33)
        assertEquals(33L, sampler.read())

        sampler.record(40)
        assertEquals(40L, sampler.read())

        sampler.clear()
        assertNull(sampler.read())
    }
}

class UsbDeviceMatchesTest {

    @Test
    fun matchesUsb_byDeviceId() {
        val a = SignalSource.UsbDevice(1, "A", vendorId = 0, productId = 0)
        val b = SignalSource.UsbDevice(1, "B", vendorId = 1, productId = 2)
        assertTrue(a.matchesUsb(b))
    }

    @Test
    fun matchesUsb_byVidPid_whenVendorValid() {
        val a = SignalSource.UsbDevice(1, "A", vendorId = 0x534d, productId = 0x2109)
        val b = SignalSource.UsbDevice(99, "B", vendorId = 0x534d, productId = 0x2109)
        assertTrue(a.matchesUsb(b))
    }

    @Test
    fun matchesUsb_rejectsWhenVendorZeroOrMismatch() {
        val zeroVendor = SignalSource.UsbDevice(1, "A", vendorId = 0, productId = 1)
        val other = SignalSource.UsbDevice(2, "B", vendorId = 0, productId = 1)
        assertFalse(zeroVendor.matchesUsb(other))

        val a = SignalSource.UsbDevice(1, "A", vendorId = 1, productId = 1)
        val b = SignalSource.UsbDevice(2, "B", vendorId = 1, productId = 2)
        assertFalse(a.matchesUsb(b))
    }
}
