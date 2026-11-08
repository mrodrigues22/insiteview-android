package com.getinsiteview.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

/** A labelled stop on a [DetentSlider]. */
data class Detent(val value: Double, val label: String)

/**
 * A slider with labelled detents that snaps on release and ticks (a haptic) as the thumb reaches
 * a detent: the See inside opacity slider (docs/PLAN.md §3, iOS IOS-M2-06). The snapping and
 * crossing rules come from the caller (`:modelkit`'s `SeeInside`), where they're tested.
 *
 * @param reachedDetent whether a move from the first value to the second reached a detent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetentSlider(
    value: Double,
    onValueChange: (Double) -> Unit,
    detents: List<Detent>,
    accessibilityLabel: String,
    snapped: (Double) -> Double,
    reachedDetent: (Double, Double) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // The newest value, also between a drag's last onValueChange and onValueChangeFinished,
    // which can come before the caller's new value is recomposed in.
    val current = remember { DoubleArray(1) { value } }
    SideEffect { current[0] = value }
    val nearest = detents.minByOrNull { abs(it.value - value) }?.label.orEmpty()
    // iOS `withAnimation(.snappy)`: release and label taps glide to the detent.
    fun glide(to: Double) {
        val from = current[0]
        current[0] = to
        scope.launch {
            Animatable(from.toFloat()).animateTo(to.toFloat(), spring(dampingRatio = 0.85f, stiffness = 600f)) {
                onValueChange(this.value.toDouble())
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Slider(
            value = value.toFloat(),
            onValueChange = { new ->
                if (reachedDetent(current[0], new.toDouble())) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                current[0] = new.toDouble()
                onValueChange(new.toDouble())
            },
            onValueChangeFinished = {
                val target = snapped(current[0])
                if (target != current[0]) {
                    glide(target)
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
            },
            // iOS's slider: a thin rounded track, a round white thumb with a soft shadow.
            thumb = {
                Box(
                    Modifier
                        .size(28.dp)
                        .shadow(3.dp, CircleShape)
                        .background(Color.White, CircleShape),
                )
            },
            track = { state ->
                val fraction = (state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.sunk)) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(Palette.accent))
                }
            },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = accessibilityLabel
                stateDescription = nearest
                // VoiceOver swipes move between detents (iOS accessibilityAdjustableAction): TalkBack's
                // adjust gestures too.
                setProgress { target ->
                    val values = detents.map { it.value }.sorted()
                    val now = current[0]
                    val next = if (target > now) values.firstOrNull { it > now + 0.001 } else values.lastOrNull { it < now - 0.001 }
                    if (next != null) {
                        current[0] = next
                        onValueChange(next)
                    }
                    next != null
                }
            },
        )
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            detents.forEachIndexed { index, detent ->
                val selected = abs(detent.value - value) < 0.01
                Text(
                    detent.label,
                    style = IvType.system(SystemStyle.Caption2, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (selected) Palette.ink else Palette.muted,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        detents.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f).clickable(interactionSource = null, indication = null) {
                        glide(detent.value)
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    },
                )
            }
        }
    }
}
