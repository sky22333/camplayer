package com.zhenshi.capture.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.zhenshi.capture.R
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.media.SourceKeys

fun SignalSource.displayName(): String = when (this) {
    is SignalSource.UsbDevice -> name
    is SignalSource.RtmpUrl -> url
    is SignalSource.RtspUrl -> url
}

@Composable
fun formatSignalSourceLabel(source: SignalSource?): String = when (source) {
    null -> stringResource(R.string.push_source_none)
    is SignalSource.UsbDevice -> stringResource(R.string.push_source_usb, source.name)
    is SignalSource.RtmpUrl -> stringResource(R.string.push_source_network, source.url)
    is SignalSource.RtspUrl -> stringResource(R.string.push_source_network, source.url)
}

@Composable
fun formatHistoryLabel(raw: String): String {
    val source = SourceKeys.decode(raw)
    return if (source != null) {
        formatSignalSourceLabel(source)
    } else {
        raw
    }
}
