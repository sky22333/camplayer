package com.zhenshi.capture.screens.push

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhenshi.capture.R
import com.zhenshi.capture.data.PushTarget
import com.zhenshi.capture.data.PushTargetStore
import com.zhenshi.capture.data.validatePushTargetName
import com.zhenshi.capture.data.validatePushTargetUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PushTargetsUiState(
    val targets: List<PushTarget> = emptyList(),
    val editingId: String? = null,
    val nameInput: String = "",
    val urlInput: String = "",
    val error: String? = null,
)

@HiltViewModel
class PushTargetsViewModel @Inject constructor(
    application: Application,
    private val pushTargetStore: PushTargetStore,
) : AndroidViewModel(application) {

    private val editingId = MutableStateFlow<String?>(null)
    private val nameInput = MutableStateFlow("")
    private val urlInput = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PushTargetsUiState> = combine(
        pushTargetStore.targets,
        editingId,
        nameInput,
        urlInput,
        error,
    ) { targets, editing, name, url, err ->
        PushTargetsUiState(
            targets = targets,
            editingId = editing,
            nameInput = name,
            urlInput = url,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PushTargetsUiState())

    fun onNameChange(value: String) {
        nameInput.value = value
        error.value = null
    }

    fun onUrlChange(value: String) {
        urlInput.value = value
        error.value = null
    }

    fun startEdit(target: PushTarget) {
        editingId.value = target.id
        nameInput.value = target.name
        urlInput.value = target.url
        error.value = null
    }

    fun cancelEdit() {
        editingId.value = null
        nameInput.value = ""
        urlInput.value = ""
        error.value = null
    }

    fun save() {
        val app = getApplication<Application>()
        val name = validatePushTargetName(nameInput.value)
        if (name == null) {
            error.value = app.getString(R.string.push_target_name_required)
            return
        }
        val url = validatePushTargetUrl(urlInput.value)
        if (url == null) {
            error.value = app.getString(R.string.push_target_url_invalid)
            return
        }
        val target = editingId.value?.let { id ->
            PushTarget(id = id, name = name, url = url)
        } ?: PushTarget.create(name = name, url = url)

        viewModelScope.launch {
            pushTargetStore.upsert(target)
            cancelEdit()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            if (editingId.value == id) {
                cancelEdit()
            }
            pushTargetStore.remove(id)
        }
    }
}
