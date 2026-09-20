package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.common.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun testMajorVersionUpgrade() {
        val cmp = VersionCompare.compare("OS4.0.0.8.XOKCNXM", "OS3.0.308.0.WOKCNXM")
        assertTrue("OS4 should be greater than OS3", cmp > 0)
    }

    @Test
    fun testNumericSegmentComparison() {
        // Critical: 308 must be numerically greater than 9
        val cmp = VersionCompare.compare("OS3.0.308.0", "OS3.0.9.0")
        assertTrue("308 must be greater than 9", cmp > 0)
    }

    @Test
    fun testEqualVersions() {
        val cmp = VersionCompare.compare("OS1.0.1.0", "OS1.0.1.0")
        assertEquals(0, cmp)
    }

    @Test
    fun testMinorVersionIncrease() {
        val cmp = VersionCompare.compare("OS1.0.1.0", "OS1.0.2.0")
        assertTrue("1.0.1.0 should be less than 1.0.2.0", cmp < 0)
    }
}
