package com.zhenshi.capture.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhenshi.capture.BuildConfig
import com.zhenshi.capture.R
import com.zhenshi.capture.screens.components.FlatDivider
import com.zhenshi.capture.screens.components.ScreenHeader
import com.zhenshi.capture.screens.components.SectionLabel
import com.zhenshi.capture.screens.components.TabScreenLayout

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    var showLatencyHelp by rememberSaveable { mutableStateOf(false) }
    TabScreenLayout(contentPadding = contentPadding) {
        ScreenHeader(title = stringResource(R.string.settings_title))
        SectionLabel(stringResource(R.string.settings_about))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlatDivider()
        TextButton(onClick = { showLatencyHelp = !showLatencyHelp }) {
            Text(stringResource(R.string.settings_latency))
        }
        if (showLatencyHelp) {
            Text(
                text = stringResource(R.string.settings_latency_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
