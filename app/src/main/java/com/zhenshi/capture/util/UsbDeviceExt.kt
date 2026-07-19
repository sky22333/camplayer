package com.zhenshi.capture.util

import android.hardware.usb.UsbDevice
import com.jiangdg.ausbc.utils.CameraUtils
import com.zhenshi.capture.media.SignalSource

fun UsbDevice.isUvcCandidate(): Boolean {
    if (CameraUtils.isUsbCamera(this)) return true
    for (i in 0 until interfaceCount) {
        val intf = getInterface(i)
        if (intf.interfaceClass == 14 ||
            (intf.interfaceClass == 239 && intf.interfaceSubclass == 2)
        ) {
            return true
        }
    }
    return false
}

fun UsbDevice.toSignalSource(): SignalSource.UsbDevice =
    SignalSource.UsbDevice(
        deviceId = deviceId,
        name = productName?.takeIf { it.isNotBlank() }
            ?: "UVC ${vendorId.toString(16)}:${productId.toString(16)}",
        vendorId = vendorId,
        productId = productId,
    )

/** 同一物理设备：先比 deviceId，再比 VID/PID（须有效 vendorId）。 */
fun SignalSource.UsbDevice.matchesUsb(other: SignalSource.UsbDevice): Boolean {
    if (deviceId == other.deviceId) return true
    return vendorId != 0 &&
        vendorId == other.vendorId &&
        productId == other.productId
}

fun SignalSource.UsbDevice.matchesUsb(device: UsbDevice): Boolean {
    if (deviceId == device.deviceId) return true
    return vendorId != 0 &&
        vendorId == device.vendorId &&
        productId == device.productId
}
