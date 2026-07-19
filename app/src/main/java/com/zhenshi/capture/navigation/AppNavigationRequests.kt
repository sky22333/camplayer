package com.zhenshi.capture.navigation

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 一次性跳转设备 Tab（不粘性）。
 */
@Singleton
class AppNavigationRequests @Inject constructor() {
    private val _openUsbTabEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openUsbTabEvents: SharedFlow<Unit> = _openUsbTabEvents.asSharedFlow()

    fun requestOpenUsbTab() {
        _openUsbTabEvents.tryEmit(Unit)
    }
}
