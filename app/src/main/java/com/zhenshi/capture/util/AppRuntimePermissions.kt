package com.zhenshi.capture.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.URI

/** 启动一次性申请：USB 相机/麦克风 + Android 17 ACCESS_LOCAL_NETWORK。 */
object AppRuntimePermissions {
    val required: Array<String> = buildList {
        addAll(UsbRuntimePermissions.required)
        if (Build.VERSION.SDK_INT >= ANDROID_17_API) {
            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }.toTypedArray()

    fun allGranted(context: Context): Boolean =
        required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun localNetworkGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_17_API) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Android 17+ 访问局域网 IP / mDNS 主机名时需要 [ACCESS_LOCAL_NETWORK]。 */
    fun urlRequiresLocalNetwork(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull() ?: return false
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        val octets = h.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        val (a, b, _, _) = octets
        return when {
            a == 10 || a == 127 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }

    private const val ANDROID_17_API = 37
}
