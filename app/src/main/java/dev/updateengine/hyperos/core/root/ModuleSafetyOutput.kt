package dev.updateengine.hyperos.core.root

/**
 * Parser for the machine-readable output of the module disabling / restoring shell steps.
 *
 * The shell side prints one summary line plus one line per module so the result never depends on
 * incidental wording:
 *
 * ```text
 * OTA_MOD_ENABLED <id>                 # was enabled before we touched it
 * OTA_MOD_FAILED <id>                  # still has no disable flag afterwards
 * OTA_MOD_DISABLE_OK total=7 disabled=7 global=1 parked=0 ksud=1
 * OTA_MOD_RESTORED <id>
 * OTA_MOD_RESTORE_FAILED <id>
 * OTA_MOD_RESTORE_OK restored=7 unparked=0 global=0
 * ```
 */
object ModuleSafetyOutput {

    const val DISABLE_SUMMARY = "OTA_MOD_DISABLE_OK"
    const val RESTORE_SUMMARY = "OTA_MOD_RESTORE_OK"
    private const val ENABLED_MARKER = "OTA_MOD_ENABLED "
    private const val FAILED_MARKER = "OTA_MOD_FAILED "
    private const val RESTORED_MARKER = "OTA_MOD_RESTORED "
    private const val RESTORE_FAILED_MARKER = "OTA_MOD_RESTORE_FAILED "

    data class DisableResult(
        val armed: Boolean,
        val previouslyEnabled: List<String>,
        val failed: List<String>,
        val total: Int,
        val disabledCount: Int,
        val globalFlagSet: Boolean,
        val pendingUpdatesParked: Boolean,
        val ksudUsed: Boolean
    )

    data class RestoreResult(
        val completed: Boolean,
        val restored: List<String>,
        val failed: List<String>,
        val pendingUpdatesUnparked: Boolean,
        val globalFlagCleared: Boolean
    )

    fun parseDisable(stdout: String): DisableResult {
        val summary = stdout.lineSequence().firstOrNull { it.trimStart().startsWith(DISABLE_SUMMARY) }
        val totals = flags(summary.orEmpty())
        return DisableResult(
            armed = summary != null,
            previouslyEnabled = markers(stdout, ENABLED_MARKER),
            failed = markers(stdout, FAILED_MARKER),
            total = totals["total"] ?: 0,
            disabledCount = totals["disabled"] ?: 0,
            globalFlagSet = totals["global"] == 1,
            pendingUpdatesParked = totals["parked"] == 1,
            ksudUsed = totals["ksud"] == 1
        )
    }

    fun parseRestore(stdout: String): RestoreResult {
        val summary = stdout.lineSequence().firstOrNull { it.trimStart().startsWith(RESTORE_SUMMARY) }
        val flags = flags(summary.orEmpty())
        return RestoreResult(
            completed = summary != null,
            restored = markers(stdout, RESTORED_MARKER),
            failed = markers(stdout, RESTORE_FAILED_MARKER),
            pendingUpdatesUnparked = flags["unparked"] == 1,
            globalFlagCleared = flags["global"] == 0
        )
    }

    private fun markers(stdout: String, marker: String): List<String> = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(marker) }
        .map { it.removePrefix(marker).trim() }
        .filter { it.isNotBlank() }
        .toList()

    private const val MODULE_MARKER = "OTA_MODULE "

    /**
     * Parses the bundled module listing into `id -> (enabled, zygoteHook, name, version)`.
     * Unknown or malformed lines are skipped rather than guessed at.
     */
    fun parseModuleList(stdout: String): List<ModuleListEntry> = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(MODULE_MARKER) }
        .mapNotNull { line ->
            val fields = line.removePrefix(MODULE_MARKER).split('|')
            if (fields.size < 5) return@mapNotNull null
            val id = fields[0].trim()
            if (id.isBlank()) return@mapNotNull null
            ModuleListEntry(
                id = id,
                isEnabled = fields[1].trim() == "1",
                isZygoteHook = fields[2].trim() == "1",
                name = fields[3].trim().ifBlank { id },
                version = fields[4].trim().ifBlank { "1.0" }
            )
        }
        .toList()

    data class ModuleListEntry(
        val id: String,
        val isEnabled: Boolean,
        val isZygoteHook: Boolean,
        val name: String,
        val version: String
    )

    private fun flags(summary: String): Map<String, Int> = summary
        .split(' ')
        .mapNotNull { token ->
            val key = token.substringBefore('=', "")
            val value = token.substringAfter('=', "")
            val parsed = value.toIntOrNull() ?: return@mapNotNull null
            if (key.isBlank()) null else key to parsed
        }
        .toMap()
}
