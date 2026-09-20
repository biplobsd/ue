package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.common.RomCompatibility
import dev.updateengine.hyperos.core.model.OtaTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomCompatibilityTest {

    private fun rom(build: String, release: String = "2026-09-01") = OtaTarget(
        device = "zorn",
        branch = "Official",
        osVersion = build,
        releaseDate = release,
        recoveryFilename = "zorn-ota_full-$build-user-17.0-c5b483f634.zip"
    )

    @Test
    fun testRegionExtractionFromBuildTag() {
        assertEquals("CN", RomCompatibility.region("OS4.0.0.8.XOKCNXM"))
        assertEquals("MI", RomCompatibility.region("OS4.0.0.8.XOKMIXM"))
        assertEquals("EU", RomCompatibility.region("OS4.0.0.8.XOKEUXM"))
        assertEquals("IN", RomCompatibility.region("V816.0.4.0.UNBINXM"))
        assertEquals("RU", RomCompatibility.region("V816.0.4.0.UNBRUXM"))
        assertEquals("ID", RomCompatibility.region("V816.0.4.0.UNBIDXM"))
    }

    @Test
    fun testRegionMissingOnUnrecognisedTag() {
        assertNull(RomCompatibility.region(""))
        assertNull(RomCompatibility.region("OS4.0.0.8"))
        assertNull(RomCompatibility.region("OS4.0.0.8.XOK"))
    }

    @Test
    fun testRegionLabel() {
        assertEquals("China (CN)", RomCompatibility.regionLabel("CN"))
        assertEquals("Global (MI)", RomCompatibility.regionLabel("MI"))
        assertEquals("Unknown", RomCompatibility.regionLabel(null))
    }

    @Test
    fun testCrossResignBuildsAreHidden() {
        val roms = listOf(
            rom("OS4.0.0.9.XOKCNXM", "2026-09-10"),
            rom("OS4.0.0.9.XOKMIXM", "2026-09-11"),
            rom("OS4.0.0.9.XOKEUXM", "2026-09-12")
        )

        val result = RomCompatibility.selectableRoms(roms, "OS4.0.0.8.XOKCNXM")

        assertEquals(listOf("OS4.0.0.9.XOKCNXM"), result.map { it.osVersion })
    }

    @Test
    fun testDowngradesAndCurrentBuildAreHidden() {
        val roms = listOf(
            rom("OS4.0.0.7.XOKCNXM"),
            rom("OS4.0.0.8.XOKCNXM"),
            rom("OS4.0.0.9.XOKCNXM")
        )

        val result = RomCompatibility.selectableRoms(roms, "OS4.0.0.8.XOKCNXM")

        assertEquals(listOf("OS4.0.0.9.XOKCNXM"), result.map { it.osVersion })
    }

    @Test
    fun testNumericSegmentsCompareNumerically() {
        val roms = listOf(
            rom("OS4.0.0.9.XOKCNXM"),
            rom("OS4.0.0.10.XOKCNXM")
        )

        val result = RomCompatibility.selectableRoms(roms, "OS4.0.0.8.XOKCNXM")

        assertEquals(listOf("OS4.0.0.10.XOKCNXM", "OS4.0.0.9.XOKCNXM"), result.map { it.osVersion })
    }

    @Test
    fun testNewestReleaseComesFirst() {
        val roms = listOf(
            rom("OS4.0.0.9.XOKCNXM", "2026-09-01"),
            rom("OS4.0.0.10.XOKCNXM", "2026-09-15"),
            rom("OS4.0.0.11.XOKCNXM", "2026-09-10")
        )

        val result = RomCompatibility.selectableRoms(roms, "OS4.0.0.8.XOKCNXM")

        assertEquals(
            listOf("OS4.0.0.10.XOKCNXM", "OS4.0.0.11.XOKCNXM", "OS4.0.0.9.XOKCNXM"),
            result.map { it.osVersion }
        )
    }

    @Test
    fun testUnknownInstalledBuildSkipsResignFilter() {
        val roms = listOf(
            rom("OS4.0.0.9.XOKCNXM"),
            rom("OS4.0.0.9.XOKMIXM")
        )

        val result = RomCompatibility.selectableRoms(roms, "")

        assertEquals(2, result.size)
    }

    @Test
    fun testUnreadableBuildTagIsHiddenWhenResignIsKnown() {
        val roms = listOf(
            rom("OS4.0.0.9.XOKCNXM"),
            rom("OS4.0.0.9")
        )

        val result = RomCompatibility.selectableRoms(roms, "OS4.0.0.8.XOKCNXM")

        assertEquals(listOf("OS4.0.0.9.XOKCNXM"), result.map { it.osVersion })
    }
}
