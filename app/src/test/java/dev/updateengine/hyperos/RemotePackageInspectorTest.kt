package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.network.RemotePackageInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Package classification: incremental packages must be rejected, and the real hash/size values from
 * payload_properties.txt must survive parsing.
 */
class RemotePackageInspectorTest {

    private val fullOtaMetadata = """
        ota-type=AB
        pre-device=zorn
        post-build=Redmi/zorn/zorn:17/CP2A.260605.016/17OS4.0.260916.164405901.QCPECN.S:user/release-keys
        post-build-incremental=17OS4.0.260916.164405901.QCPECN.S
        post-sdk-level=37
        post-timestamp=1789556516
    """.trimIndent()

    private val incrementalMetadata = """
        ota-type=AB
        pre-device=zorn
        pre-build=Redmi/zorn/zorn:16/AP2A.240905.003/OS3.0.308.0.WOKCNXM:user/release-keys
        pre-build-incremental=OS3.0.308.0.WOKCNXM
        post-build-incremental=17OS4.0.260916.164405901.QCPECN.S
        post-sdk-level=37
        post-timestamp=1789556516
    """.trimIndent()

    private val properties = """
        FILE_HASH=f0seSmVhDotJ/r7gEq6T9O64HJ1k2/SIs6vv6neDMUs=
        FILE_SIZE=7730411893
        METADATA_HASH=WxQHwbXa74rRYMAd9/4PdI+bksl9Ssas5S8CrVbosz8=
        METADATA_SIZE=287799
    """.trimIndent()

    @Test
    fun testFullOtaIsAcceptedWithItsRealHashes() {
        val result = RemotePackageInspector.parseInspection(fullOtaMetadata, properties)

        assertTrue(result.isFullOta)
        assertNull(result.preBuildIncremental)
        assertEquals("zorn", result.preDevice)
        assertEquals("17OS4.0.260916.164405901.QCPECN.S", result.postBuildIncremental)
        assertEquals(37, result.postSdkLevel)
        assertEquals(1789556516L, result.postTimestamp)
        assertEquals("f0seSmVhDotJ/r7gEq6T9O64HJ1k2/SIs6vv6neDMUs=", result.fileHash)
        assertEquals(7730411893L, result.fileSize)
        assertEquals("WxQHwbXa74rRYMAd9/4PdI+bksl9Ssas5S8CrVbosz8=", result.metadataHash)
        assertEquals(287799L, result.metadataSize)
    }

    @Test
    fun testIncrementalPackageIsFlaggedAsNotFullOta() {
        val result = RemotePackageInspector.parseInspection(incrementalMetadata, properties)

        assertFalse(result.isFullOta)
        assertEquals("OS3.0.308.0.WOKCNXM", result.preBuildIncremental)
    }

    @Test
    fun testMissingPropertiesComeBackEmptySoTheVerifierCanRejectThem() {
        val result = RemotePackageInspector.parseInspection(fullOtaMetadata, "")

        assertTrue(result.fileHash.isEmpty())
        assertEquals(0L, result.fileSize)
        assertTrue(result.metadataHash.isEmpty())
        assertEquals(0L, result.metadataSize)
    }
}
