package com.zhenshi.capture.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.pushTargetDataStore by preferencesDataStore("zhenshi_push_targets")

@Singleton
class PushTargetStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val keyTargetsJson = stringPreferencesKey("targets_json")
    private val keySelectedId = stringPreferencesKey("selected_id")

    val targets: Flow<List<PushTarget>> = context.pushTargetDataStore.data.map { prefs ->
        decodeTargets(prefs[keyTargetsJson].orEmpty())
    }

    val selectedTarget: Flow<PushTarget?> = combine(
        targets,
        context.pushTargetDataStore.data.map { prefs -> prefs[keySelectedId] },
    ) { list, id ->
        resolveSelected(list, id)
    }.distinctUntilChanged()

    suspend fun upsert(target: PushTarget) {
        context.pushTargetDataStore.edit { prefs ->
            val current = decodeTargets(prefs[keyTargetsJson].orEmpty())
            val next = current
                .filterNot { it.id == target.id }
                .plus(target)
                .sortedBy { it.name.lowercase() }
            prefs[keyTargetsJson] = encodeTargets(next)
            writeSelectedId(prefs, reconcileSelection(next, prefs[keySelectedId]))
        }
    }

    suspend fun remove(id: String) {
        context.pushTargetDataStore.edit { prefs ->
            val next = decodeTargets(prefs[keyTargetsJson].orEmpty()).filterNot { it.id == id }
            prefs[keyTargetsJson] = encodeTargets(next)
            writeSelectedId(prefs, reconcileSelection(next, prefs[keySelectedId]))
        }
    }

    suspend fun selectTarget(id: String) {
        context.pushTargetDataStore.edit { prefs ->
            val list = decodeTargets(prefs[keyTargetsJson].orEmpty())
            if (list.none { it.id == id }) return@edit
            prefs[keySelectedId] = id
        }
    }

    private fun resolveSelected(list: List<PushTarget>, id: String?): PushTarget? {
        if (id != null) {
            list.find { it.id == id }?.let { return it }
        }
        return list.firstOrNull()
    }

    private fun reconcileSelection(list: List<PushTarget>, currentId: String?): String? {
        if (currentId != null && list.any { it.id == currentId }) return currentId
        return list.firstOrNull()?.id
    }

    private fun writeSelectedId(prefs: MutablePreferences, id: String?) {
        if (id == null) {
            prefs.remove(keySelectedId)
        } else {
            prefs[keySelectedId] = id
        }
    }

    private fun encodeTargets(targets: List<PushTarget>): String {
        val array = JSONArray()
        targets.forEach { target ->
            array.put(
                JSONObject().apply {
                    put("id", target.id)
                    put("name", target.name)
                    put("url", target.url)
                },
            )
        }
        return array.toString()
    }

    private fun decodeTargets(raw: String): List<PushTarget> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    val url = validatePushTargetUrl(item.optString("url")) ?: continue
                    if (id.isEmpty() || name.isEmpty()) continue
                    add(PushTarget(id = id, name = name, url = url))
                }
            }
        }.getOrDefault(emptyList())
    }
}
