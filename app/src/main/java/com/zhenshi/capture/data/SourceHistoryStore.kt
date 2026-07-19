package com.zhenshi.capture.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("zhenshi_prefs")

@Singleton
class SourceHistoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val keyRecent = stringPreferencesKey("recent_sources")

    val recent: Flow<List<String>> = context.dataStore.data.map { prefs ->
        SourceHistoryLru.decode(prefs[keyRecent])
    }

    /** 最近网络源（排除 USB）。 */
    val recentNetwork: Flow<List<String>> = recent.map(SourceHistoryLru::networkOnly)

    suspend fun add(urlOrKey: String) {
        val value = urlOrKey.trim()
        if (value.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = SourceHistoryLru.decode(prefs[keyRecent])
            prefs[keyRecent] = SourceHistoryLru.encode(
                SourceHistoryLru.add(current, value),
            )
        }
    }
}
