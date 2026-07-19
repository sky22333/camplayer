package com.zhenshi.capture.util

import java.util.concurrent.atomic.AtomicLong

/**
 * 延迟采样：写入可高频，UI 按间隔 [read]。
 * 当前用于网络缓冲延迟（USB 预览不走帧回调采样）。
 */
class LatencySampler {
    private val latestMs = AtomicLong(-1L)

    fun record(intervalMs: Long) {
        if (intervalMs > 0L) {
            latestMs.set(intervalMs)
        }
    }

    fun read(): Long? = latestMs.get().takeIf { it >= 0L }

    fun clear() {
        latestMs.set(-1L)
    }
}
