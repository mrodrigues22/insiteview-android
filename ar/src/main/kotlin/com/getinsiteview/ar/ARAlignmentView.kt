package com.getinsiteview.ar

// IVAR's port: the ARCore session, plate anchoring, alignment by reference points and plate
// registration on site, manual alignment, relocalization and thermal handling (docs/PLAN.md §3
// "AR"). Every rule is in `:modelkit`'s `ARAlignmentLogic` (tested on the JVM); this file only
// feeds it ARCore's frames and poses and applies what it decides to the scene.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.PowerManager
import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.ar.ARAlignmentLogic
import com.getinsiteview.modelkit.ar.ARSessionSettings
import com.getinsiteview.modelkit.ar.AnchorChange
import com.getinsiteview.modelkit.ar.CameraTrackingFailure
import com.getinsiteview.modelkit.ar.CameraTrackingState
import com.getinsiteview.modelkit.ar.CoolCameraFormat
import com.getinsiteview.modelkit.ar.CrosshairTargeting
import com.getinsiteview.modelkit.ar.FloorAim
import com.getinsiteview.modelkit.ar.FloorPlanes
import com.getinsiteview.modelkit.ar.FrameSample
import com.getinsiteview.modelkit.ar.PlateImageName
import com.getinsiteview.modelkit.ar.Ray
import com.getinsiteview.modelkit.ar.ScreenGeometry
import com.getinsiteview.modelkit.ar.TrackingStatus
import com.getinsiteview.modelkit.ar.fromAnchorTransform
import com.getinsiteview.modelkit.ar.matrix
import com.getinsiteview.modelkit.ar.quaternion
import com.getinsiteview.modelkit.geometry.AlignmentSite
import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.LidarAim
import com.getinsiteview.modelkit.geometry.PlateFrame
import com.getinsiteview.modelkit.geometry.Vec2
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.YawTransform
import com.getinsiteview.scene.BuildingScene
import com.getinsiteview.scene.SceneEngine
import com.google.android.filament.MaterialInstance
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.arcore.ARSession
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The AR view (iOS `ARAlignmentView`): world tracking, plate detection and the 4-DoF alignment,
 * with alignment by reference points and manual placement on the floor as the fallbacks. Systems
 * show over the camera without occlusion. Show it with the [ARAlignmentScene] composable; this
 * object keeps the state (the iOS view's), so the screen's model holds it as iOS's holds the view.
 *
 * Everything public comes from [ARAlignmentLogic] (state, callbacks, marking, fixing, gestures) or
 * is declared here with the iOS names: [attach], [detach], [run], [pause], [watchPlate],
 * [stopWatchingPlate], [onTap], [hasLiDAR]. Main thread only.
 */
class ARAlignmentView(context: Context) : ARAlignmentLogic() {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Called with the element node under a tap once the model is placed; `BuildingScene.elementID` names it. */
    var onTap: ((Node?) -> Unit)? = null

    /** ARCore couldn't start or failed (Android only: missing Google Play Services for AR, camera…). */
    var onSessionFailed: ((Exception) -> Unit)? = null

    private var scene: BuildingScene? = null

    /** The world-origin node in the AR scene (iOS `worldAnchor`), while the composable shows. */
    private var container: Node? = null

    private var session: Session? = null

    /**
     * The session while it may be called: SceneView closes it when the AR scene goes, and a call on
     * a closed ARCore session kills the process.
     */
    private val liveSession: Session?
        get() = session?.takeUnless { (it as? ARSession)?.isClosed == true }
    private var latestFrame: Frame? = null
    private var lastFrameTimestamp = Long.MIN_VALUE
    private var width = 0
    private var height = 0
    private var lastView: FloatArray? = null
    private var lastProjection: FloatArray? = null

    /** Downloaded reference images by name (`plate-N`, `register-N`), with their printed width in metres. */
    private val images = HashMap<String, Pair<Bitmap, Float>>()
    private var databaseNames: List<String>? = null
    private var forgetRequested = false
    private var pendingSettings: ARSessionSettings? = null
    private var depthEnabled = false

    private val anchors = LinkedHashMap<UUID, Anchor>()
    private val anchorPoses = HashMap<UUID, YawTransform>()
    private var reportedTracking: TrackingStatus? = null
    private var relocalizing = false
    private var paused = false

    /** A `PowerManager.OnThermalStatusChangedListener` (API 29+), kept untyped so older APIs never load the class. */
    private var thermalListener: Any? = null

    private var crosshairWanted = false
    private var crosshairNode: Node? = null
    private var crosshairAim: FloorAim? = null
    private val discData = ArrayList<Pair<Vec3, Vec3?>>()
    private val discNodes = ArrayList<Node>()

    init {
        hasLiDAR(appContext)
    }

    override val hasDepthSensor: Boolean get() = hasLiDAR

    // region Session

    /**
     * Starts world tracking (the floor; plates when there are any). The thermal state is read now,
     * not only on changes (docs/PLAN.md §3 "AR": iOS treats a hot phone as cool until it changes).
     * The screen stays on while the AR scene shows (`ARSceneView` holds a keep-screen-on lease).
     */
    override fun run() {
        startThermalUpdates()
        super.run()
    }

    override fun pause() {
        super.pause()
        stopThermalUpdates()
    }

    override fun configureSession(settings: ARSessionSettings) {
        pendingSettings = settings
        val session = liveSession ?: return
        val config = session.config
        applySettings(settings, session, config)
        try {
            session.configure(config)
        } catch (e: Exception) {
            onSessionFailed?.invoke(e)
        }
    }

    /** Settings onto a config: planes, light estimation, the image database (rebuilt when it changes). */
    private fun applySettings(settings: ARSessionSettings, session: Session, config: Config) {
        config.planeFindingMode = if (settings.findsVerticalPlanes) Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL else Config.PlaneFindingMode.HORIZONTAL
        config.lightEstimationMode = if (settings.estimatesLight) Config.LightEstimationMode.ENVIRONMENTAL_HDR else Config.LightEstimationMode.DISABLED
        config.depthMode = if (depthEnabled) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        val names = settings.imageNames.filter { images.containsKey(it) }
        if (names != databaseNames || forgetRequested) {
            val database = AugmentedImageDatabase(session)
            for (name in names) {
                val (bitmap, width) = images[name] ?: continue
                try {
                    database.addImage(name, bitmap, width)
                } catch (e: Exception) {
                    // Not enough features (a blank image): it can't be detected.
                }
            }
            config.augmentedImageDatabase = database
            databaseNames = names
            forgetRequested = false
        }
    }

    /**
     * ARCore keeps tracking an image it found; a rebuilt image database starts every image over (iOS
     * removes the image anchors so they're reported again).
     */
    override fun forgetImageDetections() {
        forgetRequested = true
    }

    /** The camera format: ARCore's default, or when hot the smallest at 30 fps or less (iOS `coolVideoFormat`). */
    internal fun chooseCameraConfig(session: Session): CameraConfig {
        thermalStateChanged(isHot())
        if (!thermalLimited) return session.cameraConfig
        return runCatching {
            val configs = session.getSupportedCameraConfigs(CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.BACK))
            CoolCameraFormat.choose(configs, maxFps = { it.fpsRange.upper }, pixels = { it.imageSize.width.toLong() * it.imageSize.height })
        }.getOrNull() ?: session.cameraConfig
    }

    /**
     * The new session's configuration, before it first runs: depth on phones with a time-of-flight
     * sensor (wall and corner marks; docs/PLAN.md §3 "AR"), no occlusion, the current settings.
     */
    internal fun configureNewSession(session: Session, config: Config) {
        val toF = hasDepthCamera(appContext)
        hasLiDAR = toF && session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        confirmedBySession = true
        depthEnabled = hasLiDAR && session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        databaseNames = null
        applySettings(pendingSettings ?: settings, session, config)
    }

    internal fun sessionCreated(session: Session) {
        if (this.session !== session) {
            // A new session: anchors of an old one are gone with it.
            anchors.clear()
            anchorPoses.clear()
            siteAnchorsLost()
        }
        this.session = session
        if (isRunning) {
            configureSession(pendingSettings ?: settings)
            reconcileSiteAnchors()
        }
    }

    internal fun sessionDidPause(session: Session) {
        if (!isRunning || paused) return
        paused = true
        reportedTracking = null
        sessionInterrupted()
    }

    internal fun sessionDidResume(session: Session) {
        this.session = session
        if (!paused) return
        paused = false
        relocalizing = true
        reportedTracking = null
        sessionResumed()
    }

    internal fun viewSizeChanged(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    /**
     * One ARCore frame, on the render loop (the main thread): reduced to values while it's valid
     * (tracking, planes, images, site anchors, the camera), then handed to the logic.
     */
    internal fun sessionUpdated(session: Session, frame: Frame) {
        this.session = session
        latestFrame = frame
        if (frame.timestamp == lastFrameTimestamp) return // the same camera image again
        lastFrameTimestamp = frame.timestamp
        val camera = frame.camera

        val state = camera.trackingState.mapped
        if (state == CameraTrackingState.TRACKING) relocalizing = false
        val status = TrackingStatus.of(state, camera.trackingFailureReason.mapped, relocalizing)
        if (status != reportedTracking) {
            reportedTracking = status
            trackingChanged(status)
        }

        val view = FloatArray(16)
        camera.getViewMatrix(view, 0)
        val projection = FloatArray(16)
        camera.getProjectionMatrix(projection, 0, 0.01f, 1000f)
        lastView = view
        lastProjection = projection

        val change = anchorChange(frame)
        if (!change.isEmpty) anchorsChanged(change)

        val pose = camera.displayOrientedPose
        val z = pose.zAxis
        frameUpdated(
            FrameSample(
                timestamp = clock(),
                cameraPosition = Vec3(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble()),
                cameraForward = Vec3(-z[0].toDouble(), -z[1].toDouble(), -z[2].toDouble()),
                viewMatrix = view,
                projectionMatrix = projection,
                focal = if (width > 0 && height > 0) Vec2(projection[0].toDouble(), projection[5].toDouble()) else null,
            ),
        )
    }

    /** iOS `AnchorChange(anchors:)`: horizontal planes, fully tracked images, moved site anchors, removals. */
    private fun anchorChange(frame: Frame): AnchorChange {
        var hasHorizontalPlane = false
        val floors = ArrayList<AnchorChange.Floor>()
        val removed = ArrayList<Any>()
        for (plane in frame.getUpdatedTrackables(Plane::class.java)) {
            if (plane.subsumedBy != null || plane.trackingState == TrackingState.STOPPED) {
                removed += plane
                continue
            }
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            hasHorizontalPlane = true
            val area = plane.extentX.toDouble() * plane.extentZ.toDouble()
            FloorPlanes.candidate(height = plane.centerPose.ty().toDouble(), area = area)?.let { floors += AnchorChange.Floor(plane, it) }
        }
        val detected = ArrayList<AnchorChange.Image>()
        for (image in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            // Only full tracking feeds the smoother: a last known pose would poison its window.
            if (image.trackingState != TrackingState.TRACKING || image.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) continue
            val name = image.name ?: continue
            val matrix = FloatArray(16)
            image.centerPose.toMatrix(matrix, 0)
            detected += AnchorChange.Image(name, PlateFrame.fromAnchorTransform(matrix))
        }
        val sites = ArrayList<AnchorChange.Site>()
        for ((id, anchor) in anchors) {
            if (anchor.trackingState != TrackingState.TRACKING) continue
            val matrix = FloatArray(16)
            anchor.pose.toMatrix(matrix, 0)
            val pose = YawTransform.fromAnchorTransform(matrix)
            if (anchorPoses[id] != pose) {
                anchorPoses[id] = pose
                sites += AnchorChange.Site(id, pose)
            }
        }
        return AnchorChange(hasHorizontalPlane = hasHorizontalPlane, floors = floors, images = detected, sites = sites, removed = removed)
    }

    // endregion

    // region Reference images

    /** Downloads each placed plate's `image.png` (the QR with its quiet zone) at its printed width. */
    override fun loadReferenceImages(plates: List<Manifest.Plate>) {
        for (plate in plates) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { downloadBitmap(plate.imageUrl) } ?: return@launch
                images[PlateImageName.plate(plate.number)] = bitmap to plate.physicalWidth.toFloat()
                referenceImageArrived(plate.number)
            }
        }
    }

    /**
     * Registering a plate on site: detection of that plate's image, reported through
     * `onRegistrationDetection` instead of aligning the building.
     */
    fun watchPlate(number: Int, imageURL: URI, physicalWidth: Double) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { downloadBitmap(imageURL) } ?: return@launch
            val name = PlateImageName.register(number)
            images[name] = bitmap to physicalWidth.toFloat()
            setRegistrationImage(name)
        }
    }

    fun stopWatchingPlate() {
        setRegistrationImage(null)
    }

    // endregion

    // region Scene

    /**
     * Moves the building's root into the AR world, hidden until it's aligned. An alignment belongs
     * to one AR session (its world origin), so every session starts over.
     */
    fun attach(scene: BuildingScene) {
        this.scene = scene
        val root = scene.root
        root.parent = null
        root.isVisible = false
        container?.addChildNode(root)
        rootAttached()
    }

    /** Hands the root back (the 3D viewer re-attaches it) and shows it again. */
    fun detach() {
        rootDetaching()
        val root = scene?.root ?: return
        val container = container
        // The AR scene may have gone first (Compose disposes it before the screen's own effect):
        // `containerDetached` already took the root off. Only a root another view has taken since
        // is left alone.
        val parent = root.parent
        if (parent != null && parent !== container) return
        if (parent != null) root.parent = null
        root.transform = Mat4()
        root.isVisible = true
    }

    override val hasScene: Boolean get() = scene != null

    override fun applyRootTransform(transform: YawTransform) {
        val root = scene?.root ?: return
        root.transform = transform.mat4
        root.isVisible = true
    }

    override fun setRootEnabled(enabled: Boolean) {
        scene?.root?.isVisible = enabled
    }

    override val isRootEnabled: Boolean get() = scene?.root?.isVisible == true

    override fun showLocateMarker(bounds: Bounds?) {
        scene?.showLocateMarker(bounds)
    }

    override fun setLocateMarkerScale(scale: Double) {
        scene?.setLocateMarkerScale(scale.toFloat())
    }

    override fun updateSceneProximity(camera: Vec3?) {
        scene?.updateProximity(camera)
    }

    override fun tappedModel(x: Double, y: Double) {
        val tap = onTap ?: return
        val scene = scene
        val ray = screenRay(x, y)
        if (scene == null || ray == null) {
            tap(null)
            return
        }
        tap(scene.pick(ray.origin, ray.direction)?.let { scene.element(it) })
    }

    /** The composable's world-origin node arrived: the root, the crosshair and the discs go under it. */
    internal fun containerAttached(node: Node) {
        container = node
        scene?.root?.let { root ->
            if (root.parent !== node) {
                root.parent = null
                node.addChildNode(root)
            }
        }
        if (crosshairWanted) {
            showCrosshairMarker()
            placeCrosshairMarker(crosshairAim)
        }
        for ((centre, normal) in discData) discNodes += makeDisc(node, centre, normal)
    }

    /** The composable is going: what's under its node leaves (the root) or goes with it (markers). */
    internal fun containerDetached(node: Node) {
        if (container !== node) return
        scene?.root?.let { root -> if (root.parent === node) node.removeChildNode(root) }
        container = null
        crosshairNode = null
        discNodes.clear()
        // The session closes with the AR scene: nothing of it may be called after this.
        latestFrame = null
        lastView = null
        lastProjection = null
        session = null
        anchors.clear()
        anchorPoses.clear()
        siteAnchorsLost()
    }

    /** Stops the image downloads and the thermal updates (the screen's model is going). */
    fun release() {
        stopThermalUpdates()
        scope.cancel()
        if (liveSession != null) for (anchor in anchors.values) anchor.detach()
        anchors.clear()
    }

    // endregion

    // region Raycasts

    override val viewSize: Vec2 get() = Vec2(width.toDouble(), height.toDouble())

    override fun screenRay(x: Double, y: Double): Ray? {
        val view = lastView ?: return null
        val projection = lastProjection ?: return null
        return ScreenGeometry.ray(x, y, width.toDouble(), height.toDouble(), view, projection)
    }

    override fun project(point: Vec3): Vec2? {
        val view = lastView ?: return null
        val projection = lastProjection ?: return null
        return ScreenGeometry.project(point, width.toDouble(), height.toDouble(), view, projection)
    }

    /**
     * Raycast #1 (iOS `existingPlaneGeometry` then `estimatedPlane`, horizontal): an upward plane
     * within its outline, then one beyond it, then a depth point facing up.
     */
    override fun floorHit(x: Double, y: Double): Vec3? {
        val hits = hitTest(x, y)
        val inside = hits.firstOrNull { hit -> (hit.trackable as? Plane)?.let { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.isPoseInPolygon(hit.hitPose) } == true }
        val beyond = hits.firstOrNull { hit -> (hit.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val depth = hits.firstOrNull { hit -> hit.trackable is DepthPoint && hit.hitPose.yAxis[1] >= UP_DOT }
        val hit = inside ?: beyond ?: depth ?: return null
        val pose = hit.hitPose
        return Vec3(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble())
    }

    /**
     * Raycast #2 (iOS `existingPlaneGeometry` then `estimatedPlane`, vertical): wall planes within
     * their outline first, then the depth sensor's points. ARCore has no door or window planes.
     */
    override fun wallHit(x: Double, y: Double, eye: Vec3): LidarAim.Plane? {
        val hits = hitTest(x, y)
        val planes = hits.filter { hit -> (hit.trackable as? Plane)?.let { it.type == Plane.Type.VERTICAL && it.isPoseInPolygon(hit.hitPose) } == true }
        val depth = hits.filter { it.trackable is DepthPoint }
        return CrosshairTargeting.firstWall((planes + depth).map { hit -> hit.hitPose.point to hit.hitPose.normal }, eye)
    }

    private fun hitTest(x: Double, y: Double): List<HitResult> {
        if (liveSession == null) return emptyList()
        val frame = latestFrame ?: return emptyList()
        if (frame.camera.trackingState != TrackingState.TRACKING) return emptyList()
        return runCatching { frame.hitTest(x.toFloat(), y.toFloat()) }.getOrDefault(emptyList())
    }

    // endregion

    // region Site anchors

    override fun addSiteAnchor(site: AlignmentSite): Boolean {
        val session = liveSession ?: return false
        val pose = site.anchor
        val translation = floatArrayOf(pose.translation.x.toFloat(), pose.translation.y.toFloat(), pose.translation.z.toFloat())
        val anchor = runCatching { session.createAnchor(Pose(translation, pose.quaternion)) }.getOrNull() ?: return false
        anchors[site.id] = anchor
        anchorPoses[site.id] = pose
        return true
    }

    override fun removeSiteAnchor(id: UUID) {
        val anchor = anchors.remove(id)
        if (liveSession != null) anchor?.detach()
        anchorPoses.remove(id)
    }

    // endregion

    // region Crosshair and mark discs

    override fun showCrosshairMarker() {
        crosshairWanted = true
        val container = container ?: return
        val node = crosshairNode ?: makeCrosshair().also { crosshairNode = it }
        if (node.parent !== container) container.addChildNode(node)
        node.isVisible = false
    }

    override fun hideCrosshairMarker() {
        crosshairWanted = false
        crosshairAim = null
        crosshairNode?.let { node ->
            node.parent = null
            node.destroy()
        }
        crosshairNode = null
    }

    /**
     * A faint 12 cm circle with a dot, lying on the detected floor where the crosshair meets it (or
     * standing on the wall). It shows the floor's height as the app has it.
     */
    override fun placeCrosshairMarker(aim: FloorAim?) {
        crosshairAim = aim
        val node = crosshairNode ?: return
        if (aim == null) {
            node.isVisible = false
            return
        }
        node.position = aim.point.float3
        node.quaternion = aim.normal?.let { facing(it) } ?: Quaternion()
        node.isVisible = true
    }

    private fun makeCrosshair(): Node {
        val engine = SceneEngine.engine
        val group = Node(engine)
        val circle = CylinderNode(engine = engine, radius = 0.06f, height = 0.0005f, materialInstance = markerMaterial(FAINT_WHITE))
        circle.position = Float3(0f, 0.002f, 0f)
        val dot = CylinderNode(engine = engine, radius = 0.006f, height = 0.0005f, materialInstance = markerMaterial(WHITE))
        dot.position = Float3(0f, 0.003f, 0f)
        group.addChildNode(circle)
        group.addChildNode(dot)
        return group
    }

    override fun addMarkDisc(centre: Vec3, normal: Vec3?) {
        discData += centre to normal
        val container = container ?: return
        discNodes += makeDisc(container, centre, normal)
    }

    override fun moveMarkDisc(index: Int, centre: Vec3, normal: Vec3?) {
        if (index < discData.size) discData[index] = centre to normal
        val node = discNodes.getOrNull(index) ?: return
        node.position = centre.float3
        node.quaternion = normal?.let { facing(it) } ?: Quaternion()
    }

    override fun removeLastMarkDisc() {
        if (discData.isNotEmpty()) discData.removeAt(discData.size - 1)
        if (discNodes.isEmpty()) return
        val node = discNodes.removeAt(discNodes.size - 1)
        node.parent = null
        node.destroy()
    }

    override fun clearMarkDiscs() {
        discData.clear()
        for (node in discNodes) {
            node.parent = null
            node.destroy()
        }
        discNodes.clear()
    }

    /** A green disc 6 cm across on a mark, flat or facing out of its wall. */
    private fun makeDisc(container: Node, centre: Vec3, normal: Vec3?): Node {
        val disc = CylinderNode(engine = SceneEngine.engine, radius = 0.03f, height = 0.0005f, materialInstance = markerMaterial(GREEN))
        disc.position = centre.float3
        disc.quaternion = normal?.let { facing(it) } ?: Quaternion()
        container.addChildNode(disc)
        return disc
    }

    private fun markerMaterial(color: Float4): MaterialInstance =
        markerMaterials.getOrPut(color) { SceneEngine.materialLoader(appContext).createUnlitColorInstance(color) }

    // endregion

    // region Thermal

    private fun isHot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false // no thermal status before Android 10
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    private fun startThermalUpdates() {
        thermalStateChanged(isHot())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || thermalListener != null) return
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            thermalStateChanged(status >= PowerManager.THERMAL_STATUS_SEVERE)
        }
        power.addThermalStatusListener(appContext.mainExecutor, listener)
        thermalListener = listener
    }

    private fun stopThermalUpdates() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.removeThermalStatusListener(listener)
        thermalListener = null
    }

    // endregion

    companion object {
        /**
         * A phone with a time-of-flight depth sensor (iOS `hasLiDAR`): ARCore raw depth supported and
         * a back camera reporting depth output (docs/PLAN.md §3 "AR"). Known from the camera alone
         * until an AR session has confirmed ARCore's side; [hasLiDAR] with a context checks the camera.
         */
        @Volatile
        var hasLiDAR: Boolean = false
            private set

        /** An AR session has answered ARCore's side of [hasLiDAR]: the camera alone no longer decides. */
        @Volatile
        private var confirmedBySession = false

        /** [hasLiDAR], from the camera alone until an AR session has confirmed it. */
        fun hasLiDAR(context: Context): Boolean {
            if (!confirmedBySession) hasLiDAR = hasDepthCamera(context.applicationContext)
            return hasLiDAR
        }

        private val markerMaterials = HashMap<Float4, MaterialInstance>()
        private val FAINT_WHITE = Float4(1f, 1f, 1f, 0.35f)
        private val WHITE = Float4(1f, 1f, 1f, 1f)

        /** iOS `systemGreen`. */
        private val GREEN = Float4(0x34 / 255f, 0xC7 / 255f, 0x59 / 255f, 1f)

        /** A depth point counts as floor when its normal is this close to straight up (about 25°). */
        private const val UP_DOT = 0.9f

        /** A back camera with depth output: a time-of-flight sensor. */
        internal fun hasDepthCamera(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.cameraIdList.any { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val back = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                back && capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
            }
        }.getOrDefault(false)

        private fun downloadBitmap(uri: URI): Bitmap? = runCatching {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val bytes = connection.inputStream.use { it.readBytes() }
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        /** Turns a flat (+Y up) marker to face [normal]. */
        private fun facing(normal: Vec3): Quaternion {
            val q = ScreenGeometry.rotationFromUp(normal)
            return Quaternion(q[0], q[1], q[2], q[3])
        }
    }
}

