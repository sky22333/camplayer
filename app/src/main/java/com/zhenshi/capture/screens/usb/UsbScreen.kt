package com.zhenshi.capture.screens.usb

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenshi.capture.R
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.media.SourceKeys
import com.zhenshi.capture.media.VideoProfile
import com.zhenshi.capture.screens.components.EmptyHint
import com.zhenshi.capture.screens.components.ExposedDropdownField
import com.zhenshi.capture.screens.components.FlatDivider
import com.zhenshi.capture.screens.components.LocalAppSnackbar
import com.zhenshi.capture.screens.components.ScreenHeader
import com.zhenshi.capture.screens.components.SectionLabel
import com.zhenshi.capture.screens.components.TabScreenLayout
import com.zhenshi.capture.util.UsbRuntimePermissions
import com.zhenshi.capture.util.formatHistoryLabel
import com.zhenshi.capture.util.matchesUsb
import kotlinx.coroutines.launch

private data class PendingPreview(
    val device: SignalSource.UsbDevice,
    val profile: VideoProfile,
)

@Composable
fun UsbScreen(
    contentPadding: PaddingValues,
    onPreview: (sourceKey: String, profileKey: String) -> Unit,
    onOpenRecent: (String) -> Unit,
    viewModel: UsbViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val capabilityRevision by viewModel.capabilityRevision.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current
    val usbPermissionNeeded = stringResource(R.string.usb_permission_needed)
    val usbRuntimePermissionDenied = stringResource(R.string.usb_runtime_permission_denied)
    val usbDeviceMissing = stringResource(R.string.usb_device_missing)
    var pendingPreview by remember { mutableStateOf<PendingPreview?>(null) }
    var pendingProbeDevice by remember { mutableStateOf<SignalSource.UsbDevice?>(null) }

    fun requestAccessAndProbe(device: SignalSource.UsbDevice) {
        val usbDevice = viewModel.findDevice(device.deviceId) ?: return
        if (viewModel.hasPermission(usbDevice)) {
            viewModel.select(device)
            return
        }
        pendingProbeDevice = device
        if (viewModel.requestUsbAccess(usbDevice)) {
            scope.launch { snackbar.showSnackbar(usbPermissionNeeded) }
        }
    }

    fun navigatePreview(device: SignalSource.UsbDevice, profile: VideoProfile) {
        onPreview(SourceKeys.encode(device), SourceKeys.encodeProfile(profile))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        val pending = pendingPreview
        if (granted && pending != null) {
            viewModel.onUsbPermissionGranted(pending.device)
            pendingPreview = null
            navigatePreview(pending.device, pending.profile)
        } else if (!granted) {
            pendingPreview = null
            scope.launch { snackbar.showSnackbar(usbRuntimePermissionDenied) }
        }
    }

    fun tryStartPreview(device: SignalSource.UsbDevice, profile: VideoProfile) {
        if (!UsbRuntimePermissions.allGranted(context)) {
            pendingPreview = PendingPreview(device, profile)
            permissionLauncher.launch(UsbRuntimePermissions.required)
            return
        }
        val usbDevice = viewModel.findDevice(device.deviceId)
        if (usbDevice == null) {
            scope.launch { snackbar.showSnackbar(usbDeviceMissing) }
            viewModel.refresh()
            return
        }
        if (!viewModel.hasPermission(usbDevice)) {
            pendingPreview = PendingPreview(device, profile)
            if (viewModel.requestUsbAccess(usbDevice)) {
                scope.launch { snackbar.showSnackbar(usbPermissionNeeded) }
            }
            return
        }
        navigatePreview(device, profile)
    }

    LaunchedEffect(state.selected?.deviceId) {
        state.selected?.let { device ->
            val usbDevice = viewModel.findDevice(device.deviceId) ?: return@let
            if (!viewModel.hasPermission(usbDevice)) {
                requestAccessAndProbe(device)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.usbPermissionGrantedEvents.collect { signal ->
            // 授权后探测一次能力集
            val probeTarget = pendingProbeDevice
            if (probeTarget != null && probeTarget.matchesUsb(signal)) {
                pendingProbeDevice = null
                viewModel.select(signal)
            } else {
                viewModel.onUsbPermissionGranted(signal)
            }
            val pending = pendingPreview
            if (pending != null && pending.device.matchesUsb(signal)) {
                pendingPreview = null
                navigatePreview(signal, pending.profile)
            }
        }
    }

    TabScreenLayout(contentPadding = contentPadding) {
        ScreenHeader(
            title = stringResource(R.string.usb_title),
            actionLabel = stringResource(R.string.usb_refresh),
            onAction = viewModel::refresh,
        )

        if (state.devices.isEmpty()) {
            EmptyHint(stringResource(R.string.usb_empty))
        } else {
            if (state.devices.size > 1) SectionLabel(stringResource(R.string.usb_select_device))
            state.devices.forEach { device ->
                val selected = state.selected?.deviceId == device.deviceId
                DeviceRow(
                    device = device,
                    showSelection = state.devices.size > 1,
                    selected = selected,
                    onClick = { viewModel.select(device) },
                )
            }
        }

        state.selected?.let { device ->
            key(device.deviceId) {
                val profiles = remember(device.deviceId, capabilityRevision) {
                    viewModel.profiles(device.deviceId)
                }
                UsbVideoSettingsPanel(
                    profiles = profiles,
                    probing = state.probingCapabilities,
                    onReloadProfiles = { requestAccessAndProbe(device) },
                    onPreview = { profile -> tryStartPreview(device, profile) },
                    preferredProfile = { viewModel.preferredProfile(device.deviceId) },
                )
            }
        }

        if (recent.isNotEmpty()) {
            SectionLabel(stringResource(R.string.usb_recent))
            recent.forEach { item ->
                RecentRow(
                    label = formatHistoryLabel(item),
                    onClick = { onOpenRecent(item) },
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: SignalSource.UsbDevice,
    showSelection: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var showDetails by rememberSaveable(device.deviceId) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (showSelection) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showSelection) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showDetails = !showDetails }) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.usb_device_info))
            }
        }
        if (showDetails) {
            Text(
                text = "VID ${device.vendorId.toString(16).uppercase()} · PID ${device.productId.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        FlatDivider()
    }
}

