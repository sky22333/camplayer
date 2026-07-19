package com.zhenshi.capture.media

import android.view.SurfaceView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 单路观看会话。 */
interface PlaybackSession {
    val state: StateFlow<PlaybackSessionState>

    /** USB 不可用（拔出或意外软断开）的 deviceId。 */
    val usbDeviceLost: SharedFlow<Int>

    suspend fun open(source: SignalSource, profile: VideoProfile? = null)
    fun attachPreview(surfaceView: SurfaceView)

    /**
     * 离开预览：同步停写后可选导航；完整拆流在返回的 [Job] 中（USB 保留连接）。
     */
    fun leavePreview(onNavigate: (() -> Unit)? = null): Job

    fun previewSurface(): SurfaceView?

    suspend fun suspendForPush()
    suspend fun resumePreviewAfterPush()
    suspend fun pausePreviewForAppBackground()
    suspend fun resumePreviewFromAppBackground()
    fun consumeUserMessage()
    /** 立刻停 USB 写帧与听声（不关设备）。 */
    fun haltUsbPreviewFrames()
}
