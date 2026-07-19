package com.zhenshi.capture.media.push

import com.zhenshi.capture.media.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPushHandoffTrackerTest {

    @Test
    fun beginFinish_clearsFlagsBeforeResumeDecision() {
        val tracker = UsbPushHandoffTracker()
        tracker.markUsbSuspended()
        tracker.markStreamReady()

        val shouldResume = tracker.beginFinish(resumePreview = true)

        assertTrue(shouldResume)
        assertFalse(tracker.previewSuspended)
        assertFalse(tracker.streamWasReady)
        assertFalse(tracker.shouldRecoverOnConnection(ConnectionState.Error("x")))
        assertFalse(tracker.shouldRecoverOnConnection(ConnectionState.Idle))
    }

    @Test
    fun beginFinish_withoutSuspend_doesNotResume() {
        val tracker = UsbPushHandoffTracker()
        assertFalse(tracker.beginFinish(resumePreview = true))
    }

    @Test
    fun recover_onError_requiresSuspendOnly() {
        val tracker = UsbPushHandoffTracker()
        tracker.markUsbSuspended()
        assertTrue(tracker.shouldRecoverOnConnection(ConnectionState.Error("断")))
        assertFalse(tracker.shouldRecoverOnConnection(ConnectionState.Idle))
        assertFalse(tracker.shouldRecoverOnConnection(ConnectionState.Connecting))
    }

    @Test
    fun recover_onIdle_requiresWasReady() {
        val tracker = UsbPushHandoffTracker()
        tracker.markUsbSuspended()
        assertFalse(tracker.shouldRecoverOnConnection(ConnectionState.Idle))
        tracker.markStreamReady()
        assertTrue(tracker.shouldRecoverOnConnection(ConnectionState.Idle))
    }

    @Test
    fun markUsbSuspended_resetsReadyFlag() {
        val tracker = UsbPushHandoffTracker()
        tracker.markUsbSuspended()
        tracker.markStreamReady()
        tracker.markUsbSuspended()
        assertTrue(tracker.previewSuspended)
        assertFalse(tracker.streamWasReady)
    }
}
