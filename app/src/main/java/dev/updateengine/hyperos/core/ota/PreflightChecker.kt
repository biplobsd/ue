package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.common.RomCompatibility
import dev.updateengine.hyperos.core.datastore.SettingsStorage
import dev.updateengine.hyperos.core.model.Check
import dev.updateengine.hyperos.core.model.OtaTarget
import dev.updateengine.hyperos.core.model.Outcome
import dev.updateengine.hyperos.core.model.PreflightReport
import dev.updateengine.hyperos.core.model.RootKind
import dev.updateengine.hyperos.core.model.Severity
import dev.updateengine.hyperos.core.root.RootProvider
import dev.updateengine.hyperos.core.root.SystemProps
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class PreflightChecker(
    private val rootProvider: RootProvider,
    private val systemProps: SystemProps,
    private val settingsStorage: SettingsStorage,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    suspend fun runChecks(
        target: OtaTarget?,
        isFullOta: Boolean = true,
        postSdkLevel: Int = 0,
        postTimestamp: Long = 0L
    ): PreflightReport = withContext(dispatchers.io) {
        val checks = mutableListOf<Check>()

        // 1. Root & KernelSU Next Check
        val rootStatus = rootProvider.probe()
        if (!rootStatus.isRootGranted || rootStatus.kind != RootKind.KERNELSU_NEXT) {
            checks.add(
                Check(
                    id = "root_provider",
                    title = "KernelSU Next Root Access",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = rootStatus.details,
                    remediation = "Install KernelSU Next and grant root access to HyperOS OTA Engine."
                )
            )
        } else {
            checks.add(
                Check(
                    id = "root_provider",
                    title = "KernelSU Next Root Access",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.PASS,
                    detail = "Root granted (${rootStatus.ksudVersion}). Default partition: ${rootStatus.defaultPartition}${rootStatus.inactiveSlot}"
                )
            )
        }

        // 1b. The inactive slot has to be known before the ~7 GB download starts. Everything up to
        //     Gate 1 is reversible, but an unresolvable patch target only surfaces when ksud is asked
        //     to patch it — by which point the payload is already written into the new slot and the
        //     only action left is reverting the slot switch. Reporting it here keeps the run on the
        //     non-destructive side of the pipeline.
        if (rootStatus.isRootGranted && rootStatus.inactiveSlot.isBlank()) {
            checks.add(
                Check(
                    id = "inactive_slot",
                    title = "Inactive Slot Detection",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = "The inactive slot could not be determined (ksud boot-info slot-suffix and ro.boot.slot_suffix both returned nothing).",
                    remediation = "Confirm this is an A/B device (getprop ro.boot.slot_suffix) and that KernelSU Next is working, then start the update again."
                )
            )
        } else {
            checks.add(
                Check(
                    id = "inactive_slot",
                    title = "Inactive Slot Detection",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.PASS,
                    detail = if (rootStatus.isRootGranted) {
                        "Inactive slot resolved: ${rootStatus.defaultPartition}${rootStatus.inactiveSlot}"
                    } else {
                        "Not evaluated: root access is not available."
                    }
                )
            )
        }

        // 2. Virtual A/B Support
        val isVab = systemProps.isVirtualAb()
        if (!isVab) {
            checks.add(
                Check(
                    id = "vab_support",
                    title = "Virtual A/B Architecture",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = "Device does not report Virtual A/B support (ro.virtual_ab.enabled / ro.build.ab_update).",
                    remediation = "This tool requires a modern Google Virtual A/B partition layout."
                )
            )
        } else {
            checks.add(
                Check(
                    id = "vab_support",
                    title = "Virtual A/B Architecture",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.PASS,
                    detail = "Virtual A/B enabled on active slot ${rootStatus.currentSlot}"
                )
            )
        }

        // 3. Device Codename Match
        val currentDevice = systemProps.getDevice()
        if (target != null && !target.device.equals(currentDevice, ignoreCase = true)) {
            checks.add(
                Check(
                    id = "device_match",
                    title = "Device Hardware Codename",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = "ROM target is for '${target.device}', but device is '$currentDevice'.",
                    remediation = "Flash strictly ROMs matching your device hardware codename."
                )
            )
        } else {
            checks.add(
                Check(
                    id = "device_match",
                    title = "Device Hardware Codename",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.PASS,
                    detail = "Device codename verified: $currentDevice"
                )
            )
        }

        // 4. Regional Resign Match (cross-region packages can break userdata decryption)
        val currentRegion = RomCompatibility.region(systemProps.getIncremental())
        val targetRegion = target?.let { RomCompatibility.region(it.osVersion) }
        when {
            target == null -> checks.add(
                Check(
                    id = "region_resign_match",
                    title = "Regional Resign Match",
                    severity = Severity.WARN,
                    outcome = Outcome.UNKNOWN,
                    detail = "No target ROM selected yet."
                )
            )
            currentRegion == null || targetRegion == null -> checks.add(
                Check(
                    id = "region_resign_match",
                    title = "Regional Resign Match",
                    severity = Severity.WARN,
                    outcome = Outcome.UNKNOWN,
                    detail = "Could not determine the regional resign (installed: ${currentRegion ?: "unknown"}, target: ${targetRegion ?: "unknown"})."
                )
            )
            targetRegion != currentRegion -> checks.add(
                Check(
                    id = "region_resign_match",
                    title = "Regional Resign Match",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = "Target ROM is ${RomCompatibility.regionLabel(targetRegion)}, but the installed build is ${RomCompatibility.regionLabel(currentRegion)}.",
                    remediation = "Only flash ROMs carrying the same regional resign. A cross-region package can fail userdata decryption and bootloop the device."
                )
            )
            else -> checks.add(
                Check(
                    id = "region_resign_match",
                    title = "Regional Resign Match",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.PASS,
                    detail = "Target ROM matches installed resign: ${RomCompatibility.regionLabel(targetRegion)}"
                )
            )
        }

        // 5. Anti-Downgrade: Android SDK level & timestamp check
        val currentSdk = systemProps.getSdkLevel()
        val currentTimestamp = systemProps.getBuildDateUtc()
        if (postSdkLevel > 0 && currentSdk > 0 && postSdkLevel < currentSdk) {
            checks.add(
                Check(
                    id = "anti_downgrade_sdk",
                    title = "Android OS Anti-Downgrade",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = "Target Android SDK ($postSdkLevel) is lower than current device SDK ($currentSdk).",
                    remediation = "Downgrading Android versions destroys File-Based Encryption (FBE) keys, forcing a complete data wipe."
                )
            )
        } else if (postTimestamp > 0 && currentTimestamp > 0 && postTimestamp < currentTimestamp) {
            checks.add(
                Check(
                    id = "anti_downgrade_timestamp",
                    title = "Security Patch Anti-Downgrade",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = "Target build date ($postTimestamp) is older than current build date ($currentTimestamp).",
                    remediation = "Downgrading patch levels can trigger decryption failures. Use a build released after your current ROM."
                )
            )
        } else if (postSdkLevel <= 0 && postTimestamp <= 0) {
            // A catalogue entry carries no build metadata: post-sdk-level and post-timestamp only exist
            // inside the package, and HyperDataApiClient does not invent them. Reporting a green PASS
            // here would claim a comparison that never ran, so it stays unresolved until the package
            // has been inspected — the same values are re-checked against the real metadata by
            // checkPackageAgainstDevice() before anything is written to the inactive slot.
            checks.add(
                Check(
                    id = "anti_downgrade",
                    title = "Anti-Downgrade Protection",
                    severity = Severity.WARN,
                    outcome = Outcome.UNKNOWN,
                    detail = "Not compared yet: the package build metadata (post-sdk-level / post-timestamp) is read from the package itself after the download.",
                    remediation = "Nothing to do. The engine repeats this check on the inspected package and blocks the flash if the package is a downgrade."
                )
            )
        } else {
            checks.add(
                Check(
                    id = "anti_downgrade",
                    title = "Anti-Downgrade Protection",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.PASS,
                    detail = "Target OS version is forward or equal to current system."
                )
            )
        }

        // 6. Full OTA Package check
        if (!isFullOta) {
            checks.add(
                Check(
                    id = "full_ota_type",
                    title = "Package Type (Full Recovery OTA)",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = "Package contains 'pre-build-incremental' tags (Incremental OTA).",
                    remediation = "Only Full Recovery OTA packages (ota_full) are safe to flash over root."
                )
            )
        } else {
            checks.add(
                Check(
                    id = "full_ota_type",
                    title = "Package Type (Full Recovery OTA)",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.PASS,
                    detail = "Package verified as Full Recovery OTA."
                )
            )
        }

        // 7. Storage Space Free Check on /data
        val dfRes = rootProvider.shell.exec("df -k /data 2>/dev/null", timeoutMs = 5000)
        val freeBytes = parseDfAvailableBytes(dfRes.stdout) ?: File("/data").freeSpace
        // The user's margin is the one that decides, so the picker in Settings really is in charge.
        val minGb = settingsStorage.minStorageMarginGb.first()
        val recommendedGb = minGb + 4
        val minRequiredBytes = minGb * 1024L * 1024 * 1024
        val recommendedBytes = recommendedGb * 1024L * 1024 * 1024
        val freeGb = freeBytes / (1024.0 * 1024 * 1024)

        if (freeBytes < minRequiredBytes) {
            checks.add(
                Check(
                    id = "storage_free",
                    title = "Free Storage in /data",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = String.format("%.1f GB free (minimum $minGb GB required for snapshot COW blocks).", freeGb),
                    remediation = "Free up storage space before starting the update."
                )
            )
        } else if (freeBytes < recommendedBytes) {
            checks.add(
                Check(
                    id = "storage_free",
                    title = "Free Storage in /data",
                    severity = Severity.WARN,
                    outcome = Outcome.FAIL,
                    detail = String.format("%.1f GB free (recommended >= $recommendedGb GB).", freeGb),
                    remediation = "Consider deleting large media files to ensure ample room for Virtual A/B snapshots."
                )
            )
        } else {
            checks.add(
                Check(
                    id = "storage_free",
                    title = "Free Storage in /data",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.PASS,
                    detail = String.format("%.1f GB free in /data", freeGb)
                )
            )
        }

        // 8. Active Snapshot / Merge Session Check. The dump is parsed instead of pattern-matched so an
        //    unreadable answer is reported as unknown rather than assumed to be a clean device.
        val snapshot = SnapshotState.parse(rootProvider.shell.exec("snapshotctl dump 2>&1", timeoutMs = 5000).stdout)
        when {
            snapshot.blocksNewUpdate -> checks.add(
                Check(
                    id = "snapshot_state",
                    title = "Virtual A/B Snapshot State",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.FAIL,
                    detail = "The snapshot daemon reports 'Update state: ${snapshot.label}'.",
                    remediation = "Wait for the background snapshot merge to finish before starting a new update."
                )
            )
            !snapshot.isDetermined -> checks.add(
                Check(
                    id = "snapshot_state",
                    title = "Virtual A/B Snapshot State",
                    severity = Severity.WARN,
                    outcome = Outcome.UNKNOWN,
                    detail = "Could not read the snapshot state from 'snapshotctl dump' (${snapshot.raw.ifBlank { "no output" }}).",
                    remediation = "Run 'su -c snapshotctl dump' to check manually before flashing."
                )
            )
            else -> checks.add(
                Check(
                    id = "snapshot_state",
                    title = "Virtual A/B Snapshot State",
                    severity = Severity.BLOCKER,
                    outcome = Outcome.PASS,
                    detail = if (snapshot.state == SnapshotState.State.INITIATED) {
                        "Update state: ${snapshot.label} (a previous space allocation is present; it is released before flashing)."
                    } else {
                        "Snapshots idle (Update state: ${snapshot.label})."
                    }
                )
            )
        }

        // 9. Injection Modules Inspection
        val modules = rootProvider.listModules()
        val injectionModules = modules.filter { it.isEnabled && it.isZygoteHook }
        if (injectionModules.isNotEmpty()) {
            val names = injectionModules.joinToString(", ") { it.name }
            checks.add(
                Check(
                    id = "injection_modules",
                    title = "Zygote Hook & Injection Modules",
                    severity = Severity.WARN,
                    outcome = Outcome.FAIL,
                    detail = "${injectionModules.size} active hook module(s) found ($names).",
                    remediation = "Every module will be disabled for the first boot (through ksud) to prevent display compositor crashes."
                )
            )
        } else {
            checks.add(
                Check(
                    id = "injection_modules",
                    title = "Zygote Hook & Injection Modules",
                    severity = Severity.INFO,
                    outcome = Outcome.PASS,
                    detail = "No conflicting active injection modules detected."
                )
            )
        }

        // 10. Battery & Power Blocker
        checks.add(evaluateBatteryCheck())

        PreflightReport(checks)
    }

    /**
     * Re-validates the conditions that can change between Chain A and Gate 1: the battery may have
     * drained and another update session may have started in the meantime. Returns the first failing
     * check, or null when flashing may proceed.
     */
    suspend fun recheckFlashReadiness(): Check? = withContext(dispatchers.io) {
        val battery = evaluateBatteryCheck()
        if (battery.outcome == Outcome.FAIL) {
            return@withContext battery
        }

        val snapshot = SnapshotState.parse(rootProvider.shell.exec("snapshotctl dump 2>&1", timeoutMs = 5000).stdout)
        if (snapshot.blocksNewUpdate) {
            return@withContext Check(
                id = "snapshot_state_recheck",
                title = "Virtual A/B Snapshot State Changed",
                severity = Severity.BLOCKER,
                outcome = Outcome.FAIL,
                detail = "The snapshot daemon now reports 'Update state: ${snapshot.label}'.",
                remediation = "Wait for the running update or merge to finish, then start the update again."
            )
        }

        // This runs immediately before update_engine is allowed to write. An unreadable snapshot state
        // is treated as a blocker here (unlike in the first pass) because the next step is destructive.
        if (!snapshot.isDetermined) {
            return@withContext Check(
                id = "snapshot_state_recheck",
                title = "Snapshot State Unreadable Before Flashing",
                severity = Severity.BLOCKER,
                outcome = Outcome.FAIL,
                detail = "Could not read the snapshot state from 'snapshotctl dump' (${snapshot.raw.ifBlank { "no output" }}).",
                remediation = "Run 'su -c snapshotctl dump' and confirm it prints 'Update state: none', then start the update again."
            )
        }

        null
    }

    /** Free space on /data in bytes, as seen by the root shell. */
    suspend fun freeSpaceOnData(): Long = withContext(dispatchers.io) {
        val dfRes = rootProvider.shell.exec("df -k /data 2>/dev/null", timeoutMs = 5000)
        parseDfAvailableBytes(dfRes.stdout) ?: File("/data").freeSpace
    }

    /**
     * Validates the downloaded package metadata against the running device.
     * Returns a failing BLOCKER check when the package must not be installed, or null when safe.
     */
    suspend fun checkPackageAgainstDevice(
        preDevice: String,
        postSdkLevel: Int,
        postTimestamp: Long,
        targetRegion: String? = null
    ): Check? = withContext(dispatchers.io) {
        val currentDevice = systemProps.getDevice()
        if (preDevice.isNotBlank() && !preDevice.equals(currentDevice, ignoreCase = true)) {
            return@withContext Check(
                id = "package_device_match",
                title = "Package Hardware Codename",
                severity = Severity.BLOCKER,
                outcome = Outcome.FAIL,
                detail = "The downloaded package targets '$preDevice', but this device is '$currentDevice'.",
                remediation = "Download the package for your exact device codename."
            )
        }

        // Cross-resign packages can fail userdata decryption, so the check is repeated against the
        // real package metadata and not only against the catalogue entry.
        val installedRegion = RomCompatibility.region(systemProps.getIncremental())
        if (targetRegion != null && installedRegion != null && !targetRegion.equals(installedRegion, ignoreCase = true)) {
            return@withContext Check(
                id = "package_region_resign",
                title = "Package Regional Resign",
                severity = Severity.BLOCKER,
                outcome = Outcome.FAIL,
                detail = "The package is ${RomCompatibility.regionLabel(targetRegion)}, but the installed build is ${RomCompatibility.regionLabel(installedRegion)}.",
                remediation = "Only flash packages carrying the same regional resign as the installed build."
            )
        }

        val currentSdk = systemProps.getSdkLevel()
        if (postSdkLevel > 0 && currentSdk > 0 && postSdkLevel < currentSdk) {
            return@withContext Check(
                id = "package_anti_downgrade_sdk",
                title = "Android OS Anti-Downgrade",
                severity = Severity.BLOCKER,
                outcome = Outcome.FAIL,
                detail = "Package Android SDK level ($postSdkLevel) is lower than the installed SDK ($currentSdk).",
                remediation = "Downgrading Android destroys File-Based Encryption keys and forces a data wipe. Use a newer package."
            )
        }

        val currentTimestamp = systemProps.getBuildDateUtc()
        if (postTimestamp > 0 && currentTimestamp > 0 && postTimestamp < currentTimestamp) {
            return@withContext Check(
                id = "package_anti_downgrade_timestamp",
                title = "Build Anti-Downgrade",
                severity = Severity.BLOCKER,
                outcome = Outcome.FAIL,
                detail = "Package build date ($postTimestamp) is older than the installed build date ($currentTimestamp).",
                remediation = "Use a package released after your current build to avoid decryption failures."
            )
        }

        null
    }

    private suspend fun evaluateBatteryCheck(): Check {
        val res = rootProvider.shell.exec("dumpsys battery 2>/dev/null", timeoutMs = 5000)
        val level = Regex("(?m)^\\s*level:\\s*(\\d+)").find(res.stdout)?.groupValues?.get(1)?.toIntOrNull()
        val status = Regex("(?m)^\\s*status:\\s*(\\d+)").find(res.stdout)?.groupValues?.get(1)?.toIntOrNull()
        val isCharging = status == 2 || status == 5 // BATTERY_STATUS_CHARGING / BATTERY_STATUS_FULL

        return when {
            level == null -> Check(
                id = "battery",
                title = "Battery & Power",
                severity = Severity.WARN,
                outcome = Outcome.UNKNOWN,
                detail = "Could not read the battery level.",
                remediation = "Confirm the battery is at least 60% or that the device is plugged in before flashing."
            )
            isCharging || level >= 60 -> Check(
                id = "battery",
                title = "Battery & Power",
                severity = Severity.BLOCKER,
                outcome = Outcome.PASS,
                detail = "Battery $level%" + (if (isCharging) " (charging)" else "")
            )
            else -> Check(
                id = "battery",
                title = "Battery & Power",
                severity = Severity.BLOCKER,
                outcome = Outcome.FAIL,
                detail = "Battery is at $level% and the device is not charging.",
                remediation = "Charge the device to at least 60% or keep it plugged into a reliable charger during the update."
            )
        }
    }

    private fun parseDfAvailableBytes(output: String): Long? {
        val line = output.lines().lastOrNull { it.isNotBlank() && !it.startsWith("Filesystem") } ?: return null
        val cols = line.trim().split(Regex("\\s+"))
        if (cols.size < 4) return null
        val availableKb = cols[3].toLongOrNull() ?: return null
        return availableKb * 1024L
    }
}
