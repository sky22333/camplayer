package com.zhenshi.capture.screens.usb

import android.hardware.usb.UsbDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhenshi.capture.data.SourceHistoryStore
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.media.VideoProfile
import com.zhenshi.capture.media.usb.UsbCameraController
import com.zhenshi.capture.media.usb.UsbHostCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class UsbUiState(
    val devices: List<SignalSource.UsbDevice> = emptyList(),
    val selected: SignalSource.UsbDevice? = null,
    val probingCapabilities: Boolean = false,
)

@HiltViewModel
class UsbViewModel @Inject constructor(
    private val usbCamera: UsbCameraController,
    private val usbHostCoordinator: UsbHostCoordinator,
    historyStore: SourceHistoryStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(UsbUiState())
    val uiState: StateFlow<UsbUiState> = _uiState.asStateFlow()

    val recent: StateFlow<List<String>> = historyStore.recent.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val capabilityRevision = usbCamera.capabilityRevision
    val usbPermissionGrantedEvents: SharedFlow<SignalSource.UsbDevice> =
        usbHostCoordinator.usbPermissionGrantedEvents

    private var probeJob: Job? = null
    /** 已探测过的 deviceId。 */
    private var lastProbedDeviceId: Int? = null

    init {
        viewModelScope.launch {
            yield()
            usbHostCoordinator.reconcileDevices()
            usbHostCoordinator.devices.collect { applyDevices(it) }
        }
    }

    /** 手动刷新：统一走 reconcile。 */
    fun refresh() {
        usbHostCoordinator.reconcileDevices()
    }

    fun select(device: SignalSource.UsbDevice) {
        _uiState.update { it.copy(selected = device) }
        prepareAndProbe(device, force = true)
    }

    fun onUsbPermissionGranted(device: SignalSource.UsbDevice) {
        prepareAndProbe(device, force = true)
    }

    fun profiles(deviceId: Int): List<VideoProfile> = usbCamera.capabilityProfiles(deviceId)

    fun preferredProfile(deviceId: Int): VideoProfile = usbCamera.preferredPreviewProfile(deviceId)

    fun findDevice(deviceId: Int): UsbDevice? = usbCamera.findUsbDevice(deviceId)

    fun hasPermission(device: UsbDevice): Boolean = usbCamera.hasPermission(device)

    /**
     * 申请 USB 访问权。未授权时登记 pending，供回前台用 hasPermission 收尾
     *（AUSBC 权限广播可能 null device，无 onConnect）。
     * @return false = MultiCameraClient 未就绪
     */
    fun requestUsbAccess(device: UsbDevice): Boolean {
        if (usbCamera.hasPermission(device)) return true
        usbHostCoordinator.markPermissionRequested(device.deviceId)
        return usbCamera.requestDevicePermission(device)
    }

    private fun applyDevices(devices: List<SignalSource.UsbDevice>) {
        _uiState.update { state ->
            val selected = state.selected?.takeIf { sel ->
                devices.any { it.deviceId == sel.deviceId }
            } ?: devices.firstOrNull()
            state.copy(devices = devices, selected = selected)
        }
        val selected = _uiState.value.selected
        if (selected == null) {
            lastProbedDeviceId = null
            return
        }
        prepareAndProbe(selected, force = false)
    }

    /** 单飞探测；[force] 为用户点选/授权。空结果不记 lastProbed，便于 CB 就绪后补探。 */
    private fun prepareAndProbe(device: SignalSource.UsbDevice, force: Boolean) {
        val usbDevice = findDevice(device.deviceId) ?: return
        if (!hasPermission(usbDevice)) return
        if (!force && device.deviceId == lastProbedDeviceId) return

        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            _uiState.update { it.copy(probingCapabilities = true) }
            try {
                val profiles = withContext(Dispatchers.IO) {
                    usbCamera.probeCapabilities(device)
                }
                if (profiles.isNotEmpty()) {
                    lastProbedDeviceId = device.deviceId
                }
            } finally {
                _uiState.update { it.copy(probingCapabilities = false) }
            }
        }
    }
}
