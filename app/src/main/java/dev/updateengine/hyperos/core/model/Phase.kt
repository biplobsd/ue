package dev.updateengine.hyperos.core.model

enum class Phase {
    IDLE,
    CHECKING,
    PREFLIGHT,
    DOWNLOADING,
    EXTRACTING,
    VERIFYING,
    ARMED,                  // Chain A completes safely here. Non-destructive boundary.
    FLASHING,               // Gate 1 passed (5 checkboxes confirmed). Flashes inactive slot.
    ROOT_PATCHING,          // Executes ksud boot-patch -u -f
    VERIFYING_PATCH,        // Cryptographically checks sha256 of target inactive boot vs stock backup
    MODULES_DISABLING,      // Puts KernelSU in Safe Mode (/data/adb/disable) & disables modules
    READY_TO_REBOOT,        // Gate 2 reached. Emergency rescue file written to /sdcard/Download.
    AWAITING_BOOT,          // Reboot triggered; waiting for system to start into new slot
    POSTBOOT_VERIFY,        // First boot check: slot switched, incremental match, root present
    MERGING,                // Virtual A/B snapshot merge via snapuserd / update_engine_client --merge
    SAFE_MODE_CLEAR,        // Snapshot merge verified complete. Prompt user to remove Safe Mode.
    CLEANUP,                // Delete /data/ota_package temporary payload
    DONE,                   // Complete!
    PAUSED,                 // User paused or interrupted
    FAILED,                 // General failure with actionable diagnostic message
    ROOT_PATCH_FAILED       // Root patch or hash check failed: Gate 2 locked; Retry or Revert Slot Switch
}
