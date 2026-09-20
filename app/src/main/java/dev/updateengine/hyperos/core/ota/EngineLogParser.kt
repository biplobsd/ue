package dev.updateengine.hyperos.core.ota

object EngineLogParser {

    data class StatusUpdate(
        val statusName: String,
        val statusCode: Int,
        val progress: Float
    )

    data class CompletionUpdate(
        val errorName: String,
        val errorCode: Int,
        val isSuccess: Boolean,
        val userExplanation: String
    )

    private val statusRegex = Regex("""onStatusUpdate\(UPDATE_STATUS_([A-Z_]+)\s*\((-?\d+)\),\s*([0-9.]+)\)""")
    private val completeRegex = Regex("""onPayloadApplicationComplete\(ErrorCode::([a-zA-Z0-9_]+)\s*\((-?\d+)\)\)""")

    fun parseStatusLine(line: String): StatusUpdate? {
        val match = statusRegex.find(line) ?: return null
        val name = match.groupValues[1]
        val code = match.groupValues[2].toIntOrNull() ?: 0
        val progress = match.groupValues[3].toFloatOrNull() ?: 0f
        return StatusUpdate(name, code, progress)
    }

    fun parseCompletionLine(line: String): CompletionUpdate? {
        val match = completeRegex.find(line) ?: return null
        val name = match.groupValues[1]
        val code = match.groupValues[2].toIntOrNull() ?: -1
        // update_engine_client exits with EX_OK for kSuccess *and* kUpdatedButNotActive
        // (see aosp/update_engine_client_android.cc), so the latter must not be reported as a failure.
        val isSuccess = code == 0 || code == 52

        val explanation = when (code) {
            0 -> "Payload application succeeded."
            4 -> "kFilesystemCopierError: a partition could not be written (verify free space in /data)."
            6 -> "kPayloadMismatchedType: the package is a delta/incremental payload where a full payload was expected."
            7 -> "kInstallDeviceOpenError: the target partition could not be opened; the device or partition layout does not match the package."
            8 -> "kKernelDeviceOpenError: the target kernel partition could not be opened; the device or partition layout does not match the package."
            10 -> "kPayloadHashMismatchError: payload.bin does not match FILE_HASH (corrupted or modified package)."
            12 -> "kDownloadPayloadVerificationError: the payload signature could not be verified against the system OTA certificates."
            14 -> "kDownloadWriteError: writing the payload failed (insufficient free space in /data)."
            47 -> "kFilesystemVerifierError: a written partition failed verification."
            48 -> "kUserCanceled: the update was cancelled."
            51 -> "kPayloadTimestampError: the package is older than the installed build (downgrade rejected)."
            52 -> "kUpdatedButNotActive: the payload was applied but this slot is not the one that will boot."
            53 -> "kNoUpdate: update_engine found nothing to apply."
            54 -> "kRollbackNotPossible: the update cannot be rolled back at this point."
            60 -> "kNotEnoughSpace: not enough free space on /data for the snapshot COW blocks."
            61 -> "kDeviceCorrupted: the device reports a corrupted partition."
            65 -> "kUpdateProcessing: another update is already being processed."
            else -> "update_engine returned error code $code ($name)."
        }

        return CompletionUpdate(name, code, isSuccess, explanation)
    }
}
