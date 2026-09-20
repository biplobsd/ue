package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.ota.ChecksumVerifier
import dev.updateengine.hyperos.core.root.RootResult
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChecksumVerifierTest {

    private companion object {
        const val PAYLOAD_SIZE = 1024L
        const val METADATA_SIZE = 287799L
        const val FILE_HASH_HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val METADATA_HASH_HEX = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }

    private class FakeRootShell(
        private val allocateOutput: String,
        private val payloadSize: Long = PAYLOAD_SIZE,
        private val metadataSize: Long = METADATA_SIZE,
        private val fileHash: String = FILE_HASH_HEX,
        private val metadataHash: String = METADATA_HASH_HEX,
        private val onAllocate: (() -> Unit)? = null
    ) : RootShell {

        val commands = mutableListOf<String>()

        override suspend fun exec(cmd: String, timeoutMs: Long): RootResult {
            commands.add(cmd)
            return when {
                cmd.contains("stat -c %s /data/ota_package/payload.bin") -> RootResult(0, "$payloadSize", "")
                cmd.contains("stat -c %s /data/ota_package/metadata") -> RootResult(0, "$metadataSize", "")
                cmd.contains("sha256sum /data/ota_package/metadata") -> RootResult(0, "$metadataHash  /data/ota_package/metadata", "")
                cmd.contains("sha256sum /data/ota_package/payload.bin") -> RootResult(0, "$fileHash  /data/ota_package/payload.bin", "")
                cmd.contains("--allocate") -> {
                    onAllocate?.invoke()
                    RootResult(0, allocateOutput, "")
                }
                else -> RootResult(0, "", "")
            }
        }

        override fun execStreaming(cmd: String): Flow<String> = flowOf()

        override suspend fun isAvailable(): Boolean = true
    }

    private fun zipFile(): File {
        val file = File.createTempFile("payload", ".zip")
        file.writeBytes(ByteArray(16))
        file.deleteOnExit()
        return file
    }

    private fun verify(
        shell: RootShell,
        expectedFileSize: Long = PAYLOAD_SIZE,
        expectedFileHash: String = FILE_HASH_HEX,
        metadataSize: Long = METADATA_SIZE,
        expectedMetadataHash: String = METADATA_HASH_HEX,
        zip: File? = null,
        autoPurge: Boolean = true
    ) = runBlocking {
        ChecksumVerifier(shell).verifyStagedPayload(
            expectedFileSize = expectedFileSize,
            expectedFileHash = expectedFileHash,
            metadataSize = metadataSize,
            expectedMetadataHash = expectedMetadataHash,
            downloadedZipFile = zip,
            autoPurgeZip = autoPurge
        )
    }

    @Test
    fun testInsufficientSpaceIsRejectedEvenThoughTheExitCodeIsZero() {
        // update_engine_client exits 0 and only prints the shortfall, so the exit code alone would
        // make an unusable device look ready to flash.
        val result = verify(FakeRootShell("Insufficient space; required 5368709120 bytes.\n"))

        assertTrue(result is ChecksumVerifier.VerificationResult.Failure)
        val reason = (result as ChecksumVerifier.VerificationResult.Failure).reason
        assertTrue("Message should report the shortfall, was: $reason", reason.contains("5.0 GB"))
    }

    @Test
    fun testSuccessfulAllocationIsAcceptedAndReported() {
        val result = verify(FakeRootShell("Successfully allocated space for payload.\n"))

        assertTrue(result is ChecksumVerifier.VerificationResult.Success)
        val notes = (result as ChecksumVerifier.VerificationResult.Success).notes
        assertTrue(notes.any { it.contains("pre-allocation confirmed") })
    }

    @Test
    fun testZipIsPurgedBeforeTheSpaceCheck() {
        val zip = zipFile()
        var zipPresentDuringAllocation = true
        val shell = FakeRootShell("Successfully allocated space for payload.\n") {
            zipPresentDuringAllocation = zip.exists()
        }

        verify(shell, zip = zip, autoPurge = true)

        assertFalse("The zip must be deleted before the COW check runs", zipPresentDuringAllocation)
        assertFalse(zip.exists())
    }

    @Test
    fun testUserSuppliedPackageIsNeverDeleted() {
        // A package the user picked is read in place, so the verifier must be told not to purge it.
        val zip = zipFile()
        val result = verify(FakeRootShell("Successfully allocated space for payload.\n"), zip = zip, autoPurge = false)

        assertTrue(zip.exists())
        assertTrue(result is ChecksumVerifier.VerificationResult.Success)
        val notes = (result as ChecksumVerifier.VerificationResult.Success).notes
        assertTrue(notes.any { it.contains("was kept") })
    }

    @Test
    fun testUnconfirmedAllocationIsARejection() {
        // Fail closed: an --allocate that reports nothing means the COW space could not be confirmed,
        // and the plan makes a successful pre-allocation a blocker.
        val result = verify(FakeRootShell("ERROR: unknown command line flag 'allocate'\n"))

        assertTrue(result is ChecksumVerifier.VerificationResult.Failure)
        assertTrue(
            (result as ChecksumVerifier.VerificationResult.Failure).reason.contains("Could not confirm")
        )
    }

    @Test
    fun testPayloadSizeMismatchIsRejected() {
        val result = verify(FakeRootShell("Successfully allocated space for payload.\n"), expectedFileSize = 2048)

        assertTrue(result is ChecksumVerifier.VerificationResult.Failure)
        assertTrue((result as ChecksumVerifier.VerificationResult.Failure).reason.contains("size mismatch"))
    }

    @Test
    fun testMissingHashesAreRejectedInsteadOfSkipped() {
        val result = verify(FakeRootShell("Successfully allocated space for payload.\n"), expectedFileHash = "")

        assertTrue(result is ChecksumVerifier.VerificationResult.Failure)
        assertTrue(
            (result as ChecksumVerifier.VerificationResult.Failure).reason.contains("without verifiable hashes")
        )
    }

    @Test
    fun testMetadataHashMismatchIsRejected() {
        val result = verify(FakeRootShell("Successfully allocated space for payload.\n"), expectedMetadataHash = METADATA_HASH_HEX)

        assertTrue(result is ChecksumVerifier.VerificationResult.Success)

        val mismatch = verify(
            FakeRootShell("Successfully allocated space for payload.\n", metadataHash = "cc".repeat(32)),
            expectedMetadataHash = METADATA_HASH_HEX
        )
        assertTrue(mismatch is ChecksumVerifier.VerificationResult.Failure)
        assertTrue((mismatch as ChecksumVerifier.VerificationResult.Failure).reason.contains("METADATA_HASH mismatch"))
    }

    @Test
    fun testAllocationOutputParsing() {
        assertEquals(
            ChecksumVerifier.AllocationResult.InsufficientSpace(42L),
            ChecksumVerifier.evaluateAllocation(0, "Insufficient space; required 42 bytes.")
        )
        assertEquals(
            ChecksumVerifier.AllocationResult.Allocated,
            ChecksumVerifier.evaluateAllocation(0, "Successfully allocated space for payload.")
        )
        assertEquals(
            ChecksumVerifier.AllocationResult.Unsupported,
            ChecksumVerifier.evaluateAllocation(64, "ERROR: unknown command line flag 'allocate'")
        )
        assertTrue(ChecksumVerifier.evaluateAllocation(1, "some failure") is ChecksumVerifier.AllocationResult.Failed)
    }
}
