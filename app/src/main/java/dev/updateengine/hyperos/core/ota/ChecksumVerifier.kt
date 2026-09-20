package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

class ChecksumVerifier(
    private val rootShell: RootShell,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    sealed interface VerificationResult {
        data class Success(val notes: List<String> = emptyList()) : VerificationResult
        data class Failure(val reason: String) : VerificationResult
    }

    /**
     * Result of `update_engine_client --allocate`.
     *
     * The client exits 0 even when the daemon reports that the payload does not fit: it only prints
     * `Insufficient space; required <N> bytes.` and returns EX_OK, because the binder call itself
     * succeeded. The exit code alone therefore proves nothing and the output has to be parsed.
     */
    sealed interface AllocationResult {
        data object Allocated : AllocationResult
        data class InsufficientSpace(val requiredBytes: Long) : AllocationResult
        data class Failed(val reason: String) : AllocationResult

        /** The installed update_engine_client does not implement --allocate. */
        data object Unsupported : AllocationResult
    }

    suspend fun verifyStagedPayload(
        expectedFileSize: Long,
        expectedFileHash: String,
        metadataSize: Long,
        expectedMetadataHash: String,
        downloadedZipFile: File? = null,
        autoPurgeZip: Boolean = true
    ): VerificationResult = withContext(dispatchers.io) {
        val notes = mutableListOf<String>()

        try {
            // 0. A package without hashes cannot be verified at all. Failing here is deliberate: the
            //    alternative is arming the pipeline on a package whose integrity was never checked.
            if (expectedFileSize <= 0L || expectedFileHash.isBlank() || expectedMetadataHash.isBlank() || metadataSize <= 0L) {
                return@withContext VerificationResult.Failure(
                    "The package does not declare FILE_SIZE/FILE_HASH/METADATA_SIZE/METADATA_HASH in " +
                        "payload_properties.txt (size=$expectedFileSize, fileHash=${expectedFileHash.isBlank()}, " +
                        "metadataSize=$metadataSize, metadataHash=${expectedMetadataHash.isBlank()}). " +
                        "Refusing to arm the update without verifiable hashes."
                )
            }

            // 1. Instant check: file size
            val sizeRes = rootShell.exec("stat -c %s /data/ota_package/payload.bin", timeoutMs = 5000)
            if (!sizeRes.isSuccess) {
                return@withContext VerificationResult.Failure("payload.bin not found in /data/ota_package: ${sizeRes.stderr}")
            }
            val actualSize = sizeRes.stdout.trim().toLongOrNull() ?: 0L
            if (actualSize != expectedFileSize) {
                return@withContext VerificationResult.Failure("Payload size mismatch: expected $expectedFileSize bytes, got $actualSize bytes.")
            }

            // 2. Fast check: METADATA_HASH.
            //    update_engine defines METADATA_SIZE as (24-byte CrAU header + manifest) and
            //    METADATA_HASH as the SHA-256 of the first METADATA_SIZE bytes of payload.bin.
            //    PayloadStager writes the full metadata (header + manifest + signature) to
            //    /data/ota_package/metadata so update_engine_client --allocate can validate it.
            //    We verify the file has at least metadataSize bytes, and hash the first metadataSize bytes.
            val metaSizeRes = rootShell.exec("stat -c %s /data/ota_package/metadata", timeoutMs = 5000)
            val actualMetaSize = metaSizeRes.stdout.trim().toLongOrNull() ?: 0L
            if (actualMetaSize < metadataSize) {
                return@withContext VerificationResult.Failure(
                    "Staged metadata size mismatch: expected at least $metadataSize bytes, got $actualMetaSize bytes."
                )
            }

            val shaCmd = if (actualMetaSize == metadataSize) {
                "sha256sum /data/ota_package/metadata"
            } else {
                "head -c $metadataSize /data/ota_package/metadata 2>/dev/null | sha256sum"
            }
            val manifestShaRes = rootShell.exec(shaCmd, timeoutMs = 30_000)
            if (!manifestShaRes.isSuccess) {
                return@withContext VerificationResult.Failure("Failed to calculate metadata hash: ${manifestShaRes.stderr}")
            }
            val actualMetadataSha = manifestShaRes.stdout.split("\\s+".toRegex()).firstOrNull()?.trim()?.lowercase() ?: ""
            val expectedMetadataHex = toHex(expectedMetadataHash)
            if (actualMetadataSha != expectedMetadataHex) {
                return@withContext VerificationResult.Failure("METADATA_HASH mismatch: expected $expectedMetadataHex, got $actualMetadataSha")
            }

            // 3. Full check: FILE_HASH of payload.bin. Reads the whole ~7.7 GB payload, so allow
            //    up to 15 minutes before declaring failure.
            val fileShaRes = rootShell.exec("sha256sum /data/ota_package/payload.bin", timeoutMs = 900_000)
            if (!fileShaRes.isSuccess) {
                return@withContext VerificationResult.Failure("Failed to calculate FILE_HASH: ${fileShaRes.stderr}")
            }
            val actualFileSha = fileShaRes.stdout.split("\\s+".toRegex()).firstOrNull()?.trim()?.lowercase() ?: ""
            val expectedFileHex = toHex(expectedFileHash)
            if (actualFileSha != expectedFileHex) {
                return@withContext VerificationResult.Failure("FILE_HASH mismatch: expected $expectedFileHex, got $actualFileSha")
            }

            // 4. Purge the engine's own downloaded .zip BEFORE the COW space check. The allocation
            //    below needs 8-12 GB of free space on top of the staged payload, and the 7.7 GB zip is
            //    the only disposable copy at this point. Purging it after the check would make the
            //    check fail on exactly the 16-20 GB devices the checklist asks the user to prepare.
            if (downloadedZipFile != null && downloadedZipFile.exists()) {
                if (autoPurgeZip) {
                    val freed = downloadedZipFile.length()
                    if (downloadedZipFile.delete()) {
                        notes.add("Purged downloaded package to free ${freed / (1024 * 1024 * 1024)} GB before snapshot allocation.")
                    } else {
                        notes.add("WARNING: could not delete the downloaded .zip; snapshot allocation may not fit.")
                    }
                } else {
                    notes.add(
                        "The package was kept (it is not the engine's own download, or auto-purge is off), " +
                            "so the snapshot allocation has to fit next to it."
                    )
                }
            }

            // 5. Virtual A/B COW space pre-allocation. This is the last non-destructive guard before
            //    update_engine is allowed to write into the inactive slot.
            val allocCmd = "H=\"\$(cat /data/ota_package/payload_properties.txt)\"; update_engine_client --allocate --metadata=/data/ota_package/metadata --headers=\"\$H\" 2>&1"
            val allocRes = rootShell.exec(allocCmd, timeoutMs = 120_000)
            when (val allocation = evaluateAllocation(allocRes.exitCode, allocRes.stdout)) {
                is AllocationResult.InsufficientSpace -> {
                    val requiredGb = allocation.requiredBytes / (1024.0 * 1024 * 1024)
                    return@withContext VerificationResult.Failure(
                        "Not enough free space for the Virtual A/B snapshot: update_engine needs " +
                            "${"%.1f".format(requiredGb)} GB more on /data. Free up storage and run the update again."
                    )
                }
                is AllocationResult.Failed -> {
                    return@withContext VerificationResult.Failure(
                        "Virtual A/B COW space pre-allocation failed: ${allocation.reason}"
                    )
                }
                AllocationResult.Allocated -> {
                    notes.add("Snapshot COW space pre-allocation confirmed by update_engine.")
                }
                AllocationResult.Unsupported -> {
                    // Fail closed: the plan makes a successful pre-allocation a blocker, because a
                    // package that does not fit the COW space corrupts the inactive slot mid-flash.
                    return@withContext VerificationResult.Failure(
                        "Could not confirm the Virtual A/B snapshot space: update_engine_client did not " +
                            "report a result for --allocate. Output: ${allocRes.stdout.trim().takeLast(300).ifBlank { "none" }}"
                    )
                }
            }

            VerificationResult.Success(notes)
        } catch (e: Exception) {
            VerificationResult.Failure("Verification exception: ${e.message}")
        }
    }

    companion object {
        private val INSUFFICIENT_SPACE = Regex("""Insufficient space; required (\d+) bytes""")

        /**
         * Interprets the output of `update_engine_client --allocate`. Exposed for tests because the
         * exit code is meaningless here: an insufficient-space result still exits 0.
         */
        fun evaluateAllocation(exitCode: Int, output: String): AllocationResult {
            INSUFFICIENT_SPACE.find(output)?.let { match ->
                return AllocationResult.InsufficientSpace(match.groupValues[1].toLongOrNull() ?: 0L)
            }

            if (output.contains("Successfully allocated space for payload", ignoreCase = true)) {
                return AllocationResult.Allocated
            }

            if (output.contains("unknown command line flag 'allocate'") ||
                output.contains("unknown command line flag \"allocate\"")
            ) {
                return AllocationResult.Unsupported
            }

            if (output.contains("Allocation failed", ignoreCase = true)) {
                return AllocationResult.Failed("update_engine reported that the allocation failed.")
            }

            if (exitCode != 0) {
                val detail = output.trim().takeLast(400).ifBlank { "no output" }
                return AllocationResult.Failed("update_engine_client --allocate exited with $exitCode: $detail")
            }

            return AllocationResult.Unsupported
        }

        /**
         * Converts a base64-encoded hash to a lowercase hex string, or returns the input if it is already hex.
         */
        fun toHex(hash: String): String {
            val trimmed = hash.trim()
            if (trimmed.length == 64 && trimmed.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                return trimmed.lowercase()
            }
            return try {
                val bytes = Base64.getMimeDecoder().decode(trimmed)
                bytes.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                trimmed.lowercase()
            }
        }
    }
}
