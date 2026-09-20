package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.model.KernelModule
import dev.updateengine.hyperos.core.model.RootKind
import dev.updateengine.hyperos.core.model.RootStatus
import dev.updateengine.hyperos.core.ota.RootPatchKeeper
import dev.updateengine.hyperos.core.root.BootPatchOutcome
import dev.updateengine.hyperos.core.root.ModuleDisableReport
import dev.updateengine.hyperos.core.root.ModuleRestoreReport
import dev.updateengine.hyperos.core.root.RootProvider
import dev.updateengine.hyperos.core.root.RootResult
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The root-patch gate decides whether Gate 2 may unlock, so its three outcomes are asserted explicitly:
 * a verified write, the one legitimate no-write case (the slot already carried KernelSU) and an
 * unverifiable run that must block the reboot.
 */
class RootPatchKeeperTest {

    private companion object {
        const val NEXT_SLOT = "_b"
        const val PRE_HASH = "1111111111111111111111111111111111111111111111111111111111111111"
        const val POST_HASH = "2222222222222222222222222222222222222222222222222222222222222222"
        const val BACKUP = "/data/adb/ksu/ksun_backup_a568c26cd55290222511a47b1529b69909a0bb1b"
    }

    private class FakeShell : RootShell {
        override suspend fun exec(cmd: String, timeoutMs: Long): RootResult = RootResult(0, "", "")
        override fun execStreaming(cmd: String): Flow<String> = flowOf()
        override suspend fun isAvailable(): Boolean = true
    }

    private class FakeRootProvider(
        private val status: RootStatus,
        private val patch: Result<BootPatchOutcome>,
        private val backupDiffersFromTarget: Boolean = true
    ) : RootProvider {
        override val kind: RootKind = RootKind.KERNELSU_NEXT
        override val shell: RootShell = FakeShell()

        override suspend fun probe(): RootStatus = status
        override suspend fun patchInactiveBoot(targetPartition: String): Result<BootPatchOutcome> = patch
        override suspend fun verifyPatchedBoot(stockBackupPath: String, targetPartition: String): Boolean =
            backupDiffersFromTarget

        override suspend fun revertSlotSwitch(): Boolean = true
        override suspend fun disableAllModules(): ModuleDisableReport = ModuleDisableReport(
            previouslyEnabled = emptyList(),
            disabled = emptyList(),
            failed = emptyList(),
            pendingUpdatesParked = false,
            globalFlagSet = true,
            ksudUsed = true
        )

        override suspend fun restoreModules(moduleIds: List<String>): ModuleRestoreReport =
            ModuleRestoreReport(
                restored = moduleIds,
                failed = emptyList(),
                pendingUpdatesUnparked = false,
                globalFlagCleared = true
            )

        override suspend fun clearGlobalSafeMode(): Boolean = true
        override suspend fun listModules(): List<KernelModule> = emptyList()
        override suspend fun reboot(): Boolean = true
    }

    private fun status(inactiveSlot: String = NEXT_SLOT) = RootStatus(
        kind = RootKind.KERNELSU_NEXT,
        isRootGranted = true,
        ksudVersion = "ksud 3.3.0",
        defaultPartition = "init_boot",
        currentSlot = "_a",
        inactiveSlot = inactiveSlot,
        details = "test"
    )

    private fun keeper(
        patch: Result<BootPatchOutcome>,
        backupDiffersFromTarget: Boolean = true,
        inactiveSlot: String = NEXT_SLOT
    ) = RootPatchKeeper(
        FakeRootProvider(status(inactiveSlot), patch, backupDiffersFromTarget)
    )

    @Test
    fun testPartitionHashChangeIsAccepted() = runBlocking {
        val result = keeper(
            Result.success(
                BootPatchOutcome(
                    stockBackupPath = BACKUP,
                    bootDevice = "/dev/block/by-name/init_boot$NEXT_SLOT",
                    prePatchHash = PRE_HASH,
                    postPatchHash = POST_HASH
                )
            )
        ).patchAndVerify()

        assertTrue(result.isSuccess)
        assertEquals("init_boot$NEXT_SLOT", result.targetPartition)
        assertEquals(NEXT_SLOT, result.targetSlot)
        assertEquals(BACKUP, result.stockBackupPath)
        assertEquals(null, result.warning)
    }

