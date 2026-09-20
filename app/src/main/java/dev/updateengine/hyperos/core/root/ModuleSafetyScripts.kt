package dev.updateengine.hyperos.core.root

import dev.updateengine.hyperos.core.common.PathSafety

/**
 * The shell steps that disable and re-enable KernelSU modules around an OTA.
 *
 * Kept as pure string builders so the exact text that reaches `su` can be reviewed and tested instead
 * of hiding inside a method that also executes it. Every step prints machine-readable markers that
 * [ModuleSafetyOutput] parses, and the summary line is always printed last so a partially failed run is
 * still reported instead of silently looking successful.
 *
 * `ksud module disable/enable` is used rather than touching the flag file directly, because ksud also
 * regenerates the preinit `modules.rc` that gets spliced into `init.rc`; a stale rc file would let a
 * module mount itself before the disable flags are even read.
 */
object ModuleSafetyScripts {

    const val DEFAULT_MODULE_ROOT = "/data/adb/modules"
    const val DEFAULT_PARKED_MODULE_ROOT = "/data/adb/modules_update.ota_parked"
    const val DEFAULT_KSUD = "/data/adb/ksu/bin/ksud"
    const val GLOBAL_FLAG = "/data/adb/disable"

    fun disableScript(
        moduleRoot: String = DEFAULT_MODULE_ROOT,
        parkedRoot: String = DEFAULT_PARKED_MODULE_ROOT,
        ksud: String = DEFAULT_KSUD,
        globalFlag: String = GLOBAL_FLAG
    ): String = buildString {
        append("KSUD=${PathSafety.quote(ksud)}; ")
        append("MODS=${PathSafety.quote(moduleRoot)}; ")
        append("PARKED=${PathSafety.quote(parkedRoot)}; ")
        append("FLAG=${PathSafety.quote(globalFlag)}; ")
        append("[ -x \"\$KSUD\" ] && KSUD_OK=1 || KSUD_OK=0; ")
        // A module update that completes during the first boot would bypass the disable flags, so the
        // pending directory is moved aside (not deleted) and restored if the update is aborted.
        append("PARK=0; ")
        append("if [ -d \"\$MODS/../modules_update\" ]; then ")
        append("rm -rf \"\$PARKED\"; ")
        append("mv \"\$MODS/../modules_update\" \"\$PARKED\" && PARK=1; ")
        append("fi; ")
        append("touch \"\$FLAG\"; ")
        append("TOTAL=0; DISABLED=0; ")
        append("for m in \"\$MODS\"/*; do ")
        append("[ -d \"\$m\" ] || continue; ")
        append("id=\${m##*/}; TOTAL=\$((TOTAL+1)); ")
        append("[ -f \"\$m/disable\" ] || echo \"OTA_MOD_ENABLED \$id\"; ")
        append("[ \"\$KSUD_OK\" = 1 ] && \"\$KSUD\" module disable \"\$id\" >/dev/null 2>&1; ")
        append("[ -f \"\$m/disable\" ] || touch \"\$m/disable\"; ")
        append("if [ -f \"\$m/disable\" ]; then DISABLED=\$((DISABLED+1)); else echo \"OTA_MOD_FAILED \$id\"; fi; ")
        append("done; ")
        append("[ -f \"\$FLAG\" ] && GLOBAL=1 || GLOBAL=0; ")
        append("echo \"${ModuleSafetyOutput.DISABLE_SUMMARY} total=\$TOTAL disabled=\$DISABLED global=\$GLOBAL parked=\$PARK ksud=\$KSUD_OK\"")
    }

    fun restoreScript(
        moduleIds: List<String>,
        moduleRoot: String = DEFAULT_MODULE_ROOT,
        parkedRoot: String = DEFAULT_PARKED_MODULE_ROOT,
        ksud: String = DEFAULT_KSUD,
        globalFlag: String = GLOBAL_FLAG
    ): String {
        val safeIds = moduleIds.filter { it.isNotBlank() && PathSafety.isSafeFileName(it) }.distinct()
        val idList = safeIds.joinToString(" ") { PathSafety.quote(it) }

        return buildString {
            append("KSUD=${PathSafety.quote(ksud)}; ")
            append("MODS=${PathSafety.quote(moduleRoot)}; ")
            append("PARKED=${PathSafety.quote(parkedRoot)}; ")
            append("FLAG=${PathSafety.quote(globalFlag)}; ")
            append("[ -x \"\$KSUD\" ] && KSUD_OK=1 || KSUD_OK=0; ")
            append("RESTORED=0; ")
            append("for id in $idList; do ")
            append("m=\"\$MODS/\$id\"; ")
            append("[ -d \"\$m\" ] || { echo \"OTA_MOD_RESTORE_FAILED \$id\"; continue; }; ")
            append("[ \"\$KSUD_OK\" = 1 ] && \"\$KSUD\" module enable \"\$id\" >/dev/null 2>&1; ")
            append("rm -f \"\$m/disable\"; ")
            append("if [ -f \"\$m/disable\" ]; then echo \"OTA_MOD_RESTORE_FAILED \$id\"; ")
            append("else RESTORED=\$((RESTORED+1)); echo \"OTA_MOD_RESTORED \$id\"; fi; ")
            append("done; ")
            append("UNPARK=0; ")
            append("if [ -d \"\$PARKED\" ]; then ")
            append("rm -rf \"\$MODS/../modules_update\"; ")
            append("mv \"\$PARKED\" \"\$MODS/../modules_update\" && UNPARK=1; ")
            append("fi; ")
            append("rm -f \"\$FLAG\"; ")
            append("[ -f \"\$FLAG\" ] && GLOBAL=1 || GLOBAL=0; ")
            append("echo \"${ModuleSafetyOutput.RESTORE_SUMMARY} restored=\$RESTORED unparked=\$UNPARK global=\$GLOBAL\"")
        }
    }

    /**
     * One pass over the installed modules for the preflight report.
     *
     * The previous implementation spawned three `su` processes per module (measured ~3 s for seven
     * modules, on every Home refresh); this keeps it to a single shell.
     *
     * Output, one line per module: `OTA_MODULE <id>|<0|1 enabled>|<0|1 zygote hook>|<name>|<version>`.
     */
    fun listScript(moduleRoot: String = DEFAULT_MODULE_ROOT): String = buildString {
        append("MODS=${PathSafety.quote(moduleRoot)}; ")
        append("for m in \"\$MODS\"/*; do ")
        append("[ -d \"\$m\" ] || continue; ")
        append("id=\${m##*/}; ")
        append("[ -f \"\$m/disable\" ] && E=0 || E=1; ")
        append("if [ -d \"\$m/zygisk\" ] || [ -f \"\$m/zygisk\" ] || [ -f \"\$m/post-fs-data.sh\" ]; then Z=1; else Z=0; fi; ")
        append("case \"\$id\" in *zygisk*|*tricky*) Z=1;; esac; ")
        append("N=\$(sed -n 's/^name=//p' \"\$m/module.prop\" 2>/dev/null | head -n 1); ")
        append("V=\$(sed -n 's/^version=//p' \"\$m/module.prop\" 2>/dev/null | head -n 1); ")
        append("echo \"OTA_MODULE \$id|\$E|\$Z|\$N|\$V\"; ")
        append("done")
    }
}
