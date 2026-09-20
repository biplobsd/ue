package dev.updateengine.hyperos.core.model

enum class RootKind {
    KERNELSU_NEXT,
    MAGISK,
    APATCH,
    NONE
}

data class RootStatus(
    val kind: RootKind = RootKind.NONE,
    val isRootGranted: Boolean = false,
    val ksudVersion: String? = null,
    val defaultPartition: String = "init_boot", // init_boot or boot
    val currentSlot: String = "",               // e.g. "_a" or "_b"
    val inactiveSlot: String = "",              // opposite slot
    val details: String = ""
)

data class KernelModule(
    val id: String,
    val name: String,
    val version: String,
    val isEnabled: Boolean,
    val isZygoteHook: Boolean = false
)
