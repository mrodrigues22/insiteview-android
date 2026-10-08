package com.getinsiteview.modelkit.ar

import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.floorHeight
import com.getinsiteview.modelkit.geometry.AlignmentSite
import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.LidarAim
import com.getinsiteview.modelkit.geometry.LocateGuide
import com.getinsiteview.modelkit.geometry.LocateIndicator
import com.getinsiteview.modelkit.geometry.ManualAlignment
import com.getinsiteview.modelkit.geometry.PlateAnchoring
import com.getinsiteview.modelkit.geometry.PlateFrame
import com.getinsiteview.modelkit.geometry.ReferenceAlignment
import com.getinsiteview.modelkit.geometry.ReferenceMark
import com.getinsiteview.modelkit.geometry.RoomCorrections
import com.getinsiteview.modelkit.geometry.RoomObservation
import com.getinsiteview.modelkit.geometry.Vec2
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.YawTransform
import com.getinsiteview.modelkit.intersectFloor
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * What the view needs from an AR frame, copied out while the frame is valid (iOS `FrameSample`).
 *
 * @property timestamp seconds, on the same clock as [ARAlignmentLogic]'s.
 * @property cameraPosition world.
 * @property cameraForward world, unit length: where the camera looks (the negated z axis of its pose).
 * @property viewMatrix world → camera for the screen's orientation, column-major (Locate).
 * @property projectionMatrix the camera's projection for the view, column-major (screen rays).
 * @property focal the projection's focal lengths `[0][0]`, `[1][1]` (Locate); `null` until the view is laid out.
 */
class FrameSample(
    val timestamp: Double,
    val cameraPosition: Vec3,
    val cameraForward: Vec3,
    val viewMatrix: FloatArray? = null,
    val projectionMatrix: FloatArray? = null,
    val focal: Vec2? = null,
)

/**
 * Anchor changes as values (iOS `AnchorChange`): floor heights, tracked plate images, the local
 * alignments' anchors, removals. Plane identities are whatever the session uses (ARCore `Plane`s).
 */
data class AnchorChange(
    /** A horizontal (upward) plane was among them. */
    val hasHorizontalPlane: Boolean = false,
    /** Floor candidates ([FloorPlanes.candidate]), not a table. */
    val floors: List<Floor> = emptyList(),
    /** Tracked plate images (full tracking only). */
    val images: List<Image> = emptyList(),
    /** Local alignments' anchors that moved, where the session has them now. */
    val sites: List<Site> = emptyList(),
    val removed: List<Any> = emptyList(),
) {
    data class Floor(val id: Any, val plane: FloorPlane)

    data class Image(val name: String, val frame: PlateFrame)

    data class Site(val id: UUID, val pose: YawTransform)

    val isEmpty: Boolean get() = !hasHorizontalPlane && floors.isEmpty() && images.isEmpty() && sites.isEmpty() && removed.isEmpty()
}

/** A gesture's phase (UIKit's `.began` / `.changed` / ended or cancelled). */
enum class GesturePhase { BEGAN, CHANGED, ENDED }

/**
 * The AR alignment as the iOS view runs it (IVAR `ARAlignmentView`), without the platform: world
 * tracking, plate detection and the 4-DoF alignment, with alignment by reference points and manual
 * placement on the floor as the fallbacks. `:ar`'s `ARAlignmentView` extends it and does what this
 * class asks of the platform (the session, raycasts, the root's transform, the crosshair and mark
 * discs); everything else, every rule and transition, is here and tested on the JVM.
 *
 * Main thread only, like the iOS view. Times are seconds from [clock] (the frames' timestamps use
 * the same clock).
 */
