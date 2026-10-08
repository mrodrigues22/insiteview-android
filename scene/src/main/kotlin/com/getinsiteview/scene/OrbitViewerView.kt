package com.getinsiteview.scene

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.OrbitCamera
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.scene.ScenePicking
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.DefaultCameraNode
import io.github.sceneview.SceneView
import io.github.sceneview.createEnvironment
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.node.CameraNode
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import io.github.sceneview.node.Node as SceneNode

/**
 * The 3D viewer (iOS `OrbitViewerView`): a virtual camera driven by [OrbitCamera] (`:modelkit`,
 * tested on the JVM). One finger orbits, two fingers pan, pinch zooms, a tap picks an element.
 * Show it with the [OrbitViewer] composable; this object keeps the camera, so the orbit pose
 * survives the composable leaving (AR, a navigation push).
 *
 * Android has no counterpart to iOS's `OrbitViewers` (a living `.nonAR` ARView blacks out the AR
 * camera feed): there is one Filament engine, and the screen that opens AR removes its
 * [OrbitViewer] first (docs/PLAN.md §3 "AR"), which hands the building's root over.
 */
class OrbitViewerView {
    var camera: OrbitCamera = OrbitCamera(target = Vec3.zero, distance = 10.0)
        private set

    /** Whether the camera has framed content yet. */
    var hasFitted: Boolean = false
        private set

    /** Called with the element node under a tap (`null` for empty space); `BuildingScene.elementID` names it. */
    var onTap: ((SceneNode?) -> Unit)? = null

    /** A preview on the guest landing isn't interactive. */
    var isInteractive: Boolean = true

    private var scene: BuildingScene? = null
    private var container: SceneNode? = null
    private var cameraNode: CameraNode? = null
    private var width = 0
    private var height = 0

    /** What the camera shows now (mid-animation, between poses). */
    private var shownEye: Vec3? = null
    private var shownTarget: Vec3? = null
    private var animation: Animation? = null

    private class Animation(val fromEye: Vec3, val fromTarget: Vec3, val toEye: Vec3, val toTarget: Vec3, val start: Long)

    /**
     * Moves the building's root into this view, at the origin (the model's own frame). Always shown:
     * AR hides it until placed, and the viewer can take it back before AR's own detach.
     */
    fun attach(scene: BuildingScene) {
        this.scene = scene
        scene.root.isVisible = true
        container?.let { adopt(it, scene) }
    }

    private fun adopt(container: SceneNode, scene: BuildingScene) {
        val root = scene.root
        root.isVisible = true
        if (root.parent === container) return
        root.parent = null
        root.transform = Mat4()
        container.addChildNode(root)
    }

    /** Frames [bounds] (model coordinates). The first fit also resets the view angle. */
    fun fit(bounds: Bounds?, animated: Boolean = true) {
        if (bounds == null) return
        if (hasFitted) {
            camera.refit(bounds, verticalFieldOfView = fieldOfView, aspectRatio = aspectRatio)
        } else {
            camera = OrbitCamera.fitting(bounds, verticalFieldOfView = fieldOfView, aspectRatio = aspectRatio)
            hasFitted = true
        }
        updateCamera(animated = animated)
    }

    /** Fits once, when the first content arrives. */
    fun fitIfNeeded(bounds: Bounds?) {
        if (hasFitted) return
        fit(bounds, animated = false)
    }

    private val fieldOfView: Double get() = FIELD_OF_VIEW_DEGREES * PI / 180

    private val aspectRatio: Double get() = if (height > 0) width.toDouble() / height else 0.5

    private fun updateCamera(animated: Boolean = false) {
        val eye = camera.position
        val target = camera.target
        val fromEye = shownEye
        val fromTarget = shownTarget
        if (!animated || fromEye == null || fromTarget == null) {
            animation = null
            show(eye, target)
            return
        }
        animation = Animation(fromEye, fromTarget, eye, target, System.nanoTime())
        onFrame()
    }

