package com.zhenshi.capture.screens.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.zhenshi.capture.util.AppRuntimePermissions

@Composable
fun RequestAppRuntimePermissionsOnLaunch() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 不在此阻塞 UI */ }

    LaunchedEffect(Unit) {
        if (!AppRuntimePermissions.allGranted(context)) {
            launcher.launch(AppRuntimePermissions.required)
        }
    }
}
