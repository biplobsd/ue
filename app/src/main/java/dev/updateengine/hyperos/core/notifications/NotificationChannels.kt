package dev.updateengine.hyperos.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val CHANNEL_OTA_PROGRESS = "ota_progress_channel"
    const val CHANNEL_OTA_ALERTS = "ota_alerts_channel"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progressChannel = NotificationChannel(
            CHANNEL_OTA_PROGRESS,
            "OTA Update Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live system update progress and phase status"
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            CHANNEL_OTA_ALERTS,
            "OTA System Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "High-priority alerts regarding root integrity and safe reboot"
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(alertChannel)
    }
}
