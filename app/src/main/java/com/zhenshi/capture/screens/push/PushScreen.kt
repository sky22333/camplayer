package com.zhenshi.capture.screens.push

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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

@Composable
fun PushScreen(
    contentPadding: PaddingValues,
    viewModel: PushTargetsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isEditing = state.editingId != null

    TabScreenLayout(
        contentPadding = contentPadding,
        enableImePadding = true,
    ) {
        ScreenHeader(
            title = stringResource(R.string.push_targets_title),
            subtitle = stringResource(R.string.push_targets_subtitle),
        )

        SectionLabel(
            if (isEditing) {
                stringResource(R.string.push_target_edit)
            } else {
                stringResource(R.string.push_target_add)
            },
        )

        AppTextField(
            value = state.nameInput,
            onValueChange = viewModel::onNameChange,
            label = stringResource(R.string.push_target_name),
            placeholder = stringResource(R.string.push_target_name_hint),
            isError = state.error != null,
        )
        AppTextField(
            value = state.urlInput,
            onValueChange = viewModel::onUrlChange,
            label = stringResource(R.string.push_target_url),
            placeholder = stringResource(R.string.push_target_url_hint),
            isError = state.error != null,
            supportingText = state.error,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isEditing) {
                OutlinedButton(
                    onClick = viewModel::cancelEdit,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.push_target_cancel))
                }
            }
            Button(
                onClick = viewModel::save,
                modifier = Modifier.weight(1f),
                shape = CircleShape,
            ) {
                Text(
                    if (isEditing) {
                        stringResource(R.string.push_target_save)
                    } else {
                        stringResource(R.string.push_target_add_action)
                    },
                )
            }
        }

        SectionLabel(stringResource(R.string.push_target_list))
        if (state.targets.isEmpty()) {
            EmptyHint(stringResource(R.string.push_target_list_empty))
        } else {
            state.targets.forEach { target ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text = target.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = target.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { viewModel.startEdit(target) }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.cd_push_target_edit),
                            )
                        }
                        IconButton(onClick = { viewModel.delete(target.id) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.cd_push_target_delete),
                            )
                        }
                    }
                    FlatDivider()
                }
            }
        }
    }
}
