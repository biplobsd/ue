package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.root.RootProvider
import kotlinx.coroutines.withContext

class RootPatchKeeper(
    private val rootProvider: RootProvider,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    data class PatchResult(
        val isSuccess: Boolean,
        val stockBackupPath: String,
        val targetPartition: String,
        val targetSlot: String = "",
        val errorMessage: String? = null,
        val warning: String? = null
    )

    /**
     * Patches the inactive boot partition and proves the write.
     *
     * The write is proven primarily by the pre/post SHA-256 of the inactive partition itself (taken
     * inside [RootProvider.patchInactiveBoot]), and secondarily by comparing the partition against the
     * stock backup ksud produced. The backup comparison is advisory: ksud does not produce a backup at
     * all when the target image already carried KernelSU, and treating a missing backup as a failure
     * used to wedge the pipeline in a state where "Retry Patch" could never succeed.
     *
     * [onVerifyStep] is invoked between the ksud flash and the backup comparison so the UI can report
     * the `VERIFYING_PATCH` phase while the verification is running.
     */
    suspend fun patchAndVerify(onVerifyStep: (() -> Unit)? = null): PatchResult = withContext(dispatchers.io) {
        val rootStatus = rootProvider.probe()
        if (!rootStatus.isRootGranted) {
            return@withContext PatchResult(
                isSuccess = false,
                stockBackupPath = "",
                targetPartition = "",
                errorMessage = "Cannot patch inactive slot: Root access is not available."
            )
        }

        val partition = rootStatus.defaultPartition
        val inactiveSlot = rootStatus.inactiveSlot
        if (inactiveSlot.isBlank()) {
            return@withContext PatchResult(
                isSuccess = false,
                stockBackupPath = "",
                targetPartition = partition,
                errorMessage = "The inactive slot could not be determined (ksud boot-info slot-suffix returned nothing), " +
                    "so the patched partition cannot be verified. Nothing was written."
            )
        }

        val fullTargetPart = "$partition$inactiveSlot"

        // 1. Run patch (pre/post partition hashes are taken inside the provider).
        val patchRes = rootProvider.patchInactiveBoot(fullTargetPart)
        if (patchRes.isFailure) {
            return@withContext PatchResult(
                isSuccess = false,
                stockBackupPath = "",
                targetPartition = fullTargetPart,
                errorMessage = "ksud boot-patch failed: ${patchRes.exceptionOrNull()?.message}"
            )
        }

        val outcome = patchRes.getOrThrow()
        val warnings = mutableListOf<String>()

        // 2. Cross-check the partition ksud reported against the slot this engine computed. The hash
        //    comparison above already proves that the expected partition changed, so a disagreement
        //    here is reported instead of aborting: the evidence that matters is that the partition
        //    update_engine wrote into is the one that now carries a different boot image.
        val patchedName = outcome.bootDevice.substringAfterLast('/')
        if (patchedName.isNotBlank() && !patchedName.equals(fullTargetPart, ignoreCase = true)) {
            warnings.add(
                "ksud reported '$patchedName' while the inactive partition for this device is " +
                    "'$fullTargetPart'; the write was verified against '$fullTargetPart'."
            )
        } else if (patchedName.isBlank()) {
            warnings.add("ksud did not report which partition it patched; verification relied on hashing $fullTargetPart.")
        }

        if (outcome.alreadyPatched) {
            warnings.add(
                "The inactive slot already carried a KernelSU-patched image (verified by the KernelSU " +
                    "ramdisk entry), so ksud wrote nothing new and produced no backup. Root is already " +
                    "in place on $fullTargetPart; the stock image named in the rescue guide is a " +
                    "best-effort match from /data/adb/ksu and may belong to the other slot."
            )
        }

        // 3. Cryptographic verification. Primary evidence: the partition content changed.
        onVerifyStep?.invoke()
        val changed = outcome.prePatchHash.isNotBlank() &&
            outcome.postPatchHash.isNotBlank() &&
            outcome.prePatchHash != outcome.postPatchHash
        if (!changed && !outcome.alreadyPatched) {
            return@withContext PatchResult(
                isSuccess = false,
                stockBackupPath = outcome.stockBackupPath,
                targetPartition = fullTargetPart,
                targetSlot = inactiveSlot,
                errorMessage = "Post-patch verification failed: the SHA-256 of $fullTargetPart did not " +
                    "change, so root was not written into the inactive slot.",
                warning = warnings.joinToString(" ").ifBlank { null }
            )
        }

        // 4. Secondary evidence: the partition must no longer match the stock backup. Advisory only,
        //    because the partition hash above already proves the write.
        if (outcome.stockBackupPath.isNotBlank() && !outcome.alreadyPatched) {
            val differsFromStock = rootProvider.verifyPatchedBoot(outcome.stockBackupPath, fullTargetPart)
            if (!differsFromStock) {
                warnings.add(
                    "The stock backup ${outcome.stockBackupPath} could not be compared against " +
                        "$fullTargetPart (unreadable, or still identical); the write was verified by the " +
                        "partition hash change instead."
                )
            }
        }

        PatchResult(
            isSuccess = true,
            stockBackupPath = outcome.stockBackupPath,
            targetPartition = fullTargetPart,
            targetSlot = inactiveSlot,
            warning = warnings.joinToString(" ").ifBlank { null }
        )
    }

    suspend fun revertSlotSwitch(): Boolean = rootProvider.revertSlotSwitch()
}
