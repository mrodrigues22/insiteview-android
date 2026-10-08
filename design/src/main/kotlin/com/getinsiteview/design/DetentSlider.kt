package com.getinsiteview.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** A labelled stop on a [DetentSlider]. */
data class Detent(val value: Double, val label: String)

/**
 * A slider with labelled detents that snaps on release and ticks (a haptic) as the thumb reaches
 * a detent: the See inside opacity slider (docs/PLAN.md §3, iOS IOS-M2-06). The snapping and
 * crossing rules come from the caller (`:modelkit`'s `SeeInside`), where they're tested.
 *
 * @param reachedDetent whether a move from the first value to the second reached a detent.
 */
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
    // The newest value, also between a drag's last onValueChange and onValueChangeFinished,
    // which can come before the caller's new value is recomposed in.
    val current = remember { DoubleArray(1) { value } }
    SideEffect { current[0] = value }
    val nearest = detents.minByOrNull { abs(it.value - value) }?.label.orEmpty()
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
                    current[0] = target
                    onValueChange(target)
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = Palette.accent,
                activeTrackColor = Palette.accent,
                inactiveTrackColor = Palette.sunk,
            ),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = accessibilityLabel
                stateDescription = nearest
            },
        )
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            detents.forEachIndexed { index, detent ->
                val selected = abs(detent.value - value) < 0.01
                Text(
                    detent.label,
                    style = IvType.body(11.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (selected) Palette.ink else Palette.muted,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        detents.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f).clickable {
                        current[0] = detent.value
                        onValueChange(detent.value)
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    },
                )
            }
        }
    }
}
