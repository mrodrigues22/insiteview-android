package com.getinsiteview.features.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.scene.BuildingScene
import com.getinsiteview.scene.OrbitViewer
import com.getinsiteview.scene.OrbitViewerView

/**
 * Compose wrapper of `:scene`'s orbit viewer (iOS `ModelViewer`, a `UIViewRepresentable` of
 * `OrbitViewerView`). Shows the session's scene while this view holds the scene claim (the landing
 * preview and the 3D viewer take turns).
 *
 * @param isOwner whether this view holds the scene's root right now.
 * @param revision changes when chunks arrive: the first content fits the camera.
 * @param fitRequest changes when the user taps "Fit".
 * @param focusRequest changes when the camera should frame [focusBounds] (a room was chosen).
 * @param onTap the element under a tap (`null` for empty space).
 */
@Composable
fun ModelViewer(
    scene: BuildingScene,
    isOwner: Boolean,
    revision: Int,
    fitRequest: Int,
    modifier: Modifier = Modifier,
    focusRequest: Int = 0,
    focusBounds: () -> Bounds? = { null },
    isInteractive: Boolean = true,
    onTap: (String?) -> Unit = {},
) {
    val state = remember { OrbitViewerView() }
    val currentOnTap by rememberUpdatedState(onTap)
    // iOS's coordinator: the requests already handled.
    var handledFit by remember { mutableIntStateOf(fitRequest) }
    var handledFocus by remember { mutableIntStateOf(focusRequest) }
    val currentFocusBounds by rememberUpdatedState(focusBounds)

    state.isInteractive = isInteractive
    state.onTap = { node -> currentOnTap(scene.elementID(node)) }
    // The root moves into this viewer while it holds the scene claim; the other one (landing
    // preview or 3D viewer) shows an empty scene meanwhile, as on iOS.
    LaunchedEffect(isOwner, scene) {
        if (isOwner) state.attach(scene)
    }
    OrbitViewer(view = state, modifier = modifier)

    LaunchedEffect(isOwner, revision, fitRequest, focusRequest) {
        if (!isOwner) return@LaunchedEffect
        if (handledFit != fitRequest) {
            handledFit = fitRequest
            state.fit(scene.visibleBounds())
        } else if (handledFocus != focusRequest) {
            handledFocus = focusRequest
            state.fit(currentFocusBounds() ?: scene.visibleBounds())
        } else if (!state.hasFitted) {
            // Runs again whenever `revision` changes: the first content fits the camera. Bounds
            // walk the whole scene, so they're only computed until then. A room chosen before the
            // model loaded is framed as soon as it's there.
            state.fitIfNeeded(currentFocusBounds() ?: scene.visibleBounds())
        }
    }
}
