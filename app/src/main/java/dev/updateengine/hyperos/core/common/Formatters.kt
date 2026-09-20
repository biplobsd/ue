package dev.updateengine.hyperos.core.common

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object Formatters {
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.2f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    fun formatTransferRate(bytesPerSec: Double): String {
        return "${formatBytes(bytesPerSec.toLong())}/s"
    }

    fun formatEta(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "--:--"
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins >= 60) {
            val hours = mins / 60
            val remMins = mins % 60
            String.format(Locale.US, "%d:%02d:%02d", hours, remMins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }
}
