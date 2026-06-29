package com.joshuamandel.excalibur.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /** Persisted SAF tree Uri for a user-selected Drive-backed inbox folder. */
    val driveInboxUri: String = "",
    val driveInboxName: String = "",
    /** Daily background Drive sync is constrained to charging + healthy device state. */
    val driveDailySyncOnCharger: Boolean = false,
    val driveImportedDocumentKeys: Set<String> = emptySet(),
    val driveLastSyncSummary: String = "",
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
        val driveInboxUri = stringPreferencesKey("drive_inbox_uri")
        val driveInboxName = stringPreferencesKey("drive_inbox_name")
        val driveDailySyncOnCharger = booleanPreferencesKey("drive_daily_sync_on_charger")
        val driveImportedDocumentKeys = stringSetPreferencesKey("drive_imported_document_keys")
        val driveLastSyncSummary = stringPreferencesKey("drive_last_sync_summary")
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
            driveInboxUri = p[Keys.driveInboxUri] ?: "",
            driveInboxName = p[Keys.driveInboxName] ?: "",
            driveDailySyncOnCharger = p[Keys.driveDailySyncOnCharger] ?: false,
            driveImportedDocumentKeys = p[Keys.driveImportedDocumentKeys] ?: emptySet(),
            driveLastSyncSummary = p[Keys.driveLastSyncSummary] ?: "",
        )
    }

    suspend fun setProfile(id: String) = context.dataStore.edit { it[Keys.profile] = id }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[Keys.dynamic] = on }
    suspend fun setPort(port: Int) = context.dataStore.edit { it[Keys.port] = port }
    suspend fun setDeviceTag(tag: String) = context.dataStore.edit { it[Keys.deviceTag] = tag }
    suspend fun setSyncTagsIntoTitle(on: Boolean) = context.dataStore.edit { it[Keys.syncTagsIntoTitle] = on }
    suspend fun setAutoSyncKindleOnConnect(on: Boolean) = context.dataStore.edit { it[Keys.autoSyncKindleOnConnect] = on }
    suspend fun setDriveInbox(uri: String, name: String) = context.dataStore.edit {
        it[Keys.driveInboxUri] = uri
        it[Keys.driveInboxName] = name
        it[Keys.driveImportedDocumentKeys] = emptySet()
        it[Keys.driveLastSyncSummary] = "Drive inbox selected. Run Sync now to import books."
    }
    suspend fun clearDriveInbox() = context.dataStore.edit {
        it.remove(Keys.driveInboxUri)
        it.remove(Keys.driveInboxName)
        it[Keys.driveDailySyncOnCharger] = false
        it.remove(Keys.driveImportedDocumentKeys)
        it[Keys.driveLastSyncSummary] = "Drive inbox cleared."
    }
    suspend fun setDriveDailySyncOnCharger(on: Boolean) = context.dataStore.edit { it[Keys.driveDailySyncOnCharger] = on }
    suspend fun addDriveImportedDocumentKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        context.dataStore.edit { it[Keys.driveImportedDocumentKeys] = (it[Keys.driveImportedDocumentKeys] ?: emptySet()) + keys }
    }
    suspend fun setDriveLastSyncSummary(summary: String) = context.dataStore.edit { it[Keys.driveLastSyncSummary] = summary }
}
