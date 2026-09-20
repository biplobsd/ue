package dev.updateengine.hyperos.core.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.updateengine.hyperos.MainActivity
import dev.updateengine.hyperos.core.datastore.PipelineStorage
import dev.updateengine.hyperos.core.model.Phase
import dev.updateengine.hyperos.core.notifications.OtaNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val storage = PipelineStorage(context)
        val notifier = OtaNotifier(context)

        CoroutineScope(Dispatchers.IO).launch {
            val phase = storage.currentPhase.first()
            if (phase == Phase.AWAITING_BOOT) {
                // Device rebooted after Gate 2!
                storage.savePhase(Phase.POSTBOOT_VERIFY)

                notifier.showAlertNotification(
                    "HyperOS OTA: Verifying First Boot",
                    "Device reboot completed. Open HyperOS OTA Engine to verify root and monitor snapshot merge."
                )

                // Launching an activity from the background may be blocked on modern Android;
                // the app also resumes Chain C from PipelineRunner when it is opened.
                try {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(launchIntent)
                } catch (_: Exception) {
                    // Notification already tells the user to open the app.
                }
            }
        }
    }
}
