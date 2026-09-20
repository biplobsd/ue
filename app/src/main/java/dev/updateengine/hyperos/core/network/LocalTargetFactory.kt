package dev.updateengine.hyperos.core.network

import dev.updateengine.hyperos.core.common.RomCompatibility
import dev.updateengine.hyperos.core.model.OtaTarget

/**
 * Builds an [OtaTarget] from a locally imported recovery package so a user-supplied zip travels
 * the exact same pipeline as a catalogue entry: resign check, codename check, anti-downgrade and
 * hash verification all read from this target.
 */
object LocalTargetFactory {

    private val ANDROID_VERSION = Regex("-user-([0-9]+(?:\\.[0-9]+)*)-")
    private val BUILD_TAG = Regex("((?:OS|V)[0-9][^-]*)-user-")

    fun create(
        fileName: String,
        inspected: RemotePackageInspector.InspectionResult,
        localZipPath: String = ""
    ): OtaTarget {
        val metadataTag = inspected.postBuildIncremental.trim()
        val fileNameTag = BUILD_TAG.find(fileName)?.groupValues?.get(1)

        return OtaTarget(
            device = inspected.preDevice,
            branch = "Local package",
            osVersion = resolveDisplayTag(metadataTag, fileNameTag, fileName),
            androidVersion = ANDROID_VERSION.find(fileName)?.groupValues?.get(1) ?: "",
            recoveryFilename = fileName,
            fileHash = inspected.fileHash,
            fileSize = inspected.fileSize,
            metadataHash = inspected.metadataHash,
            metadataSize = inspected.metadataSize,
            postBuildIncremental = metadataTag.ifBlank { resolveDisplayTag(metadataTag, fileNameTag, fileName) },
            postTimestamp = inspected.postTimestamp,
            postSdkLevel = inspected.postSdkLevel,
            localZipPath = localZipPath
        )
    }

    /**
     * `post-build-incremental` is the authoritative build id for post-boot verification, but on
     * Xiaomi packages it is the AOSP build id (e.g. `17OS4.0.260916.164405901.QCPECN.S`) which does
     * not carry the regional resign. The regional resign drives a hard blocker, so the display tag
     * falls back to the build tag embedded in the file name when it is the only one that resolves.
     */
    private fun resolveDisplayTag(metadataTag: String, fileNameTag: String?, fileName: String): String {
        if (metadataTag.isNotBlank() && RomCompatibility.region(metadataTag) != null) return metadataTag
        if (fileNameTag != null && RomCompatibility.region(fileNameTag) != null) return fileNameTag
        if (metadataTag.isNotBlank()) return metadataTag
        if (fileNameTag != null) return fileNameTag
        return fileName.substringBeforeLast('.')
    }
}
