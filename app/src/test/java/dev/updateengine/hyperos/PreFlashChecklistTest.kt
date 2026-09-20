package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.model.PreFlashChecklist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreFlashChecklistTest {

    @Test
    fun testAllCheckedRequirement() {
        val empty = PreFlashChecklist()
        assertFalse(empty.isAllChecked)

        val fourChecked = PreFlashChecklist(
            backupConfirmed = true,
            batteryConfirmed = true,
            modulesSafeModeConfirmed = true,
            storageConfirmed = true,
            fastbootRescueConfirmed = false
        )
        assertFalse("Must not allow flashing if even one item is unchecked", fourChecked.isAllChecked)

        val allFive = PreFlashChecklist(
            backupConfirmed = true,
            batteryConfirmed = true,
            modulesSafeModeConfirmed = true,
            storageConfirmed = true,
            fastbootRescueConfirmed = true
        )
        assertTrue("All 5 items checked must activate Gate 1", allFive.isAllChecked)
    }
}
