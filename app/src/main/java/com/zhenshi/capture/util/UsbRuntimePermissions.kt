package com.zhenshi.capture.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** AUSBC targetSdk 28+ 需 CAMERA；UsbDeviceMicPlayer（UAC 听声）另需 RECORD_AUDIO。 */
object UsbRuntimePermissions {
    val required: Array<String> = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    fun allGranted(context: Context): Boolean =
        required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
