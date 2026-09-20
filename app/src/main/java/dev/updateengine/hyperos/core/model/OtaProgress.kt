package dev.updateengine.hyperos.core.model

data class OtaProgress(
    val phase: Phase = Phase.IDLE,
    val detail: ProgressDetail = ProgressDetail.Indeterminate,
    val target: OtaTarget? = null,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val statusText: String = "",
    val failure: FailureInfo? = null
) {
    val isFlashingOrPost: Boolean
        get() = phase in listOf(
            Phase.FLASHING,
            Phase.ROOT_PATCHING,
            Phase.VERIFYING_PATCH,
            Phase.MODULES_DISABLING,
            Phase.READY_TO_REBOOT
        )

    val isTerminal: Boolean
        get() = phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.ROOT_PATCH_FAILED

    val showsProgressNotification: Boolean
        get() = phase in listOf(
            Phase.CHECKING,
            Phase.PREFLIGHT,
            Phase.DOWNLOADING,
            Phase.EXTRACTING,
            Phase.VERIFYING,
            Phase.FLASHING,
            Phase.ROOT_PATCHING,
            Phase.VERIFYING_PATCH,
            Phase.MODULES_DISABLING,
            Phase.POSTBOOT_VERIFY,
            Phase.MERGING,
            Phase.CLEANUP
        )
}
