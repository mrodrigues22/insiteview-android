package com.getinsiteview.features.guest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.getinsiteview.design.Palette
import com.getinsiteview.features.building.BuildingSession

/**
 * Covers a building screen with the guest state when access ends mid-session (a link revoked or
 * expired, the building paused or taken offline, access removed; IOS-M2-02, M4-02). Every
 * `/v1/visit/…` call re-checks access, so this can happen on any screen. AR and the object card
 * dismiss themselves instead, back to a screen with the gate.
 *
 * iOS's `.accessGate(session)` modifier: wrap the screen's content.
 */
@Composable
fun AccessGate(session: BuildingSession, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        content()
        val problem = session.accessProblem
        AnimatedVisibility(visible = problem != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Palette.background)
                    // Swallow touches meant for the screen underneath.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                if (problem != null) ProblemView(problem)
            }
        }
    }
}
