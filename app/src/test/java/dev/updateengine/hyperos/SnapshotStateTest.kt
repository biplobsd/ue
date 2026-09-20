package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.ota.SnapshotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotStateTest {

    @Test
    fun testRealDumpOutputIsParsed() {
        // Captured from `snapshotctl dump` on zorn (HyperOS 4, userspace snapshots).
        val dump = """
            [libfstab] Using Android DT directory /proc/device-tree/firmware/android/
            Update state: none
            Using snapuserd: 0
            Using userspace snapshots: 0
            Current slot: _b
        """.trimIndent()

        val status = SnapshotState.parse(dump)

        assertEquals(SnapshotState.State.NONE, status.state)
        assertTrue(status.isDetermined)
        assertFalse(status.blocksNewUpdate)
    }

    @Test
    fun testMergingAndSnapshottedBlockANewUpdate() {
        assertTrue(SnapshotState.parse("Update state: Merging").blocksNewUpdate)
        assertTrue(SnapshotState.parse("Update state: Snapshotted").blocksNewUpdate)
        assertTrue(SnapshotState.parse("Update state: MergeFailed").blocksNewUpdate)
    }

    @Test
    fun testLeftoverAllocationDoesNotBlock() {
        // Chain A's own --allocate leaves the daemon in Initiated; re-running must not self-block.
        val status = SnapshotState.parse("Update state: Initiated")

        assertEquals(SnapshotState.State.INITIATED, status.state)
        assertTrue(status.isDetermined)
        assertFalse(status.blocksNewUpdate)
    }

    @Test
    fun testUnreadableDumpIsUnknownInsteadOfIdle() {
        val missing = SnapshotState.parse("sh: snapshotctl: inaccessible or not found")
        assertEquals(SnapshotState.State.UNKNOWN, missing.state)
        assertFalse(missing.isDetermined)
        assertFalse(missing.blocksNewUpdate)

        val empty = SnapshotState.parse("")
        assertEquals(SnapshotState.State.UNKNOWN, empty.state)
        assertFalse(empty.isDetermined)
    }

    @Test
    fun testUnrecognisedStateValueIsUnknown() {
        val status = SnapshotState.parse("Update state: SomethingNew")

        assertEquals(SnapshotState.State.UNKNOWN, status.state)
        assertEquals("SomethingNew", status.raw)
    }
}
