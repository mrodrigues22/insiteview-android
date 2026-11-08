// iOS 26 "Liquid Glass" chrome, as the iPhone app shows it (insiteview-ios
// docs/app-store-screenshots): a floating capsule tab bar, circular glass buttons and capsule
// button groups in the navigation bar, glass panels over the camera and the 3D model.
//
// Glass blurs what's behind it when that's Compose content (lists, sheets): screens mark their
// content with [glassSource] and provide [LocalGlassState]. Over the camera and the 3D model
// (SurfaceViews, which can't be sampled) it's a tinted translucent surface with a light rim: the
// one documented difference (docs/PLAN.md §6).
package com.getinsiteview.design

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/** The iOS materials the app uses: `.ultraThinMaterial` (most glass) and `.regularMaterial`. */
enum class GlassMaterial(internal val opacity: Float, internal val blur: Dp) {
    UltraThin(0.6f, 24.dp),
    Regular(0.85f, 30.dp),
}

/**
 * The space the floating tab bar takes at the bottom (with the navigation bar under it): content
 * scrolls under the bar, so lists add this to their bottom padding to let the last row clear it.
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

/** Counts taps on the tab that's already selected: tab roots scroll back to the top (iOS). */
val LocalTabReselects = compositionLocalOf { 0 }

/** The blur source for glass on this screen, when its content is Compose (null: tinted glass). */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/** Marks the content glass floats over (a screen's list or scroll content). */
fun Modifier.glassSource(state: HazeState?): Modifier = if (state != null) hazeSource(state) else this

/**
 * A glass background in [shape]: blurs [LocalGlassState]'s content where Android can (12+), else a
 * translucent surface; a hairline rim and a soft shadow, as iOS draws Liquid Glass.
 */
@Composable
fun Modifier.glass(
    shape: Shape = CircleShape,
    material: GlassMaterial = GlassMaterial.UltraThin,
    shadow: Boolean = true,
): Modifier {
    val state = LocalGlassState.current
    val surface = Palette.surface
    val rim = if (LocalIvColors.current.isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.7f)
    val base = (if (shadow) this.shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f)) else this)
        .clip(shape)
    val filled = if (state != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        base.hazeEffect(
            state = state,
            style = HazeStyle(
                backgroundColor = surface,
                tint = HazeTint(surface.copy(alpha = material.opacity * 0.7f)),
                blurRadius = material.blur,
                noiseFactor = 0f,
            ),
        )
    } else {
        base.background(surface.copy(alpha = material.opacity), shape)
    }
    return filled.border(0.5.dp, rim, shape)
}

/**
 * A circular glass button in a navigation bar (iOS 26: the back chevron, close): 44 dp, the icon in
 * [tint]. Dims while pressed.
 */
@Composable
fun IvGlassButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.ink,
    material: GlassMaterial = GlassMaterial.UltraThin,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(44.dp)
            .glass(CircleShape, material)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (!enabled) 0.4f else if (pressed) 0.6f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A capsule of glass holding a navigation bar's trailing buttons (iOS 26 toolbar group). */
@Composable
fun IvGlassButtonGroup(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.UltraThin,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.heightIn(min = 44.dp).glass(RoundedCornerShape(percent = 50), material).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One icon in an [IvGlassButtonGroup]: 44 dp, accent by default (iOS tints toolbar items). */
@Composable
fun IvGlassGroupItem(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = Palette.accent,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (!enabled) 0.4f else if (pressed) 0.6f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A tab of [IvGlassTabBar]. */
data class GlassTab(val title: String, val icon: ImageVector)

/**
 * The iOS 26 tab bar: a floating glass capsule, inset from the screen's edges; the selected tab sits
 * on a grey pill with its icon and title in the accent colour, the others in ink.
 */
@Composable
fun IvGlassTabBar(
    tabs: List<GlassTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(percent = 50), GlassMaterial.Regular)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selected
            val color = if (isSelected) Palette.accent else Palette.ink
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (isSelected) Palette.sunk.copy(alpha = 0.8f) else Color.Transparent)
                    .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(index) }
                    .semantics(mergeDescendants = true) {
                        this.selected = isSelected
                        contentDescription = tab.title
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
                Text(
                    tab.title,
                    style = IvType.system(SystemStyle.Caption2, FontWeight.Medium),
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}
