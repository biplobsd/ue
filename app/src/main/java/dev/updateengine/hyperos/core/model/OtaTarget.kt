package dev.updateengine.hyperos.core.model

import kotlinx.serialization.Serializable

@Serializable
data class OtaTarget(
    val device: String,                 // e.g. "zorn"
    val branch: String,                 // e.g. "China Beta"
    val osVersion: String,              // e.g. "OS4.0.0.8.XOKCNXM"
    val androidVersion: String = "",    // e.g. "17.0"
    val releaseDate: String = "",       // e.g. "2026-09-17"
    val recoveryFilename: String,       // e.g. "zorn-ota_full-OS4.0.0.8.XOKCNXM-user-17.0-c5b483f634.zip"
    val downloadUrls: List<String> = emptyList(), // primary + mirror URLs
    val fileHash: String = "",          // SHA-256 of payload.bin (base64 or hex)
    val fileSize: Long = 0L,            // payload.bin size in bytes
    val metadataHash: String = "",      // SHA-256 of manifest header (base64 or hex)
    val metadataSize: Long = 0L,        // manifest size in bytes
    val postBuildIncremental: String = "",
    val postTimestamp: Long = 0L,
    val postSdkLevel: Int = 0,
    /**
     * Real path of a locally picked package. When set, the engine reads that file in place instead of
     * downloading or copying it, and it is never deleted by the auto-purge because it belongs to the
     * user.
     */
    val localZipPath: String = ""
)
