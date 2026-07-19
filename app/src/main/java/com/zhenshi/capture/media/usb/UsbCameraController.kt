package com.zhenshi.capture.media.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.USBMonitor
import com.zhenshi.capture.BuildConfig
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.media.UsbOpenResult
import com.zhenshi.capture.media.VideoProfile
import com.zhenshi.capture.util.isUvcCandidate
import com.zhenshi.capture.util.toSignalSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.coroutines.resume

private fun resumeCoroutineOnce(
    continuation: kotlinx.coroutines.CancellableContinuation<Unit>,
    resumed: AtomicBoolean,
) {
    if (resumed.compareAndSet(false, true)) {
        continuation.resume(Unit)
    }
}

interface UsbDeviceEventListener {
    fun onDeviceAttached(device: UsbDevice) = Unit
    fun onDeviceConnected(device: UsbDevice)
    fun onDeviceDisconnected(device: UsbDevice)
    /** 意外软断开（设备仍在）；主动 close 不回调。 */
    fun onSoftDisconnected(device: UsbDevice) = Unit
}

enum class UsbCaptureMode {
    Idle,
    Preview,
    Push,
}

/** AUSBC UVC 采集；推流时外送 NV21 / UAC PCM。 */
@Singleton
class UsbCameraController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceMicPlayer: UsbDeviceMicPlayer,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sessionMutex = Mutex()
    /** 关流/软断开收尾（与探测 scope 分离）。 */
    private val cameraScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 能力探测专用。 */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var client: MultiCameraClient? = null
    private var activeCamera: MultiCameraClient.ICamera? = null
    /** 当前开流使用的控制块；用于识别迟到 onDisconnect 是否针对旧块。 */
    private var activeControlBlock: USBMonitor.UsbControlBlock? = null
    private var captureMode = UsbCaptureMode.Idle
    /** 主动 closeCamera 后忽略对应 onDisconnect。 */
    private val expectedSoftDisconnectDeviceIds = ConcurrentHashMap.newKeySet<Int>()
    /** 开流世代，用于听声/拆流校验。 */
    private var activeSessionGen = 0L
    private val sessionGenSeed = AtomicLong(0L)
    private var micStartJob: Job? = null

    private var previewSurfaceView: SurfaceView? = null
    private var previewActive = false
    private var pushNv21Listener: ((ByteArray, Int, Int) -> Unit)? = null
    private var pushPcmListener: ((ByteArray) -> Unit)? = null
    private val pushEncodingActive = AtomicBoolean(false)
    private val pushNonNv21Logged = AtomicBoolean(false)
    @Volatile var lastPushMicParams: UsbDeviceMicPlayer.MicParams? = null
        private set
    private val deviceEventListeners = CopyOnWriteArraySet<UsbDeviceEventListener>()

    private val controlBlocks = ConcurrentHashMap<Int, USBMonitor.UsbControlBlock>()
    /** 正在 warm 控制块的 deviceId。 */
    private val warmingControlBlockDeviceIds = ConcurrentHashMap.newKeySet<Int>()
    private val connectWaiters = ConcurrentHashMap<Int, CompletableDeferred<USBMonitor.UsbControlBlock>>()
    private val capabilityCache = ConcurrentHashMap<CapabilityKey, List<VideoProfile>>()
    private val probeInFlight = ConcurrentHashMap<CapabilityKey, CompletableDeferred<List<VideoProfile>>>()

    private val _capabilityRevision = MutableStateFlow(0)
    val capabilityRevision: StateFlow<Int> = _capabilityRevision.asStateFlow()

    fun addDeviceEventListener(listener: UsbDeviceEventListener) {
        deviceEventListeners.add(listener)
    }

    fun ensureMonitorRegistered() {
        ensureClient()
    }

    fun listDevices(): List<SignalSource.UsbDevice> =
        usbManager.deviceList.values
            .filter { it.isUvcCandidate() }
            .map { it.toSignalSource() }
            .sortedBy { it.name }

    /** 经 AUSBC 申请 USB 权限。 */
    fun requestDevicePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        ensureClient()
        return client?.requestPermission(device) == true
    }

    fun findUsbDevice(deviceId: Int): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
            ?: client?.getDeviceList()?.firstOrNull { it.deviceId == deviceId }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** 开流默认档（非能力列表）。 */
    fun preferredPreviewProfile(deviceId: Int): VideoProfile {
        val caps = capabilityProfiles(deviceId)
        return caps
            .filter { it.fps == VideoProfile.DEFAULT_FPS }
            .maxByOrNull { it.width * it.height }
            ?: caps.firstOrNull()
            ?: VideoProfile.DEFAULT_PREVIEW
    }

    /** 已探测能力集；未探测返回空。 */
    fun capabilityProfiles(deviceId: Int): List<VideoProfile> {
        findUsbDevice(deviceId)?.let { device ->
            capabilityCache[CapabilityKey(device.vendorId, device.productId)]?.let { return it }
        }
        val live = activeCamera?.takeIf {
            it.getUsbDevice().deviceId == deviceId && previewActive
        }
        if (live != null) {
            return profilesFromCamera(live)
        }
        return emptyList()
    }

    fun scheduleCapabilityProbe(source: SignalSource.UsbDevice) {
        val key = CapabilityKey(source.vendorId, source.productId)
        if (capabilityCache.containsKey(key)) return
        if (captureMode != UsbCaptureMode.Idle) return
        if (probeInFlight.containsKey(key)) return

        probeScope.launch {
            try {
                probeCapabilities(source)
            } catch (_: CancellationException) {
            }
        }
    }

    /** 探测并缓存同 VID/PID 能力集；不占用 sessionMutex。 */
    suspend fun probeCapabilities(source: SignalSource.UsbDevice): List<VideoProfile> {
        val key = CapabilityKey(source.vendorId, source.productId)
        capabilityCache[key]?.let { return it }

        probeInFlight[key]?.let { inFlight ->
            return runCatching { inFlight.await() }.getOrElse { emptyList() }
        }

        val device = findUsbDevice(source.deviceId) ?: return emptyList()
        if (!usbManager.hasPermission(device)) return emptyList()

        // 开流中只读缓存，不与 open/close 抢锁
        if (captureMode != UsbCaptureMode.Idle) {
            return capabilityProfiles(source.deviceId)
        }

        val waiter = CompletableDeferred<List<VideoProfile>>()
        val racing = probeInFlight.putIfAbsent(key, waiter)
        if (racing != null) {
            return runCatching { racing.await() }.getOrElse { emptyList() }
        }

        return try {
            capabilityCache[key]?.let { cached ->
                waiter.complete(cached)
                return cached
            }
            currentCoroutineContext().ensureActive()
            val profiles = withContext(Dispatchers.IO) {
                readCapabilitiesForDevice(device, key, source.name)
            }
            waiter.complete(profiles)
            profiles
        } catch (e: CancellationException) {
            waiter.cancel()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "probeCapabilities failed deviceId=${source.deviceId}", e)
            waiter.complete(emptyList())
            emptyList()
        } finally {
            probeInFlight.remove(key)
        }
    }

    suspend fun openWithFallback(
        source: SignalSource.UsbDevice,
        profile: VideoProfile?,
        surfaceView: SurfaceView? = null,
        mode: UsbCaptureMode = UsbCaptureMode.Preview,
    ): UsbOpenResult {
        cancelPendingCapabilityProbes()
        return sessionMutex.withLock {
            val caps = resolveOpenCandidates(source.deviceId)
            val requested = profile ?: caps.firstOrNull() ?: VideoProfile.openFallbacks().first()
            val candidates = buildCandidateProfiles(requested, caps)

            var lastError: Exception? = null
            for (candidate in candidates) {
                try {
                    val outcome = openInternal(source, candidate, surfaceView, mode)
                    val fallbackFrom = candidate.takeIf { it != requested }?.let { requested }
                    return@withLock UsbOpenResult(
                        profile = outcome.profile,
                        fallbackFrom = fallbackFrom,
                    )
                } catch (e: Exception) {
                    lastError = e
                    stopCameraAwaitClosed()
                    previewActive = false
                    captureMode = UsbCaptureMode.Idle
                    if (isSurfaceOrLayoutOpenError(e)) throw e
                }
            }
            throw lastError ?: IllegalStateException("USB 打开失败")
        }
    }

    fun bindPreviewSurface(surfaceView: SurfaceView) {
        previewSurfaceView = surfaceView
        Log.i(
            TAG,
            "bindPreviewSurface view=${surfaceView.javaClass.simpleName}@" +
                "${System.identityHashCode(surfaceView)} " +
                "valid=${surfaceView.holder.surface.isValid} " +
                "size=${surfaceView.width}x${surfaceView.height}",
        )
    }

    fun detachPreviewSurface() {
        Log.i(
            TAG,
            "detachPreviewSurface hadView=${previewSurfaceView != null} " +
                "mode=$captureMode previewActive=$previewActive",
        )
        previewSurfaceView = null
    }

    suspend fun stopPreview() {
        sessionMutex.withLock {
            if (captureMode != UsbCaptureMode.Preview) return@withLock
            val deviceId = activeCamera?.getUsbDevice()?.deviceId
            stopCameraAwaitClosed()
            previewActive = false
            captureMode = UsbCaptureMode.Idle
            if (deviceId != null) {
                warmControlBlock(deviceId)
            }
        }
    }

    fun attachPushListeners(
        onNv21: (ByteArray, Int, Int) -> Unit,
        onPcm: (ByteArray) -> Unit,
    ) {
        pushNv21Listener = onNv21
        pushPcmListener = onPcm
    }

    fun clearPushListeners() {
        pushEncodingActive.set(false)
        pushNv21Listener = null
        pushPcmListener = null
    }

    fun setPushEncodingActive(active: Boolean) {
        pushEncodingActive.set(active)
        if (active) pushNonNv21Logged.set(false)
        Log.i(TAG, "pushEncodingActive=$active")
    }

    suspend fun stopPushCapture() {
        sessionMutex.withLock {
            if (captureMode != UsbCaptureMode.Push) return@withLock
            pushEncodingActive.set(false)
            clearPushListeners()
            micStartJob?.cancel()
            micStartJob = null
            val deviceId = activeCamera?.getUsbDevice()?.deviceId
            runCatching { deviceMicPlayer.stop() }
                .onFailure { Log.w(TAG, "push mic stop failed", it) }
            lastPushMicParams = null
            stopCameraAwaitClosed()
            previewActive = false
            captureMode = UsbCaptureMode.Idle
            if (deviceId != null) {
                warmControlBlock(deviceId)
            }
        }
    }

    /** 切源/拔出时释放连接（离开预览勿用）。 */
    suspend fun stopPreviewAndReleaseDevice(deviceId: Int? = null) {
        sessionMutex.withLock {
            if (captureMode != UsbCaptureMode.Idle) {
                stopCameraAwaitClosed()
            }
            previewActive = false
            captureMode = UsbCaptureMode.Idle
            releaseControlBlocks(deviceId)
        }
    }

    private fun nearestProfile(requested: VideoProfile, caps: List<VideoProfile>): VideoProfile {
        if (caps.isEmpty()) return requested
        return caps.minBy { cap ->
            kotlin.math.abs(cap.width - requested.width) * 10 +
                kotlin.math.abs(cap.height - requested.height) * 10 +
                kotlin.math.abs(cap.fps - requested.fps)
        }
    }

    /** 优先用已有控制块读描述符。 */
    private fun readCapabilitiesForDevice(
        device: UsbDevice,
        key: CapabilityKey,
        sourceName: String,
    ): List<VideoProfile> {
        val raw = resolveRawDescriptors(device) ?: return emptyList()
        return try {
            val sizes = UvcDescriptorParser.parseFrameSizes(raw)
            val profiles = VideoProfile.fromResolutions(sizes)
            if (profiles.isNotEmpty()) {
                cacheCapabilities(key, profiles)
                Log.i(
                    TAG,
                    "probeCapabilities $sourceName -> ${sizes.size} resolutions, " +
                        "${VideoProfile.STANDARD_FPS_OPTIONS} fps (${profiles.size} profiles)",
                )
            }
            profiles
        } catch (e: Exception) {
            Log.w(TAG, "readCapabilitiesForDevice failed", e)
            emptyList()
        }
    }

    /** 有控制块则复用；否则短暂 openDevice 读完即关。 */
    private fun resolveRawDescriptors(device: UsbDevice): ByteArray? {
        controlBlocks[device.deviceId]?.let { block ->
            rawDescriptorsFromControlBlock(block)?.let { return it }
        }
        val connection = usbManager.openDevice(device) ?: return null
        return try {
            connection.rawDescriptors
        } catch (e: Exception) {
            Log.w(TAG, "UsbManager.openDevice rawDescriptors failed", e)
            null
        } finally {
            connection.close()
        }
    }

    private fun rawDescriptorsFromControlBlock(block: USBMonitor.UsbControlBlock): ByteArray? {
        runCatching { block.rawDescriptors }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching { block.connection?.rawDescriptors }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun cancelPendingCapabilityProbes() {
        probeScope.coroutineContext[Job]?.cancelChildren()
        probeInFlight.values.forEach { it.cancel() }
        probeInFlight.clear()
    }

    private suspend fun openInternal(
        source: SignalSource.UsbDevice,
        applied: VideoProfile,
        surfaceView: SurfaceView?,
        mode: UsbCaptureMode,
    ): OpenOutcome {
        Log.i(TAG, "open start deviceId=${source.deviceId} mode=$mode")
        val device = findUsbDevice(source.deviceId) ?: error("设备已断开")
        if (!usbManager.hasPermission(device)) {
            error("需要 USB 访问权限")
        }

        if (surfaceView != null) {
            previewSurfaceView = surfaceView
        }

        stopCameraAwaitClosed()
        previewActive = false
        ensureClient()

        val ctrlBlock = awaitControlBlock(source.deviceId)
        val requestBuilder = CameraRequest.Builder()
            .setPreviewWidth(applied.width)
            .setPreviewHeight(applied.height)
            // 仅 Push 开 NV21 回调
            .setRenderMode(CameraRequest.RenderMode.NORMAL)
            .setAudioSource(CameraRequest.AudioSource.NONE)
        if (mode == UsbCaptureMode.Push) {
            runCatching { requestBuilder.setRawPreviewData(true) }
        }
        val request = requestBuilder.create()

        val renderTarget = resolveRenderSurface(mode, surfaceView)

        val opened = CompletableDeferred<Unit>()
        val camera = CameraUVC(context, device)
        camera.setUsbControlBlock(ctrlBlock)
        if (mode == UsbCaptureMode.Push) {
            camera.addPreviewDataCallBack(object : IPreviewDataCallBack {
                override fun onPreviewData(
                    data: ByteArray?,
                    width: Int,
                    height: Int,
                    format: IPreviewDataCallBack.DataFormat,
                ) {
                    if (!pushEncodingActive.get() || data == null) return
                    if (format == IPreviewDataCallBack.DataFormat.NV21) {
                        pushNv21Listener?.invoke(data, width, height)
                    } else if (pushNonNv21Logged.compareAndSet(false, true)) {
                        Log.w(TAG, "push skip preview format=$format (need NV21)")
                    }
                }
            })
        }
        camera.setCameraStateCallBack(probeStateCallback(opened, source.deviceId, mode))

        val sessionGen = sessionGenSeed.incrementAndGet()
        activeSessionGen = sessionGen
        activeCamera = camera
        activeControlBlock = ctrlBlock
        // 新会话已绑定 CB：后续迟到的旧块 disconnect 不再走 expected 集合。
        expectedSoftDisconnectDeviceIds.remove(source.deviceId)
        Log.i(
            TAG,
            "openCamera deviceId=${source.deviceId} ${applied.width}x${applied.height} " +
                "mode=$mode gen=$sessionGen target=${renderTarget.javaClass.simpleName}",
        )
        withContext(Dispatchers.Main.immediate) {
            camera.openCamera(renderTarget, request)
        }

        try {
            withTimeout(OPEN_PREVIEW_TIMEOUT_MS) { opened.await() }
        } catch (e: CancellationException) {
            if (activeSessionGen == sessionGen) {
                stopCameraAwaitClosed()
                captureMode = UsbCaptureMode.Idle
            }
            throw e
        } catch (e: Exception) {
            if (activeSessionGen == sessionGen) {
                stopCameraAwaitClosed()
                captureMode = UsbCaptureMode.Idle
            }
            throw IllegalStateException(
                (e as? kotlinx.coroutines.TimeoutCancellationException)?.let {
                    if (mode == UsbCaptureMode.Push) {
                        "USB 推流启动超时，请重新插拔设备后重试"
                    } else {
                        "USB 预览启动超时，请重新插拔设备后重试"
                    }
                } ?: e.message ?: if (mode == UsbCaptureMode.Push) {
                    "USB 推流启动失败"
                } else {
                    "USB 预览启动失败"
                },
            )
        }

        if (activeSessionGen != sessionGen || activeCamera !== camera) {
            error("USB 开流已取消")
        }

        previewActive = true
        captureMode = mode
        val openedRequest = camera.getCameraRequest()
        val resultProfile = if (openedRequest != null) {
            VideoProfile(openedRequest.previewWidth, openedRequest.previewHeight, applied.fps)
        } else {
            applied
        }
        val profiles = profilesFromCamera(camera)
        if (profiles.isNotEmpty()) {
            cacheCapabilities(CapabilityKey(source.vendorId, source.productId), profiles)
        }

        // UAC：预览听声异步；推流 PCM 同步就绪（供 AAC 在 Publish 后立刻开编）。
        micStartJob?.cancel()
        val blockForMic = ctrlBlock
        when (mode) {
            UsbCaptureMode.Preview -> {
                micStartJob = cameraScope.launch {
                    if (activeSessionGen != sessionGen || activeCamera !== camera) return@launch
                    runCatching { deviceMicPlayer.start(blockForMic) }
                        .onFailure {
                            if (it is CancellationException) return@onFailure
                            Log.w(TAG, "device mic start failed deviceId=${source.deviceId}", it)
                        }
                }
            }
            UsbCaptureMode.Push -> {
                lastPushMicParams = null
                runCatching {
                    deviceMicPlayer.start(blockForMic) { pcm ->
                        if (pushEncodingActive.get()) {
                            pushPcmListener?.invoke(pcm)
                        }
                    }
                }.onSuccess { params ->
                    lastPushMicParams = params
                }.onFailure {
                    if (it !is CancellationException) {
                        Log.w(TAG, "push mic start failed deviceId=${source.deviceId}", it)
                    }
                }
            }
            UsbCaptureMode.Idle -> Unit
        }

        return OpenOutcome(resultProfile)
    }

    /** 停 UVC native 出帧，不关 USB；须在等 UAC release 之前调用。 */
    private fun stopUvcPreviewFrames(camera: MultiCameraClient.ICamera) {
        runCatching {
            var clazz: Class<*>? = camera.javaClass
            while (clazz != null) {
                try {
                    val field = clazz.getDeclaredField("mUvcCamera")
                    field.isAccessible = true
                    val uvc = field.get(camera) as? com.jiangdg.uvc.UVCCamera ?: return
                    uvc.stopPreview()
                    Log.i(TAG, "UVC stopPreview (halt Surface writes)")
                    return
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
        }.onFailure { Log.w(TAG, "UVC stopPreview failed", it) }
    }

    private fun probeStateCallback(
        opened: CompletableDeferred<Unit>,
        deviceId: Int? = null,
        mode: UsbCaptureMode? = null,
    ) = object : ICameraStateCallBack {
        override fun onCameraState(
            self: MultiCameraClient.ICamera,
            code: ICameraStateCallBack.State,
            msg: String?,
        ) {
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    if (deviceId != null && mode != null) {
                        Log.i(TAG, "camera OPENED deviceId=$deviceId mode=$mode")
                    }
                    if (!opened.isCompleted) opened.complete(Unit)
                }
                ICameraStateCallBack.State.ERROR -> {
                    if (deviceId != null) {
                        Log.e(TAG, "camera ERROR deviceId=$deviceId: $msg")
                    }
                    if (!opened.isCompleted) {
                        opened.completeExceptionally(IllegalStateException(msg ?: "USB 打开失败"))
                    }
                }
                else -> Unit
            }
        }
    }

    private fun cacheCapabilities(key: CapabilityKey, profiles: List<VideoProfile>) {
        if (profiles.isEmpty()) return
        capabilityCache[key] = profiles
        _capabilityRevision.update { it + 1 }
    }

    private fun resolveOpenCandidates(deviceId: Int): List<VideoProfile> {
        val cached = capabilityProfiles(deviceId)
        return cached.ifEmpty { VideoProfile.openFallbacks() }
    }

    private fun buildCandidateProfiles(
        requested: VideoProfile,
        caps: List<VideoProfile>,
    ): List<VideoProfile> {
        if (caps.isEmpty()) return listOf(requested)
        val nearest = nearestProfile(requested, caps)
        return buildList {
            add(requested)
            if (nearest != requested) add(nearest)
            addAll(caps.filter { it != requested && it != nearest })
        }.distinctBy { "${it.width}x${it.height}@${it.fps}" }
    }

    private fun profilesFromCamera(camera: MultiCameraClient.ICamera): List<VideoProfile> {
        val sizes = camera.getAllPreviewSizes(null)
        if (sizes.isEmpty()) return emptyList()
        return VideoProfile.fromResolutions(sizes.map { it.width to it.height })
    }

    private suspend fun awaitControlBlock(deviceId: Int): USBMonitor.UsbControlBlock {
        controlBlocks[deviceId]?.let { cached ->
            if (isDeviceStillAttached(cached.device)) {
                warmingControlBlockDeviceIds.remove(deviceId)
                return cached
            }
            takeAndCloseControlBlock(deviceId)
            warmingControlBlockDeviceIds.remove(deviceId)
        }

        connectWaiters[deviceId]?.let { inFlight ->
            Log.i(TAG, "awaitControlBlock join in-flight deviceId=$deviceId")
            return try {
                withTimeout(CONNECT_TIMEOUT_MS) { inFlight.await() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                error("USB 设备连接失败，请重新插拔设备后重试")
            }
        }

        val device = findUsbDevice(deviceId) ?: error("设备已断开")
        ensureClient()

        // 已授权直接 openDevice
        if (usbManager.hasPermission(device)) {
            val opened = withContext(Dispatchers.IO) { openControlBlockIfPermitted(device) }
            if (opened != null) {
                controlBlocks[deviceId] = opened
                Log.i(TAG, "awaitControlBlock openDevice deviceId=$deviceId")
                return opened
            }
            Log.w(TAG, "awaitControlBlock openDevice failed, fallback requestPermission deviceId=$deviceId")
        }

        repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
            val waiter = CompletableDeferred<USBMonitor.UsbControlBlock>()
            connectWaiters[deviceId] = waiter
            Log.i(TAG, "awaitControlBlock connect deviceId=$deviceId attempt=${attempt + 1}")
            if (client?.requestPermission(device) != true) {
                connectWaiters.remove(deviceId)
                error("USB 监控未就绪")
            }

            try {
                return withTimeout(CONNECT_TIMEOUT_MS) { waiter.await() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                connectWaiters.remove(deviceId)
                if (attempt == MAX_CONNECT_ATTEMPTS - 1) {
                    error("USB 设备连接失败，请重新插拔设备后重试")
                }
            } finally {
                connectWaiters.remove(deviceId)
            }
        }

        error("USB 设备连接失败，请重新插拔设备后重试")
    }

    /** 已授权时经 USBMonitor.openDevice 取控制块。 */
    private fun openControlBlockIfPermitted(device: UsbDevice): USBMonitor.UsbControlBlock? {
        val monitor = peekUsbMonitor() ?: return null
        return runCatching { monitor.openDevice(device) }
            .onFailure { Log.w(TAG, "USBMonitor.openDevice failed", it) }
            .getOrNull()
    }

    private fun peekUsbMonitor(): USBMonitor? {
        val cam = client ?: return null
        return runCatching {
            val field = MultiCameraClient::class.java.getDeclaredField("mUsbMonitor")
            field.isAccessible = true
            field.get(cam) as? USBMonitor
        }.onFailure {
            Log.w(TAG, "peekUsbMonitor failed", it)
        }.getOrNull()
    }

    /** 停写 → 等 UAC 释放 → closeCamera → await CLOSED。 */
    private suspend fun stopCameraAwaitClosed() {
        val camera = activeCamera ?: return
        val deviceId = camera.getUsbDevice().deviceId
        expectedSoftDisconnectDeviceIds.add(deviceId)
        activeSessionGen = sessionGenSeed.incrementAndGet()
        val closed = CompletableDeferred<Unit>()
        camera.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(
                self: MultiCameraClient.ICamera,
                code: ICameraStateCallBack.State,
                msg: String?,
            ) {
                if (code == ICameraStateCallBack.State.CLOSED && !closed.isCompleted) {
                    closed.complete(Unit)
                }
            }
        })
        micStartJob?.cancel()
        micStartJob = null
        withContext(Dispatchers.Main.immediate) {
            stopUvcPreviewFrames(camera)
        }
        withContext(Dispatchers.Default) {
            runCatching { deviceMicPlayer.stop() }
                .onFailure { Log.w(TAG, "deviceMicPlayer.stop failed", it) }
        }
        withContext(Dispatchers.Main.immediate) {
            if (activeCamera === camera) {
                activeCamera = null
                activeControlBlock = null
            }
            runCatching { camera.closeCamera() }
                .onFailure { Log.w(TAG, "closeCamera failed", it) }
        }
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(CLOSE_PREVIEW_TIMEOUT_MS) { closed.await() }
        }
    }

    /** 立刻停预览帧与设备麦（不关设备）。 */
    fun haltPreviewFramesIfActive() {
        val camera = activeCamera ?: return
        runCatching { haltFramesAndMic(camera) }
            .onFailure { Log.w(TAG, "haltPreviewFramesIfActive failed", it) }
    }

    private fun haltFramesAndMic(camera: MultiCameraClient.ICamera) {
        stopUvcPreviewFrames(camera)
        micStartJob?.cancel()
        micStartJob = null
        deviceMicPlayer.halt()
    }
    private fun releaseControlBlocks(deviceId: Int?) {
        if (deviceId != null) {
            warmingControlBlockDeviceIds.remove(deviceId)
            takeAndCloseControlBlock(deviceId)
        } else {
            warmingControlBlockDeviceIds.clear()
            controlBlocks.keys.toList().forEach { takeAndCloseControlBlock(it) }
        }
    }

    /** 从缓存移除并关闭控制块。 */
    private fun takeAndCloseControlBlock(deviceId: Int) {
        controlBlocks.remove(deviceId)?.let { closeControlBlock(it) }
    }

    private fun closeControlBlock(block: USBMonitor.UsbControlBlock) {
        runCatching {
            block.close()
            Log.i(TAG, "UsbControlBlock closed deviceId=${block.deviceId}")
        }.onFailure {
            Log.w(TAG, "UsbControlBlock close failed deviceId=${block.deviceId}", it)
        }
    }

    /** 主线程 halt 后异步收尾 UAC / closeCamera。 */
    private fun detachActiveCameraAsync(deviceId: Int, reason: String) {
        val camera = activeCamera ?: return
        if (camera.getUsbDevice().deviceId != deviceId) {
            Log.i(TAG, "detachActiveCameraAsync skip stale deviceId=$deviceId reason=$reason")
            return
        }
        Log.i(TAG, "detachActiveCameraAsync deviceId=$deviceId reason=$reason")
        activeSessionGen = sessionGenSeed.incrementAndGet()
        runCatching { haltFramesAndMic(camera) }
            .onFailure { Log.w(TAG, "haltFramesAndMic on $reason failed", it) }
        if (activeCamera === camera) {
            activeCamera = null
            activeControlBlock = null
        }
        previewActive = false
        captureMode = UsbCaptureMode.Idle
        cameraScope.launch {
            runCatching { deviceMicPlayer.stop() }
                .onFailure { Log.w(TAG, "deviceMicPlayer.stop after $reason failed", it) }
            if (reason == "softDisconnect") {
                runCatching { camera.closeCamera() }
                    .onFailure { Log.w(TAG, "closeCamera after softDisconnect failed", it) }
            }
        }
    }

    /** 预热控制块，供下次快速开流。 */
    private suspend fun warmControlBlock(deviceId: Int) {
        warmingControlBlockDeviceIds.add(deviceId)
        try {
            takeAndCloseControlBlock(deviceId)
            val device = findUsbDevice(deviceId)
            if (device == null || !usbManager.hasPermission(device)) {
                return
            }
            ensureClient()
            val opened = withContext(Dispatchers.IO) { openControlBlockIfPermitted(device) }
            if (opened == null || !isDeviceStillAttached(device)) {
                if (opened != null) closeControlBlock(opened)
                return
            }
            val previous = controlBlocks.put(deviceId, opened)
            if (previous != null && previous !== opened) {
                closeControlBlock(previous)
            }
            Log.i(TAG, "warmControlBlock deviceId=$deviceId")
        } finally {
            warmingControlBlockDeviceIds.remove(deviceId)
        }
    }

    private fun onSoftDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock?) {
        val deviceId = device.deviceId
        connectWaiters.remove(deviceId)?.cancel()
        if (expectedSoftDisconnectDeviceIds.remove(deviceId)) {
            // 仅摘回调对应的旧块；map 里已是 warm/新会话块则不动。
            peelControlBlockIfMatch(deviceId, ctrlBlock)
            Log.i(TAG, "onSoftDisconnect ignored (expected close) id=$deviceId")
            return
        }
        Log.i(TAG, "onSoftDisconnect unexpected id=$deviceId (device still attached)")
        // 迟到回调：断开的是旧 CB，当前会话已换新块——绝不能拆新会话。
        if (ctrlBlock != null) {
            val active = activeControlBlock
            val cached = controlBlocks[deviceId]
            if (active != null && ctrlBlock !== active) {
                peelControlBlockIfMatch(deviceId, ctrlBlock)
                Log.i(TAG, "onSoftDisconnect stale (old controlBlock) id=$deviceId")
                return
            }
            if (cached != null && cached !== ctrlBlock) {
                Log.i(TAG, "onSoftDisconnect stale (controlBlock replaced) id=$deviceId")
                return
            }
        } else if (deviceId in warmingControlBlockDeviceIds) {
            Log.i(TAG, "onSoftDisconnect stale (no ctrlBlock during warm) id=$deviceId")
            return
        }
        if (activeCamera?.getUsbDevice()?.deviceId != deviceId) {
            Log.i(TAG, "onSoftDisconnect stale (activeCamera replaced) id=$deviceId")
            return
        }
        if (ctrlBlock != null) {
            peelControlBlockIfMatch(deviceId, ctrlBlock)
        } else {
            takeAndCloseControlBlock(deviceId)
        }
        detachActiveCameraAsync(deviceId, reason = "softDisconnect")
        deviceEventListeners.forEach { listener ->
            runCatching { listener.onSoftDisconnected(device) }
        }
    }

    /** 仅当 map 中仍是该实例时摘下并 close。 */
    private fun peelControlBlockIfMatch(deviceId: Int, ctrlBlock: USBMonitor.UsbControlBlock?) {
        if (ctrlBlock == null) return
        if (controlBlocks.remove(deviceId, ctrlBlock)) {
            closeControlBlock(ctrlBlock)
        }
    }

    private fun onDevicePhysicallyRemoved(device: UsbDevice) {
        val deviceId = device.deviceId
        Log.i(TAG, "onDevicePhysicallyRemoved id=$deviceId")
        expectedSoftDisconnectDeviceIds.remove(deviceId)
        warmingControlBlockDeviceIds.remove(deviceId)
        connectWaiters.remove(deviceId)?.cancel()
        detachActiveCameraAsync(deviceId, reason = "physicalRemove")
        releaseControlBlocks(deviceId)
        deviceEventListeners.forEach { listener ->
            runCatching { listener.onDeviceDisconnected(device) }
        }
    }

    private fun isDeviceStillAttached(device: UsbDevice?): Boolean {
        device ?: return false
        return usbManager.deviceList.values.any { it.deviceId == device.deviceId }
    }

    private suspend fun awaitSurfaceViewLaidOut(surfaceView: SurfaceView) {
        if (surfaceView.width > 0 && surfaceView.height > 0) return
        val completed = withTimeoutOrNull(SURFACE_LAYOUT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                lateinit var listener: View.OnLayoutChangeListener
                listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    if (view.width > 0 && view.height > 0) {
                        view.removeOnLayoutChangeListener(listener)
                        resumeCoroutineOnce(continuation, resumed)
                    }
                }
                surfaceView.addOnLayoutChangeListener(listener)
                surfaceView.post {
                    if (surfaceView.width > 0 && surfaceView.height > 0) {
                        surfaceView.removeOnLayoutChangeListener(listener)
                        resumeCoroutineOnce(continuation, resumed)
                    }
                }
                continuation.invokeOnCancellation {
                    surfaceView.removeOnLayoutChangeListener(listener)
                }
            }
        }
        if (completed == null) error("Surface 布局超时，请重试")
    }

    /** 预览视图须实现 IAspectRatio。 */
    private suspend fun resolveRenderSurface(
        mode: UsbCaptureMode,
        paramSurfaceView: SurfaceView?,
    ): SurfaceView {
        if (paramSurfaceView != null) {
            previewSurfaceView = paramSurfaceView
        }
        val surfaceView = previewSurfaceView ?: run {
            logSurfaceNotReady(
                mode = mode,
                reason = "no previewSurfaceView",
                paramSurfaceView = paramSurfaceView,
            )
            error(if (mode == UsbCaptureMode.Push) "推流需要预览 Surface" else "Surface 未就绪")
        }
        if (surfaceView !is IAspectRatio) {
            error("预览 Surface 须为 AspectRatioSurfaceView")
        }
        withContext(Dispatchers.Main.immediate) {
            awaitSurfaceHolderValid(surfaceView)
            awaitSurfaceViewLaidOut(surfaceView)
            if (!surfaceView.holder.surface.isValid) {
                logSurfaceNotReady(
                    mode = mode,
                    reason = "holder.surface.isValid=false",
                    paramSurfaceView = paramSurfaceView,
                )
                error("Surface 未就绪")
            }
        }
        return surfaceView
    }

    private suspend fun awaitSurfaceHolderValid(surfaceView: SurfaceView) {
        if (surfaceView.holder.surface.isValid && surfaceView.width > 0 && surfaceView.height > 0) {
            return
        }
        val completed = withTimeoutOrNull(SURFACE_READY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val holder = surfaceView.holder
                val resumed = AtomicBoolean(false)
                val callback = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = Unit

                    override fun surfaceChanged(
                        h: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        if (width > 0 && height > 0 && h.surface.isValid) {
                            holder.removeCallback(this)
                            resumeCoroutineOnce(continuation, resumed)
                        }
                    }

                    override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                }
                holder.addCallback(callback)
                surfaceView.post {
                    if (
                        surfaceView.width > 0 &&
                        surfaceView.height > 0 &&
                        holder.surface.isValid
                    ) {
                        holder.removeCallback(callback)
                        resumeCoroutineOnce(continuation, resumed)
                    }
                }
                continuation.invokeOnCancellation { holder.removeCallback(callback) }
            }
        }
        if (completed == null) error("Surface 未就绪")
    }

    private fun isSurfaceOrLayoutOpenError(error: Exception): Boolean {
        val message = error.message ?: return false
        return message.contains("Surface") || message.contains("布局超时")
    }

    private fun logSurfaceNotReady(
        mode: UsbCaptureMode,
        reason: String,
        paramSurfaceView: SurfaceView?,
    ) {
        val view = previewSurfaceView
        Log.e(
            TAG,
            "SurfaceNotReady mode=$mode reason=$reason " +
                "paramSurfaceView=${paramSurfaceView?.let { "${it.javaClass.simpleName}@${System.identityHashCode(it)}" }} " +
                "previewSurfaceView=${view?.let { "${it.javaClass.simpleName}@${System.identityHashCode(it)}" }} " +
                "viewValid=${view?.holder?.surface?.isValid} " +
                "viewSize=${view?.width}x${view?.height} " +
                "captureMode=$captureMode previewActive=$previewActive",
        )
    }

    private fun ensureClient() {
        if (client != null) return
        client = MultiCameraClient(context.applicationContext, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                if (device == null) return
                deviceEventListeners.forEach { listener ->
                    runCatching { listener.onDeviceAttached(device) }
                }
                if (usbManager.hasPermission(device)) {
                    listDevices().firstOrNull { it.deviceId == device.deviceId }?.let { source ->
                        scheduleCapabilityProbe(source)
                    }
                }
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                if (device == null || ctrlBlock == null) return
                Log.i(TAG, "onConnectDev ${device.deviceName} id=${device.deviceId}")
                val previous = controlBlocks.put(device.deviceId, ctrlBlock)
                if (previous != null && previous !== ctrlBlock) {
                    closeControlBlock(previous)
                }
                connectWaiters.remove(device.deviceId)?.complete(ctrlBlock)
                listDevices().firstOrNull { it.deviceId == device.deviceId }?.let { source ->
                    scheduleCapabilityProbe(source)
                }
                deviceEventListeners.forEach { listener ->
                    runCatching { listener.onDeviceConnected(device) }
                }
            }

            override fun onDetachDec(device: UsbDevice?) {
                device?.let { dev ->
                    Log.i(TAG, "onDetachDev id=${dev.deviceId}")
                    onDevicePhysicallyRemoved(dev)
                }
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device?.let { dev ->
                    Log.i(TAG, "onDisconnectDev id=${dev.deviceId}")
                    if (isDeviceStillAttached(dev)) {
                        onSoftDisconnect(dev, ctrlBlock)
                    } else {
                        onDevicePhysicallyRemoved(dev)
                    }
                }
            }

            override fun onCancelDev(device: UsbDevice?) {
                device?.deviceId?.let { id ->
                    connectWaiters.remove(id)?.completeExceptionally(
                        IllegalStateException("USB 权限被拒绝"),
                    )
                }
            }
        }).also { cam ->
            cam.openDebug(BuildConfig.DEBUG)
            cam.register()
            Log.i(TAG, "MultiCameraClient registered")
        }
    }

    private data class CapabilityKey(val vendorId: Int, val productId: Int)

    private data class OpenOutcome(val profile: VideoProfile)

    companion object {
        private const val TAG = "UsbCameraController"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val OPEN_PREVIEW_TIMEOUT_MS = 10_000L
        private const val CLOSE_PREVIEW_TIMEOUT_MS = 3_000L
        private const val MAX_CONNECT_ATTEMPTS = 3
        private const val SURFACE_LAYOUT_TIMEOUT_MS = 3_000L
        private const val SURFACE_READY_TIMEOUT_MS = 5_000L
    }
}
