package dev.updateengine.hyperos.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Miuix supplies the role palette (primary/surface/outline/error/…). Only the states Miuix has no
// token for live here. They read LocalAppDarkTheme rather than the system configuration, because
// green-on-white and amber-on-white are unreadable in the light Monet scheme — including the light
// scheme a user forces while the system is dark.

/** Pass / verified state. */
val SuccessGreen: Color
    @Composable get() = if (LocalAppDarkTheme.current) Color(0xFF00E676) else Color(0xFF00875A)

/** Warning state: cross-branch jumps, degradations, throttling. */
val WarningAmber: Color
    @Composable get() = if (LocalAppDarkTheme.current) Color(0xFFFFD600) else Color(0xFF8A6D00)
