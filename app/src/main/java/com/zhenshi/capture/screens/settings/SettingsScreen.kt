package com.zhenshi.capture.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    TabScreenLayout(contentPadding = contentPadding) {
        ScreenHeader(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle),
        )

        SectionLabel(stringResource(R.string.settings_about))
        SettingsRow(
            title = stringResource(R.string.app_name),
            subtitle = stringResource(R.string.settings_about_body),
            trailing = "v${BuildConfig.VERSION_NAME}",
        )

        SectionLabel(stringResource(R.string.settings_latency))
        SettingsRow(
            title = stringResource(R.string.settings_latency),
            subtitle = stringResource(R.string.settings_latency_body),
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    trailing: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlatDivider()
    }
}
