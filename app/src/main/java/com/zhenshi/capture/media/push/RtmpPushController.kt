package com.zhenshi.capture.media.push

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import com.zhenshi.capture.R
import com.zhenshi.capture.data.validatePushTargetUrl
import com.zhenshi.capture.media.BitratePreset
import com.zhenshi.capture.media.ConnectionState
import com.zhenshi.capture.media.PushSessionState
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.media.VideoProfile
import com.zhenshi.capture.media.usb.UsbCameraController
import com.zhenshi.capture.media.usb.UsbCaptureMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** USB NV21/PCM → H.264/AAC → [RtmpClient]。 */
@Singleton
class RtmpPushController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val usbCamera: UsbCameraController,
) {
    private val _state = MutableStateFlow(PushSessionState())
    val state: StateFlow<PushSessionState> = _state.asStateFlow()

    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopMutex = Mutex()

    private var rtmpClient: RtmpClient? = null
    private var pipeline: PushAvPipeline? = null

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            _state.update { it.copy(connection = ConnectionState.Connecting) }
        }

        override fun onConnectionSuccess() {
            _state.update { it.copy(connection = ConnectionState.Ready) }
            pushScope.launch {
                runCatching { startPipelineAfterPublish() }
                    .onFailure { error ->
                        Log.e(TAG, "start encode pipeline failed", error)
                        endPushTransport(
                            error.message?.let { msg ->
                                PushConnectionErrorMapper.toUserMessage(msg) { context.getString(it) }
                            } ?: context.getString(R.string.push_error_encode_start),
                        )
                    }
            }
        }

        override fun onConnectionFailed(reason: String) {
            Log.w(TAG, "rtmp connection failed: $reason")
            endPushTransport(PushConnectionErrorMapper.toUserMessage(reason) { context.getString(it) })
        }

        override fun onNewBitrate(bitrate: Long) = Unit

        override fun onDisconnect() {
            Log.i(TAG, "rtmp disconnected")
            endPushTransport(error = null)
        }

        override fun onAuthError() {
            endPushTransport(context.getString(R.string.push_error_auth))
        }

        override fun onAuthSuccess() = Unit
    }

    private fun endPushTransport(error: String?) {
        pushScope.launch {
            PushForegroundService.stop(context)
            stopInternal()
            _state.update { current ->
                when {
                    error != null -> current.copy(
                        connection = ConnectionState.Error(error),
                        activeTargetName = null,
                    )
                    current.connection is ConnectionState.Error -> current.copy(activeTargetName = null)
                    else -> current.copy(
                        connection = ConnectionState.Idle,
                        activeTargetName = null,
                    )
                }
            }
        }
    }

    fun abortWithError(message: String) {
        endPushTransport(message)
    }

    fun updateBitrate(preset: BitratePreset) {
        _state.update { it.copy(bitrate = preset) }
        if (_state.value.connection is ConnectionState.Ready) {
            pipeline?.updateVideoBitrate(preset.bitrateBps)
        }
    }

    suspend fun start(
        source: SignalSource?,
        profile: VideoProfile?,
        pushUrl: String,
        targetName: String?,
        previewSurface: SurfaceView? = null,
    ) {
        val url = validatePushTargetUrl(pushUrl)
        if (url == null) {
            val blank = pushUrl.trim().isEmpty()
            _state.update {
                it.copy(
                    connection = ConnectionState.Error(
                        context.getString(
                            if (blank) R.string.push_error_no_target else R.string.push_error_invalid_url,
                        ),
                    ),
                )
            }
            return
        }
        _state.update { it.copy(activeTargetName = targetName) }
        when (source) {
            is SignalSource.UsbDevice -> startUsb(source, profile, url, previewSurface)
            is SignalSource.RtmpUrl, is SignalSource.RtspUrl -> {
                _state.update {
                    it.copy(
                        connection = ConnectionState.Error(
                            context.getString(R.string.push_error_network_forward),
                        ),
                    )
                }
            }
            null -> {
                _state.update {
                    it.copy(connection = ConnectionState.Error(context.getString(R.string.push_error_no_source)))
                }
            }
        }
    }

    private suspend fun startUsb(
        source: SignalSource.UsbDevice,
        profile: VideoProfile?,
        url: String,
        previewSurface: SurfaceView?,
    ) {
        stopInternal()
        _state.update { it.copy(connection = ConnectionState.Connecting) }
        val requested = profile ?: usbCamera.preferredPreviewProfile(source.deviceId)

        val openResult = runCatching {
            usbCamera.openWithFallback(
                source = source,
                profile = requested,
                surfaceView = previewSurface,
                mode = UsbCaptureMode.Push,
            )
        }.getOrElse { error ->
            Log.e(TAG, "open push camera failed deviceId=${source.deviceId}", error)
            _state.update {
                it.copy(
                    connection = ConnectionState.Error(
                        error.message ?: context.getString(R.string.push_error_usb_open),
                    ),
                )
            }
            return
        }
        openResult.fallbackFrom?.let { from ->
            Log.w(TAG, "push profile fallback ${from.label} -> ${openResult.profile.label}")
        }

        val actual = openResult.profile
        val bitrate = BitratePreset.atLeast(
            _state.value.bitrate,
            BitratePreset.forResolution(actual.width, actual.height),
        )
        _state.update { it.copy(bitrate = bitrate) }

        val hasAudio = usbCamera.lastPushMicParams != null
        val client = RtmpClient(connectChecker).apply {
            setOnlyVideo(!hasAudio)
            setVideoCodec(VideoCodec.H264)
            setVideoResolution(actual.width, actual.height)
            setFps(actual.fps)
            shouldFailOnRead = true
        }
        rtmpClient = client

        val pipe = PushAvPipeline(client, actual, bitrate.bitrateBps)
        pipeline = pipe
        usbCamera.attachPushListeners(
            onNv21 = { data, w, h -> pipe.offerNv21(data, w, h) },
            onPcm = { pcm -> pipe.offerPcm(pcm) },
        )

        Log.i(
            TAG,
            "push connect deviceId=${source.deviceId} ${actual.label} " +
                "bitrate=${bitrate.bitrateBps} audio=$hasAudio",
        )
        withContext(Dispatchers.IO) {
            client.connect(url)
        }
    }

    private fun startPipelineAfterPublish() {
        val pipe = pipeline ?: error("推流编码管道未就绪")
        val mic = usbCamera.lastPushMicParams
        if (mic != null && mic.sampleRate > 0 && mic.channelCount > 0) {
            pipe.start(mic.sampleRate, mic.channelCount)
        } else {
            Log.w(TAG, "no UAC mic params, video-only push")
            pipe.startVideoOnly()
        }
        usbCamera.setPushEncodingActive(true)
    }

    suspend fun stop() {
        stopInternal()
        _state.update {
            it.copy(connection = ConnectionState.Idle, activeTargetName = null)
        }
    }

    private suspend fun stopInternal() {
        stopMutex.withLock {
            usbCamera.setPushEncodingActive(false)
            usbCamera.clearPushListeners()
            pipeline?.stop()
            pipeline = null

            runCatching { usbCamera.stopPushCapture() }
                .onFailure { Log.w(TAG, "stopPushCapture failed", it) }

            runCatching {
                rtmpClient?.let { client ->
                    if (client.isStreaming) {
                        client.disconnect()
                    }
                }
            }.onFailure { Log.w(TAG, "rtmp disconnect failed", it) }
            rtmpClient = null
        }
    }

    companion object {
        private const val TAG = "RtmpPushController"
    }
}
