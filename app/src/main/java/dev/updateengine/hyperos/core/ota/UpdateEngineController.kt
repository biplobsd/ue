package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class UpdateEngineController(
    private val rootShell: RootShell,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    sealed interface EngineEvent {
        data class LogLine(val line: String) : EngineEvent
        data class Progress(val statusName: String, val progressFraction: Float) : EngineEvent
        data class Completed(val isSuccess: Boolean, val message: String) : EngineEvent
    }

    suspend fun resetStatus(): Boolean = withContext(dispatchers.io) {
        val res = rootShell.exec("update_engine_client --cancel; update_engine_client --reset_status", timeoutMs = 10_000)
        res.isSuccess
    }

    /**
     * Emits the current status once, or null when the status could not be read.
     * `--follow` immediately prints `onStatusUpdate(...)` for the current daemon state.
     */
    suspend fun probeCurrentStatus(): Pair<String, Float>? = withContext(dispatchers.io) {
        val res = rootShell.exec("timeout 6 update_engine_client --follow 2>&1", timeoutMs = 12_000)
        (res.stdout + "\n" + res.stderr).lines().asReversed().firstNotNullOfOrNull { line ->
            EngineLogParser.parseStatusLine(line)?.let { it.statusName to it.progress }
        }
    }

    /**
     * Re-attachment stream: follows update_engine until a final state is reached without
     * starting, cancelling or resetting anything. Used after an app restart mid-flash.
     */
    fun followStatus(): Flow<EngineEvent> = flow {
        rootShell.execStreaming("update_engine_client --follow").collect { line ->
            emit(EngineEvent.LogLine(line))

            val status = EngineLogParser.parseStatusLine(line)
            if (status != null) {
                emit(EngineEvent.Progress(status.statusName, status.progress))
                if (status.statusName == "UPDATED_NEED_REBOOT") {
                    emit(EngineEvent.Completed(true, "Update applied successfully. System waiting for reboot."))
                }
            }

            val completion = EngineLogParser.parseCompletionLine(line)
            if (completion != null) {
                emit(EngineEvent.Completed(completion.isSuccess, completion.userExplanation))
            }
        }
    }.flowOn(dispatchers.io)

    fun applyPayload(): Flow<EngineEvent> = flow {
        // 1. Reset any stale state
        rootShell.exec("update_engine_client --cancel; update_engine_client --reset_status", timeoutMs = 10_000)

        // 2. Write runner script to /data/ota_package/apply.sh.
        //    This is byte-for-byte the script the manual flow was proved with, so the properties are
        //    read once into H and passed through it. `${'$'}` is how a literal shell `$` is written
        //    inside a Kotlin raw string.
        val scriptContent = """
            #!/system/bin/sh
            H="${'$'}(cat /data/ota_package/payload_properties.txt)"
            update_engine_client --payload=file:///data/ota_package/payload.bin --update --follow --headers="${'$'}H"
        """.trimIndent()

        rootShell.exec("cat << 'EOF' > /data/ota_package/apply.sh\n$scriptContent\nEOF\nchmod 755 /data/ota_package/apply.sh", timeoutMs = 5000)

        // 3. Stream execution of apply.sh
        var completedEmitted = false
        rootShell.execStreaming("/data/ota_package/apply.sh").collect { line ->
            emit(EngineEvent.LogLine(line))

            // Parse progress updates
            val status = EngineLogParser.parseStatusLine(line)
            if (status != null) {
                emit(EngineEvent.Progress(status.statusName, status.progress))
                if (status.statusName == "UPDATED_NEED_REBOOT") {
                    emit(EngineEvent.Completed(true, "Update applied successfully. System waiting for reboot."))
                    completedEmitted = true
                }
            }

            // Parse completion updates
            val completion = EngineLogParser.parseCompletionLine(line)
            if (completion != null) {
                emit(EngineEvent.Completed(completion.isSuccess, completion.userExplanation))
                completedEmitted = true
            }
        }

        if (!completedEmitted) {
            // The stream ended without a parsed final state. Ask the daemon for its real status
            // instead of trusting persist.sys.ota.status, which Xiaomi services clear.
            val lastStatus = probeCurrentStatus()?.first.orEmpty()
            if (lastStatus == "UPDATED_NEED_REBOOT") {
                emit(EngineEvent.Completed(true, "Update finished successfully (UPDATED_NEED_REBOOT)."))
            } else {
                emit(
                    EngineEvent.Completed(
                        false,
                        "update_engine_client finished without confirming UPDATED_NEED_REBOOT (last status: ${lastStatus.ifBlank { "unknown" }})."
                    )
                )
            }
        }
    }.flowOn(dispatchers.io)
}
