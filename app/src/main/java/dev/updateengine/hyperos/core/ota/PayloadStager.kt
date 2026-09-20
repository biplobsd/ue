package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.common.PathSafety
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.withContext
import java.io.File

class PayloadStager(
    private val rootShell: RootShell,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    /**
     * [metadataSize] is the package's own `METADATA_SIZE` and has no default: it differs per package
     * (287798 for OS4.0.0.9.XOKCNXM, 287799 for OS4.0.0.8.XOKCNXM), so a hard-coded fallback would
     * silently extract the wrong byte range for any package but the one it was taken from.
     */
    suspend fun stage(
        zipFile: File,
        metadataSize: Long
    ): Result<Unit> = withContext(dispatchers.io) {
        try {
            // 1. Create target directory
            val mkdirRes = rootShell.exec("mkdir -p /data/ota_package", timeoutMs = 5000)
            if (!mkdirRes.isSuccess) {
                return@withContext Result.failure(Exception("Failed to create /data/ota_package: ${mkdirRes.stderr}"))
            }

            // 2. Extract payload.bin and payload_properties.txt.
            //    The staging files are removed first so a retry after an interrupted extraction can
            //    never race a previous, still running writer on the same paths.
            val cleanCmd = "rm -f /data/ota_package/payload.bin /data/ota_package/payload_properties.txt /data/ota_package/metadata"
            val cleanRes = rootShell.exec(cleanCmd, timeoutMs = 30_000)
            if (!cleanRes.isSuccess) {
                return@withContext Result.failure(Exception("Failed to clear /data/ota_package: ${cleanRes.stderr}"))
            }

            val unzipCmd = "unzip -o ${PathSafety.quote(zipFile.absolutePath)} payload.bin payload_properties.txt -d /data/ota_package"
            val unzipRes = rootShell.exec(unzipCmd, timeoutMs = 900_000) // 15 mins max
            if (!unzipRes.isSuccess) {
                return@withContext Result.failure(Exception("Failed to extract payload.bin from zip: ${unzipRes.stderr}"))
            }

            // 3. Extract the payload metadata used by --allocate and METADATA_HASH verification.
            //    update_engine defines METADATA_SIZE as (24-byte CrAU header + manifest), so METADATA_HASH
            //    covers the first METADATA_SIZE bytes. However, update_engine_client --allocate requires the
            //    full metadata file including the signature (24 + manifest_size + sig_size).
            //    We read the 4-byte big-endian metadata_signature_size from offset 20 of payload.bin, and extract
            //    (metadataSize + sig_size) bytes so --allocate accepts it without 'Invalid metadata size'.
            val extractMetaCmd = "sig_hex=\$(od -j 20 -N 4 -t x1 -An /data/ota_package/payload.bin 2>/dev/null | tr -d ' \\n\\r\\t'); " +
                "sig_size=0; [ -n \"\$sig_hex\" ] && sig_size=\$((16#\$sig_hex)); " +
                "total_size=\$(($metadataSize + sig_size)); " +
                "dd if=/data/ota_package/payload.bin of=/data/ota_package/metadata bs=1 count=\$total_size 2>/dev/null"
            val ddRes = rootShell.exec(extractMetaCmd, timeoutMs = 15_000)
            if (!ddRes.isSuccess) {
                return@withContext Result.failure(Exception("Failed to create /data/ota_package/metadata: ${ddRes.stderr}"))
            }

            val metaSizeRes = rootShell.exec("stat -c %s /data/ota_package/metadata", timeoutMs = 5000)
            val stagedMetaSize = metaSizeRes.stdout.trim().toLongOrNull() ?: 0L
            if (metadataSize > 0 && stagedMetaSize < metadataSize) {
                return@withContext Result.failure(
                    Exception("Staged metadata size mismatch: expected at least $metadataSize bytes, got $stagedMetaSize bytes.")
                )
            }

            // 4. Set permissions and SELinux label: update_engine requires ota_package_file label
            val permCmd = "chmod 644 /data/ota_package/*; chcon u:object_r:ota_package_file:s0 /data/ota_package/*"
            val permRes = rootShell.exec(permCmd, timeoutMs = 10_000)
            if (!permRes.isSuccess) {
                return@withContext Result.failure(Exception("Failed to set SELinux label on /data/ota_package: ${permRes.stderr}"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extracts only the metadata file from an already staged /data/ota_package/payload.bin.
     */
    suspend fun stageMetadataOnly(metadataSize: Long): Result<Unit> = withContext(dispatchers.io) {
        try {
            val extractMetaCmd = "sig_hex=\$(od -j 20 -N 4 -t x1 -An /data/ota_package/payload.bin 2>/dev/null | tr -d ' \\n\\r\\t'); " +
                "sig_size=0; [ -n \"\$sig_hex\" ] && sig_size=\$((16#\$sig_hex)); " +
                "total_size=\$(($metadataSize + sig_size)); " +
                "dd if=/data/ota_package/payload.bin of=/data/ota_package/metadata bs=1 count=\$total_size 2>/dev/null; " +
                "chmod 644 /data/ota_package/*; chcon u:object_r:ota_package_file:s0 /data/ota_package/*"
            val res = rootShell.exec(extractMetaCmd, timeoutMs = 15_000)
            if (!res.isSuccess) {
                return@withContext Result.failure(Exception("Failed to stage /data/ota_package/metadata: ${res.stderr}"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

