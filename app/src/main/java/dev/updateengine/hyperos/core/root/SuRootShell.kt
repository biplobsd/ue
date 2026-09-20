package dev.updateengine.hyperos.core.root

import dev.updateengine.hyperos.core.common.AppDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class SuRootShell(
    private val dispatchers: AppDispatchers = AppDispatchers()
) : RootShell {

    override suspend fun isAvailable(): Boolean = withContext(dispatchers.io) {
        try {
            val result = exec("id", timeoutMs = 5000)
            result.isSuccess && result.stdout.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun exec(cmd: String, timeoutMs: Long): RootResult = withContext(dispatchers.io) {
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                val process = ProcessBuilder("su", "-c", cmd).start()
                val stdoutBuilder = StringBuilder()
                val stderrBuilder = StringBuilder()

                val stdoutThread = Thread {
                    try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                stdoutBuilder.append(line).append("\n")
                            }
                        }
                    } catch (_: Exception) {}
                }

                val stderrThread = Thread {
                    try {
                        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                stderrBuilder.append(line).append("\n")
                            }
                        }
                    } catch (_: Exception) {}
                }

                stdoutThread.start()
                stderrThread.start()

                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (finished) {
                    stdoutThread.join(1000)
                    stderrThread.join(1000)
                    RootResult(
                        exitCode = process.exitValue(),
                        stdout = stdoutBuilder.toString().trim(),
                        stderr = stderrBuilder.toString().trim()
                    )
                } else {
                    process.destroyForcibly()
                    RootResult(
                        exitCode = -1,
                        stdout = stdoutBuilder.toString().trim(),
                        stderr = "Command timed out after ${timeoutMs}ms"
                    )
                }
            } catch (e: Exception) {
                RootResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = e.message ?: "Execution failed"
                )
            }
        }

        result ?: RootResult(
            exitCode = -1,
            stdout = "",
            stderr = "Command timed out after ${timeoutMs}ms"
        )
    }

    override fun execStreaming(cmd: String): Flow<String> = flow {
        val process = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()

        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    emit(line!!)
                }
            }
            process.waitFor()
        } finally {
            try {
                process.destroy()
            } catch (_: Exception) {}
        }
    }.flowOn(dispatchers.io)
}