    private fun show(eye: Vec3, target: Vec3) {
        shownEye = eye
        shownTarget = target
        val node = cameraNode ?: return
        val forward = target - eye
        // Up: the orbit camera's, which stays perpendicular to the line of sight.
        val up = camera.up
        if (forward.x * forward.x + forward.y * forward.y + forward.z * forward.z < 1e-12) return
        node.lookAt(eye = eye.float3, center = target.float3, up = up.float3)
    }

    /** Each presented frame: steps a fit's animation (0.35 s, ease in and out). */
    internal fun onFrame() {
        val animation = animation ?: return
        val t = ((System.nanoTime() - animation.start) / 1e9 / ANIMATION_DURATION).coerceIn(0.0, 1.0)
        val eased = if (t < 0.5) 2 * t * t else 1 - (-2 * t + 2).pow(2) / 2
        show(
            animation.fromEye + (animation.toEye - animation.fromEye) * eased,
            animation.fromTarget + (animation.toTarget - animation.fromTarget) * eased,
        )
        if (t >= 1.0) this.animation = null
    }

    internal fun sizeChanged(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    internal fun cameraAttached(node: CameraNode) {
        cameraNode = node
        show(shownEye ?: camera.position, shownTarget ?: camera.target)
    }

    internal fun cameraDetached(node: CameraNode) {
        if (cameraNode === node) cameraNode = null
    }

    internal fun containerAttached(node: SceneNode) {
        container = node
        scene?.let { adopt(node, it) }
    }

    /** The composable is going: the root leaves before its container is destroyed (which would destroy it too). */
    internal fun containerDetached(node: SceneNode) {
        if (container === node) container = null
        val root = scene?.root ?: return
        if (root.parent === node) node.removeChildNode(root)
    }

    // region Gestures

    /** One finger: a full-width drag turns half a circle; a full-height drag a quarter. */
    internal fun orbited(dx: Float, dy: Float) {
        val w = maxOf(width, 1).toDouble()
        val h = maxOf(height, 1).toDouble()
        camera.orbit(dx = dx / w * PI, dy = dy / h * PI / 2)
        updateCamera()
    }

    /** Two fingers: the model follows them. */
    internal fun panned(dx: Float, dy: Float) {
        val h = maxOf(height, 1).toDouble()
        camera.pan(dx = dx / h, dy = dy / h, fieldOfView = fieldOfView)
        updateCamera()
    }

    internal fun pinched(scale: Float) {
        camera.zoom(scale = scale.toDouble())
        updateCamera()
    }

    internal fun tapped(x: Float, y: Float) {
        val tap = onTap ?: return
        val scene = scene
        val ray = ScenePicking.orbitRay(camera, fieldOfView, x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
        if (scene == null || ray == null) {
            tap(null)
            return
        }
        tap(scene.pick(ray.first, ray.second)?.let { scene.element(it) })
    }

    // endregion

    companion object {
        /** Vertical field of view. */
        const val FIELD_OF_VIEW_DEGREES = 55.0
        const val NEAR = 0.05
        const val FAR = 2000.0

        /** A fit's camera move, seconds. */
        const val ANIMATION_DURATION = 0.35

        /** `--bg` from the blueprint tokens (sRGB): light #F3F5F4, dark #0E1413. */
        val BACKGROUND_LIGHT = floatArrayOf(0xF3 / 255f, 0xF5 / 255f, 0xF4 / 255f)
        val BACKGROUND_DARK = floatArrayOf(0x0E / 255f, 0x14 / 255f, 0x13 / 255f)

        /** The sun, from (3, 8, 5) towards the origin (iOS's directional light). */
        val LIGHT_DIRECTION: Float3 = normalize(Float3(-3f, -8f, -5f))

        internal fun environment(engine: Engine, loader: EnvironmentLoader, dark: Boolean): Environment {
            // The neutral IBL SceneView ships, under a skybox of the background colour.
            val base = createEnvironment(loader)
            base.skybox?.let { engine.destroySkybox(it) }
            val srgb = if (dark) BACKGROUND_DARK else BACKGROUND_LIGHT
            val linear = FloatArray(4) { if (it == 3) 1f else toLinear(srgb[it]) }
            return base.copy(skybox = Skybox.Builder().color(linear).build(engine))
        }

        private fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
}

private val Vec3.float3: Float3 get() = Float3(x.toFloat(), y.toFloat(), z.toFloat())

/** A camera with the viewer's vertical field of view, kept through resizes. */
internal class OrbitCameraNode(engine: Engine) : CameraNode(engine) {
    init {
        setExposure(DefaultCameraNode.DEFAULT_APERTURE, DefaultCameraNode.DEFAULT_SHUTTER_SPEED, DefaultCameraNode.DEFAULT_ISO)
    }

    override fun updateProjection(focalLength: Double, near: Float, far: Float, aspect: Double) {
        setProjection(
            fovInDegrees = OrbitViewerView.FIELD_OF_VIEW_DEGREES,
            aspect = if (aspect > 0 && aspect.isFinite()) aspect else 1.0,
            near = OrbitViewerView.NEAR,
            far = OrbitViewerView.FAR,
            direction = Camera.Fov.VERTICAL,
        )
    }
}

/**
 * One finger orbits (after the touch slop), two fingers pan and pinch together, a tap picks
 * (iOS: pan with one touch, pan with two, pinch, tap).
 */
internal class OrbitTouch(context: Context, private val view: OrbitViewerView) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!moved) view.tapped(e.x, e.y)
                return true
            }
        },
    )
    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                view.pinched(detector.scaleFactor)
                return true
            }
        },
    )
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    fun onTouch(event: MotionEvent) {
        if (!view.isInteractive) return
        pinch.onTouchEvent(event)
        taps.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // The centroid jumps when a finger comes or goes: start from the new one.
                val (x, y) = centroid(event, excluding = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
                lastX = x
                lastY = y
                moved = true
            }
            MotionEvent.ACTION_MOVE -> {
                val (x, y) = centroid(event, excluding = -1)
                val dx = x - lastX
                val dy = y - lastY
                if (event.pointerCount == 1) {
                    if (!moved && hypot(x - downX, y - downY) > slop) moved = true
                    if (moved) view.orbited(dx, dy)
                } else {
                    view.panned(dx, dy)
                }
                lastX = x
                lastY = y
            }
            else -> Unit
        }
    }

    private fun centroid(event: MotionEvent, excluding: Int): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excluding) continue
            x += event.getX(i)
            y += event.getY(i)
            count += 1
        }
        return if (count == 0) event.x to event.y else x / count to y / count
    }
}

