package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.common.PathSafety
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EmergencyRescueWriter(
    private val rootShell: RootShell,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    suspend fun writeRescueGuide(
        deviceCodename: String,
        currentSlot: String,
        updatedSlot: String,
        stockBackupPath: String,
        targetPartition: String = ""
    ): Result<File> = withContext(dispatchers.io) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            // Slots from the OS/ksud carry a leading underscore (_a/_b); fastboot wants bare (a/b).
            val fastbootCurrentSlot = currentSlot.trim().removePrefix("_")
            val fastbootUpdatedSlot = updatedSlot.trim().removePrefix("_")
            // The stock backup path never names the partition, so the verified target partition
            // is passed in explicitly instead of being guessed from the backup path.
            val fullTarget = targetPartition.trim()
            val basePartition = when {
                fullTarget.endsWith("_a") || fullTarget.endsWith("_b") -> fullTarget.dropLast(2)
                fullTarget == "init_boot" || fullTarget == "boot" -> fullTarget
                fullTarget.startsWith("init_boot") -> "init_boot"
                fullTarget.startsWith("boot") -> "boot"
                else -> "init_boot"
            }
            val flashPartition = if (fastbootUpdatedSlot == "a" || fastbootUpdatedSlot == "b") {
                "${basePartition}_$fastbootUpdatedSlot"
            } else {
                basePartition
            }

            val guideContent = """
                =======================================================
                HyperOS OTA Engine — Emergency Rescue Instructions
                Generated: $timestamp
                Device: $deviceCodename
                Previous Working Slot: $currentSlot
                Updated Target Slot: $updatedSlot
                Stock Boot Image Backup: $stockBackupPath
                =======================================================

                IF THE DEVICE EVER FAILS TO BOOT OR DISPLAY IS UNLIT:
                DO NOT PANIC. Your user data is safe. Follow these steps:

                STEP 1: ENTER FASTBOOT MODE
                - Power off the device completely.
                - Hold [Volume Down + Power] until the Fastboot logo appears.
                - Connect device to your computer via USB cable.

                STEP 2: REVERT ACTIVE BOOT SLOT (SAFEST OPTION)
                Run this command from your computer terminal to switch back
                to your previously working slot ($currentSlot):

                    fastboot --set-active=$fastbootCurrentSlot
                    fastboot reboot

                This immediately boots you back into your working OS with root!

                STEP 3: OR RESTORE STOCK BOOT IMAGE TO UPDATED SLOT
                If you wish to test the updated slot without root:
                Pull the stock backup file first:
                    adb pull $stockBackupPath stock_boot.img
                Then in Fastboot:
                    fastboot flash $flashPartition stock_boot.img
                    fastboot reboot

                =======================================================
            """.trimIndent()

            val primaryFile = File("/sdcard/Download/ota_emergency_recovery.txt")
            val fallbackFile = File("/data/ota_package/ota_emergency_recovery.txt")

            // Write via root shell to ensure write permission regardless of scoped storage
            val primaryPath = PathSafety.quote(primaryFile.absolutePath)
            val primaryRes = rootShell.exec(
                "mkdir -p /sdcard/Download; cat << 'EOF' > $primaryPath\n$guideContent\nEOF\nchmod 644 $primaryPath; [ -s $primaryPath ] && echo RESCUE_OK",
                timeoutMs = 8000
            )
            if (primaryRes.stdout.contains("RESCUE_OK")) {
                return@withContext Result.success(primaryFile)
            }

            // Fallback: keep the guide next to the staged payload
            val fallbackPath = PathSafety.quote(fallbackFile.absolutePath)
            val fallbackRes = rootShell.exec(
                "mkdir -p /data/ota_package; cat << 'EOF' > $fallbackPath\n$guideContent\nEOF\n[ -s $fallbackPath ] && echo RESCUE_OK",
                timeoutMs = 8000
            )
            if (fallbackRes.stdout.contains("RESCUE_OK")) {
                return@withContext Result.success(fallbackFile)
            }

            Result.failure(
                Exception(
                    "Could not write the emergency rescue guide. " +
                        "Primary: ${primaryRes.stderr.ifBlank { "no output" }}; " +
                        "Fallback: ${fallbackRes.stderr.ifBlank { "no output" }}"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
