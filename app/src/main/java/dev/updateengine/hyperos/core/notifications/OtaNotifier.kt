package dev.updateengine.hyperos.core.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.updateengine.hyperos.MainActivity
import dev.updateengine.hyperos.core.common.Formatters
import dev.updateengine.hyperos.core.model.OtaProgress
import dev.updateengine.hyperos.core.model.ProgressDetail

class OtaNotifier(private val context: Context) {

    companion object {
        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_ALERT = 1002
    }

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun buildProgressNotification(progress: OtaProgress): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_OTA_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("HyperOS OTA: ${progress.phase.name.replace('_', ' ')}")
            .setContentIntent(pendingIntent)
            .setOngoing(progress.isFlashingOrPost)
            .setOnlyAlertOnce(true)

        when (val detail = progress.detail) {
            is ProgressDetail.Bytes -> {
                val percent = (detail.fraction * 100).toInt()
                builder.setProgress(100, percent, false)
                val rateStr = Formatters.formatTransferRate(detail.rateBps)
                val etaStr = Formatters.formatEta(detail.etaSecs)
                val text = "${Formatters.formatBytes(detail.done)} / ${Formatters.formatBytes(detail.total)} ($rateStr • ETA $etaStr)"
                builder.setContentText(text)
            }
            is ProgressDetail.Percent -> {
                val percent = (detail.value * 100).toInt().coerceIn(0, 100)
                builder.setProgress(100, percent, false)
                builder.setContentText(detail.caption ?: "$percent%")
            }
            is ProgressDetail.Indeterminate -> {
                builder.setProgress(0, 0, true)
                builder.setContentText(progress.statusText.ifBlank { "Processing..." })
            }
        }

        return builder.build()
    }

    fun updateProgressNotification(progress: OtaProgress) {
        if (!progress.showsProgressNotification) {
            cancelProgressNotification()
            return
        }
        try {
            manager.notify(NOTIFICATION_ID_PROGRESS, buildProgressNotification(progress))
        } catch (_: Exception) {}
    }

    fun cancelProgressNotification() {
        try {
            manager.cancel(NOTIFICATION_ID_PROGRESS)
        } catch (_: Exception) {}
    }

    /**
     * Reports a state the user has to act on: a failure that must not be followed by a reboot, or a
     * finished update. It goes to the high-importance alert channel on its own id, so it survives the
     * foreground service the pipeline stops when it ends — a "DO NOT REBOOT" message must not vanish
     * together with the progress notification.
     */
    fun showTerminalNotification(title: String, message: String) {
        showAlertNotification(title, message)
    }

    fun showAlertNotification(title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_OTA_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID_ALERT, notification)
    }
}
