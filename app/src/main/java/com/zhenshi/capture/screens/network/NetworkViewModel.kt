package com.zhenshi.capture.screens.network

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhenshi.capture.R
import com.zhenshi.capture.data.SourceHistoryStore
import com.zhenshi.capture.media.parseNetworkSource
import com.zhenshi.capture.util.AppRuntimePermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class NetworkUiState(
    val url: String = "",
    val history: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    application: Application,
    private val historyStore: SourceHistoryStore,
) : AndroidViewModel(application) {

    private val url = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<NetworkUiState> = combine(
        url,
        historyStore.recentNetwork,
        error,
    ) { current, history, err ->
        NetworkUiState(url = current, history = history, error = err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkUiState())

    fun onUrlChange(value: String) {
        url.value = value
        error.value = null
    }

    fun confirmPlay(): String? {
        val raw = url.value.trim()
        if (parseNetworkSource(raw) == null) {
            error.value = getApplication<Application>().getString(R.string.network_invalid_url)
            return null
        }
        val app = getApplication<Application>()
        if (AppRuntimePermissions.urlRequiresLocalNetwork(raw) &&
            !AppRuntimePermissions.localNetworkGranted(app)
        ) {
            error.value = app.getString(R.string.network_local_permission_denied)
            return null
        }
        return raw
    }
}
