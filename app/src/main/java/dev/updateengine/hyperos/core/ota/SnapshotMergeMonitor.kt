package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class SnapshotMergeMonitor(
    private val rootShell: RootShell,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    /**
     * Evidence collected about the Virtual A/B snapshot merge.
     *
     * [mergeConfirmed] is the only signal Safe Mode may be unlocked on: `update_engine_client
     * --merge` returns success exclusively after `cleanupSuccessfulUpdate()` finished, i.e. when the
     * snapshots are really gone. [snapshotsIdle] is corroborating evidence from `snapshotctl dump`,
     * which on its own can be observed before a merge has even started.
     */
    data class MergeEvidence(
        val mergeConfirmed: Boolean,
        val snapshotsIdle: Boolean,
        val detail: String
    ) {
        val complete: Boolean get() = mergeConfirmed
    }

    /**
     * Waits for the background Virtual A/B snapshot merge to finish.
     *
     * The strong signal is the exit result of `update_engine_client --merge`; `snapshotctl dump` and
     * `persist.sys.abreuse.otastatus` are only used as corroboration, never as the sole reason to
     * declare the merge finished.
     */
    suspend fun awaitMergeComplete(
        timeoutMinutes: Long = 20,
        onProgressText: ((String) -> Unit)? = null
    ): MergeEvidence = withContext(dispatchers.io) {
        val timeoutMs = timeoutMinutes * 60 * 1000
        val startTime = System.currentTimeMillis()

        // Let post-boot services settle before the daemon is asked to wait for the merge.
        delay(15_000)

        val mergeProbe = async(dispatchers.io) {
            rootShell.exec("update_engine_client --merge 2>&1", timeoutMs = timeoutMs + 60_000)
        }

        var mergeConfirmed = false
        var mergeError: String? = null
        var consecutiveIdle = 0
        var lastReported = ""
        var detail = "Waiting for the background snapshot merge..."

        fun report(text: String) {
            if (text != lastReported) {
                lastReported = text
                onProgressText?.invoke(text)
            }
        }

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (mergeProbe.isCompleted) {
                val res = runCatching { mergeProbe.await() }.getOrNull()
                val output = (res?.stdout ?: "") + "\n" + (res?.stderr ?: "")
                val completion = output.lines().firstNotNullOfOrNull { EngineLogParser.parseCompletionLine(it) }

                if (completion?.isSuccess == true || output.contains("ErrorCode::kSuccess", ignoreCase = true)) {
                    mergeConfirmed = true
                    detail = "Snapshot merge confirmed complete by update_engine."
                    report(detail)
                    break
                }

                mergeError = completion?.userExplanation
                    ?: "update_engine_client --merge exited without confirming success."
                report("Merge not confirmed by update_engine: $mergeError")
            }

            val dumpRes = rootShell.exec("snapshotctl dump 2>&1", timeoutMs = 10_000)
            val dumpOutput = dumpRes.stdout

            if (dumpOutput.contains("Update state: none", ignoreCase = true)) {
                consecutiveIdle++
                report("Snapshot daemon reports no active update ($consecutiveIdle/2 consecutive checks).")
            } else {
                consecutiveIdle = 0
                when {
                    dumpOutput.contains("Update state: Merging", ignoreCase = true) ->
                        report("Virtual A/B snapshots merging in background...")
                    dumpOutput.contains("Update state: Snapshotted", ignoreCase = true) ->
                        report("Snapshots still in use; waiting for the merge to start...")
                    else ->
                        report("Waiting for the snapshot merge to be reported...")
                }
            }

            // The merge client already gave up and the daemon reports nothing left to merge.
            if (mergeError != null && consecutiveIdle >= 2) break

            delay(5000)
        }

        if (!mergeConfirmed) {
            mergeProbe.cancel()
        }

        MergeEvidence(
            mergeConfirmed = mergeConfirmed,
            snapshotsIdle = consecutiveIdle >= 2,
            detail = if (mergeConfirmed) {
                detail
            } else {
                buildString {
                    append(mergeError ?: "Timed out waiting for update_engine to confirm the merge.")
                    if (consecutiveIdle >= 2) {
                        append(" snapshotctl dump reports no active snapshot update.")
                    } else {
                        append(" snapshotctl dump still reports an active snapshot.")
                    }
                }
            }
        )
    }
}
