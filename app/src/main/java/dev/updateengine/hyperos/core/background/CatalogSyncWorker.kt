package dev.updateengine.hyperos.core.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.updateengine.hyperos.core.network.HyperDataApiClient
import dev.updateengine.hyperos.core.root.RootShell
import dev.updateengine.hyperos.core.root.SuRootShell
import dev.updateengine.hyperos.core.root.SystemProps

class CatalogSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val rootShell = SuRootShell()
            val systemProps = SystemProps(rootShell)
            val client = HyperDataApiClient()

            val device = systemProps.getDevice()
            if (device.isNotBlank()) {
                val romsRes = client.getRomsForDevice(device)
                if (romsRes.isSuccess) {
                    Result.success()
                } else {
                    Result.retry()
                }
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
