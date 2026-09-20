package dev.updateengine.hyperos.core.ota

/**
 * `snapshotctl dump` reports the userspace snapshot daemon's view of the device:
 *
 * ```text
 * Update state: none
 * ```
 *
 * The value decides whether a new OTA may be started: a merge that is still running (or that failed)
 * must finish first, while an allocation left behind by a previous space pre-check is harmless and is
 * released when the next update resets the engine.
 *
 * Anything that cannot be read is [State.UNKNOWN] rather than "idle": silently treating an unreadable
 * dump as a clean device is how a merge that is still running gets interrupted.
 */
object SnapshotState {

    enum class State {
        NONE,
        CANCELLED,
        INITIATED,
        UNVERIFIED,
        SNAPSHOTTED,
        MERGING,
        MERGE_FAILED,
        UNKNOWN
    }

    data class SnapshotStatus(
        val state: State,
        val raw: String
    ) {
        /** True when starting a new update would race an unfinished previous one. */
        val blocksNewUpdate: Boolean
            get() = state == State.MERGING || state == State.SNAPSHOTTED || state == State.MERGE_FAILED

        /** True when the dump was actually understood. */
        val isDetermined: Boolean get() = state != State.UNKNOWN

        val label: String
            get() = when (state) {
                State.NONE -> "none"
                State.CANCELLED -> "Cancelled"
                State.INITIATED -> "Initiated"
                State.UNVERIFIED -> "Unverified"
                State.SNAPSHOTTED -> "Snapshotted"
                State.MERGING -> "Merging"
                State.MERGE_FAILED -> "MergeFailed"
                State.UNKNOWN -> "unreadable"
            }
    }

    private val STATE_LINE = Regex("""Update state:\s*(\S+)""", RegexOption.IGNORE_CASE)

    fun parse(output: String): SnapshotStatus {
        val match = STATE_LINE.find(output)
            ?: return SnapshotStatus(State.UNKNOWN, output.trim().takeLast(200))

        val value = match.groupValues[1].trim().trimEnd('.', ',')
        val state = when (value.lowercase()) {
            "none" -> State.NONE
            "cancelled", "canceled" -> State.CANCELLED
            "initiated" -> State.INITIATED
            "unverified" -> State.UNVERIFIED
            "snapshotted" -> State.SNAPSHOTTED
            "merging" -> State.MERGING
            "mergefailed", "merge_failed" -> State.MERGE_FAILED
            else -> State.UNKNOWN
        }
        return SnapshotStatus(state, value)
    }
}
