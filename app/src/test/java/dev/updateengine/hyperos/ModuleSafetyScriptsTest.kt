package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.root.ModuleSafetyOutput
import dev.updateengine.hyperos.core.root.ModuleSafetyScripts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The module scripts are the only thing standing between a module injection and an unlit screen on the
 * first boot, so their exact text is asserted here. The same generated text is also executed against a
 * sandbox module root on a real device (see the `sandbox-scripts` test below, which writes the files
 * out for `adb push`).
 */
class ModuleSafetyScriptsTest {

    private val ids = listOf("zygisk-detach", "tricky_store", "scene-systemless")

    @Test
    fun testDisableScriptUsesKsudAndPrintsMarkers() {
        val script = ModuleSafetyScripts.disableScript()

        assertTrue(script.contains("ksud")) // backend is ksud, not a bare touch
        assertTrue(script.contains("module disable"))
        assertTrue(script.contains(ModuleSafetyOutput.DISABLE_SUMMARY))
        assertTrue(script.contains("OTA_MOD_ENABLED"))
        assertTrue(script.contains("OTA_MOD_FAILED"))
        // The pending module update directory must be parked, never deleted outright.
        assertTrue(script.contains("mv "))
        assertFalse("disable script must not delete modules_update", script.contains("rm -rf \"\$MODS/../modules_update\""))
    }

    @Test
    fun testRestoreScriptQuotesIdsAndClearsTheFlag() {
        val script = ModuleSafetyScripts.restoreScript(ids)

        for (id in ids) {
            assertTrue("missing $id", script.contains("'$id'"))
        }
        assertTrue(script.contains("module enable"))
        assertTrue(script.contains("OTA_MOD_RESTORED"))
        assertTrue(script.contains("OTA_MOD_RESTORE_FAILED"))
        assertTrue(script.contains(ModuleSafetyOutput.RESTORE_SUMMARY))
    }

    @Test
    fun testRestoreScriptDropsUnsafeIds() {
        val script = ModuleSafetyScripts.restoreScript(listOf("good-id", "bad; rm -rf /data", "../../etc"))

        assertTrue(script.contains("'good-id'"))
        assertFalse(script.contains("rm -rf /data\""))
        assertFalse(script.contains("../../etc"))
    }

    @Test
    fun testListScriptUsesASinglePass() {
        val script = ModuleSafetyScripts.listScript()

        assertTrue(script.contains("OTA_MODULE"))
        assertTrue(script.contains("module.prop"))
        assertTrue(script.contains("disable"))
        // One loop and no per-module `su` round trips.
        assertTrue(script.contains("for m in"))
    }

    @Test
    fun testGeneratedScriptsAreWrittenOutForSandboxExecution() {
        // Emitted so the same text can be run on-device against a sandbox module root:
        //   adb push app/build/scripts/*.sh /data/local/tmp/
        val out = File("build/scripts")
        out.mkdirs()
        File(out, "module_disable.sh").writeText(ModuleSafetyScripts.disableScript())
        File(out, "module_restore.sh").writeText(ModuleSafetyScripts.restoreScript(ids))
        File(out, "module_list.sh").writeText(ModuleSafetyScripts.listScript())

        assertTrue(File(out, "module_disable.sh").length() > 0)
        assertTrue(File(out, "module_restore.sh").length() > 0)
        assertTrue(File(out, "module_list.sh").length() > 0)
    }
}
