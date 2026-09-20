package dev.updateengine.hyperos.core.network

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.common.PathSafety
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class RemotePackageInspector(
    private val rootShell: RootShell? = null,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    data class InspectionResult(
        val isFullOta: Boolean,
        val preDevice: String,
        val postBuildIncremental: String,
        val preBuildIncremental: String?,
        val postSdkLevel: Int,
        val postTimestamp: Long,
        val fileHash: String,
        val fileSize: Long,
        val metadataHash: String,
        val metadataSize: Long
    )

    /** Reads the two metadata entries out of a zip the app process can open itself. */
    suspend fun inspectLocalZip(zipFile: File): Result<InspectionResult> = withContext(dispatchers.io) {
        try {
            ZipFile(zipFile).use { zip ->
                val metaEntry = zip.getEntry(METADATA_ENTRY)
                    ?: return@withContext Result.failure(Exception("Missing $METADATA_ENTRY in zip"))
                val metaContent = zip.getInputStream(metaEntry).bufferedReader().use { it.readText() }

                val propsEntry = zip.getEntry(PROPS_ENTRY)
                    ?: return@withContext Result.failure(Exception("Missing $PROPS_ENTRY in zip"))
                val propsContent = zip.getInputStream(propsEntry).bufferedReader().use { it.readText() }

                Result.success(parseInspection(metaContent, propsContent))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads the same two entries through the root shell, so a zip the app cannot open by path (scoped
     * storage) can still be used in place instead of being copied into the staging directory.
     */
    suspend fun inspectZipInPlace(path: String, expectedSize: Long = 0L): Result<InspectionResult> =
        withContext(dispatchers.io) {
            val shell = rootShell
                ?: return@withContext Result.failure(Exception("Root shell is not available for in-place inspection."))
            try {
                val quoted = PathSafety.quote(path)
                val statRes = shell.exec("stat -c %s $quoted 2>/dev/null", timeoutMs = 5000)
                if (!statRes.isSuccess) {
                    return@withContext Result.failure(Exception("The selected file could not be read by the root shell."))
                }
                val actualSize = statRes.stdout.trim().toLongOrNull() ?: 0L
                if (actualSize <= 0L) {
                    return@withContext Result.failure(Exception("The selected file is empty or not a regular file."))
                }
                if (expectedSize > 0L && actualSize != expectedSize) {
                    return@withContext Result.failure(
                        Exception("The selected file changed on disk ($actualSize bytes, expected $expectedSize).")
                    )
                }

                val metaRes = shell.exec("unzip -p $quoted $METADATA_ENTRY 2>/dev/null", timeoutMs = 120_000)
                if (!metaRes.isSuccess || metaRes.stdout.isBlank()) {
                    return@withContext Result.failure(Exception("Missing $METADATA_ENTRY in the selected zip."))
                }

                val propsRes = shell.exec("unzip -p $quoted $PROPS_ENTRY 2>/dev/null", timeoutMs = 120_000)
                if (!propsRes.isSuccess || propsRes.stdout.isBlank()) {
                    return@withContext Result.failure(Exception("Missing $PROPS_ENTRY in the selected zip."))
                }

                Result.success(parseInspection(metaRes.stdout, propsRes.stdout))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    companion object {
        const val METADATA_ENTRY = "META-INF/com/android/metadata"
        const val PROPS_ENTRY = "payload_properties.txt"

        /**
         * Turns the two metadata blobs into an [InspectionResult]. Pure so the full-OTA / downgrade /
         * hash logic can be unit tested without a zip.
         */
        fun parseInspection(metaContent: String, propsContent: String): InspectionResult {
            val metaMap = parseProperties(metaContent)
            val propsMap = parseProperties(propsContent)

            val preIncremental = metaMap["pre-build-incremental"]
            val isFullOta = preIncremental.isNullOrBlank()

            return InspectionResult(
                isFullOta = isFullOta,
                preDevice = metaMap["pre-device"] ?: "",
                postBuildIncremental = metaMap["post-build-incremental"] ?: "",
                preBuildIncremental = preIncremental,
                postSdkLevel = metaMap["post-sdk-level"]?.toIntOrNull() ?: 0,
                postTimestamp = metaMap["post-timestamp"]?.toLongOrNull() ?: 0L,
                fileHash = propsMap["FILE_HASH"] ?: "",
                fileSize = propsMap["FILE_SIZE"]?.toLongOrNull() ?: 0L,
                metadataHash = propsMap["METADATA_HASH"] ?: "",
                metadataSize = propsMap["METADATA_SIZE"]?.toLongOrNull() ?: 0L
            )
        }

        fun parseProperties(content: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val key = trimmed.substringBefore('=').trim()
                    val value = trimmed.substringAfter('=').trim()
                    map[key] = value
                }
            }
            return map
        }
    }
}
