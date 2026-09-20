package dev.updateengine.hyperos.core.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.common.LocalStoragePath
import dev.updateengine.hyperos.core.common.PathSafety
import dev.updateengine.hyperos.core.model.OtaTarget
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imports a recovery package the user picked from storage. The content resolver URI is copied into
 * the engine's staging directory (root can only read real files, not `content://` URIs), then its
 * metadata is inspected and turned into an [OtaTarget].
 */
class LocalPackageImporter(
    private val context: Context,
    private val inspector: RemotePackageInspector,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    suspend fun import(uri: Uri): Result<OtaTarget> = withContext(dispatchers.io) {
        try {
            val fileName = queryDisplayName(uri) ?: DEFAULT_FILE_NAME
            if (!fileName.endsWith(".zip", ignoreCase = true)) {
                return@withContext Result.failure(Exception("$fileName is not a .zip recovery package."))
            }
            if (!PathSafety.isSafeFileName(fileName)) {
                return@withContext Result.failure(
                    Exception("$fileName has characters that are not allowed in a recovery package name.")
                )
            }

            val sourceSize = querySize(uri)

            // Preferred path: use the file where it already is. Copying a 7–8 GB package next to
            // itself doubled the storage the preflight demands and pushed the device below the
            // documented 16–20 GB margin, so a resolvable path is read in place by the root shell.
            val realPath = LocalStoragePath.fromUriString(uri.toString())
            if (realPath != null) {
                val inPlace = inspector.inspectZipInPlace(realPath, sourceSize)
                if (inPlace.isSuccess) {
                    return@withContext Result.success(
                        LocalTargetFactory.create(
                            fileName = fileName,
                            inspected = inPlace.getOrThrow(),
                            localZipPath = realPath
                        )
                    )
                }
            }

            val stagingDir = context.getExternalFilesDir(STAGING_DIR)
                ?: return@withContext Result.failure(Exception("App staging storage is unavailable."))
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                return@withContext Result.failure(Exception("Could not create the staging directory."))
            }

            // A killed import can leave a multi-GB partial file behind; reclaim it before copying.
            // The downloader's `.part.meta` sidecar goes with it, so a later download can never try
            // to resume from a partial that this clean-up has just deleted.
            stagingDir.listFiles { file -> file.name.endsWith(PART_SUFFIX) || file.name.endsWith(META_SUFFIX) }
                ?.forEach { it.delete() }

            val staged = File(stagingDir, fileName)
            if (staged.length() == 0L || (sourceSize > 0 && staged.length() != sourceSize)) {
                copyIntoStaging(uri, staged)
            }

            val inspection = inspector.inspectLocalZip(staged).getOrElse { error ->
                staged.delete()
                return@withContext Result.failure(
                    Exception("Not a Full Recovery OTA package: ${error.message}")
                )
            }

            Result.success(LocalTargetFactory.create(fileName, inspection, localZipPath = staged.absolutePath))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun copyIntoStaging(uri: Uri, dest: File) {
        val partial = File(dest.parentFile, dest.name + ".part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Could not open the selected file.")
            input.use { source ->
                partial.outputStream().use { sink -> source.copyTo(sink) }
            }
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    } catch (_: Exception) {
        null
    }

    private fun querySize(uri: Uri): Long = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            } ?: 0L
    } catch (_: Exception) {
        0L
    }

    private companion object {
        const val STAGING_DIR = "ota"
        const val DEFAULT_FILE_NAME = "local-ota.zip"
        const val PART_SUFFIX = ".part"
        const val META_SUFFIX = ".part.meta"
    }
}