    @Test
    fun testUnchangedPartitionBlocksTheReboot() = runBlocking {
        val result = keeper(
            Result.success(
                BootPatchOutcome(
                    stockBackupPath = BACKUP,
                    bootDevice = "/dev/block/by-name/init_boot$NEXT_SLOT",
                    prePatchHash = PRE_HASH,
                    postPatchHash = PRE_HASH
                )
            )
        ).patchAndVerify()

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("did not"))
    }

    @Test
    fun testAlreadyPatchedSlotIsAcceptedWithAWarning() = runBlocking {
        val result = keeper(
            Result.success(
                BootPatchOutcome(
                    stockBackupPath = BACKUP,
                    bootDevice = "/dev/block/by-name/init_boot$NEXT_SLOT",
                    prePatchHash = PRE_HASH,
                    postPatchHash = PRE_HASH,
                    alreadyPatched = true
                )
            )
        ).patchAndVerify()

        assertTrue(result.isSuccess)
        assertTrue(result.warning!!.contains("already carried a KernelSU-patched image"))
    }

    @Test
    fun testMissingBackupParsingStillVerifiesTheWrite() = runBlocking {
        // ksud prints no backup path when the target image already carries KernelSU; the partition
        // hash change is what proves the write, so the run must not be reported as a failure.
        val result = keeper(
            Result.success(
                BootPatchOutcome(
                    stockBackupPath = "",
                    bootDevice = "/dev/block/by-name/init_boot$NEXT_SLOT",
                    prePatchHash = PRE_HASH,
                    postPatchHash = POST_HASH
                )
            )
        ).patchAndVerify()

        assertTrue(result.isSuccess)
        assertEquals("", result.stockBackupPath)
    }

    @Test
    fun testBootDeviceMismatchIsReportedButDoesNotBlock() = runBlocking {
        val result = keeper(
            Result.success(
                BootPatchOutcome(
                    stockBackupPath = BACKUP,
                    bootDevice = "/dev/block/by-name/init_boot_a",
                    prePatchHash = PRE_HASH,
                    postPatchHash = POST_HASH
                )
            )
        ).patchAndVerify()

        assertTrue(result.isSuccess)
        assertTrue(result.warning!!.contains("init_boot_a"))
    }

    @Test
    fun testBackupComparisonFailureIsOnlyAWarning() = runBlocking {
        val result = keeper(
            patch = Result.success(
                BootPatchOutcome(
                    stockBackupPath = BACKUP,
                    bootDevice = "/dev/block/by-name/init_boot$NEXT_SLOT",
                    prePatchHash = PRE_HASH,
                    postPatchHash = POST_HASH
                )
            ),
            backupDiffersFromTarget = false
        ).patchAndVerify()

        assertTrue(result.isSuccess)
        assertTrue(result.warning!!.contains("partition hash change"))
    }

    @Test
    fun testKsudFailureBlocks() = runBlocking {
        val result = keeper(Result.failure(Exception("ksud boot-patch failed to finish"))).patchAndVerify()

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("ksud boot-patch failed"))
    }

    @Test
    fun testUnknownInactiveSlotFailsClosed() = runBlocking {
        val result = keeper(
            patch = Result.success(BootPatchOutcome(stockBackupPath = BACKUP, bootDevice = "")),
            inactiveSlot = ""
        ).patchAndVerify()

        assertFalse(result.isSuccess)
    }

    @Test
    fun testMissingRootFailsClosed() = runBlocking {
        val provider = object : RootProvider by FakeRootProvider(
            status().copy(isRootGranted = false),
            Result.success(BootPatchOutcome(stockBackupPath = "", bootDevice = ""))
        ) {}
        val result = RootPatchKeeper(provider).patchAndVerify()

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Root access is not available"))
    }
}
