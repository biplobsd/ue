package dev.updateengine.hyperos.core.model

/** How the app picks its light or dark appearance. */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "Follow system"),
    DARK("dark", "Dark"),
    LIGHT("light", "Light");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}
