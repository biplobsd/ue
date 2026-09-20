package dev.updateengine.hyperos.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.updateengine.hyperos.core.model.OtaTarget
import dev.updateengine.hyperos.core.model.Phase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.pipelineDataStore: DataStore<Preferences> by preferencesDataStore(name = "pipeline_state")

class PipelineStorage(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    private object Keys {
        val CURRENT_PHASE = stringPreferencesKey("current_phase")
        val TARGET_JSON = stringPreferencesKey("target_json")
        val PENDING_SLOT = stringPreferencesKey("pending_slot")
        val STOCK_BACKUP_PATH = stringPreferencesKey("stock_backup_path")
        val DISABLED_MODULES = stringPreferencesKey("disabled_modules")
    }

    val currentPhase: Flow<Phase> = context.pipelineDataStore.data.map { prefs ->
        val name = prefs[Keys.CURRENT_PHASE] ?: Phase.IDLE.name
        try {
            Phase.valueOf(name)
        } catch (_: Exception) {
            Phase.IDLE
        }
    }

    val target: Flow<OtaTarget?> = context.pipelineDataStore.data.map { prefs ->
        val raw = prefs[Keys.TARGET_JSON] ?: return@map null
        try {
            json.decodeFromString<OtaTarget>(raw)
        } catch (_: Exception) {
            null
        }
    }

    val pendingSlot: Flow<String?> = context.pipelineDataStore.data.map { prefs ->
        prefs[Keys.PENDING_SLOT]
    }

    val stockBackupPath: Flow<String?> = context.pipelineDataStore.data.map { prefs ->
        prefs[Keys.STOCK_BACKUP_PATH]
    }

    /**
     * Module ids that were enabled before the engine disabled them for the first boot. Persisted so an
     * aborted update can re-enable exactly those modules again, even after an app restart.
     */
    val disabledModules: Flow<List<String>> = context.pipelineDataStore.data.map { prefs ->
        prefs[Keys.DISABLED_MODULES]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    suspend fun saveDisabledModules(moduleIds: List<String>) {
        context.pipelineDataStore.edit { prefs ->
            val cleaned = moduleIds.map { it.trim() }.filter { it.isNotBlank() && !it.contains(',') }.distinct()
            if (cleaned.isEmpty()) {
                prefs.remove(Keys.DISABLED_MODULES)
            } else {
                prefs[Keys.DISABLED_MODULES] = cleaned.joinToString(",")
            }
        }
    }

    suspend fun savePhase(phase: Phase) {
        context.pipelineDataStore.edit { prefs ->
            prefs[Keys.CURRENT_PHASE] = phase.name
        }
    }

    suspend fun saveTarget(target: OtaTarget?) {
        context.pipelineDataStore.edit { prefs ->
            if (target != null) {
                prefs[Keys.TARGET_JSON] = json.encodeToString(target)
            } else {
                prefs.remove(Keys.TARGET_JSON)
            }
        }
    }

    suspend fun savePendingReboot(targetSlot: String, backupPath: String) {
        context.pipelineDataStore.edit { prefs ->
            prefs[Keys.PENDING_SLOT] = targetSlot
            prefs[Keys.STOCK_BACKUP_PATH] = backupPath
        }
    }

    suspend fun clear() {
        context.pipelineDataStore.edit { it.clear() }
    }
}
