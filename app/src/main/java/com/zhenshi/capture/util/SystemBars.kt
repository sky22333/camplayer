package com.zhenshi.capture.util

import android.app.Activity
import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** 透明暗色系统栏（浅色图标）。 */
fun ComponentActivity.enableDarkEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

fun Activity.enterImmersiveLandscape() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}

/** 显示系统栏并恢复暗色图标（宜在播放页已不可见时调用）。 */
fun Activity.restoreDarkSystemBars() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.show(WindowInsetsCompat.Type.systemBars())
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}
