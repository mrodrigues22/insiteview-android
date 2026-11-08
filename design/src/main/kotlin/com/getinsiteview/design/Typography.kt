// :design typography: Anton (display, uppercase headlines), Barlow (body) and JetBrains Mono
// (eyebrows, codes, numbers), the same families as the web and iOS. The fonts are in
// res/font (OFL, Latin subsets; licenses in design/licenses). Sizes are in sp, so they follow the
// system font scale (iOS: Dynamic Type).
package com.getinsiteview.design

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object IvFonts {
    val display = FontFamily(Font(R.font.anton_regular, FontWeight.Normal))
    val body = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_bold, FontWeight.Bold),
    )
    val mono = FontFamily(
        Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
        Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
    )
}

/**
 * The iOS text styles at the default Dynamic Type size ("Large"): size and leading in points. iOS
 * draws them in the system font (SF Pro); Android uses its own ([FontFamily.Default], Roboto), as
 * each platform's "system font" (P1-13).
 */
enum class SystemStyle(val size: TextUnit, val lineHeight: TextUnit) {
    LargeTitle(34.sp, 41.sp),
    Title(28.sp, 34.sp),
    Title2(22.sp, 28.sp),
    Title3(20.sp, 25.sp),
    Headline(17.sp, 22.sp),
    Body(17.sp, 22.sp),
    Callout(16.sp, 21.sp),
    Subheadline(15.sp, 20.sp),
    Footnote(13.sp, 18.sp),
    Caption(12.sp, 16.sp),
    Caption2(11.sp, 13.sp),
}

/** Text styles (iOS `Font.ivDisplay`, `.ivBody`, `.ivHeadline`, `.ivMono`, and the system styles). */
object IvType {
    /**
     * Anton, for headlines. Upper-case the text (or use [DisplayText]). Line height is the font's own
     * (ascent + descent ≈ 1.505 em), as iOS lays it out.
     */
    fun display(size: TextUnit = 34.sp) = TextStyle(fontFamily = IvFonts.display, fontSize = size, lineHeight = size * 1.505)

    /** Barlow, for body text and controls: regular, medium, semibold or bold. Line height 1.2 em (the font's). */
    fun body(size: TextUnit = 17.sp, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = IvFonts.body, fontWeight = bodyWeight(weight), fontSize = size, lineHeight = size * 1.2)

    /**
     * An iOS system text style (`.font(.footnote)`, `.subheadline.weight(.semibold)`, …) in the
     * platform font. [monospaced] is `.monospaced()`, [monospacedDigits] `.monospacedDigit()`.
     */
    fun system(
        style: SystemStyle,
        weight: FontWeight = if (style == SystemStyle.Headline) FontWeight.SemiBold else FontWeight.Normal,
        monospaced: Boolean = false,
        monospacedDigits: Boolean = false,
    ) = TextStyle(
        fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
        fontWeight = weight,
        fontSize = style.size,
        lineHeight = style.lineHeight,
        fontFeatureSettings = if (monospacedDigits) "tnum" else null,
    )

    /** `.footnote`: captions under rows, hints, small print. */
    val footnote: TextStyle get() = system(SystemStyle.Footnote)

    /** `.subheadline`. */
    val subheadline: TextStyle get() = system(SystemStyle.Subheadline)

    /** `.callout`. */
    val callout: TextStyle get() = system(SystemStyle.Callout)

    /** `.caption`. */
    val caption: TextStyle get() = system(SystemStyle.Caption)

    /** `.caption2`. */
    val caption2: TextStyle get() = system(SystemStyle.Caption2)

    /** Barlow semibold 17: section titles, card titles, names. */
    val headline: TextStyle get() = body(17.sp, FontWeight.SemiBold)

    /** JetBrains Mono, for eyebrows, codes and numbers: regular, or medium for anything heavier. */
    fun mono(size: TextUnit = 13.sp, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = IvFonts.mono,
        fontWeight = if (weight <= FontWeight.Normal) FontWeight.Normal else FontWeight.Medium,
        fontSize = size,
        lineHeight = size * 1.32,
    )

    /** An eyebrow: JetBrains Mono 11 medium, uppercase (by the caller), tracked. */
    val eyebrow: TextStyle get() = mono(11.sp, FontWeight.Medium).copy(letterSpacing = 0.11.em)

    private fun bodyWeight(weight: FontWeight) = when {
        weight >= FontWeight.Bold -> FontWeight.Bold
        weight >= FontWeight.SemiBold -> FontWeight.SemiBold
        weight >= FontWeight.Medium -> FontWeight.Medium
        else -> FontWeight.Normal
    }
}

/** A small mono label above a heading ("BUILDING", "SYSTEMS"): uppercase, tracked, muted. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Palette.muted) {
    Text(text.uppercase(), modifier = modifier, style = IvType.eyebrow, color = color)
}

/** A display headline: Anton, uppercase. For headings only, never for body text or catalog data. */
@Composable
fun DisplayText(text: String, modifier: Modifier = Modifier, size: TextUnit = 34.sp, color: Color = Palette.ink) {
    Text(text.uppercase(), modifier = modifier, style = IvType.display(size), color = color)
}