abstract class ARAlignmentLogic(
    protected val clock: () -> Double = { System.nanoTime() / 1e9 },
) {
    // region Platform (implemented by `:ar`)

    /** A phone with a time-of-flight depth sensor (iOS `hasLiDAR`): walls and corners can be marked. */
    protected abstract val hasDepthSensor: Boolean

    /** A building scene is attached. */
    protected abstract val hasScene: Boolean

    /** Puts the building's root at [transform] (world) and shows it. */
    protected abstract fun applyRootTransform(transform: YawTransform)

    /** Shows or hides the building's root. */
    protected abstract fun setRootEnabled(enabled: Boolean)

    protected abstract val isRootEnabled: Boolean

    /** Configures the running session for [settings] (without a reset: the world and the alignment stay). */
    protected abstract fun configureSession(settings: ARSessionSettings)

    /** Forget detected images so they're reported again (iOS removes the image anchors). */
    protected abstract fun forgetImageDetections()

    /** Downloads the placed plates' images; each arrival calls [referenceImageArrived]. */
    protected abstract fun loadReferenceImages(plates: List<Manifest.Plate>)

    /** Adds the session anchor of a local alignment at its pose; `false` when the session can't now. */
    protected abstract fun addSiteAnchor(site: AlignmentSite): Boolean

    protected abstract fun removeSiteAnchor(id: UUID)

    /** The ray through a view point (pixels, y down) from the current frame. */
    protected abstract fun screenRay(x: Double, y: Double): Ray?

    /** The view's size in pixels. */
    protected abstract val viewSize: Vec2

    /** Where the floor is under a view point: horizontal planes first, then estimates (raycast #1). */
    protected abstract fun floorHit(x: Double, y: Double): Vec3?

    /** The wall a raycast through a view point meets (raycast #2), seen from [eye]. */
    protected abstract fun wallHit(x: Double, y: Double, eye: Vec3): LidarAim.Plane?

    /** Where a world point shows in the view (pixels), `null` behind the camera. */
    protected abstract fun project(point: Vec3): Vec2?

    protected abstract fun showCrosshairMarker()

    protected abstract fun hideCrosshairMarker()

    /** The crosshair marker at [aim] (lying on the floor, or standing on a wall), hidden for `null`. */
    protected abstract fun placeCrosshairMarker(aim: FloorAim?)

    /** A green disc at [centre] (world), lying flat, or facing [normal] on a wall. */
    protected abstract fun addMarkDisc(centre: Vec3, normal: Vec3?)

    protected abstract fun moveMarkDisc(index: Int, centre: Vec3, normal: Vec3?)

    protected abstract fun removeLastMarkDisc()

    protected abstract fun clearMarkDiscs()

    /** The scene's pulsing marker at the centre of [bounds] (model coordinates), or none. */
    protected abstract fun showLocateMarker(bounds: Bounds?)

    protected abstract fun setLocateMarkerScale(scale: Double)

    /** The scene's behind-wall fade from the camera in model coordinates; `null` stops it. */
    protected abstract fun updateSceneProximity(camera: Vec3?)

    /** A tap on the placed model: the platform picks the element and calls [onTap]. */
    protected abstract fun tappedModel(x: Double, y: Double)

    // endregion

    // region State

    var state: PlacementState = PlacementState.FindingFloor
        protected set(value) {
            val old = field
            field = value
            if (value != old) onStateChange?.invoke(value)
        }

    var tracking: TrackingStatus = TrackingStatus.INITIALIZING
        protected set(value) {
            val old = field
            field = value
            if (value != old) onTrackingChange?.invoke(value)
            refreshCoaching()
        }

    /**
     * Plate anchoring; manual placement drives the building while `anchoring.isManual`. Changed
     * through [updateAnchoring], which reports only what the screen shows (image detections update
     * the smoother every frame).
     */
    var anchoring: PlateAnchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = clock())
        private set

    /** Model elevation of the storey whose floor goes on the detected plane. */
    var floorElevation: Double = 0.0
        set(value) {
            val oldValue = field
            field = value
            if (value == oldValue || !anchoring.isManual || isMarking || anchoring.alignment?.method == PlateAnchoring.Method.Points) return
            val transform = state.transform ?: return
            // Keep the floor on the same plane when switching storeys (floor placement only: an
            // alignment by points is fixed by its marks).
            val height = ManualAlignment.floorHeight(transform, elevation = oldValue)
            setManualTransform(transform.copy(translation = transform.translation.copy(y = height - value)))
        }

    var onStateChange: ((PlacementState) -> Unit)? = null
    var onTrackingChange: ((TrackingStatus) -> Unit)? = null
    var onAnchoringChange: ((PlateAnchoring) -> Unit)? = null

    /** Alignments and the fallback, for the status line and `ar_aligned`. */
    var onAlignmentEvent: ((PlateAnchoring.Event) -> Unit)? = null

    /**
     * Locate in AR: the element's box in model coordinates; `null` stops. The scene's marker
     * pulses on it.
     */
    var locateTarget: Bounds? = null
        set(value) {
            if (value == field) return
            field = value
            showLocateMarker(value)
            lastLocateUpdate = 0.0
            if (value == null) onLocateChange?.invoke(null)
        }

    /**
     * Distance, on screen or not, and the arrow, about ten times a second while locating (`null`
     * until the model is placed).
     */
    var onLocateChange: ((LocateIndicator?) -> Unit)? = null
    private var lastLocateUpdate = 0.0

    /**
     * Marking floor corners: the building hides until a fit is previewed; gestures, floor coaching,
     * plate detection and light estimation are off.
     */
    var isMarking: Boolean = false
        private set

    /** "Fix here": aiming at one corner over the aligned building. */
    var isFixing: Boolean = false
        private set

    /** Either: the crosshair follows the floor and gestures are off. */
    val isAiming: Boolean get() = isMarking || isFixing

    /** What the crosshair is on, while aiming. */
    var crosshair: CrosshairState = CrosshairState.NO_FLOOR
        private set

    /** The crosshair changed state (not each move on the floor). */
    var onCrosshairChange: ((CrosshairState) -> Unit)? = null

    private val floorPlanes = FloorPlanes()

    /** The floor's height in the AR world ([FloorPlanes.worldFloorY]). */
    val worldFloorY: Double? get() = floorPlanes.worldFloorY

    /** [worldFloorY] moved by 5 mm or more since last told (the session refining the floor), so marks on it can follow. */
    var onFloorChange: ((Double) -> Unit)? = null
    private val floorChange = FloorChangeNotifier()

    /** The planes [worldFloorY] chooses from, for the copied details. */
    val floorPlaneCandidates: List<FloorPlane> get() = floorPlanes.candidates

    /** The camera's height in the AR world, for the copied details. */
    val cameraY: Double get() = cameraPosition.y

    /**
     * Detections of the plate being registered on site (not placed yet), with the camera position;
     * they don't align the building.
     */
    var onRegistrationDetection: ((PlateFrame, Vec3) -> Unit)? = null

    /**
     * The floor coaching (iOS `ARCoachingOverlayView`, goal horizontal plane) may show: only for
     * manual placement before the first placement, not while aiming (AAV 563-571).
     */
    var coachingAllowed: Boolean = true
        private set

    /** The coaching card shows: allowed, and no floor plane yet or tracking not normal (the overlay's own goal). */
    var showsCoaching: Boolean = true
        private set

    var onCoachingChange: ((Boolean) -> Unit)? = null

    private val discNormals = ArrayList<Vec3?>()
    private var lastCrosshairUpdate = 0.0

    /** Crosshair points, with the camera they were seen from, while "Mark" averages them. */
    private var capture: MarkCapture? = null

    /** The model floor height the alignment by points was fitted on, for the floor glue. */
    private var pointsFloorY: Double? = null

    /** The last frame's time, for plate detections. */
    private var lastFrameTime: Double? = null
    private var lastSample: FrameSample? = null

    /** The unplaced plate being registered (its image name). */
    protected var registrationImageName: String? = null
        private set

    private var lastDragPoint: Vec3? = null
    var hasHorizontalPlane: Boolean = false
        private set

    /** Plate numbers whose reference image has been downloaded. */
    private val referenceImages = LinkedHashSet<Int>()
    var isRunning: Boolean = false
        private set
    var thermalLimited: Boolean = false
        private set
    private var lostSince: Double? = null

    /** The transform last put on the root. */
    var lastApplied: YawTransform? = null
        private set

    /** Sites with a session anchor (the platform keeps the anchors). */
    private val siteAnchors = LinkedHashSet<UUID>()

    /**
     * Walls and corners at any height can be aimed at (depth sensor, not when hot). Off for the
     * marking device test, which measures floor corners.
     */
    var allowsWallAims: Boolean = true
    protected val aimsAtWalls: Boolean get() = ARSessionSettings.aimsAtWalls(hasDepthSensor, thermalLimited, allowsWallAims)

    private var runningSettings: ARSessionSettings? = null

    // endregion

    // region Session

    /**
     * The placed plates to align on, the one from the invocation URL, and where the rooms really
     * are. Reference images download in the background; detection starts as each arrives.
     */
    fun configure(plates: List<Manifest.Plate>, scannedPlate: Int?, corrections: RoomCorrections = RoomCorrections.none) {
        replaceAnchoring(PlateAnchoring(plates = plates, scannedPlate = scannedPlate, at = clock(), corrections = corrections))
        updateCoachingOverlay()
        loadReferenceImages(plates)
    }

    /** A placed plate's reference image arrived: detection can include it. */
    protected fun referenceImageArrived(number: Int) {
        referenceImages += number
        reconfigureIfNeeded()
    }

    /** The plate being registered: its image is ready ([name] `register-N`), or watching stopped (`null`). */
    protected fun setRegistrationImage(name: String?) {
        registrationImageName = name
        reconfigureIfNeeded()
    }

    /**
     * Starts world tracking (the floor; plates when there are any). The screen stays awake: going
     * to sleep interrupts the session, which then has to find its place again.
     */
    open fun run() {
        isRunning = true
        val wanted = settings
        runningSettings = wanted
        configureSession(wanted)
        reconcileSiteAnchors()
    }

    open fun pause() {
        isRunning = false
    }

    /** Placed plates to detect. None while marking: the marks decide (the plate being registered keeps being watched). */
    private val detectionSet: Set<Int>
        get() = if (anchoring.wantsImageDetection && !isMarking) referenceImages.toSet() else emptySet()

    /** What the configuration depends on now. */
    val settings: ARSessionSettings
        get() = ARSessionSettings(
            detection = detectionSet, registration = registrationImageName, lean = isMarking, thermalLimited = thermalLimited,
            walls = isAiming && aimsAtWalls,
        )

    /**
     * Reconfigures the session (without a reset, so the world and the alignment stay) when what
     * it depends on changed, e.g. detection off once aligned to save power. [force] does it anyway
     * (look for plates again, or after an interruption).
     */
    protected fun reconfigureIfNeeded(force: Boolean = false) {
        if (!isRunning) return
        val wanted = settings
        if (!force && wanted == runningSettings) return
        if (ARSessionSettings.forgetsImages(runningSettings, wanted)) {
            forgetImageDetections()
        }
        runningSettings = wanted
        configureSession(wanted)
    }

    /**
     * The thermal state changed: severe or worse, no light estimation, no wall aims, a cool camera
     * format. Unlike iOS, `:ar` also calls this with the state at start (docs/PLAN.md §3 "AR").
     */
    fun thermalStateChanged(limited: Boolean) {
        if (limited == thermalLimited) return
        thermalLimited = limited
        reconfigureIfNeeded()
    }

    // endregion

    // region Scene

    /**
     * The building's root moved into the AR world, hidden until it's aligned. An alignment belongs
     * to one AR session (its world origin), so every session starts over. (iOS `attach`, after the
     * re-parenting.)
     */
    protected fun rootAttached() {
        setRootEnabled(false)
        val transform = anchoring.transform(at = clock()) ?: state.transform
        if (transform != null) apply(transform)
    }

    /** Before the root goes back to the 3D viewer (iOS `detach`, before the re-parenting). */
    protected fun rootDetaching() {
        locateTarget = null
        updateSceneProximity(null)
    }

    private fun apply(transform: YawTransform) {
        lastApplied = transform
        applyRootTransform(transform)
    }

    // endregion

    // region Plate anchoring

    /**
     * Rooms' corrections changed (an admin saved one): the building glides so the rooms measured
     * this session stay where they are.
     */
    fun updateCorrections(corrections: RoomCorrections) {
        updateAnchoring { it.updateCorrections(corrections, at = clock()) }
    }

    /** "Re-align": look for a plate again; the building stays where it is until one converges. */
    fun realign() {
        updateAnchoring { it.requestRealign(at = clock()) }
        forgetImageDetections()
        reconfigureIfNeeded(force = true)
    }

    /** "Place manually": the floor placement takes over, starting from the current alignment. */
    fun placeManually() {
        val current = anchoring.transform(at = clock())
        updateAnchoring { it.switchToManual(at = clock()) }
        state = if (current != null) PlacementState.Adjusting(current) else floorSearchState
        updateCoachingOverlay()
        reconfigureIfNeeded()
    }

    private val floorSearchState: PlacementState
        get() = if (hasHorizontalPlane) PlacementState.ReadyToPlace else PlacementState.FindingFloor

    /**
     * Changes the anchoring state; tells the screen only when the phase, coaching or alignment
     * changed, not for every smoothed detection.
     */
    protected fun <T> updateAnchoring(change: (PlateAnchoring) -> T): T {
        val before = Triple(anchoring.phase, anchoring.coaching, anchoring.alignment)
        val sitesBefore = anchoring.sites.ids
        val result = change(anchoring)
        afterAnchoringChange(before, sitesBefore)
        return result
    }

    private fun replaceAnchoring(new: PlateAnchoring) {
        val before = Triple(anchoring.phase, anchoring.coaching, anchoring.alignment)
        val sitesBefore = anchoring.sites.ids
        anchoring = new
        afterAnchoringChange(before, sitesBefore)
    }

    private fun afterAnchoringChange(before: Triple<PlateAnchoring.Phase, PlateAnchoring.Coaching, PlateAnchoring.Alignment?>, sitesBefore: Set<UUID>) {
        if (anchoring.sites.ids != sitesBefore) reconcileSiteAnchors()
        if (before != Triple(anchoring.phase, anchoring.coaching, anchoring.alignment)) onAnchoringChange?.invoke(anchoring)
    }

    private fun handle(event: PlateAnchoring.Event?) {
        if (event == null) return
        when (event) {
            is PlateAnchoring.Event.AlignedOnPlate -> {
                // Taps open object cards right away; the blend moves the root meanwhile.
                anchoring.transform(at = clock())?.let { state = PlacementState.Locked(it) }
            }
            PlateAnchoring.Event.FellBackToManual -> {
                // While marking points the marking panel leads; otherwise the floor placement.
                if (!isMarking) state = floorSearchState
            }
            is PlateAnchoring.Event.AlignedManually, is PlateAnchoring.Event.AlignedByPoints -> Unit
        }
        updateCoachingOverlay()
        reconfigureIfNeeded()
        onAlignmentEvent?.invoke(event)
    }

    /** Floor coaching only for manual placement; plates have their own coaching line. Not while aiming. */
    protected fun updateCoachingOverlay() {
        coachingAllowed = anchoring.isManual && state.transform == null && !isAiming
        refreshCoaching()
    }

    private fun refreshCoaching() {
        val shows = coachingAllowed && (!hasHorizontalPlane || tracking != TrackingStatus.NORMAL)
        if (shows != showsCoaching) {
            showsCoaching = shows
            onCoachingChange?.invoke(shows)
        }
    }

    // endregion

    // region Manual placement

    /** Puts the storey floor on the plane under the view point. Returns whether a floor was found. */
    fun place(x: Double, y: Double): Boolean {
        if (!anchoring.isManual) return false
        val hit = floorHit(x, y) ?: return false
        setManualTransform(ManualAlignment.placingFloor(elevation = floorElevation, at = hit, cameraForward = cameraForward))
        return true
    }

    /** One "Fine-tune" step (±1 cm, ±0.5°), relative to where the camera looks. */
    fun nudge(nudge: ManualAlignment.Nudge) {
        val transform = state.transform ?: return
        val size = viewSize
        val pivot = floorPoint(size.x / 2, size.y / 2, transform) ?: transform.apply(Vec3(0.0, floorElevation, 0.0))
        setManualTransform(ManualAlignment.nudged(transform, nudge, cameraForward = cameraForward, pivot = pivot))
    }

    /** Done adjusting: taps now open the object card, and it counts as aligned (`manual`). */
    fun lock() {
        val adjusting = state as? PlacementState.Adjusting ?: return
        val transform = adjusting.value
        state = PlacementState.Locked(transform)
        val camera = cameraPosition
        val event = updateAnchoring { it.placedManually(transform, camera = camera, at = clock()) }
        handle(event)
    }

    /** Back to adjusting. */
    fun unlock() {
        val locked = state as? PlacementState.Locked ?: return
        // Where the building is on screen: a local alignment may have taken over since it locked.
        val transform = lastApplied ?: locked.value
        if (!anchoring.isManual || anchoring.alignment?.method == PlateAnchoring.Method.Points) {
            updateAnchoring { it.switchToManual(at = clock()) }
        }
        state = PlacementState.Adjusting(transform)
        updateCoachingOverlay()
    }

    /** Clears the placement ("Place again"). */
    fun reset() {
        updateAnchoring { it.clearedManualPlacement(at = clock()) }
        state = floorSearchState
        setRootEnabled(false)
        updateCoachingOverlay()
    }

    private fun setManualTransform(transform: YawTransform) {
        state = if (state is PlacementState.Locked) PlacementState.Locked(transform) else PlacementState.Adjusting(transform)
        apply(transform)
    }

    private val cameraForward: Vec3 get() = lastSample?.cameraForward ?: Vec3(0.0, 0.0, -1.0)

    private val cameraPosition: Vec3 get() = lastSample?.cameraPosition ?: Vec3.zero

    /** Where the view point's ray meets the placed storey's floor. */
    private fun floorPoint(x: Double, y: Double, transform: YawTransform): Vec3? {
        val ray = screenRay(x, y) ?: return null
        return ManualAlignment.intersectFloor(
            origin = ray.origin, direction = ray.direction,
            height = ManualAlignment.floorHeight(transform, elevation = floorElevation),
        )
    }

    // endregion

    // region Reference points

    /**
     * Starts marking floor corners: the building hides until a fit is previewed, and the crosshair
     * follows the floor. An earlier alignment stays in the anchoring, so cancelling goes back to it.
     */
    fun startMarking() {
        isMarking = true
        capture = null
        state = floorSearchState
        setRootEnabled(false)
        showCrosshair()
        updateCoachingOverlay()
        reconfigureIfNeeded()
    }

    /**
     * Stops marking (cancelled, or before the fit is confirmed); the marks' discs go and the
     * alignment there was before, if any, shows again.
     */
    fun stopMarking() {
        isMarking = false
        clearMarks()
        hideCrosshair()
        val transform = anchoring.transform(at = clock())
        if (transform != null) {
            state = PlacementState.Locked(transform)
            apply(transform)
        } else {
            state = floorSearchState
            setRootEnabled(false)
        }
        updateCoachingOverlay()
        reconfigureIfNeeded()
    }

    /**
     * "Mark": a floor corner under the crosshair, with a disc on it. `null` when the aim or tracking
     * didn't hold (see [captureFloorAim]). The mark keeps its line of sight, so it can follow the
     * floor as the session refines it (`ReferenceMark.onFloor`).
     */
    suspend fun markCorner(): ReferenceMark? {
        if (!isMarking) return null
        val aim = captureFloorAim() ?: return null
        addDisc(aim.point, aim.normal)
        return ReferenceMark(position = aim.point, surface = aim.surface, seenFrom = aim.eye, normal = aim.normal)
    }

    /**
     * The crosshair averaged over half a second, leaving out hand shake, with where the camera was
     * ([MarkCapture]). `null` when it wasn't on the floor (or a wall), or the aim or tracking didn't
     * hold the whole time.
     */
    suspend fun captureFloorAim(): FloorAim? {
        if (!isAiming || capture != null || !crosshair.canMark) return null
        val mine = MarkCapture()
        capture = mine
        delay(MarkCapture.CAPTURE_DURATION_MILLIS)
        val stillMine = capture === mine
        if (stillMine) capture = null
        // Cleared meanwhile (the crosshair was hidden): nothing was captured.
        return if (stillMine) mine.result() else null
    }

    /**
     * A green disc on a mark, flat on the detected floor (3 mm above it, so it doesn't flicker
     * against the model's floor). A wall mark's disc stands on the wall, facing out, 3 mm out.
     */
    private fun addDisc(position: Vec3, normal: Vec3?) {
        discNormals += normal
        addMarkDisc(discCentre(position, normal), normal)
    }

    /**
     * The marks' discs where the marks are now (on the floor as the session refined it), in the
     * order they were made. Wall discs keep their offset along the wall's normal (iOS moves them
     * 3 mm up instead, a bug fixed here: docs/PLAN.md §3 "AR", reference §10.13).
     */
    fun moveMarkDiscs(positions: List<Vec3>) {
        for ((index, position) in positions.withIndex()) {
            if (index >= discNormals.size) break
            val normal = discNormals[index]
            moveMarkDisc(index, discCentre(position, normal), normal)
        }
    }

    /** "Undo": the last mark's disc goes. */
    fun removeLastMark() {
        if (discNormals.isEmpty()) return
        discNormals.removeAt(discNormals.size - 1)
        removeLastMarkDisc()
    }

    fun clearMarks() {
        discNormals.clear()
        clearMarkDiscs()
    }

    /**
     * Shows the building where a fit puts it while the user checks it (`null` hides it again).
     * Gestures stay off until [confirmPoints].
     */
    fun previewAlignment(transform: YawTransform?) {
        if (transform != null) {
            state = PlacementState.Adjusting(transform)
            apply(transform)
        } else {
            state = floorSearchState
            setRootEnabled(false)
        }
    }

    /**
     * "It matches": aligned by points. Marking ends and taps open object cards; a plate seen later
     * still re-anchors. [around] is where the marks are (world), the alignment's site. [modelFloorY]
     * is the model floor the corners are on, which the floor glue keeps on the detected floor from
     * now on. [room] is the room marked.
     */
    fun confirmPoints(transform: YawTransform, around: Vec3, modelFloorY: Double, room: String?) {
        val camera = cameraPosition
        val event = updateAnchoring {
            it.alignedByPoints(transform, around = around, camera = camera, room = room, at = clock())
        }
        pointsFloorY = modelFloorY
        stopMarking()
        handle(event)
    }

    // endregion

    // region Drift

    /**
     * The alignment "Fix here" corrects, while it can (by points, or on a plate): where the local
     * alignment nearest the camera places the as-built building.
     */
    val fixableAlignment: YawTransform? get() = if (anchoring.canFix) anchoring.alignment?.transform else null

    /** The same, as drawn: the model as is around the camera. Placing a plate in the model's coordinates uses this. */
    val drawnAlignment: YawTransform? get() = if (anchoring.canFix) anchoring.transform(at = clock()) else null

    /** The camera in model coordinates, where the building is drawn; `null` before it's placed. */
    val cameraInModel: Vec3? get() = lastApplied?.inverseApply(cameraPosition)

    /** "Fix here": the crosshair comes back over the aligned building, for one corner. */
    fun startFixing() {
        if (fixableAlignment == null || isMarking) return
        isFixing = true
        capture = null
        showCrosshair()
        updateCoachingOverlay()
        reconfigureIfNeeded()
    }

    fun stopFixing() {
        isFixing = false
        hideCrosshair()
        updateCoachingOverlay()
        reconfigureIfNeeded()
    }

    /**
     * "Fix here" found its corner: the building glides onto it, and the fix holds around there as
     * a new local alignment. Ignored once a placement by hand has taken over. Returns what the fix
     * says about where its room really is, when it measured that.
     */
    fun fixAlignment(fix: ReferenceAlignment.CornerFix): RoomObservation? {
        val camera = cameraPosition
        val observations = anchoring.observationCount
        val fixed = updateAnchoring { it.fixedAlignment(fix, camera = camera, at = clock()) }
        if (fixed) {
            anchoring.transform(at = clock())?.let { state = PlacementState.Locked(it) }
        }
        return if (anchoring.observationCount > observations) anchoring.lastObservation else null
    }

    /**
     * The floor glue: an alignment by points keeps its model floor on the detected floor as the
     * session refines it or drifts up or down ([ManualAlignment.gluedToFloor]), every local
     * alignment with it.
     */
    private fun glueFloor() {
        if (isAiming) return
        val modelFloorY = pointsFloorY ?: return
        val worldFloorY = worldFloorY ?: return
        val alignment = anchoring.alignment ?: return
        if (alignment.method != PlateAnchoring.Method.Points) return
        val glued = ManualAlignment.gluedToFloor(alignment.transform, modelFloorY = modelFloorY, worldFloorY = worldFloorY) ?: return
        val raised = updateAnchoring {
            it.raisedPointsAlignment(by = glued.translation.y - alignment.transform.translation.y, at = clock())
        }
        if (raised) state = PlacementState.Locked(glued)
    }

    /** One session anchor per local alignment ([SiteAnchors]). */
    protected fun reconcileSiteAnchors() {
        if (!SiteAnchors.FOLLOWS_SITE_ANCHORS || !isRunning) return
        val plan = SiteAnchors.plan(existing = siteAnchors.toSet(), sites = anchoring.sites.all)
        for (id in plan.remove) {
            removeSiteAnchor(id)
            siteAnchors.remove(id)
        }
        for (site in plan.add) {
            if (addSiteAnchor(site)) siteAnchors += site.id
        }
    }

    /** The session's anchors are gone (a new session): every site gets a new one. */
    protected fun siteAnchorsLost() {
        siteAnchors.clear()
    }

    /** Sites whose anchor couldn't be added yet (ARCore refuses anchors while not tracking). */
    private val hasUnanchoredSites: Boolean get() = anchoring.sites.all.any { it.id !in siteAnchors }

    // endregion

    // region Crosshair

    private fun showCrosshair() {
        showCrosshairMarker()
        placeCrosshairMarker(null)
    }

    private fun hideCrosshair() {
        capture = null
        hideCrosshairMarker()
        setCrosshair(CrosshairState.NO_FLOOR)
    }

    private fun setCrosshair(state: CrosshairState) {
        if (state == crosshair) return
        crosshair = state
        onCrosshairChange?.invoke(state)
    }

    /** Every frame while capturing a mark, 15 times a second otherwise: where the crosshair is. */
    private fun updateCrosshair(time: Double) {
        val current = capture
        if (!CrosshairTargeting.isDue(time, lastCrosshairUpdate, capturing = current != null)) return
        lastCrosshairUpdate = time
        val target = crosshairTarget()
        current?.add(target.aim)
        placeCrosshairMarker(target.aim)
        setCrosshair(target.state)
    }

    private fun crosshairTarget(): CrosshairTargeting.Target {
        val size = viewSize
        val centreX = size.x / 2
        val centreY = size.y / 2
        val canTrack = tracking == TrackingStatus.NORMAL && size.x > 0
        val ray = if (canTrack && worldFloorY != null) screenRay(centreX, centreY) else null
        return CrosshairTargeting.target(
            worldFloorY = worldFloorY,
            canTrack = canTrack,
            ray = ray,
            aimsAtWalls = aimsAtWalls,
            wallHitAtCentre = { ray?.let { wallHit(centreX, centreY, it.origin) } },
            wallHitAt = { point ->
                val screen = project(point)
                if (screen == null || ray == null) null else wallHit(screen.x, screen.y, ray.origin)
            },
        )
    }

    // endregion

    // region Gestures

    /** A tap: places the floor before a placement, opens the object card after (not while aiming). */
    fun handleTap(x: Double, y: Double) {
        if (isAiming) return
        when (state) {
            PlacementState.FindingFloor, PlacementState.ReadyToPlace -> place(x, y)
            is PlacementState.Adjusting, is PlacementState.Locked -> tappedModel(x, y)
        }
    }

    /** One finger drags the building along its floor while adjusting. */
    fun handleDrag(phase: GesturePhase, x: Double, y: Double) {
        if (isAiming) return
        val transform = (state as? PlacementState.Adjusting)?.value ?: return
        when (phase) {
            GesturePhase.BEGAN -> lastDragPoint = floorPoint(x, y, transform)
            GesturePhase.CHANGED -> {
                val last = lastDragPoint ?: return
                val current = floorPoint(x, y, transform) ?: return
                setManualTransform(ManualAlignment.dragged(transform, from = last, to = current))
                lastDragPoint = current
            }
            GesturePhase.ENDED -> lastDragPoint = null
        }
    }

    /**
     * Two fingers twist the building while adjusting. [rotation] is the change in radians since the
     * last call, clockwise on screen positive (UIKit's convention); ([x], [y]) is between the fingers.
     */
    fun handleTwist(rotation: Double, x: Double, y: Double) {
        if (isAiming) return
        val transform = (state as? PlacementState.Adjusting)?.value ?: return
        // A clockwise twist on screen turns the model clockwise seen from above (negative yaw).
        val angle = -rotation
        val pivot = floorPoint(x, y, transform) ?: transform.apply(Vec3(0.0, floorElevation, 0.0))
        setManualTransform(transform.rotated(by = angle, about = pivot))
    }

    // endregion

    // region Session callbacks

    /** Anchors added, updated or removed. */
    fun anchorsChanged(change: AnchorChange) {
        for (id in change.removed) floorPlanes.remove(id)
        // Always: a removed plane can change the floor too.
        noteFloorPlanes(change.floors)
        if (change.hasHorizontalPlane) {
            hasHorizontalPlane = true
            if (state == PlacementState.FindingFloor && anchoring.isManual) state = PlacementState.ReadyToPlace
            refreshCoaching()
        }
        observeImages(change.images, at = lastFrameTime ?: clock())
        if (SiteAnchors.FOLLOWS_SITE_ANCHORS && change.sites.isNotEmpty()) {
            val poses = LinkedHashMap<UUID, YawTransform>()
            for (site in change.sites) poses[site.id] = site.pose
            updateAnchoring { it.siteAnchorsMoved(poses, at = lastFrameTime ?: clock()) }
        }
    }

    /** Floor planes (or large horizontal ones) by plane; see [worldFloorY] for which is the floor. */
    private fun noteFloorPlanes(floors: List<AnchorChange.Floor>) {
        for (floor in floors) floorPlanes.update(floor.id, floor.plane)
        glueFloor()
        val floorY = worldFloorY
        if (floorChange.shouldReport(floorY) && floorY != null) onFloorChange?.invoke(floorY)
    }

    private fun observeImages(images: List<AnchorChange.Image>, at: Double) {
        for (image in images) {
            when (val route = PlateImageName.route(image.name)) {
                PlateImageName.Route.Registration -> onRegistrationDetection?.invoke(image.frame, cameraPosition)
                is PlateImageName.Route.Plate -> {
                    // While marking, the marks decide; a placed plate re-anchors once they're confirmed.
                    if (isMarking) continue
                    val camera = cameraPosition
                    val event = updateAnchoring { it.observe(plate = route.number, world = image.frame, camera = camera, at = at) }
                    handle(event)
                }
                PlateImageName.Route.Other -> Unit
            }
        }
    }

    /**
     * Every frame: the clock for the fallback and re-align timeouts, big moves, the blend and the
     * crosshair.
     */
    fun frameUpdated(sample: FrameSample) {
        val time = sample.timestamp
        lastFrameTime = time
        lastSample = sample
        val camera = sample.cameraPosition
        val phase = anchoring.phase
        val event = updateAnchoring {
            val event = it.tick(at = time)
            it.cameraMoved(to = camera, at = time)
            event
        }
        if (anchoring.phase != phase || event != null) {
            handle(event)
            reconfigureIfNeeded()
        }
        // Plate alignments and alignments by points (their corrections glide), including each step
        // of a blend and its last one. Not while marking: the building is hidden or previewing a fit.
        val followsAnchoring = !anchoring.isManual || anchoring.alignment?.method == PlateAnchoring.Method.Points
        if (!isMarking && followsAnchoring) {
            val transform = anchoring.transform(at = time)
            if (transform != null && (transform != lastApplied || !isRootEnabled)) apply(transform)
        }
        val since = lostSince
        if (since != null && time - since >= LOST_AFTER && anchoring.alignment != null && anchoring.phase != PlateAnchoring.Phase.Lost) {
            updateAnchoring { it.trackingLost(at = time) }
            reconfigureIfNeeded()
        }
        if (isRunning && tracking == TrackingStatus.NORMAL && hasUnanchoredSites) reconcileSiteAnchors()
        if (isAiming) updateCrosshair(time)
        updateLocate(sample)
        updateProximity(sample)
    }

    /**
     * Behind a wall: the scene fades what a wall of the model hides, or what's far away, from the
     * camera brought into model coordinates. Off until the building is placed and while marking
     * corners, when the building is hidden or previewing a fit.
     */
    private fun updateProximity(sample: FrameSample) {
        if (!hasScene) return
        val transform = lastApplied
        if (transform == null || !isRootEnabled || isMarking) {
            updateSceneProximity(null)
            return
        }
        updateSceneProximity(transform.inverseApply(sample.cameraPosition))
    }

    /**
     * Locate in AR: pulse every frame; distance and the arrow ten times a second, from the camera's
     * view and projection for the screen's orientation ([LocateGuide]).
     */
    private fun updateLocate(sample: FrameSample) {
        val target = locateTarget ?: return
        if (!hasScene) return
        setLocateMarkerScale(LocateGuide.pulseScale(at = sample.timestamp))
        if (sample.timestamp - lastLocateUpdate < LOCATE_INTERVAL) return
        lastLocateUpdate = sample.timestamp
        val transform = lastApplied
        val focal = sample.focal
        val view = sample.viewMatrix
        if (transform == null || !isRootEnabled || focal == null || view == null) {
            onLocateChange?.invoke(null)
            return
        }
        val centre = transform.apply(target.center)
        onLocateChange?.invoke(
            LocateGuide.indicator(
                cameraSpacePoint = ScreenGeometry.cameraSpacePoint(centre, view),
                distance = LocateGuide.distance(camera = sample.cameraPosition, to = target, placedBy = transform),
                focalX = focal.x,
                focalY = focal.y,
            ),
        )
    }

    /** The camera's tracking changed. */
    fun trackingChanged(status: TrackingStatus) {
        tracking = status
        when (status) {
            TrackingStatus.NORMAL -> {
                lostSince = null
                if (anchoring.phase == PlateAnchoring.Phase.Lost) {
                    updateAnchoring { it.trackingRestored(at = clock()) }
                    reconfigureIfNeeded()
                }
            }
            TrackingStatus.RELOCALIZING, TrackingStatus.NOT_AVAILABLE -> if (lostSince == null) lostSince = clock()
            else -> Unit
        }
    }

    /** The session was interrupted (the activity paused): the alignment is lost at once. */
    fun sessionInterrupted() {
        tracking = TrackingStatus.INTERRUPTED
        updateAnchoring { it.trackingLost(at = clock()) }
    }

    /** The session resumed: it relocalizes on its own; normal tracking again restores the alignment. */
    fun sessionResumed() {
        tracking = TrackingStatus.RELOCALIZING
        lostSince = clock()
        reconfigureIfNeeded(force = true)
    }

    // endregion

    companion object {
        /** Tracking relocalizing or unavailable this long after aligning counts as alignment lost. */
        const val LOST_AFTER = 2.0

        /** Locate's distance and arrow update this often (10 Hz). */
        const val LOCATE_INTERVAL = 0.1

        /** Mark discs sit this far off their surface. */
        const val DISC_OFFSET = 0.003

        /** A disc's centre for a mark at [position]: 3 mm up, or 3 mm out along a wall's [normal]. */
        fun discCentre(position: Vec3, normal: Vec3?): Vec3 =
            if (normal != null) position + normal * DISC_OFFSET else position + Vec3(0.0, DISC_OFFSET, 0.0)
    }
}