private val Vec3.float3: Float3 get() = Float3(x.toFloat(), y.toFloat(), z.toFloat())

/** `RotY(yaw)` then the translation, as a node transform (columns: x, y, z axes, translation). */
private val YawTransform.mat4: Mat4
    get() {
        val m = matrix
        return Mat4(
            Float4(m[0], m[1], m[2], m[3]),
            Float4(m[4], m[5], m[6], m[7]),
            Float4(m[8], m[9], m[10], m[11]),
            Float4(m[12], m[13], m[14], m[15]),
        )
    }

private val Pose.point: Vec3 get() = Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble())

/** A hit pose's Y axis: the plane's normal, or the depth point's surface normal. */
private val Pose.normal: Vec3 get() = yAxis.let { Vec3(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }

private val TrackingState.mapped: CameraTrackingState
    get() = when (this) {
        TrackingState.TRACKING -> CameraTrackingState.TRACKING
        TrackingState.PAUSED -> CameraTrackingState.PAUSED
        TrackingState.STOPPED -> CameraTrackingState.STOPPED
        else -> CameraTrackingState.PAUSED
    }

private val TrackingFailureReason.mapped: CameraTrackingFailure
    get() = when (this) {
        TrackingFailureReason.NONE -> CameraTrackingFailure.NONE
        TrackingFailureReason.BAD_STATE -> CameraTrackingFailure.BAD_STATE
        TrackingFailureReason.INSUFFICIENT_LIGHT -> CameraTrackingFailure.INSUFFICIENT_LIGHT
        TrackingFailureReason.EXCESSIVE_MOTION -> CameraTrackingFailure.EXCESSIVE_MOTION
        TrackingFailureReason.INSUFFICIENT_FEATURES -> CameraTrackingFailure.INSUFFICIENT_FEATURES
        TrackingFailureReason.CAMERA_UNAVAILABLE -> CameraTrackingFailure.CAMERA_UNAVAILABLE
        else -> CameraTrackingFailure.NONE
    }
