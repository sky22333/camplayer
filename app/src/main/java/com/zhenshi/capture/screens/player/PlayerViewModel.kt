package com.zhenshi.capture.screens.player

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.zhenshi.capture.data.SourceHistoryStore
import com.zhenshi.capture.media.ConnectionState
import com.zhenshi.capture.media.PlaybackSession
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.media.SourceKeys
import com.zhenshi.capture.media.VideoProfile
import com.zhenshi.capture.media.network.NetworkStreamPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackSession: PlaybackSession,
    private val networkPlayer: NetworkStreamPlayer,
    private val historyStore: SourceHistoryStore,
) : ViewModel() {

    val sessionState = playbackSession.state
    val networkError = networkPlayer.error

    private var pendingSource: SignalSource? = null
    private var pendingProfile: VideoProfile? = null
    private var boundSurfaceView: SurfaceView? = null
    private var surfaceReady = false
    private var openJob: Job? = null

    /** 播放页生命周期相位。 */
    private var phase: PreviewPhase = PreviewPhase.Idle

    fun start(sourceKey: String, profileKey: String) {
        phase = PreviewPhase.Watching
        pendingSource = SourceKeys.decode(sourceKey) ?: return
        pendingProfile = SourceKeys.decodeProfile(profileKey)
        viewModelScope.launch {
            historyStore.add(SourceKeys.encode(pendingSource!!))
            if (pendingSource !is SignalSource.UsbDevice) {
                maybeOpen()
            }
        }
    }

    fun onSurfaceChanged(surfaceView: SurfaceView, holder: SurfaceHolder, width: Int, height: Int) {
        Log.i(
            TAG,
            "onSurfaceChanged view@${System.identityHashCode(surfaceView)} " +
                "${width}x$height valid=${holder.surface.isValid} " +
                "phase=$phase connection=${playbackSession.state.value.connection}",
        )
        if (width <= 0 || height <= 0 || !holder.surface.isValid) return
        if (phase == PreviewPhase.Idle || phase == PreviewPhase.Left) return

        val surfaceInstanceChanged = boundSurfaceView !== surfaceView
        val needRecreateOpen = phase == PreviewPhase.SurfaceLost
        boundSurfaceView = surfaceView
        surfaceReady = true
        playbackSession.attachPreview(surfaceView)

        viewModelScope.launch {
            val session = playbackSession.state.value
            if (
                phase == PreviewPhase.AppBackground &&
                session.source is SignalSource.UsbDevice &&
                (
                    session.connection is ConnectionState.Idle ||
                        session.connection is ConnectionState.Error
                    )
            ) {
                phase = PreviewPhase.Watching
                playbackSession.resumePreviewFromAppBackground()
                return@launch
            }
            // 推流 handoff（Idle）不重开预览
            if (
                session.source is SignalSource.UsbDevice &&
                session.connection is ConnectionState.Idle
            ) {
                return@launch
            }
            val usbReady = session.connection is ConnectionState.Ready &&
                session.source is SignalSource.UsbDevice
            when {
                needRecreateOpen && session.source is SignalSource.UsbDevice -> {
                    reopenUsbPreview()
                    if (phase == PreviewPhase.SurfaceLost) {
                        phase = PreviewPhase.Watching
                    }
                }
                usbReady && surfaceInstanceChanged -> reopenUsbPreview()
                else -> maybeOpen()
            }
        }
    }

    fun onSurfaceDestroyed() {
        val session = playbackSession.state.value
        Log.w(
            TAG,
            "onSurfaceDestroyed view@${boundSurfaceView?.let { System.identityHashCode(it) }} " +
                "phase=$phase connection=${session.connection}",
        )
        if (phase == PreviewPhase.Idle || phase == PreviewPhase.Left) {
            boundSurfaceView = null
            surfaceReady = false
            return
        }
        if (
            session.source is SignalSource.UsbDevice &&
            session.connection is ConnectionState.Connecting
        ) {
            surfaceReady = false
            openJob?.cancel()
            openJob = null
            playbackSession.haltUsbPreviewFrames()
            return
        }
        // handoff 中保留 View 引用
        if (
            session.source is SignalSource.UsbDevice &&
            session.connection is ConnectionState.Idle
        ) {
            surfaceReady = false
            return
        }
        // 布局暂毁：只 halt，等 Surface 重建
        if (
            phase != PreviewPhase.AppBackground &&
            session.source is SignalSource.UsbDevice &&
            session.connection is ConnectionState.Ready
        ) {
            phase = PreviewPhase.SurfaceLost
            surfaceReady = false
            playbackSession.haltUsbPreviewFrames()
            return
        }
        boundSurfaceView = null
        surfaceReady = false
    }

    fun exoPlayer(): ExoPlayer? = networkPlayer.playerOrNull()

    fun leavePreview(onNavigate: () -> Unit = {}) {
        if (phase == PreviewPhase.Left) {
            onNavigate()
            return
        }
        phase = PreviewPhase.Left
        pendingSource = null
        boundSurfaceView = null
        surfaceReady = false
        openJob?.cancel()
        openJob = null
        playbackSession.leavePreview(onNavigate)
    }

    fun consumeUserMessage() = playbackSession.consumeUserMessage()

    /** 进后台暂停 USB 预览。 */
    fun pauseForAppBackground() {
        if (phase != PreviewPhase.Watching && phase != PreviewPhase.SurfaceLost) return
        val session = playbackSession.state.value
        if (session.source !is SignalSource.UsbDevice) return
        if (session.connection is ConnectionState.Connecting) {
            openJob?.cancel()
            openJob = null
            playbackSession.haltUsbPreviewFrames()
        }
        phase = PreviewPhase.AppBackground
        viewModelScope.launch {
            playbackSession.pausePreviewForAppBackground()
        }
    }

    /** 回前台恢复预览。 */
    fun resumeFromAppBackground() {
        if (phase != PreviewPhase.AppBackground) return
        viewModelScope.launch {
            if (boundSurfaceView != null && surfaceReady) {
                phase = PreviewPhase.Watching
                playbackSession.resumePreviewFromAppBackground()
            }
        }
    }

    private suspend fun maybeOpen() {
        if (phase != PreviewPhase.Watching) return
        val source = resolveSource() ?: return
        if (source is SignalSource.UsbDevice && (!surfaceReady || boundSurfaceView == null)) return
        if (shouldSkipOpen(source)) return
        runOpen(source, resolveProfile(), force = false)
    }

    /** Surface 重建后强制重开 USB 预览。 */
    private suspend fun reopenUsbPreview() {
        if (phase == PreviewPhase.Idle || phase == PreviewPhase.Left) return
        val source = resolveSource() as? SignalSource.UsbDevice ?: return
        if (!surfaceReady || boundSurfaceView == null) return
        runOpen(source, resolveProfile(), force = true)
    }

    private fun resolveSource(): SignalSource? =
        pendingSource ?: playbackSession.state.value.source

    private fun resolveProfile(): VideoProfile? =
        pendingProfile ?: playbackSession.state.value.profile

    private fun shouldSkipOpen(source: SignalSource): Boolean {
        val session = playbackSession.state.value
        if (session.connection is ConnectionState.Connecting) return true
        if (session.connection is ConnectionState.Ready && session.source == source) {
            pendingSource = null
            return true
        }
        return false
    }

    private suspend fun runOpen(source: SignalSource, profile: VideoProfile?, force: Boolean) {
        openJob?.takeIf { it.isActive }?.join()
        if (phase == PreviewPhase.Idle || phase == PreviewPhase.Left) return
        if (!force && shouldSkipOpen(source)) return
        openJob = viewModelScope.launch {
            if (phase == PreviewPhase.Idle || phase == PreviewPhase.Left) return@launch
            playbackSession.open(source, profile)
            if (phase == PreviewPhase.Idle || phase == PreviewPhase.Left) {
                playbackSession.leavePreview().join()
                return@launch
            }
            if (playbackSession.state.value.connection is ConnectionState.Error) {
                return@launch
            }
            pendingSource = null
            if (phase == PreviewPhase.SurfaceLost) {
                phase = PreviewPhase.Watching
            }
        }
        openJob?.join()
    }

    private enum class PreviewPhase {
        Idle,
        Watching,
        SurfaceLost,
        AppBackground,
        Left,
    }

    companion object {
        private const val TAG = "PlayerViewModel"
    }
}
