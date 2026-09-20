package dev.updateengine.hyperos.core.model

data class PreFlashChecklist(
    val backupConfirmed: Boolean = false,
    val batteryConfirmed: Boolean = false,
    val modulesSafeModeConfirmed: Boolean = false,
    val storageConfirmed: Boolean = false,
    val fastbootRescueConfirmed: Boolean = false
) {
    val isAllChecked: Boolean
        get() = backupConfirmed && batteryConfirmed && modulesSafeModeConfirmed &&
                storageConfirmed && fastbootRescueConfirmed
}
