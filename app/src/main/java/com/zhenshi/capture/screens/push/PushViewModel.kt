package com.zhenshi.capture.screens.push

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhenshi.capture.data.PushTarget
import com.zhenshi.capture.R
import com.zhenshi.capture.data.PushTargetStore
import com.zhenshi.capture.media.BitratePreset
import com.zhenshi.capture.media.ConnectionState
import com.zhenshi.capture.media.PlaybackSession
import com.zhenshi.capture.media.PlaybackSessionState
import com.zhenshi.capture.media.PushSessionState
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.media.push.PushForegroundService
import com.zhenshi.capture.media.push.RtmpPushController
import com.zhenshi.capture.media.push.UsbPushHandoffTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlayerPushUiState(
    val targets: List<PushTarget> = emptyList(),
    val selectedTarget: PushTarget? = null,
    val push: PushSessionState = PushSessionState(),
    val playback: PlaybackSessionState = PlaybackSessionState(),
    val pushHandoffBusy: Boolean = false,
)

@HiltViewModel
class PushViewModel @Inject constructor(
    application: Application,
    private val pushController: RtmpPushController,
    private val playbackSession: PlaybackSession,
    private val pushTargetStore: PushTargetStore,
) : AndroidViewModel(application) {

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val pushOperationMutex = Mutex()
    private val _pushHandoffBusy = MutableStateFlow(false)
    private val handoff = UsbPushHandoffTracker()

    val playerPushUiState: StateFlow<PlayerPushUiState> = combine(
        pushTargetStore.targets,
        pushTargetStore.selectedTarget,
        pushController.state,
        playbackSession.state,
        _pushHandoffBusy,
    ) { targets, selected, push, playback, handoffBusy ->
        PlayerPushUiState(
            targets = targets,
            selectedTarget = selected,
            push = push,
            playback = playback,
            pushHandoffBusy = handoffBusy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPushUiState())

    init {
        viewModelScope.launch {
            pushController.state
                .map { it.connection }
                .distinctUntilChanged()
                .collect { connection ->
                    when (connection) {
                        ConnectionState.Ready -> handoff.markStreamReady()
                        else -> {
                            if (handoff.shouldRecoverOnConnection(connection)) {
                                recoverPreviewIfPushEndedUnexpectedly()
                            }
                        }
                    }
                }
        }
        viewModelScope.launch {
            playbackSession.usbDeviceLost.collect { deviceId ->
                if (!handoff.previewSuspended) return@collect
                Log.w(TAG, "USB lost during push deviceId=$deviceId, abort transport")
                pushController.abortWithError(
                    getApplication<Application>().getString(R.string.push_error_usb_disconnected),
                )
            }
        }
    }

    fun selectTarget(id: String) {
        viewModelScope.launch { pushTargetStore.selectTarget(id) }
    }

    fun onBitrateChange(preset: BitratePreset) = pushController.updateBitrate(preset)

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    fun start() {
        viewModelScope.launch {
            pushOperationMutex.withLock {
                _pushHandoffBusy.value = true
                try {
                    val target = playerPushUiState.value.selectedTarget
                    if (target == null) {
                        _userMessage.value = getApplication<Application>()
                            .getString(R.string.push_error_no_target)
                        return@withLock
                    }

                    val session = playbackSession.state.value
                    Log.i(
                        TAG,
                        "push start target=${target.name} connection=${session.connection} " +
                            "source=${session.source?.javaClass?.simpleName}",
                    )

                    if (session.source is SignalSource.UsbDevice) {
                        handoff.markUsbSuspended()
                        playbackSession.suspendForPush()
                    }

                    val playback = playbackSession.state.value
                    pushController.start(
                        source = playback.source,
                        profile = playback.profile,
                        pushUrl = target.url,
                        targetName = target.name,
                        previewSurface = playbackSession.previewSurface(),
                    )

                    when (pushController.state.value.connection) {
                        ConnectionState.Connecting, ConnectionState.Ready ->
                            PushForegroundService.start(getApplication())
                        is ConnectionState.Error ->
                            finishUsbPushHandoff(resumePreview = true)
                        ConnectionState.Idle -> Unit // 已被 stopPush 打断，由其收尾
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "push start failed", e)
                    finishUsbPushHandoff(resumePreview = true)
                } finally {
                    _pushHandoffBusy.value = false
                }
            }
        }
    }

    fun stop(resumePreview: Boolean = true) {
        viewModelScope.launch { stopPush(resumePreview) }
    }

    /** 后台停推流，不恢复预览。 */
    fun onAppBackground() {
        val connection = pushController.state.value.connection
        if (
            connection !is ConnectionState.Ready &&
            connection !is ConnectionState.Connecting
        ) {
            return
        }
        _userMessage.value =
            getApplication<Application>().getString(R.string.push_stopped_app_background)
        viewModelScope.launch {
            stopPush(resumePreview = false)
        }
    }

    /** 停推流后再执行离开回调。 */
    fun stopThenLeave(after: () -> Unit) {
        viewModelScope.launch {
            stopPush(resumePreview = false)
            after()
        }
    }

    /** 停传输并收尾 handoff；可选恢复预览。 */
    suspend fun stopPush(resumePreview: Boolean = true) {
        if (!resumePreview) {
            handoff.beginFinish(resumePreview = false)
        }
        pushController.stop()
        PushForegroundService.stop(getApplication())
        pushOperationMutex.withLock {
            _pushHandoffBusy.value = true
            try {
                Log.i(TAG, "push stop resumePreview=$resumePreview")
                if (resumePreview) {
                    finishUsbPushHandoff(resumePreview = true)
                }
            } finally {
                _pushHandoffBusy.value = false
            }
        }
    }

    private fun recoverPreviewIfPushEndedUnexpectedly() {
        if (!handoff.previewSuspended) return
        viewModelScope.launch {
            pushOperationMutex.withLock {
                if (!handoff.previewSuspended) return@withLock
                _pushHandoffBusy.value = true
                try {
                    Log.w(TAG, "push session ended unexpectedly, resume preview")
                    finishUsbPushHandoff(resumePreview = true)
                } finally {
                    _pushHandoffBusy.value = false
                }
            }
        }
    }

    /** 清 handoff 标志并按需恢复预览。 */
    private suspend fun finishUsbPushHandoff(resumePreview: Boolean) {
        val shouldResume = handoff.beginFinish(resumePreview)
        if (shouldResume) {
            playbackSession.resumePreviewAfterPush()
        }
    }

    companion object {
        private const val TAG = "PushViewModel"
    }
}
