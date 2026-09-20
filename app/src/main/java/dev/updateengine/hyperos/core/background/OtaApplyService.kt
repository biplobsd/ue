package dev.updateengine.hyperos.core.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import dev.updateengine.hyperos.core.model.OtaProgress
import dev.updateengine.hyperos.core.model.Phase
import dev.updateengine.hyperos.core.notifications.OtaNotifier

class OtaApplyService : Service() {

    companion object {
        const val ACTION_START_APPLY = "dev.updateengine.hyperos.action.START_APPLY"
        const val ACTION_STOP_APPLY = "dev.updateengine.hyperos.action.STOP_APPLY"
        const val EXTRA_STATUS_TEXT = "dev.updateengine.hyperos.extra.STATUS_TEXT"
        const val EXTRA_PHASE = "dev.updateengine.hyperos.extra.PHASE"

        /** Flashing, root patching and a 7.7 GB staging run are all long; the lock is released on stop. */
        private const val WAKE_LOCK_TIMEOUT_MS = 180L * 60 * 1000

        fun start(
            context: Context,
            statusText: String = "Applying system update via update_engine...",
            phase: Phase = Phase.FLASHING
        ) {
            val intent = Intent(context, OtaApplyService::class.java).apply {
                action = ACTION_START_APPLY
                putExtra(EXTRA_STATUS_TEXT, statusText)
                putExtra(EXTRA_PHASE, phase.name)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OtaApplyService::class.java).apply {
                action = ACTION_STOP_APPLY
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notifier: OtaNotifier

    override fun onCreate() {
        super.onCreate()
        notifier = OtaNotifier(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HyperOsOtaEngine:FlashingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_APPLY) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifier.cancelProgressNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        // The same foreground service covers staging (download/extract/verify) and flashing, so the
        // process keeps running with the screen off and the download is not frozen mid-way.
        wakeLock?.let { lock ->
            if (!lock.isHeld) {
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }

        val phase = intent?.getStringExtra(EXTRA_PHASE)
            ?.let { runCatching { Phase.valueOf(it) }.getOrNull() }
            ?: Phase.FLASHING
        val initialProgress = OtaProgress(
            phase = phase,
            statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
                ?: "Applying system update via update_engine..."
        )
        startForeground(
            OtaNotifier.NOTIFICATION_ID_PROGRESS,
            notifier.buildProgressNotification(initialProgress)
        )

        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifier.cancelProgressNotification()
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
