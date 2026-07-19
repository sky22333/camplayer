package com.zhenshi.capture.media.push

import com.zhenshi.capture.media.ConnectionState

/** USB 推流 handoff 标志；收尾先 [beginFinish] 再停流/恢复。 */
class UsbPushHandoffTracker {
    var previewSuspended: Boolean = false
        private set
    var streamWasReady: Boolean = false
        private set

    fun markUsbSuspended() {
        previewSuspended = true
        streamWasReady = false
    }

    fun markStreamReady() {
        streamWasReady = true
    }

    fun shouldRecoverOnConnection(connection: ConnectionState): Boolean {
        if (!previewSuspended) return false
        return when (connection) {
            is ConnectionState.Error -> true
            ConnectionState.Idle -> streamWasReady
            else -> false
        }
    }

    /** @return 是否应在清标志后恢复预览 */
    fun beginFinish(resumePreview: Boolean): Boolean {
        val shouldResume = resumePreview && previewSuspended
        streamWasReady = false
        previewSuspended = false
        return shouldResume
    }
}
