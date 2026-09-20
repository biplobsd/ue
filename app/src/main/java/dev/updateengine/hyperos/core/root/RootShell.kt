package dev.updateengine.hyperos.core.root

import kotlinx.coroutines.flow.Flow

data class RootResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

interface RootShell {
    suspend fun exec(cmd: String, timeoutMs: Long = 30_000): RootResult
    fun execStreaming(cmd: String): Flow<String>
    suspend fun isAvailable(): Boolean
}
