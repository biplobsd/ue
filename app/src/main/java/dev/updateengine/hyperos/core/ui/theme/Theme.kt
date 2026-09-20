package dev.updateengine.hyperos.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.updateengine.hyperos.core.model.ThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Whether the app is painting its dark palette right now.
 *
 * This follows the user's [ThemeMode] rather than the system, because the two disagree as soon as
 * a mode is forced. The glass capsule's shadow and the pass/warn status colors read it.
 */
val LocalAppDarkTheme = compositionLocalOf { false }

/**
 * Miuix is the only UI system in this app. Every mode derives its palette from the wallpaper via
 * Monet, so switching between system, dark and light keeps the same colours in a different key.
 */
@Composable
fun HyperOsOtaTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val colorSchemeMode = when (themeMode) {
        ThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
        ThemeMode.DARK -> ColorSchemeMode.MonetDark
        ThemeMode.LIGHT -> ColorSchemeMode.MonetLight
    }
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val controller = remember(colorSchemeMode) {
        ThemeController(colorSchemeMode = colorSchemeMode)
    }

    // enableEdgeToEdge() decides the system bar icon appearance from the system setting, which is
    // wrong as soon as the user forces a mode, so the real answer is asserted here instead.
    val view = LocalView.current
    val window = (LocalContext.current as? Activity)?.window
    if (window != null) {
        SideEffect {
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !isDark
            insets.isAppearanceLightNavigationBars = !isDark
        }
    }

    MiuixTheme(controller = controller) {
        CompositionLocalProvider(LocalAppDarkTheme provides isDark) {
            content()
        }
    }
}
