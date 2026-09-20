package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.root.RootProvider
import dev.updateengine.hyperos.core.root.SystemProps
import kotlinx.coroutines.withContext

class PostBootVerifier(
    private val rootProvider: RootProvider,
    private val systemProps: SystemProps,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    data class PostBootResult(
        val isSuccess: Boolean,
        val activeSlot: String,
        val incremental: String,
        val isRootPresent: Boolean,
        val detail: String,
        val warnings: List<String> = emptyList()
    )

    /**
     * Verifies the first boot after the flash.
     *
     * Only the slot flip and the presence of root are blocking. The build tag is checked as a
     * non-blocking warning: HyperOS reports its own marketing version in
     * `ro.build.version.incremental` (e.g. `OS4.0.0.8.XOKCNXM`) while the package metadata carries
     * the AOSP build id (e.g. `17OS4.0.260916.164405901.QCPECN.S`), so a strict string comparison
     * would fail on every Xiaomi build and strand the device in Safe Mode.
     */
    suspend fun verify(
        expectedSlot: String,
        expectedIncremental: String? = null,
        expectedBuildDateUtc: Long = 0L
    ): PostBootResult = withContext(dispatchers.io) {
        val curSlot = systemProps.getSlotSuffix()
        val curIncremental = systemProps.getIncremental()
        val curBuildDate = systemProps.getBuildDateUtc()
        val rootStatus = rootProvider.probe()

        val slotFlipped = curSlot.isNotBlank() && curSlot.equals(expectedSlot, ignoreCase = true)
        val rootGranted = rootStatus.isRootGranted

        val incrementalMatches = !expectedIncremental.isNullOrBlank() &&
            curIncremental.equals(expectedIncremental, ignoreCase = true)
        val buildDateAdvanced = expectedBuildDateUtc > 0 && curBuildDate > 0 && curBuildDate >= expectedBuildDateUtc
        val buildConfirmed = incrementalMatches || buildDateAdvanced

        val warnings = mutableListOf<String>()
        if (!buildConfirmed) {
            warnings.add(
                "Build tag could not be confirmed from properties " +
                    "(ro.build.version.incremental='$curIncremental', package incremental='${expectedIncremental.orEmpty()}', " +
                    "build date $curBuildDate vs package timestamp $expectedBuildDateUtc)."
            )
        }

        val success = slotFlipped && rootGranted

        val detail = when {
            !slotFlipped -> "Device booted into slot '$curSlot', but the updated slot was '$expectedSlot'."
            !rootGranted -> "Device switched to slot '$curSlot', but root access was lost."
            !buildConfirmed -> "Running on slot $curSlot with root intact, but the installed build could not be confirmed."
            else -> "Post-boot verified: running on slot $curSlot, version $curIncremental with root intact."
        }

        PostBootResult(
            isSuccess = success,
            activeSlot = curSlot,
            incremental = curIncremental,
            isRootPresent = rootGranted,
            detail = detail,
            warnings = warnings
        )
    }
}
