package com.zhenshi.capture.screens.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf

val LocalAppSnackbar = compositionLocalOf<SnackbarHostState> {
    error("AppSnackbarHostState 未提供")
}
