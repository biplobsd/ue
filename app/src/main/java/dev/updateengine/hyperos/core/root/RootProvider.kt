package dev.updateengine.hyperos.core.root

import dev.updateengine.hyperos.core.model.KernelModule
import dev.updateengine.hyperos.core.model.RootKind
import dev.updateengine.hyperos.core.model.RootStatus

/**
 * Result of a KernelSU inactive-slot patch.
 *
 * [bootDevice] is the partition ksud reported it wrote to (`- Bootdevice: /dev/block/by-name/...`),
 * which is cross-checked against the slot the engine believes it is patching.
 *
 * [prePatchHash] / [postPatchHash] are the SHA-256 of the inactive partition *before* and *after*
 * ksud ran. They are the primary proof that the write happened: ksud itself prints the stock backup
 * path only when the target image did not already carry KernelSU, so a verification that depended on
 * that line could never succeed on a retry. [alreadyPatched] reports the one case where the two
 * hashes legitimately match: the inactive slot already contained a KernelSU-patched image.
 */
data class BootPatchOutcome(
    val stockBackupPath: String,
    val bootDevice: String,
    val prePatchHash: String = "",
    val postPatchHash: String = "",
    val alreadyPatched: Boolean = false
)

/**
 * Outcome of disabling every installed KernelSU module before the first boot after an OTA.
 *
 * [previouslyEnabled] is the set of module ids that were active before this ran. It is persisted so
 * an aborted update can put the device back the way the user had it, instead of silently leaving
 * every module disabled.
 *
 * [pendingUpdatesParked] reports that `/data/adb/modules_update` was moved aside, because a module
 * update that completes itself during the first boot would otherwise bypass the disable flags.
 */
data class ModuleDisableReport(
    val previouslyEnabled: List<String>,
    val disabled: List<String>,
    val failed: List<String>,
    val pendingUpdatesParked: Boolean,
    val globalFlagSet: Boolean,
    val ksudUsed: Boolean,
    val raw: String = ""
) {
    val isComplete: Boolean get() = failed.isEmpty() && globalFlagSet
}

/** Outcome of re-enabling the modules that were active before an aborted update. */
data class ModuleRestoreReport(
    val restored: List<String>,
    val failed: List<String>,
    val pendingUpdatesUnparked: Boolean,
    val globalFlagCleared: Boolean,
    val raw: String = ""
) {
    val isComplete: Boolean get() = failed.isEmpty() && globalFlagCleared
}

interface RootProvider {
    val kind: RootKind
    val shell: RootShell

    suspend fun probe(): RootStatus
    suspend fun patchInactiveBoot(targetPartition: String): Result<BootPatchOutcome>
    suspend fun verifyPatchedBoot(stockBackupPath: String, targetPartition: String): Boolean
    suspend fun revertSlotSwitch(): Boolean

    /**
     * Disables every installed module so nothing can inject into Zygote or the display compositor on
     * the first boot after an OTA. Modules are disabled through `ksud module disable`, which also
     * regenerates the preinit `modules.rc` that gets spliced into `init.rc`; a plain `touch` on the
     * disable file would leave that file stale and could still mount an injection module at preinit.
     */
    suspend fun disableAllModules(): ModuleDisableReport

    /**
     * Re-enables exactly [moduleIds] and restores any parked module update. Used when an update is
     * abandoned before the reboot, because the device keeps running the build the modules were
     * written for.
     */
    suspend fun restoreModules(moduleIds: List<String>): ModuleRestoreReport

    /** Clears the global disable flag only; the individual modules stay disabled on purpose. */
    suspend fun clearGlobalSafeMode(): Boolean

    suspend fun listModules(): List<KernelModule>
    suspend fun reboot(): Boolean
}
