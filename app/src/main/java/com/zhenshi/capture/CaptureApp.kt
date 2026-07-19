package com.zhenshi.capture

import android.app.Application
import com.zhenshi.capture.media.usb.UsbDeviceMicPlayer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CaptureApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 预加载 libUACAudio
        UsbDeviceMicPlayer.preloadNativeLibrary()
    }
}
