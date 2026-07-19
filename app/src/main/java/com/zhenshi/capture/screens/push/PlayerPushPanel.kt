package com.zhenshi.capture.screens.push

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenshi.capture.R
import com.zhenshi.capture.media.BitratePreset
import com.zhenshi.capture.media.ConnectionState
import com.zhenshi.capture.screens.components.EmptyHint
import com.zhenshi.capture.screens.components.ExposedDropdownField
import com.zhenshi.capture.screens.components.LocalAppSnackbar
import com.zhenshi.capture.util.formatSignalSourceLabel
import kotlinx.coroutines.launch

@Composable
fun PlayerPushPanel(
    viewModel: PushViewModel,
    onManageTargets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.playerPushUiState.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val notificationPermissionMessage = stringResource(R.string.push_notification_permission)
    val scheme = MaterialTheme.colorScheme

    val push = uiState.push
    val playback = uiState.playback
    val targets = uiState.targets
    val selectedTarget = uiState.selectedTarget
    val isConnecting = push.connection is ConnectionState.Connecting
    val isStreaming = push.isStreaming
    val isHandoffBusy = uiState.pushHandoffBusy
    val switchBusy = isConnecting || isHandoffBusy

    val bitratePresets = remember { BitratePreset.entries.toList() }
    val bitrateLabels = listOf(
        stringResource(R.string.push_bitrate_low),
        stringResource(R.string.push_bitrate_mid),
        stringResource(R.string.push_bitrate_high),
    )

    LaunchedEffect(userMessage) {
        val message = userMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeUserMessage()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.start()
        } else {
            scope.launch { snackbar.showSnackbar(notificationPermissionMessage) }
        }
    }

    fun requestStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.start()
            else notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start()
        }
    }

    val statusText = when (val connection = push.connection) {
        is ConnectionState.Error -> connection.message
        ConnectionState.Connecting -> stringResource(R.string.player_connecting)
        ConnectionState.Ready -> {
            push.activeTargetName?.let { name ->
                stringResource(R.string.push_running_named, name)
            } ?: stringResource(R.string.push_running)
        }
        ConnectionState.Idle -> stringResource(R.string.push_idle)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.push_enable),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.push_overlay_hint),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )

        Text(
            text = formatSignalSourceLabel(playback.source),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (targets.isEmpty()) {
            EmptyHint(text = stringResource(R.string.push_no_targets_hint))
            TextButton(onClick = onManageTargets) {
                Text(stringResource(R.string.push_manage_targets))
            }
            return@Column
        }

        val targetLabels = targets.map { it.name }
        val selectedIndex = selectedTarget?.let { target ->
            targets.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }
        } ?: 0

        ExposedDropdownField(
            label = stringResource(R.string.push_target_server),
            selectedText = targetLabels.getOrElse(selectedIndex) { targetLabels.first() },
            options = targetLabels,
            onOptionSelected = { index ->
                targets.getOrNull(index)?.let { viewModel.selectTarget(it.id) }
            },
            enabled = !isStreaming && !switchBusy,
        )

        Text(
            text = stringResource(R.string.push_bitrate),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            bitratePresets.forEachIndexed { index, preset ->
                val selected = push.bitrate == preset
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.onBitrateChange(preset) },
                    enabled = !switchBusy,
                    label = { Text(bitrateLabels[index]) },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = scheme.primaryContainer,
                        selectedLabelColor = scheme.onPrimaryContainer,
                        containerColor = scheme.surfaceContainerHighest,
                        labelColor = scheme.onSurfaceVariant,
                    ),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.push_enable),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = isStreaming || switchBusy,
                onCheckedChange = { enabled ->
                    if (enabled) requestStart() else viewModel.stop()
                },
                enabled = targets.isNotEmpty() && selectedTarget != null && !switchBusy,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = scheme.primary,
                    checkedThumbColor = scheme.onPrimary,
                ),
            )
        }

        TextButton(
            onClick = onManageTargets,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(stringResource(R.string.push_manage_targets))
        }
    }
}
