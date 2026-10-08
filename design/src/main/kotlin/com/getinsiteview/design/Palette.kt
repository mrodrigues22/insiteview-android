// :design (IVDesign on iOS): the architectural colour tokens (system colours come from the
// catalog), type, buttons, chips and the slider with detents (docs/PLAN.md §2).
package com.getinsiteview.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.getinsiteview.core.CatalogColor

/** One set of the tokens below; [IvTheme] provides the light or the dark one. */
@Immutable
data class IvColors(
    val background: Color,
    val surface: Color,
    val sunk: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val onAccent: Color,
    val onInk: Color,
    val isDark: Boolean,
)

internal val LightColors = IvColors(
    background = Color(0xFFF1F1F0),
    surface = Color(0xFFFFFFFF),
    sunk = Color(0xFFE3E3E3),
    ink = Color(0xFF0D0D0E),
    muted = Color(0xFF5F5F61),
    line = Color(0xFFD4D4D3),
    accent = Color(0xFFE30532),
    accentSoft = Color(0xFFFBE3E7),
    warn = Color(0xFFB45309),
    warnSoft = Color(0xFFFDF0DC),
    onAccent = Color(0xFFFFFFFF),
    onInk = Color(0xFFF1F1F0),
    isDark = false,
)

internal val DarkColors = IvColors(
    background = Color(0xFF0D0D0E),
    surface = Color(0xFF18181A),
    sunk = Color(0xFF222224),
    ink = Color(0xFFF1F1F0),
    muted = Color(0xFF9A9A9E),
    line = Color(0xFF2E2E31),
    accent = Color(0xFFFF3B4E),
    accentSoft = Color(0xFF3A0E15),
    warn = Color(0xFFF59E0B),
    warnSoft = Color(0xFF3A2A0A),
    onAccent = Color(0xFF18181A),
    onInk = Color(0xFF0D0D0E),
    isDark = true,
)

val LocalIvColors = staticCompositionLocalOf { LightColors }

/**
 * The architectural tokens from the web's `globals.css` (ink and paper, a red accent), light and
 * dark. Keep the values equal to the web's (and to iOS `Palette`).
 */
object Palette {
    /** Paper (`--bg`). */
    val background: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.background
    /** Cards and panels (`--surface`). */
    val surface: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.surface
    /** Sunken areas: tracks, wells, placeholders (`--sunk`). */
    val sunk: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.sunk
    /** Text and ink fills (`--ink`). */
    val ink: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.ink
    /** Secondary text (`--muted`). */
    val muted: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.muted
    /** Hairlines and borders (`--line`). */
    val line: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.line
    /** Brand red (`--accent`): primary actions, the wordmark's slash. */
    val accent: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.accent
    /** A light tint of the brand red (`--accent-soft`). */
    val accentSoft: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.accentSoft
    /** Warnings: amber, so they never read as a primary action (`--warn`). */
    val warn: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.warn
    val warnSoft: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.warnSoft
    /** Text on `accent`: white, and the dark surface in dark mode (white on the dark red is only 3.5:1). */
    val onAccent: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.onAccent
    /** Text on `ink` fills (the "ink" button, a selected chip): paper in light mode, ink in dark. */
    val onInk: Color @Composable @ReadOnlyComposable get() = LocalIvColors.current.onInk
}

/** A system or subsystem colour from the catalog. */
fun CatalogColor.toColor(): Color = Color(red = red.toFloat(), green = green.toFloat(), blue = blue.toFloat())
