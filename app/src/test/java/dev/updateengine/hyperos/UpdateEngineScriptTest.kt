package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.ota.UpdateEngineController
import dev.updateengine.hyperos.core.root.RootResult
import dev.updateengine.hyperos.core.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `apply.sh` is the script that decides how `update_engine_client` is invoked, and it is written into
 * `/data/ota_package` as plain text, so its exact content is asserted here rather than only being
 * exercised on a device. This is the manual flow the engine reproduces: the properties are read once
 * into `H` and passed through `"$H"`.
 */
class UpdateEngineScriptTest {

    private class RecordingShell : RootShell {
        val commands = mutableListOf<String>()
        var streamingCommand: String? = null

        override suspend fun exec(cmd: String, timeoutMs: Long): RootResult {
            commands.add(cmd)
            return RootResult(0, "", "")
        }

        override fun execStreaming(cmd: String): Flow<String> = flow {
            streamingCommand = cmd
        }

        override suspend fun isAvailable(): Boolean = true
    }

    /** `main` is overridden so the test never touches Android's main looper. */
    private fun controller(shell: RootShell) = UpdateEngineController(
        rootShell = shell,
        dispatchers = AppDispatchers(main = Dispatchers.Unconfined)
    )

    private fun writtenScript(shell: RecordingShell): String {
        val writer = shell.commands.firstOrNull { it.contains("> /data/ota_package/apply.sh") }
            ?: error("apply.sh was never written by applyPayload()")
        return writer.substringAfter("> /data/ota_package/apply.sh\n").substringBefore("\nEOF")
    }

    @Test
    fun applyScriptReadsThePropertiesIntoHAndPassesItToHeaders() = runBlocking {
        val shell = RecordingShell()
        controller(shell).applyPayload().toList()

        assertEquals(
            """
            #!/system/bin/sh
            H="${'$'}(cat /data/ota_package/payload_properties.txt)"
            update_engine_client --payload=file:///data/ota_package/payload.bin --update --follow --headers="${'$'}H"
            """.trimIndent(),
            writtenScript(shell)
        )
    }

    @Test
    fun applyScriptIsMadeExecutableAndThenStreamed() = runBlocking {
        val shell = RecordingShell()
        controller(shell).applyPayload().toList()

        val writer = shell.commands.first { it.contains("> /data/ota_package/apply.sh") }
        assertTrue(writer.endsWith("chmod 755 /data/ota_package/apply.sh"))
        assertEquals("/data/ota_package/apply.sh", shell.streamingCommand)
    }

    @Test
    fun staleUpdateSessionIsResetBeforeThePayloadIsApplied() = runBlocking {
        val shell = RecordingShell()
        controller(shell).applyPayload().toList()

        assertEquals(
            "update_engine_client --cancel; update_engine_client --reset_status",
            shell.commands.first()
        )
    }
}
