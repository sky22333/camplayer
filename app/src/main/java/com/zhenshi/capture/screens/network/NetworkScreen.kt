package com.zhenshi.capture.screens.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenshi.capture.R
import com.zhenshi.capture.screens.components.AppTextField
import com.zhenshi.capture.screens.components.EmptyHint
import com.zhenshi.capture.screens.components.FlatDivider
import com.zhenshi.capture.screens.components.ScreenHeader
import com.zhenshi.capture.screens.components.SectionLabel
import com.zhenshi.capture.screens.components.TabScreenLayout
import com.zhenshi.capture.util.formatHistoryLabel

@Composable
fun NetworkScreen(
    contentPadding: PaddingValues,
    onPlay: (String) -> Unit,
    viewModel: NetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    TabScreenLayout(
        contentPadding = contentPadding,
        enableImePadding = true,
    ) {
        ScreenHeader(
            title = stringResource(R.string.network_title),
            subtitle = stringResource(R.string.network_latency_note),
        )

        AppTextField(
            value = state.url,
            onValueChange = viewModel::onUrlChange,
            label = stringResource(R.string.network_url_label),
            placeholder = stringResource(R.string.network_url_hint),
            isError = state.error != null,
            supportingText = state.error,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.padding(top = 10.dp),
        )

        Button(
            onClick = { viewModel.confirmPlay()?.let(onPlay) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            shape = CircleShape,
        ) {
            Text(stringResource(R.string.network_play))
        }

        SectionLabel(stringResource(R.string.network_history))
        if (state.history.isEmpty()) {
            EmptyHint(stringResource(R.string.network_empty_history))
        } else {
            state.history.forEach { item ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatHistoryLabel(item),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.onUrlChange(item)
                                viewModel.confirmPlay()?.let(onPlay)
                            }
                            .padding(vertical = 9.dp),
                        maxLines = 2,
                    )
                    FlatDivider()
                }
            }
        }
    }
}