@Composable
private fun RecentRow(label: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 9.dp),
            maxLines = 1,
        )
        FlatDivider()
    }
}

@Composable
private fun UsbVideoSettingsPanel(
    profiles: List<VideoProfile>,
    probing: Boolean,
    onReloadProfiles: () -> Unit,
    onPreview: (VideoProfile) -> Unit,
    preferredProfile: () -> VideoProfile,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val resolutionOptions = remember(profiles) { profiles.resolutionOptions() }
    var selectedResolutionIndex by remember(profiles) { mutableIntStateOf(0) }
    var selectedFpsIndex by remember(profiles) { mutableIntStateOf(0) }

    LaunchedEffect(profiles) {
        if (profiles.isEmpty()) return@LaunchedEffect
        if (selectedResolutionIndex >= resolutionOptions.size) selectedResolutionIndex = 0
        val resolution = resolutionOptions.getOrNull(selectedResolutionIndex) ?: return@LaunchedEffect
        val fpsOptions = profiles.fpsOptionsFor(resolution.width, resolution.height)
        if (fpsOptions.isEmpty() || selectedFpsIndex >= fpsOptions.size) selectedFpsIndex = 0
    }

    val selectedResolution = resolutionOptions.getOrNull(selectedResolutionIndex)
    val fpsOptions = remember(profiles, selectedResolution) {
        selectedResolution?.let { profiles.fpsOptionsFor(it.width, it.height) }.orEmpty()
    }
    val selectedFps = fpsOptions.getOrNull(selectedFpsIndex)
    val resolutionLabels = remember(resolutionOptions) {
        resolutionOptions.map { "${it.width}×${it.height}" }
    }
    val fpsLabels = fpsOptions.map { fps ->
        stringResource(R.string.usb_profile_fps, fps)
    }

    val selectedProfile = selectedResolution?.let { res ->
        selectedFps?.let { fps -> profiles.findProfile(res.width, res.height, fps) }
    } ?: profiles.firstOrNull() ?: preferredProfile()

    Button(
        onClick = { onPreview(selectedProfile) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !probing,
        shape = CircleShape,
    ) {
        Text(stringResource(R.string.usb_preview))
    }

    when {
        probing -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.usb_probing_profiles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        profiles.isEmpty() -> {
            Text(
                text = stringResource(R.string.usb_profiles_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }

    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(stringResource(R.string.usb_video_settings))
            Text(
                text = if (profiles.isEmpty()) {
                    stringResource(R.string.usb_default_settings)
                } else {
                    selectedProfile.label
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.usb_settings_collapse else R.string.usb_settings_expand,
            ),
        )
    }

    if (expanded) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExposedDropdownField(
                label = stringResource(R.string.usb_resolution_label),
                selectedText = resolutionLabels.getOrNull(selectedResolutionIndex).orEmpty(),
                options = resolutionLabels,
                placeholder = stringResource(R.string.usb_resolution_placeholder),
                enabled = resolutionLabels.isNotEmpty() && !probing,
                onOptionSelected = { index ->
                    selectedResolutionIndex = index
                    selectedFpsIndex = 0
                },
                modifier = Modifier.weight(1.2f),
            )
            ExposedDropdownField(
                label = stringResource(R.string.usb_fps_label),
                selectedText = fpsLabels.getOrNull(selectedFpsIndex).orEmpty(),
                options = fpsLabels,
                placeholder = stringResource(R.string.usb_fps_placeholder),
                enabled = fpsLabels.isNotEmpty() && !probing,
                onOptionSelected = { selectedFpsIndex = it },
                modifier = Modifier.weight(0.8f),
            )
        }
    }

    if (profiles.isEmpty() && !probing) {
        TextButton(
            onClick = onReloadProfiles,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.usb_probe_profiles))
        }
    }
}
