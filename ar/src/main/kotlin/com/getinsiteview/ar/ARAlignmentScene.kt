package com.getinsiteview.ar

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import com.getinsiteview.modelkit.ar.GesturePhase
import com.getinsiteview.modelkit.geometry.YawTransform
import com.getinsiteview.scene.SceneEngine
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.rememberMainLightNode
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Shows [view]'s AR session (iOS: `ARAlignmentView` is itself the view): `ARSceneView` on the app's
 * one Filament engine, no plane grid, no depth occlusion (systems draw over the camera), no
 * post-processing, no shadows; one world-origin node holds the building's root, the crosshair and
 * the mark discs. Gestures: tap, one-finger drag, two-finger twist.
 *
 * The screen's camera permission flow runs before this shows (the preflight), so SceneView's own
 * permission overlay is off. Remove any [com.getinsiteview.scene.OrbitViewer] before showing this
 * (docs/PLAN.md §3 "AR").
 */
@Composable
fun ARAlignmentScene(view: ARAlignmentView, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = SceneEngine.engine
    val materialLoader = SceneEngine.materialLoader(context)
    val touch = remember(view, context) { ARTouch(context, view) }
    val cameraStream = rememberARCameraStream(materialLoader)
    SideEffect { cameraStream.isDepthOcclusionEnabled = false }
    val sun = rememberMainLightNode(engine) { isShadowCaster = false }
    ARSceneView(
        modifier = modifier.onSizeChanged { view.viewSizeChanged(it.width, it.height) },
        engine = engine,
        modelLoader = SceneEngine.modelLoader(context),
        materialLoader = materialLoader,
        sessionCameraConfig = { session -> view.chooseCameraConfig(session) },
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
        sessionConfiguration = { session, config -> view.configureNewSession(session, config) },
        planeRenderer = false,
        cameraStream = cameraStream,
        mainLightNode = sun,
        fillLightNode = null,
        onSessionCreated = { session -> view.sessionCreated(session) },
        onSessionResumed = { session -> view.sessionDidResume(session) },
        onSessionPaused = { session -> view.sessionDidPause(session) },
        onSessionFailed = { exception -> view.onSessionFailed?.invoke(exception) },
        onSessionUpdated = { session, frame -> view.sessionUpdated(session, frame) },
        onGestureListener = null,
        onTouchEvent = { event, _ ->
            touch.onTouch(event)
            true
        },
        cameraPermissionOverlay = null,
    ) {
        Node {
            val container = parentNode
            DisposableEffect(container) {
                view.containerAttached(container)
                onDispose { view.containerDetached(container) }
            }
        }
    }
}

/**
 * The AR view's gestures (iOS: a tap, a one-finger pan, a rotation): a tap places or picks; one
 * finger past the touch slop drags; two fingers twist about the point between them.
 */
internal class ARTouch(context: Context, private val view: ARAlignmentView) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!dragging && !twisted) view.handleTap(e.x.toDouble(), e.y.toDouble())
                return true
            }
        },
    )
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var twisting = false
    private var twisted = false
    private var lastAngle = 0.0

    fun onTouch(event: MotionEvent) {
        taps.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                twisting = false
                twisted = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                endDrag(event)
                if (event.pointerCount >= 2) {
                    twisting = true
                    lastAngle = angle(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> twisting = false
            MotionEvent.ACTION_MOVE -> {
                if (twisting && event.pointerCount >= 2) {
                    val angle = angle(event)
                    // Screen y points down, so a growing angle is a clockwise twist (UIKit's sign).
                    val delta = YawTransform.normalized(angle - lastAngle)
                    lastAngle = angle
                    if (delta != 0.0) {
                        twisted = true
                        view.handleTwist(delta, ((event.getX(0) + event.getX(1)) / 2).toDouble(), ((event.getY(0) + event.getY(1)) / 2).toDouble())
                    }
                } else if (event.pointerCount == 1 && !twisted) {
                    if (!dragging && hypot(event.x - downX, event.y - downY) > slop) {
                        dragging = true
                        view.handleDrag(GesturePhase.BEGAN, event.x.toDouble(), event.y.toDouble())
                    } else if (dragging) {
                        view.handleDrag(GesturePhase.CHANGED, event.x.toDouble(), event.y.toDouble())
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endDrag(event)
                twisting = false
            }
            else -> Unit
        }
    }

    private fun endDrag(event: MotionEvent) {
        if (!dragging) return
        dragging = false
        view.handleDrag(GesturePhase.ENDED, event.x.toDouble(), event.y.toDouble())
    }

    private fun angle(event: MotionEvent): Double =
        atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())
}
