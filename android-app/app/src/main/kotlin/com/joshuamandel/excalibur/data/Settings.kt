package com.joshuamandel.excalibur.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val lastProfile: String = "kindle_oasis",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val serverPort: Int = 8888,
    /** Stable per-install id; namespaces this phone's books on a shared Kindle so two
     *  phones syncing to the same Kindle don't delete each other's content. */
    val deviceTag: String = "",
    /** When syncing to a Kindle, append the book's tags to its title (e.g. "Dune · scifi")
     *  so they're visible on-device. Off by default — it's deliberately a bit ugly. */
    val syncTagsIntoTitle: Boolean = false,
    /** When Android launches us for a Kindle USB attach event, start the MTP sync automatically. */
    val autoSyncKindleOnConnect: Boolean = false,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val profile = stringPreferencesKey("last_profile")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val port = intPreferencesKey("server_port")
        val deviceTag = stringPreferencesKey("device_tag")
        val syncTagsIntoTitle = booleanPreferencesKey("sync_tags_into_title")
        val autoSyncKindleOnConnect = booleanPreferencesKey("auto_sync_kindle_on_connect")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            lastProfile = p[Keys.profile] ?: "kindle_oasis",
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamic] ?: true,
            serverPort = p[Keys.port] ?: 8888,
            deviceTag = p[Keys.deviceTag] ?: "",
            syncTagsIntoTitle = p[Keys.syncTagsIntoTitle] ?: false,
            autoSyncKindleOnConnect = p[Keys.autoSyncKindleOnConnect] ?: false,
        )
    }

    suspend fun setProfile(id: String) = context.dataStore.edit { it[Keys.profile] = id }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[Keys.dynamic] = on }
    suspend fun setPort(port: Int) = context.dataStore.edit { it[Keys.port] = port }
    suspend fun setDeviceTag(tag: String) = context.dataStore.edit { it[Keys.deviceTag] = tag }
    suspend fun setSyncTagsIntoTitle(on: Boolean) = context.dataStore.edit { it[Keys.syncTagsIntoTitle] = on }
    suspend fun setAutoSyncKindleOnConnect(on: Boolean) = context.dataStore.edit { it[Keys.autoSyncKindleOnConnect] = on }
}
