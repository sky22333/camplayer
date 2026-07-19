package com.zhenshi.capture.media

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.SurfaceView
import com.zhenshi.capture.R
import com.zhenshi.capture.media.network.NetworkStreamPlayer
import com.zhenshi.capture.media.usb.UsbCameraController
import com.zhenshi.capture.media.usb.UsbCaptureMode
import com.zhenshi.capture.media.usb.UsbDeviceEventListener
import com.zhenshi.capture.util.AppRuntimePermissions
import com.zhenshi.capture.util.UsbRuntimePermissions
import com.zhenshi.capture.util.matchesUsb
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

@Singleton
class DefaultPlaybackSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val networkPlayer: NetworkStreamPlayer,
    private val usbCamera: UsbCameraController,
) : PlaybackSession {

    private val _state = MutableStateFlow(PlaybackSessionState())
    override val state: StateFlow<PlaybackSessionState> = _state.asStateFlow()

    private val _usbDeviceLost = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    override val usbDeviceLost: SharedFlow<Int> = _usbDeviceLost.asSharedFlow()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val usbReopenMutex = Mutex()
    private val teardownMutex = Mutex()
    private var leaveJob: Job? = null

    private var activeKind: Kind = Kind.None
    private var pendingSurfaceView: SurfaceView? = null
    private var activeUsbDeviceId: Int? = null
    private var latencySamplingJob: Job? = null

    private enum class Kind { None, Usb, Network }

    private val usbHotplugListener = object : UsbDeviceEventListener {
        override fun onDeviceConnected(device: UsbDevice) {
            scheduleUsbReopen(device)
        }

        override fun onDeviceDisconnected(device: UsbDevice) {
            markUsbDisconnected(device)
        }

        override fun onSoftDisconnected(device: UsbDevice) {
            markUsbSoftDisconnected(device)
        }
    }

    init {
        usbCamera.addDeviceEventListener(usbHotplugListener)
    }

    override suspend fun open(source: SignalSource, profile: VideoProfile?) {
        leaveJob?.join()
        val nextKind = kindOf(source)
        when {
            activeKind == Kind.Usb && nextKind == Kind.Usb -> usbCamera.stopPreview()
            activeKind == Kind.Network && nextKind == Kind.Network -> networkPlayer.release()
            activeKind != Kind.None && activeKind != nextKind -> releaseActiveSource()
        }

        _state.update {
            it.copy(
                source = source,
                profile = profile,
                connection = ConnectionState.Connecting,
                userMessage = null,
                measuredLatencyMs = null,
            )
        }
        try {
            when (source) {
                is SignalSource.UsbDevice -> {
                    if (!UsbRuntimePermissions.allGranted(context)) {
                        error(context.getString(R.string.usb_runtime_permission_denied))
                    }
                    activeKind = Kind.Usb
                    activeUsbDeviceId = source.deviceId
                    usbCamera.ensureMonitorRegistered()
                    val result = usbCamera.openWithFallback(
                        source = source,
                        profile = profile,
                        surfaceView = pendingSurfaceView,
                        mode = UsbCaptureMode.Preview,
                    )
                    val userMessage = result.fallbackFrom?.let {
                        context.getString(R.string.profile_fallback, result.profile.label)
                    }
                    _state.update {
                        it.copy(
                            profile = result.profile,
                            connection = ConnectionState.Ready,
                            measuredLatencyMs = null,
                            userMessage = userMessage,
                        )
                    }
                }
                is SignalSource.RtmpUrl, is SignalSource.RtspUrl -> {
                    val networkUrl = when (source) {
                        is SignalSource.RtmpUrl -> source.url
                        is SignalSource.RtspUrl -> source.url
                    }
                    if (AppRuntimePermissions.urlRequiresLocalNetwork(networkUrl) &&
                        !AppRuntimePermissions.localNetworkGranted(context)
                    ) {
                        error(context.getString(R.string.network_local_permission_denied))
                    }
                    activeKind = Kind.Network
                    activeUsbDeviceId = null
                    networkPlayer.open(source)
                    _state.update { it.copy(connection = ConnectionState.Ready) }
                    startNetworkLatencySampling()
                }
            }
        } catch (e: Exception) {
            stopLatencySampling()
            activeKind = Kind.None
            activeUsbDeviceId = null
            _state.update {
                it.copy(connection = ConnectionState.Error(e.message ?: "打开失败"))
            }
        }
    }

    override fun previewSurface(): SurfaceView? = pendingSurfaceView

    override fun attachPreview(surfaceView: SurfaceView) {
        pendingSurfaceView = surfaceView
        Log.i(
            TAG,
            "attachPreview view@${System.identityHashCode(surfaceView)} " +
                "valid=${surfaceView.holder.surface.isValid} " +
                "size=${surfaceView.width}x${surfaceView.height} activeKind=$activeKind",
        )
        if (activeKind == Kind.Usb) {
            usbCamera.bindPreviewSurface(surfaceView)
        }
    }

    override fun leavePreview(onNavigate: (() -> Unit)?): Job {
        stopLatencySampling()
        when (activeKind) {
            Kind.Usb -> usbCamera.haltPreviewFramesIfActive()
            Kind.Network -> networkPlayer.playerOrNull()?.playWhenReady = false
            Kind.None -> Unit
        }
        // 同步剥离 Surface，异步只拆相机/UAC，避免收尾覆盖快速重进的 attach。
        pendingSurfaceView = null
        usbCamera.detachPreviewSurface()
        onNavigate?.invoke()
        leaveJob?.takeIf { it.isActive }?.let { return it }
        if (activeKind == Kind.None) {
            return completedJob()
        }
        return sessionScope.launch {
            teardownMutex.withLock {
                yield()
                performLeave()
            }
        }.also { leaveJob = it }
    }

    /** USB 保留连接；网络 release 播放器。Surface 引用已在 leavePreview 同步清除。 */
    private suspend fun performLeave() {
        stopLatencySampling()
        when (activeKind) {
            Kind.Usb -> {
                usbCamera.stopPushCapture()
                usbCamera.stopPreview()
            }
            Kind.Network -> networkPlayer.release(afterUiSettle = true)
            Kind.None -> Unit
        }
        activeKind = Kind.None
        activeUsbDeviceId = null
        _state.value = PlaybackSessionState()
    }

    override suspend fun suspendForPush() {
        Log.i(
            TAG,
            "suspendForPush activeKind=$activeKind " +
                "pendingSurfaceView=${pendingSurfaceView?.let { "@${System.identityHashCode(it)}" }} " +
                "pendingValid=${pendingSurfaceView?.holder?.surface?.isValid}",
        )
        stopLatencySampling()
        _state.update {
            it.copy(connection = ConnectionState.Idle, measuredLatencyMs = null)
        }
        when (activeKind) {
            Kind.Usb -> {
                pendingSurfaceView?.let { usbCamera.bindPreviewSurface(it) }
                usbCamera.stopPreview()
            }
            Kind.Network -> {
                networkPlayer.release()
                activeKind = Kind.None
            }
            Kind.None -> Unit
        }
    }

    override suspend fun resumePreviewAfterPush() {
        resumeUsbPreview(reason = "afterPush", allowError = true)
    }

    override suspend fun pausePreviewForAppBackground() {
        if (activeKind != Kind.Usb) return
        val connection = _state.value.connection
        Log.i(TAG, "pausePreviewForAppBackground connection=$connection")
        stopLatencySampling()
        runCatching { usbCamera.stopPushCapture() }
        runCatching { usbCamera.stopPreview() }
        if (connection is ConnectionState.Error) return
        _state.update {
            it.copy(connection = ConnectionState.Idle, measuredLatencyMs = null)
        }
    }

    override suspend fun resumePreviewFromAppBackground() {
        resumeUsbPreview(reason = "fromBackground", allowError = true)
    }

    /** 恢复 USB 预览；[allowError] 为 true 时 Idle/Error 均可恢复。 */
    private suspend fun resumeUsbPreview(reason: String, allowError: Boolean) {
        leaveJob?.join()
        val surface = pendingSurfaceView
        if (surface == null) {
            Log.e(TAG, "resumeUsbPreview($reason) skip pendingSurfaceView=null")
            return
        }
        val state = _state.value
        val source = state.source as? SignalSource.UsbDevice ?: return
        if (!UsbRuntimePermissions.allGranted(context)) return

        when (state.connection) {
            ConnectionState.Ready -> return
            ConnectionState.Connecting -> {
                Log.w(TAG, "resumeUsbPreview($reason) skip connection=Connecting")
                return
            }
            ConnectionState.Idle -> Unit
            is ConnectionState.Error -> if (!allowError) return
        }

        if (activeKind != Kind.Usb) {
            if (!allowError) return
            activeKind = Kind.Usb
            activeUsbDeviceId = source.deviceId
        }

        Log.i(
            TAG,
            "resumeUsbPreview($reason) id=${source.deviceId} " +
                "view@${System.identityHashCode(surface)} " +
                "valid=${surface.holder.surface.isValid} " +
                "size=${surface.width}x${surface.height}",
        )
        usbCamera.bindPreviewSurface(surface)
        _state.update {
            it.copy(
                connection = ConnectionState.Connecting,
                userMessage = null,
            )
        }
        open(source, state.profile)
    }

    override fun consumeUserMessage() {
        _state.update { it.copy(userMessage = null) }
    }

    override fun haltUsbPreviewFrames() {
        if (activeKind != Kind.Usb) return
        usbCamera.haltPreviewFramesIfActive()
    }

    /** 仅采样网络缓冲延迟。 */
    private fun startNetworkLatencySampling() {
        stopLatencySampling()
        latencySamplingJob = mainScope.launch {
            while (isActive && activeKind == Kind.Network) {
                val latency = networkPlayer.readBufferedLatencyMs()
                _state.update { current ->
                    if (current.measuredLatencyMs == latency) current
                    else current.copy(measuredLatencyMs = latency)
                }
                delay(LATENCY_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun stopLatencySampling() {
        latencySamplingJob?.cancel()
        latencySamplingJob = null
    }

    private fun markUsbDisconnected(device: UsbDevice) {
        if (!isWatchingUsbDevice(device)) return
        Log.i(TAG, "markUsbDisconnected id=${device.deviceId}")
        stopLatencySampling()
        activeKind = Kind.None
        activeUsbDeviceId = null
        _state.update {
            it.copy(
                connection = ConnectionState.Error(context.getString(R.string.usb_device_missing)),
                measuredLatencyMs = null,
            )
        }
        _usbDeviceLost.tryEmit(device.deviceId)
    }

    /** 意外软断开：标 Error；预览中则自动重开。 */
    private fun markUsbSoftDisconnected(device: UsbDevice) {
        if (!isWatchingUsbDevice(device)) return
        val connectionBefore = _state.value.connection
        Log.i(TAG, "markUsbSoftDisconnected id=${device.deviceId} was=$connectionBefore")
        stopLatencySampling()
        _state.update {
            it.copy(
                connection = ConnectionState.Error(context.getString(R.string.usb_preview_interrupted)),
                measuredLatencyMs = null,
            )
        }
        _usbDeviceLost.tryEmit(device.deviceId)
        if (
            connectionBefore is ConnectionState.Ready ||
            connectionBefore is ConnectionState.Connecting
        ) {
            scheduleUsbReopen(device)
        }
    }

    private fun scheduleUsbReopen(device: UsbDevice) {
        if (pendingSurfaceView == null) return
        val state = _state.value
        val lostSource = state.source as? SignalSource.UsbDevice ?: return
        if (state.connection !is ConnectionState.Error) return
        if (!lostSource.matchesUsb(device)) return
        if (!usbCamera.hasPermission(device)) return

        val updatedSource = lostSource.copy(
            deviceId = device.deviceId,
            name = device.productName?.takeIf { it.isNotBlank() } ?: lostSource.name,
        )
        mainScope.launch {
            usbReopenMutex.withLock {
                if (pendingSurfaceView == null) return@withLock
                if (_state.value.connection !is ConnectionState.Error) return@withLock
                Log.i(TAG, "reopen after reattach id=${device.deviceId}")
                open(updatedSource, state.profile)
            }
        }
    }

    private fun isWatchingUsbDevice(device: UsbDevice): Boolean {
        if (activeKind == Kind.Usb && activeUsbDeviceId == device.deviceId) return true
        val source = _state.value.source as? SignalSource.UsbDevice ?: return false
        return when (_state.value.connection) {
            ConnectionState.Ready, ConnectionState.Connecting -> source.matchesUsb(device)
            else -> false
        }
    }

    private suspend fun releaseActiveSource() {
        when (activeKind) {
            Kind.Usb -> usbCamera.stopPreviewAndReleaseDevice(activeUsbDeviceId)
            Kind.Network -> networkPlayer.release()
            Kind.None -> Unit
        }
        activeKind = Kind.None
        activeUsbDeviceId = null
    }

    private fun kindOf(source: SignalSource): Kind = when (source) {
        is SignalSource.UsbDevice -> Kind.Usb
        is SignalSource.RtmpUrl, is SignalSource.RtspUrl -> Kind.Network
    }

    companion object {
        private const val TAG = "DefaultPlaybackSession"
        private const val LATENCY_SAMPLE_INTERVAL_MS = 1_000L

        private fun completedJob(): Job = Job().also { it.complete() }
    }
}
