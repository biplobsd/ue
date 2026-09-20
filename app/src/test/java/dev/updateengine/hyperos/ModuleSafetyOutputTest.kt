package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.root.ModuleSafetyOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSafetyOutputTest {

    @Test
    fun testDisableReportParsesRealKsudRun() {
        val stdout = """
            OTA_MOD_ENABLED zygisk-detach
            OTA_MOD_ENABLED tricky_store
            OTA_MOD_ENABLED youtube-morphe-jhc
            OTA_MOD_DISABLE_OK total=7 disabled=7 global=1 parked=0 ksud=1
        """.trimIndent()

        val parsed = ModuleSafetyOutput.parseDisable(stdout)

        assertTrue(parsed.armed)
        assertEquals(listOf("zygisk-detach", "tricky_store", "youtube-morphe-jhc"), parsed.previouslyEnabled)
        assertTrue(parsed.failed.isEmpty())
        assertEquals(7, parsed.total)
        assertEquals(7, parsed.disabledCount)
        assertTrue(parsed.globalFlagSet)
        assertTrue(parsed.ksudUsed)
        assertFalse(parsed.pendingUpdatesParked)
    }

    @Test
    fun testDisableReportFlagsAModuleThatCouldNotBeDisabled() {
        val stdout = """
            OTA_MOD_ENABLED scene_systemless
            OTA_MOD_FAILED scene_systemless
            OTA_MOD_DISABLE_OK total=3 disabled=2 global=1 parked=1 ksud=0
        """.trimIndent()

        val parsed = ModuleSafetyOutput.parseDisable(stdout)

        assertEquals(listOf("scene_systemless"), parsed.failed)
        assertTrue(parsed.pendingUpdatesParked)
        assertFalse(parsed.ksudUsed)
        assertEquals(3, parsed.total)
        assertEquals(2, parsed.disabledCount)
    }

    @Test
    fun testDisableReportWithoutSummaryIsNotArmed() {
        val parsed = ModuleSafetyOutput.parseDisable("sh: ksud: not found")

        assertFalse(parsed.armed)
        assertFalse(parsed.globalFlagSet)
        assertTrue(parsed.previouslyEnabled.isEmpty())
    }

    @Test
    fun testRestoreReportParsesOutput() {
        val stdout = """
            OTA_MOD_RESTORED zygisk-detach
            OTA_MOD_RESTORE_FAILED tricky_store
            OTA_MOD_RESTORE_OK restored=1 unparked=0 global=0
        """.trimIndent()

        val parsed = ModuleSafetyOutput.parseRestore(stdout)

        assertTrue(parsed.completed)
        assertEquals(listOf("zygisk-detach"), parsed.restored)
        assertEquals(listOf("tricky_store"), parsed.failed)
        assertTrue(parsed.globalFlagCleared)
        assertFalse(parsed.pendingUpdatesUnparked)
    }

    @Test
    fun testRestoreReportReportsAFailedFlagRemoval() {
        val parsed = ModuleSafetyOutput.parseRestore("OTA_MOD_RESTORE_OK restored=0 unparked=1 global=1")

        assertTrue(parsed.completed)
        assertFalse(parsed.globalFlagCleared)
        assertTrue(parsed.pendingUpdatesUnparked)
    }

    @Test
    fun testModuleListParsesTheBundledScan() {
        val stdout = """
            OTA_MODULE zygisk-detach|1|1|Zygisk Detach|2.1
            OTA_MODULE scene_systemless|0|1|Scene Systemless|
            OTA_MODULE youtube-morphe-jhc|1|0|YouTube Morphe|1.0
            garbage line that must be ignored
            OTA_MODULE broken|1
        """.trimIndent()

        val modules = ModuleSafetyOutput.parseModuleList(stdout)

        assertEquals(3, modules.size)
        assertEquals("zygisk-detach", modules[0].id)
        assertTrue(modules[0].isEnabled)
        assertTrue(modules[0].isZygoteHook)
        assertEquals("Zygisk Detach", modules[0].name)
        assertEquals("2.1", modules[0].version)

        assertFalse(modules[1].isEnabled)
        assertEquals("Scene Systemless", modules[1].name)
        // A module without a version falls back instead of rendering an empty string.
        assertEquals("1.0", modules[1].version)

        assertFalse(modules[2].isZygoteHook)
    }
}
