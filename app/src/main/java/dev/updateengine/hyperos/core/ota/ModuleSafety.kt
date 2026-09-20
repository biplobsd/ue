package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.root.ModuleDisableReport
import dev.updateengine.hyperos.core.root.ModuleRestoreReport
import dev.updateengine.hyperos.core.root.RootProvider
import kotlinx.coroutines.withContext

/**
 * Module safety for the first boot after an OTA.
 *
 * KernelSU Next has no global "safe mode file": the mechanism that actually keeps modules out of
 * early boot is the per-module `disable` flag, which ksud honours for sepolicy, `post-fs-data.sh`,
 * `service.sh` and module mounting. Modules are therefore disabled through `ksud module disable`,
 * which additionally regenerates the preinit `modules.rc` spliced into `init.rc`.
 *
 * Modules are never enabled automatically after the update: `ksud` and the display compositor have to
 * survive the first boot and the Virtual A/B merge with an unmodified system, so the user re-enables
 * them one by one afterwards.
 */
class ModuleSafety(
    private val rootProvider: RootProvider,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    /** Disables every installed module ahead of the first boot into the new slot. */
    suspend fun disableAllModulesBeforeReboot(): ModuleDisableReport = withContext(dispatchers.io) {
        rootProvider.disableAllModules()
    }

    /**
     * Puts the module state back the way it was before an aborted update. Only called when the device
     * stays on its current, already-working slot.
     */
    suspend fun restoreModulesAfterAbort(moduleIds: List<String>): ModuleRestoreReport =
        withContext(dispatchers.io) {
            rootProvider.restoreModules(moduleIds)
        }

    /**
     * Clears the global flag after the merge finished. The individual modules stay disabled until the
     * user re-enables them in KernelSU Next.
     */
    suspend fun clearGlobalFlagAfterMerge(): Boolean = withContext(dispatchers.io) {
        rootProvider.clearGlobalSafeMode()
    }
}
