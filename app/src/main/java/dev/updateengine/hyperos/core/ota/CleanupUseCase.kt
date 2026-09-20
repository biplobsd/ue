package dev.updateengine.hyperos.core.ota

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.withContext

class CleanupUseCase(
    private val rootShell: RootShell,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    suspend fun cleanupPayload(): Boolean = withContext(dispatchers.io) {
        // Explicit file list: never wipe the whole directory, so a fallback emergency rescue
        // guide stored next to the staged payload survives cleanup.
        val res = rootShell.exec(
            "rm -f /data/ota_package/payload.bin /data/ota_package/payload_properties.txt " +
                "/data/ota_package/metadata /data/ota_package/apply.sh",
            timeoutMs = 10_000
        )
        res.isSuccess
    }
}
