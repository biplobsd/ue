package dev.updateengine.hyperos.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.updateengine.hyperos.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStorage(private val context: Context) {

    private object Keys {
        val AUTO_PURGE_ZIP = booleanPreferencesKey("auto_purge_zip")
        val MIN_STORAGE_MARGIN_GB = intPreferencesKey("min_storage_margin_gb")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val isAutoPurgeZip: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.AUTO_PURGE_ZIP] ?: true
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        ThemeMode.fromId(prefs[Keys.THEME_MODE])
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.id
        }
    }

    val minStorageMarginGb: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.MIN_STORAGE_MARGIN_GB] ?: 16
    }

    suspend fun setAutoPurgeZip(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.AUTO_PURGE_ZIP] = enabled
        }
    }

    suspend fun setMinStorageMarginGb(margin: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.MIN_STORAGE_MARGIN_GB] = margin
        }
    }
}
