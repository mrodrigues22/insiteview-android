package com.getinsiteview.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's theme: the blueprint tokens ([Palette]) and type ([IvType]), with Material 3 mapped
 * onto them so stock components (dialogs, sheets, text fields, the navigation bar) match.
 * Follows the system's dark mode, as iOS does.
 */
@Composable
fun IvTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentSoft,
        onPrimaryContainer = colors.ink,
        secondary = colors.ink,
        onSecondary = colors.onInk,
        secondaryContainer = colors.sunk,
        onSecondaryContainer = colors.ink,
        background = colors.background,
        onBackground = colors.ink,
        surface = colors.surface,
        onSurface = colors.ink,
        surfaceVariant = colors.sunk,
        onSurfaceVariant = colors.muted,
        surfaceContainer = colors.surface,
        surfaceContainerLow = colors.surface,
        surfaceContainerHigh = colors.surface,
        surfaceContainerHighest = colors.sunk,
        outline = colors.line,
        outlineVariant = colors.line,
        error = colors.accent,
        onError = colors.onAccent,
    )
    val typography = Typography(
        displayLarge = IvType.display(34.sp),
        displayMedium = IvType.display(28.sp),
        displaySmall = IvType.display(24.sp),
        headlineLarge = IvType.display(34.sp),
        headlineMedium = IvType.display(28.sp),
        headlineSmall = IvType.display(22.sp),
        titleLarge = IvType.body(20.sp, FontWeight.SemiBold),
        titleMedium = IvType.headline,
        titleSmall = IvType.body(15.sp, FontWeight.SemiBold),
        bodyLarge = IvType.body(17.sp),
        bodyMedium = IvType.body(15.sp),
        bodySmall = IvType.body(13.sp),
        labelLarge = IvType.body(15.sp, FontWeight.Medium),
        labelMedium = IvType.body(13.sp, FontWeight.Medium),
        labelSmall = IvType.mono(11.sp, FontWeight.Medium),
    )
    CompositionLocalProvider(LocalIvColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
