package com.zhenshi.capture.media.usb

import android.hardware.usb.UsbDevice
import android.util.Log
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.navigation.AppNavigationRequests
import com.zhenshi.capture.util.toSignalSource
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** USB 热插拔与授权协调；设备列表见 [devices]。 */
@Singleton
class UsbHostCoordinator @Inject constructor(
    private val usbCamera: UsbCameraController,
    private val navigationRequests: AppNavigationRequests,
) {
    private val _usbPermissionGranted = MutableSharedFlow<SignalSource.UsbDevice>(extraBufferCapacity = 1)
    val usbPermissionGrantedEvents: SharedFlow<SignalSource.UsbDevice> =
        _usbPermissionGranted.asSharedFlow()

    private val _devices = MutableStateFlow<List<SignalSource.UsbDevice>>(emptyList())
    val devices: StateFlow<List<SignalSource.UsbDevice>> = _devices.asStateFlow()

    @Volatile
    private var pendingPermissionDeviceId: Int? = null

    private val lastEmittedGrantDeviceId = AtomicInteger(Int.MIN_VALUE)

    init {
        usbCamera.addDeviceEventListener(object : UsbDeviceEventListener {
            override fun onDeviceAttached(device: UsbDevice) {
                reconcileDevices()
            }

            override fun onDeviceConnected(device: UsbDevice) {
                onUsbAccessGranted(device)
            }

            override fun onDeviceDisconnected(device: UsbDevice) {
                if (pendingPermissionDeviceId == device.deviceId) {
                    pendingPermissionDeviceId = null
                }
                if (lastEmittedGrantDeviceId.get() == device.deviceId) {
                    lastEmittedGrantDeviceId.set(Int.MIN_VALUE)
                }
                reconcileDevices()
            }
        })
    }

    fun ensureMonitoring() {
        usbCamera.ensureMonitorRegistered()
    }

    fun markPermissionRequested(deviceId: Int) {
        pendingPermissionDeviceId = deviceId
        Log.i(TAG, "markPermissionRequested deviceId=$deviceId")
    }

    /** 授权完成：刷新列表、通知 UI、跳转设备 Tab。 */
    fun onUsbAccessGranted(device: UsbDevice) {
        val id = device.deviceId
        if (pendingPermissionDeviceId == id) {
            pendingPermissionDeviceId = null
        }
        if (!lastEmittedGrantDeviceId.compareAndSet(Int.MIN_VALUE, id)) {
            if (lastEmittedGrantDeviceId.get() == id) {
                Log.i(TAG, "onUsbAccessGranted skip duplicate deviceId=$id")
                reconcileDevices()
                return
            }
            lastEmittedGrantDeviceId.set(id)
        }
        reconcileDevices()
        Log.i(TAG, "onUsbAccessGranted deviceId=$id")
        _usbPermissionGranted.tryEmit(device.toSignalSource())
        navigationRequests.requestOpenUsbTab()
    }

    /** 回前台补齐已授权但仍待处理的设备。 */
    fun completePendingPermissionIfGranted() {
        val pendingId = pendingPermissionDeviceId ?: return
        val device = usbCamera.findUsbDevice(pendingId) ?: return
        if (!usbCamera.hasPermission(device)) return
        Log.i(TAG, "completePendingPermissionIfGranted deviceId=$pendingId")
        onUsbAccessGranted(device)
    }

    fun reconcileDevices() {
        ensureMonitoring()
        val next = usbCamera.listDevices()
        if (_devices.value == next) return
        _devices.value = next
    }

    /** 外插：刷新列表并跳转设备 Tab。 */
    fun onExternalAttach() {
        reconcileDevices()
        navigationRequests.requestOpenUsbTab()
    }

    /** 拓扑变化：刷新列表并补齐待授权。 */
    fun onTopologyChanged() {
        reconcileDevices()
        completePendingPermissionIfGranted()
    }

    companion object {
        private const val TAG = "UsbHostCoordinator"
    }
}
