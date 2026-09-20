package dev.updateengine.hyperos.core.root

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.common.PathSafety
import dev.updateengine.hyperos.core.model.KernelModule
import dev.updateengine.hyperos.core.model.RootKind
import dev.updateengine.hyperos.core.model.RootStatus
import kotlinx.coroutines.withContext

class KernelSuNextProvider(
    override val shell: RootShell,
    private val dispatchers: AppDispatchers = AppDispatchers()
) : RootProvider {

    override val kind: RootKind = RootKind.KERNELSU_NEXT

    private var cachedPartition: String = "init_boot"
    private var cachedCurrentSlot: String = ""
    private var cachedInactiveSlot: String = ""

    private companion object {
        /** A boot image is never smaller than this; guards against a truncated backup file. */
        const val MIN_BOOT_IMAGE_BYTES = 1024L * 1024L

        /** SHA-256 of an empty stream, returned when a block device read comes back short. */
        const val SHA256_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    override suspend fun probe(): RootStatus = withContext(dispatchers.io) {
        if (!shell.isAvailable()) {
            return@withContext RootStatus(
                kind = RootKind.NONE,
                isRootGranted = false,
                details = "Root permission not granted or su not available"
            )
        }

        val ksudProbe = shell.exec("/data/adb/ksu/bin/ksud --version", timeoutMs = 5000)
        if (!ksudProbe.isSuccess && !ksudProbe.stdout.contains("ksud")) {
            return@withContext RootStatus(
                kind = RootKind.NONE,
                isRootGranted = true,
                details = "su granted, but KernelSU Next binary (/data/adb/ksu/bin/ksud) was not found."
            )
        }

        val version = ksudProbe.stdout.trim()

        val partRes = shell.exec("/data/adb/ksu/bin/ksud boot-info default-partition", timeoutMs = 5000)
        val defaultPart = if (partRes.isSuccess && partRes.stdout.isNotBlank()) {
            partRes.stdout.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "init_boot"
        } else {
            "init_boot"
        }
        cachedPartition = defaultPart

        val slotRes = shell.exec("/data/adb/ksu/bin/ksud boot-info slot-suffix", timeoutMs = 5000)
        var curSlot = if (slotRes.isSuccess && slotRes.stdout.isNotBlank()) {
            slotRes.stdout.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        } else {
            ""
        }
        // ksud is deliberately asked without -u/--ota, so this is the slot the device is booted from.
        // ro.boot.slot_suffix is the kernel's view of exactly the same thing, and is used as a fallback
        // so a ksud build without boot-info support cannot leave the engine without a patch target.
        if (curSlot.isBlank()) {
            curSlot = shell.exec("getprop ro.boot.slot_suffix", timeoutMs = 3000)
                .stdout.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        }
        cachedCurrentSlot = curSlot
        cachedInactiveSlot = when (curSlot) {
            "_a" -> "_b"
            "_b" -> "_a"
            else -> ""
        }

        RootStatus(
            kind = RootKind.KERNELSU_NEXT,
            isRootGranted = true,
            ksudVersion = version,
            defaultPartition = defaultPart,
            currentSlot = curSlot,
            inactiveSlot = cachedInactiveSlot,
            details = "KernelSU Next verified: $version (Target partition: $defaultPart$cachedInactiveSlot)"
        )
    }

    /**
     * Patches the inactive boot partition and proves the write with a hash comparison.
     *
     * The partition is hashed before and after ksud runs. ksud only prints the stock backup path when
     * the target image did not already carry KernelSU (`!is_kernelsu_patched && flash` in
     * boot_patch.rs), so a version of this method that required that line could never verify a retry:
     * the second run patches the already-patched image, prints no backup path and would be reported as
     * a failure forever while the slot was in fact rooted. The pre/post hashes are independent of
     * ksud's output, and the one ambiguous case — both hashes equal — is resolved by checking the
     * existing stock backups: if the partition still equals a stock image then nothing was written,
     * and if it matches none of them the image was already patched.
     */
    override suspend fun patchInactiveBoot(targetPartition: String): Result<BootPatchOutcome> =
        withContext(dispatchers.io) {
            try {
                val targetBlock = if (targetPartition.startsWith("/dev/")) {
                    targetPartition
                } else {
                    "/dev/block/by-name/$targetPartition"
                }

                val blockStat = shell.exec("ls -l ${PathSafety.quote(targetBlock)}", timeoutMs = 5000)
                if (!blockStat.isSuccess) {
                    return@withContext Result.failure(
                        Exception("The inactive partition $targetBlock does not exist. Nothing was written.")
                    )
                }

                // 1. Fingerprint the partition the engine believes update_engine just wrote. This is
                //    the reference the write has to be proven against.
                val preHash = hashPartition(targetBlock)
                    ?: return@withContext Result.failure(
                        Exception(
                            "Could not read $targetBlock before patching, so the write could not be " +
                                "verified afterwards. Nothing was written."
                        )
                    )

                // 2. Run ksud boot-patch -u -f (patches the inactive slot).
                val res = shell.exec("/data/adb/ksu/bin/ksud boot-patch -u -f", timeoutMs = 180_000)
                val combinedOutput = res.stdout + "\n" + res.stderr

                if (!combinedOutput.contains("- Done!")) {
                    return@withContext Result.failure(
                        Exception("ksud boot-patch failed to finish. Output:\n$combinedOutput")
                    )
                }

                // "- Bootdevice: /dev/block/by-name/init_boot_b" tells us which partition was actually
                // written, which is cross-checked against the slot the engine computed.
                val bootDeviceRegex = Regex("""Bootdevice:\s*(\S+)""")
                val bootDevice = bootDeviceRegex.find(combinedOutput)?.groupValues?.get(1)?.trim().orEmpty()

                // Extract backup path: "- /data/adb/ksu/ksun_backup_..."
                val backupRegex = Regex("""/data/adb/ksu/ksun_backup_[a-zA-Z0-9_]+""")
                val backupPath = backupRegex.find(combinedOutput)?.value ?: ""

                // 3. Fingerprint again: a different digest proves ksud wrote into this partition.
                val postHash = hashPartition(targetBlock)
                    ?: return@withContext Result.failure(
                        Exception(
                            "Could not read $targetBlock after patching, so the write could not be " +
                                "verified."
                        )
                    )

                if (postHash != preHash) {
                    return@withContext Result.success(
                        BootPatchOutcome(
                            stockBackupPath = backupPath,
                            bootDevice = bootDevice,
                            prePatchHash = preHash,
                            postPatchHash = postHash,
                            alreadyPatched = false
                        )
                    )
                }

                // 4. The partition is byte-identical after the run.
                if (backupPath.isNotBlank()) {
                    return@withContext Result.failure(
                        Exception(
                            "ksud backed up the stock image but $targetBlock is byte-identical " +
                                "afterwards, so root was not written into the inactive slot."
                        )
                    )
                }

                // ksud skips its backup when the target already carries kernelsu.ko, so an unchanged
                // partition is accepted only when the image itself proves it is patched: the KernelSU
                // ramdisk entry is present. Anything else is a write that cannot be proven, and an
                // unproven inactive slot must never be offered to Gate 2.
                if (!partitionCarriesKernelSu(targetBlock)) {
                    return@withContext Result.failure(
                        Exception(
                            "$targetBlock did not change and contains no KernelSU ramdisk entry, so " +
                                "root was not written into the inactive slot."
                        )
                    )
                }

                // The image is already patched. No backup was produced for it, so the rescue guide gets
                // the newest existing backup that does not match the patched content, which is the best
                // available stock image candidate.
                val stockCandidate = listStockBackups().firstOrNull { it.first != preHash }?.second.orEmpty()
                Result.success(
                    BootPatchOutcome(
                        stockBackupPath = stockCandidate,
                        bootDevice = bootDevice,
                        prePatchHash = preHash,
                        postPatchHash = postHash,
                        alreadyPatched = true
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * True when the partition holds the KernelSU ramdisk entry (`kernelsu.ko`), which is what ksud adds
     * to a patched boot image and what `boot-restore` looks for. `grep` exits 1 with a count of 0 for a
     * stock image, so the count is parsed instead of the exit code.
     */
    private suspend fun partitionCarriesKernelSu(blockPath: String): Boolean {
        val res = shell.exec("grep -a -c kernelsu.ko ${PathSafety.quote(blockPath)} 2>/dev/null", timeoutMs = 60_000)
        return res.stdout.lines()
            .mapNotNull { it.trim().toIntOrNull() }
            .any { it > 0 }
    }

    /**
     * SHA-256 of a whole partition block device, or null when it cannot be read. An empty stream is
     * rejected: a short read would otherwise look like a legitimate digest.
     */
    private suspend fun hashPartition(blockPath: String): String? {
        val res = shell.exec("sha256sum ${PathSafety.quote(blockPath)} 2>/dev/null", timeoutMs = 120_000)
        if (!res.isSuccess) return null
        val sha = res.stdout.split("\\s+".toRegex()).firstOrNull()?.trim()?.lowercase() ?: return null
        if (sha.length != 64 || sha == SHA256_EMPTY) return null
        return sha
    }

    /**
     * Existing stock boot backups as `hash to path`, newest first, so the most recent patch of a slot
     * is the first candidate.
     */
    private suspend fun listStockBackups(): List<Pair<String, String>> {
        val res = shell.exec(
            "for b in \$(ls -t /data/adb/ksu/ksun_backup_* 2>/dev/null); do " +
                "[ -f \"\$b\" ] || continue; " +
                "echo \"OTA_BACKUP \$(sha256sum \"\$b\" 2>/dev/null | cut -d' ' -f1) \$b\"; done",
            timeoutMs = 60_000
        )
        return res.stdout.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("OTA_BACKUP ")) return@mapNotNull null
            val fields = trimmed.removePrefix("OTA_BACKUP ").trim().split(' ')
            if (fields.size < 2) return@mapNotNull null
            val hash = fields[0].lowercase()
            if (hash.length != 64) return@mapNotNull null
            hash to fields[1]
        }
    }

    override suspend fun verifyPatchedBoot(stockBackupPath: String, targetPartition: String): Boolean = withContext(dispatchers.io) {
        try {
            // 1. Check stock backup exists and looks like a real boot image
            val backupStat = shell.exec("stat -c %s ${PathSafety.quote(stockBackupPath)}", timeoutMs = 5000)
            if (!backupStat.isSuccess) return@withContext false
            val backupSize = backupStat.stdout.trim().toLongOrNull() ?: return@withContext false
            if (backupSize < MIN_BOOT_IMAGE_BYTES) return@withContext false

            // 2. Target block device path
            val targetBlock = if (targetPartition.startsWith("/dev/")) {
                targetPartition
            } else {
                "/dev/block/by-name/$targetPartition"
            }

            // Check target partition exists
            val blockStat = shell.exec("ls -l ${PathSafety.quote(targetBlock)}", timeoutMs = 5000)
            if (!blockStat.isSuccess) return@withContext false

            // 3. Compute sha256sum of stock backup
            val stockShaRes = shell.exec("sha256sum ${PathSafety.quote(stockBackupPath)}", timeoutMs = 30_000)
            if (!stockShaRes.isSuccess) return@withContext false
            val stockSha = stockShaRes.stdout.split("\\s+".toRegex()).firstOrNull()?.trim()?.lowercase() ?: ""
            if (stockSha.length != 64) return@withContext false

            // 4. Hash EXACTLY the same number of bytes from the target block device. A length
            //    mismatch would make the two digests differ for the wrong reason and turn this check
            //    into a rubber stamp, so the byte count is fixed by the backup size.
            var patchedShaRes = shell.exec(
                "head -c $backupSize ${PathSafety.quote(targetBlock)} 2>/dev/null | sha256sum",
                timeoutMs = 60_000
            )
            if (!patchedShaRes.isSuccess && backupSize % 4096L == 0L) {
                // Fallback for toybox builds without `head -c`; only usable on a page aligned image.
                patchedShaRes = shell.exec(
                    "dd if=${PathSafety.quote(targetBlock)} bs=4096 count=${backupSize / 4096} 2>/dev/null | sha256sum",
                    timeoutMs = 60_000
                )
            }
            if (!patchedShaRes.isSuccess) return@withContext false
            val patchedSha = patchedShaRes.stdout.split("\\s+".toRegex()).firstOrNull()?.trim()?.lowercase() ?: ""
            if (patchedSha.length != 64) return@withContext false

            // 5. A short/empty read would produce the digest of an empty stream; reject it.
            if (patchedSha == SHA256_EMPTY) return@withContext false

            // 6. Verification condition: the target block must NOT equal the stock backup, proving
            //    that KernelSU actually wrote a modified boot image into the inactive partition.
            stockSha != patchedSha
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun revertSlotSwitch(): Boolean = withContext(dispatchers.io) {
        val res = shell.exec("update_engine_client --reset_status", timeoutMs = 10_000)
        res.isSuccess
    }

    /**
     * Disables every installed module for the first boot into the new slot.
     *
     * KernelSU Next does not read a global `/data/adb/disable` file: what keeps a module out of early
     * boot is its own `disable` flag, honoured by ksud for sepolicy, scripts and mounting. The flag is
     * therefore written through `ksud module disable <id>`, which also regenerates the preinit
     * `modules.rc` that the kernel splices into `init.rc` on the next boot. A pending
     * `/data/adb/modules_update` is moved aside instead of deleted so an abort can restore it.
     */
    override suspend fun disableAllModules(): ModuleDisableReport = withContext(dispatchers.io) {
        val res = shell.exec(ModuleSafetyScripts.disableScript(), timeoutMs = 120_000)
        val parsed = ModuleSafetyOutput.parseDisable(res.stdout)
        val failed = parsed.failed + if (parsed.disabledCount < parsed.total) {
            listOf("<${parsed.total - parsed.disabledCount} module(s) without a verified disable flag>")
        } else {
            emptyList()
        }

        ModuleDisableReport(
            previouslyEnabled = parsed.previouslyEnabled,
            disabled = if (parsed.armed) parsed.previouslyEnabled else emptyList(),
            failed = if (parsed.armed) failed else listOf("<module scan did not run: ${res.stderr.take(200)}>"),
            pendingUpdatesParked = parsed.pendingUpdatesParked,
            globalFlagSet = parsed.globalFlagSet,
            ksudUsed = parsed.ksudUsed,
            raw = res.stdout
        )
    }

    /**
     * Re-enables exactly the modules that were active before an aborted update and puts a parked
     * module update back. Module ids are validated before they are interpolated into the shell loop.
     */
    override suspend fun restoreModules(moduleIds: List<String>): ModuleRestoreReport =
        withContext(dispatchers.io) {
            val rejected = moduleIds.filterNot { it.isNotBlank() && PathSafety.isSafeFileName(it) }

            val res = shell.exec(ModuleSafetyScripts.restoreScript(moduleIds), timeoutMs = 120_000)
            val parsed = ModuleSafetyOutput.parseRestore(res.stdout)

            ModuleRestoreReport(
                restored = parsed.restored,
                failed = parsed.failed + rejected.map { "<rejected id: $it>" },
                pendingUpdatesUnparked = parsed.pendingUpdatesUnparked,
                globalFlagCleared = parsed.completed && parsed.globalFlagCleared,
                raw = res.stdout
            )
        }

    override suspend fun clearGlobalSafeMode(): Boolean = withContext(dispatchers.io) {
        val res = shell.exec("rm -f /data/adb/disable; [ -f /data/adb/disable ] && echo STILL_SET || echo CLEARED", timeoutMs = 5000)
        res.stdout.contains("CLEARED")
    }

    override suspend fun listModules(): List<KernelModule> = withContext(dispatchers.io) {
        // One shell for the whole scan: three `su` spawns per module made the preflight take seconds.
        val res = shell.exec(ModuleSafetyScripts.listScript(), timeoutMs = 30_000)
        ModuleSafetyOutput.parseModuleList(res.stdout).map { entry ->
            KernelModule(
                id = entry.id,
                name = entry.name,
                version = entry.version,
                isEnabled = entry.isEnabled,
                isZygoteHook = entry.isZygoteHook
            )
        }
    }

    override suspend fun reboot(): Boolean = withContext(dispatchers.io) {
        val res = shell.exec("/system/bin/reboot", timeoutMs = 5000)
        res.isSuccess
    }
}