/**
 * Shows [view]'s camera over the building it was given ([OrbitViewerView.attach]): the background
 * colour, SceneView's neutral light probe and a sun from above (iOS: RealityKit's default lighting
 * plus a directional light).
 */
@Composable
fun OrbitViewer(view: OrbitViewerView, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = SceneEngine.engine
    val dark = isSystemInDarkTheme()
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment = rememberEnvironment(environmentLoader, isOpaque = true, key = dark) {
        OrbitViewerView.environment(engine, environmentLoader, dark)
    }
    val cameraNode = remember(engine) { OrbitCameraNode(engine) }
    DisposableEffect(cameraNode) {
        view.cameraAttached(cameraNode)
        onDispose {
            view.cameraDetached(cameraNode)
            cameraNode.destroy()
        }
    }
    val sun = rememberMainLightNode(engine) {
        lightDirection = OrbitViewerView.LIGHT_DIRECTION
        isShadowCaster = false
    }
    val touch = remember(view, context) { OrbitTouch(context, view) }
    SceneView(
        modifier = modifier.onSizeChanged { view.sizeChanged(it.width, it.height) },
        engine = engine,
        modelLoader = SceneEngine.modelLoader(context),
        materialLoader = SceneEngine.materialLoader(context),
        environmentLoader = environmentLoader,
        autoCenterContent = false,
        autoFitContent = false,
        environment = environment,
        mainLightNode = sun,
        fillLightNode = null,
        cameraNode = cameraNode,
        cameraManipulator = null,
        onGestureListener = null,
        onTouchEvent = { event, _ ->
            touch.onTouch(event)
            true
        },
        onFrame = { view.onFrame() },
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
