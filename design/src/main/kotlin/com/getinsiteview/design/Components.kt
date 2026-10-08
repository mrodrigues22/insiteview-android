package com.getinsiteview.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

enum class PrimaryActionVariant { Accent, Ink }

/**
 * The largest action on a screen ("See in AR", "View in AR"): brand red, square, uppercase Barlow
 * semibold; ink while pressed. [PrimaryActionVariant.Ink] is the same button filled with ink.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PrimaryActionVariant = PrimaryActionVariant.Accent,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill: Color
    val foreground: Color
    when (variant) {
        PrimaryActionVariant.Accent -> {
            fill = if (pressed) Palette.ink else Palette.accent
            foreground = if (pressed) Palette.onInk else Palette.onAccent
        }
        PrimaryActionVariant.Ink -> {
            fill = if (pressed) Palette.accent else Palette.ink
            foreground = if (pressed) Palette.onAccent else Palette.onInk
        }
    }
    ActionLabel(
        text = text,
        foreground = foreground,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(fill, RectangleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/** A secondary action ("Explore in 3D", "3D model"): square, outlined in ink, uppercase; red outline while pressed. */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color = if (pressed) Palette.accent else Palette.ink
    ActionLabel(
        text = text,
        foreground = color,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(Palette.surface, RectangleShape)
            .border(1.5.dp, color, RectangleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun ActionLabel(text: String, foreground: Color, modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = IvType.body(13.sp, FontWeight.SemiBold).copy(letterSpacing = 0.06.em),
            color = foreground,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A toggleable chip with a colour dot: system chips, "What do you need?" chips. Square; selected
 * chips are filled with ink. [title] is already localized (catalog names come from the API catalog).
 */
@Composable
fun Chip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
    enabled: Boolean = true,
) {
    val fill = if (isSelected) Palette.ink else Palette.surface
    val border = if (isSelected) Palette.ink else Palette.line
    val surface = Palette.surface
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 36.dp)
            .background(fill, RectangleShape)
            .border(1.dp, border, RectangleShape)
            .selectable(selected = isSelected, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) {
            Box(
                Modifier.size(10.dp)
                    .background(color, CircleShape)
                    .border(1.dp, surface.copy(alpha = if (isSelected) 0.6f else 0f), CircleShape),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            style = IvType.body(15.sp, FontWeight.Medium),
            color = if (isSelected) Palette.onInk else Palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A horizontal, scrolling row of chips. */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A progress bar with a mono caption, for loading a building. */
@Composable
fun LoadingBar(fraction: Double, caption: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0.0, 1.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = Palette.accent,
            trackColor = Palette.sunk,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(caption, style = IvType.mono(12.sp), color = Palette.muted)
    }
}
