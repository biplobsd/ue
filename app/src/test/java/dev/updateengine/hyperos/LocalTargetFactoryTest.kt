package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.common.RomCompatibility
import dev.updateengine.hyperos.core.network.LocalTargetFactory
import dev.updateengine.hyperos.core.network.RemotePackageInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTargetFactoryTest {

    private fun inspection(
        build: String = "OS4.0.0.9.XOKCNXM",
        preDevice: String = "zorn"
    ) = RemotePackageInspector.InspectionResult(
        isFullOta = true,
        preDevice = preDevice,
        postBuildIncremental = build,
        preBuildIncremental = null,
        postSdkLevel = 36,
        postTimestamp = 1789000000L,
        fileHash = "file-hash",
        fileSize = 7730411893L,
        metadataHash = "metadata-hash",
        metadataSize = 287799L
    )

    @Test
    fun testLocalPackageBecomesAComparableTarget() {
        val fileName = "zorn-ota_full-OS4.0.0.9.XOKCNXM-user-17.0-c5b483f634.zip"

        val target = LocalTargetFactory.create(fileName, inspection())

        assertEquals("OS4.0.0.9.XOKCNXM", target.osVersion)
        assertEquals("zorn", target.device)
        assertEquals("17.0", target.androidVersion)
        assertEquals(fileName, target.recoveryFilename)
        assertEquals(7730411893L, target.fileSize)
        assertEquals("file-hash", target.fileHash)
        assertEquals(287799L, target.metadataSize)
        assertEquals(36, target.postSdkLevel)
        assertEquals(1789000000L, target.postTimestamp)
        assertTrue(target.downloadUrls.isEmpty())
    }

    @Test
    fun testResignIsReadableFromImportedBuildTag() {
        val target = LocalTargetFactory.create(
            "zorn-ota_full-OS3.0.302.0.WOKMIXM-user-16.0-abc.zip",
            inspection(build = "OS3.0.302.0.WOKMIXM")
        )

        assertEquals("MI", RomCompatibility.region(target.osVersion))
    }

    @Test
    fun testBuildTagIsRecoveredFromFileName() {
        val target = LocalTargetFactory.create(
            "zorn-ota_full-OS4.0.0.9.XOKCNXM-user-17.0-c5b483f634.zip",
            inspection(build = "")
        )

        assertEquals("OS4.0.0.9.XOKCNXM", target.osVersion)
        assertEquals("OS4.0.0.9.XOKCNXM", target.postBuildIncremental)
        assertEquals("CN", RomCompatibility.region(target.osVersion))
    }

    @Test
    fun testUnparseableFileNameFallsBackToName() {
        val target = LocalTargetFactory.create("recovery.zip", inspection(build = ""))

        assertEquals("recovery", target.osVersion)
    }

    @Test
    fun testMissingAndroidVersionIsTolerated() {
        val target = LocalTargetFactory.create("recovery.zip", inspection())

        assertEquals("", target.androidVersion)
        assertEquals("CN", RomCompatibility.region(target.osVersion))
    }

    @Test
    fun testXiaomiAospIncrementalStillResolvesTheResign() {
        // Real zorn package: the metadata carries the AOSP build id, which has no regional resign,
        // so the resign has to come from the file name or the cross-region blocker is bypassed.
        val target = LocalTargetFactory.create(
            "zorn-ota_full-OS4.0.0.8.XOKCNXM-user-17.0-c5b483f634.zip",
            inspection(build = "17OS4.0.260916.164405901.QCPECN.S")
        )

        assertEquals("OS4.0.0.8.XOKCNXM", target.osVersion)
        assertEquals("CN", RomCompatibility.region(target.osVersion))
        assertEquals("17OS4.0.260916.164405901.QCPECN.S", target.postBuildIncremental)
    }
}
