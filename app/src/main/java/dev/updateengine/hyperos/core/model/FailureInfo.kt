package dev.updateengine.hyperos.core.model

data class FailureInfo(
    val phase: Phase,
    val title: String,
    val message: String,
    val actionableResolution: String,
    val rawError: String? = null,
    val canRetry: Boolean = true,
    val canRollbackSlot: Boolean = false
)
