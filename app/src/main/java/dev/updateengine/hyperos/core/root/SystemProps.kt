package dev.updateengine.hyperos.core.root

import dev.updateengine.hyperos.core.common.AppDispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class SystemProps(
    private val rootShell: RootShell,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    suspend fun getProp(name: String, default: String = ""): String = withContext(dispatchers.io) {
        try {
            // First try via standard process execution (fast, no root prompt needed for public props)
            val process = Runtime.getRuntime().exec(arrayOf("getprop", name))
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine()?.trim() }
            if (!output.isNullOrEmpty()) {
                return@withContext output
            }
        } catch (_: Exception) {}

        // Fall back to root shell if needed (e.g. for restricted or persist properties)
        try {
            val result = rootShell.exec("getprop $name", timeoutMs = 3000)
            if (result.isSuccess && result.stdout.isNotBlank()) {
                return@withContext result.stdout.trim()
            }
        } catch (_: Exception) {}

        default
    }

    suspend fun getDevice(): String = getProp("ro.product.device")
    suspend fun getSlotSuffix(): String = getProp("ro.boot.slot_suffix")
    suspend fun getIncremental(): String = getProp("ro.build.version.incremental")
    suspend fun getMiuiIncremental(): String = getProp("ro.mi.os.version.incremental")
    suspend fun getAndroidVersion(): String = getProp("ro.build.version.release")
    suspend fun getSdkLevel(): Int = getProp("ro.build.version.sdk").toIntOrNull() ?: 0
    suspend fun getBuildDateUtc(): Long = getProp("ro.build.date.utc").toLongOrNull() ?: 0L
    suspend fun isVirtualAb(): Boolean {
        val vab = getProp("ro.virtual_ab.enabled")
        val ab = getProp("ro.build.ab_update")
        return vab.equals("true", ignoreCase = true) || ab.equals("true", ignoreCase = true)
    }
    suspend fun isBootCompleted(): Boolean = getProp("sys.boot_completed") == "1"
    suspend fun getOtaStatus(): String = getProp("persist.sys.ota.status")
    suspend fun getAbReuseOtaStatus(): String = getProp("persist.sys.abreuse.otastatus")
}
