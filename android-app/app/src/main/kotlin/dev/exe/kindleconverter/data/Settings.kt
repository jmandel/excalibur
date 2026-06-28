package dev.exe.kindleconverter.data

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
    val lastProfile: String = "kindle_pw",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val serverPort: Int = 8888,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val profile = stringPreferencesKey("last_profile")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val port = intPreferencesKey("server_port")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            lastProfile = p[Keys.profile] ?: "kindle_pw",
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamic] ?: true,
            serverPort = p[Keys.port] ?: 8888,
        )
    }

    suspend fun setProfile(id: String) = context.dataStore.edit { it[Keys.profile] = id }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[Keys.dynamic] = on }
    suspend fun setPort(port: Int) = context.dataStore.edit { it[Keys.port] = port }
}
