package com.personal.portraitguard

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class GuardedApp(
    val packageName: String,
    val label: String,
    val enabled: Boolean = false,
    val hideFloatingAfterUnlock: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "portrait_guard")

object AppConfigStore {
    private val appsKey = stringPreferencesKey("guarded_apps")
    private val hideRecentTaskKey = booleanPreferencesKey("hide_recent_task")
    private val lastUpdateCheckKey = longPreferencesKey("last_update_check")
    private val ignoredUpdateKey = intPreferencesKey("ignored_update_version_code")
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val apps: Flow<List<GuardedApp>>
        get() = appContext.dataStore.data.map { preferences -> decode(preferences[appsKey].orEmpty()) }

    val hideRecentTask: Flow<Boolean>
        get() = appContext.dataStore.data.map { preferences -> preferences[hideRecentTaskKey] ?: false }

    suspend fun setHideRecentTask(hide: Boolean) {
        appContext.dataStore.edit { preferences -> preferences[hideRecentTaskKey] = hide }
    }

    val lastUpdateCheck: Flow<Long>
        get() = appContext.dataStore.data.map { preferences -> preferences[lastUpdateCheckKey] ?: 0L }

    val ignoredUpdateVersionCode: Flow<Int>
        get() = appContext.dataStore.data.map { preferences -> preferences[ignoredUpdateKey] ?: 0 }

    suspend fun setLastUpdateCheck(timeMillis: Long) {
        appContext.dataStore.edit { preferences -> preferences[lastUpdateCheckKey] = timeMillis }
    }

    suspend fun setIgnoredUpdateVersionCode(versionCode: Int) {
        appContext.dataStore.edit { preferences -> preferences[ignoredUpdateKey] = versionCode }
    }

    suspend fun addOrUpdate(app: GuardedApp) {
        appContext.dataStore.edit { preferences ->
            val current = decode(preferences[appsKey].orEmpty())
                .filterNot { it.packageName == app.packageName }
            preferences[appsKey] = encode((current + app).sortedBy { it.label.lowercase() })
        }
    }

    suspend fun setEnabled(packageName: String, enabled: Boolean) {
        update(packageName) { it.copy(enabled = enabled) }
    }

    suspend fun setHideFloatingAfterUnlock(packageName: String, hide: Boolean) {
        update(packageName) { it.copy(hideFloatingAfterUnlock = hide) }
    }

    suspend fun remove(packageName: String) {
        appContext.dataStore.edit { preferences ->
            preferences[appsKey] = encode(decode(preferences[appsKey].orEmpty()).filterNot { it.packageName == packageName })
        }
    }

    private suspend fun update(packageName: String, transform: (GuardedApp) -> GuardedApp) {
        appContext.dataStore.edit { preferences ->
            preferences[appsKey] = encode(decode(preferences[appsKey].orEmpty()).map {
                if (it.packageName == packageName) transform(it) else it
            })
        }
    }

    private fun encode(apps: List<GuardedApp>): String = JSONArray().apply {
        apps.forEach { app ->
            put(JSONObject().apply {
                put("packageName", app.packageName)
                put("label", app.label)
                put("enabled", app.enabled)
                put("hideFloatingAfterUnlock", app.hideFloatingAfterUnlock)
            })
        }
    }.toString()

    private fun decode(raw: String): List<GuardedApp> = runCatching {
        val array = JSONArray(if (raw.isBlank()) "[]" else raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    GuardedApp(
                        packageName = item.getString("packageName"),
                        label = item.optString("label", item.getString("packageName")),
                        enabled = item.optBoolean("enabled", false),
                        hideFloatingAfterUnlock = item.optBoolean("hideFloatingAfterUnlock", false),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}
