package com.getinsiteview.modelkit.ar

import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.geometry.AlignmentSite
import com.getinsiteview.modelkit.geometry.AlignmentSmoother
import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.LidarAim
import com.getinsiteview.modelkit.geometry.LocateIndicator
import com.getinsiteview.modelkit.geometry.ManualAlignment
import com.getinsiteview.modelkit.geometry.PlateAnchoring
import com.getinsiteview.modelkit.geometry.PlateFrame
import com.getinsiteview.modelkit.geometry.SyntheticPlates
import com.getinsiteview.modelkit.geometry.Vec2
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.Vector
import com.getinsiteview.modelkit.geometry.YawTransform
import com.getinsiteview.modelkit.geometry.frame
import com.getinsiteview.modelkit.intersectFloor
import java.net.URI
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The AR view's platform, recorded. */
private class FakeAlignment(
    override val hasDepthSensor: Boolean = false,
) : ARAlignmentLogic(clock = { FakeAlignment.now }) {
    companion object {
        var now = 100.0
    }

    override val hasScene: Boolean = true
    var root: YawTransform? = null
    var rootShown = false
    val configured = ArrayList<ARSessionSettings>()
    var forgets = 0
    val loadedPlates = ArrayList<Int>()
    val anchors = LinkedHashMap<UUID, AlignmentSite>()
    var anchorsWork = true
    var crosshairShown = false
    var crosshairAim: FloorAim? = null
    val discs = ArrayList<Pair<Vec3, Vec3?>>()
    var locateMarker: Bounds? = null
    var proximityCamera: Vec3? = null
    val taps = ArrayList<Pair<Double, Double>>()

    /** The camera at [eye], looking straight down. */
    var eye = Vec3(0.0, 0.0, 0.0)
    var lookDirection = Vec3(0.0, -1.0, 0.0)
    var floorHitPoint: Vec3? = null
    var wall: LidarAim.Plane? = null

    override fun applyRootTransform(transform: YawTransform) {
        root = transform
        rootShown = true
    }

    override fun setRootEnabled(enabled: Boolean) {
        rootShown = enabled
    }

    override val isRootEnabled: Boolean get() = rootShown

    override fun configureSession(settings: ARSessionSettings) {
        configured += settings
    }

    override fun forgetImageDetections() {
        forgets += 1
    }

    override fun loadReferenceImages(plates: List<Manifest.Plate>) {
        loadedPlates += plates.map { it.number }
    }

    fun imageArrived(number: Int) = referenceImageArrived(number)

    fun registrationImage(name: String?) = setRegistrationImage(name)

    override fun addSiteAnchor(site: AlignmentSite): Boolean {
        if (!anchorsWork) return false
        anchors[site.id] = site
        return true
    }

    override fun removeSiteAnchor(id: UUID) {
        anchors.remove(id)
    }

    override fun screenRay(x: Double, y: Double): Ray = Ray(origin = eye, direction = Vector.normalized(lookDirection))

    override val viewSize: Vec2 = Vec2(1000.0, 2000.0)

    override fun floorHit(x: Double, y: Double): Vec3? = floorHitPoint

    override fun wallHit(x: Double, y: Double, eye: Vec3): LidarAim.Plane? = wall

    override fun project(point: Vec3): Vec2 = Vec2(500.0, 1000.0)

    override fun showCrosshairMarker() {
        crosshairShown = true
    }

    override fun hideCrosshairMarker() {
        crosshairShown = false
    }

    override fun placeCrosshairMarker(aim: FloorAim?) {
        crosshairAim = aim
    }

    override fun addMarkDisc(centre: Vec3, normal: Vec3?) {
        discs += centre to normal
    }

    override fun moveMarkDisc(index: Int, centre: Vec3, normal: Vec3?) {
        discs[index] = centre to normal
    }

    override fun removeLastMarkDisc() {
        discs.removeAt(discs.size - 1)
    }

    override fun clearMarkDiscs() {
        discs.clear()
    }

    override fun showLocateMarker(bounds: Bounds?) {
        locateMarker = bounds
    }

    override fun setLocateMarkerScale(scale: Double) = Unit

    override fun updateSceneProximity(camera: Vec3?) {
        proximityCamera = camera
    }

    override fun tappedModel(x: Double, y: Double) {
        taps += x to y
    }

    /** A frame [dt] seconds after the last, the camera at [eye]. */
    fun frame(dt: Double = 1.0 / 30, view: FloatArray? = null) {
        now += dt
        frameUpdated(
            FrameSample(
                timestamp = now, cameraPosition = eye, cameraForward = Vector.normalized(lookDirection),
                viewMatrix = view, projectionMatrix = null, focal = if (view != null) Vec2(1.5, 1.0) else null,
            ),
        )
    }

    fun floor(height: Double, id: Any = "floor") =
        anchorsChanged(AnchorChange(hasHorizontalPlane = true, floors = listOf(AnchorChange.Floor(id, FloorPlane(height, 4.0, false)))))
}

@DisplayName("The AR alignment (ARAlignmentView's rules)")
class ARAlignmentLogicTest {
    private val truth = YawTransform(yaw = SyntheticPlates.degrees(30.0), translation = Vec3(0.5, -1.4, -1.25))

    private fun plate(number: Int, frame: PlateFrame) = Manifest.Plate(
        number = number, label = "Plate $number",
        position = listOf(frame.position.x, frame.position.y, frame.position.z),
        normal = listOf(frame.normal.x, frame.normal.y, frame.normal.z),
        up = listOf(frame.up.x, frame.up.y, frame.up.z),
        sizeMm = 50, imageUrl = URI("https://api.test/v1/plates/$number/image.png"),
    )

    private fun running(depth: Boolean = false): FakeAlignment = FakeAlignment(hasDepthSensor = depth).apply {
        run()
        trackingChanged(TrackingStatus.NORMAL)
        eye = Vec3(0.0, 0.0, 0.0)
    }

    @Test
    @DisplayName("Without plates it starts manual: tap the floor, adjust, lock")
    fun `Without plates it starts manual - tap the floor, adjust, lock`() {
        val view = running()
        val states = ArrayList<PlacementState>()
        val events = ArrayList<PlateAnchoring.Event>()
        view.onStateChange = { states += it }
        view.onAlignmentEvent = { events += it }
        assertTrue(view.anchoring.isManual)
        assertEquals(PlacementState.FindingFloor, view.state)
        view.handleTap(1.0, 1.0)
        assertEquals(PlacementState.FindingFloor, view.state, "no floor under the tap")
        view.floor(-1.4)
        assertEquals(PlacementState.ReadyToPlace, view.state, "a horizontal plane promotes while manual")
        view.floorHitPoint = Vec3(1.0, -1.4, -2.0)
        view.lookDirection = Vec3(0.0, -0.2, -1.0)
        view.frame()
        assertTrue(view.place(500.0, 1000.0))
        val placed = assertIs<PlacementState.Adjusting>(view.state).value
        assertEquals(ManualAlignment.placingFloor(elevation = 0.0, at = Vec3(1.0, -1.4, -2.0), cameraForward = Vector.normalized(view.lookDirection)), placed)
        assertEquals(placed, view.root)
        assertTrue(view.rootShown)
        view.lock()
        assertIs<PlacementState.Locked>(view.state)
        assertIs<PlateAnchoring.Event.AlignedManually>(events.single())
        assertEquals(PlateAnchoring.Method.Manual, view.anchoring.alignment?.method)
        view.handleTap(10.0, 20.0)
        assertEquals(listOf(10.0 to 20.0), view.taps, "after placing, taps open the object card")
        view.unlock()
        assertIs<PlacementState.Adjusting>(view.state)
        view.reset()
        assertEquals(PlacementState.ReadyToPlace, view.state)
        assertFalse(view.rootShown)
        assertNull(view.anchoring.alignment)
    }

    @Test
    @DisplayName("Drag moves along the placed floor, a clockwise twist turns clockwise from above")
    fun `Drag moves along the placed floor, a clockwise twist turns clockwise from above`() {
        val view = running()
        view.floor(-1.4)
        view.floorHitPoint = Vec3(0.0, -1.4, -2.0)
        view.place(0.0, 0.0)
        val start = assertNotNull(view.state.transform)
        view.eye = Vec3(0.0, 0.0, 0.0)
        view.lookDirection = Vec3(0.0, -1.0, -1.0)
        view.handleDrag(GesturePhase.BEGAN, 0.0, 0.0)
        view.lookDirection = Vec3(0.5, -1.0, -1.0)
        view.handleDrag(GesturePhase.CHANGED, 0.0, 0.0)
        val dragged = assertNotNull(view.state.transform)
        assertTrue(abs(dragged.translation.x - start.translation.x - 0.7) < 1e-9, "moved by the floor hits' difference: ${dragged.translation}")
        assertEquals(start.translation.y, dragged.translation.y)
        view.handleDrag(GesturePhase.ENDED, 0.0, 0.0)
        view.handleTwist(0.1, 0.0, 0.0)
        assertTrue(abs(YawTransform.normalized(assertNotNull(view.state.transform).yaw - dragged.yaw) + 0.1) < 1e-9)
        view.lock()
        val locked = view.state
        view.handleTwist(0.1, 0.0, 0.0)
        assertEquals(locked, view.state, "gestures only while adjusting")
    }

    @Test
    @DisplayName("Switching storeys keeps a floor placement on the same plane")
    fun `Switching storeys keeps a floor placement on the same plane`() {
        val view = running()
        view.floor(-1.4)
        view.floorHitPoint = Vec3(0.0, -1.4, -2.0)
        view.place(0.0, 0.0)
        assertEquals(-1.4, assertNotNull(view.state.transform).translation.y)
        view.floorElevation = 3.0
        assertEquals(-4.4, assertNotNull(view.state.transform).translation.y, 1e-12)
        assertEquals(-4.4, assertNotNull(view.root).translation.y, 1e-12)
    }

    @Test
    @DisplayName("Marking hides the building, aims the crosshair and leans the session")
    fun `Marking hides the building, aims the crosshair and leans the session`() = runTest {
        val view = running()
        view.configure(plates = listOf(plate(1, SyntheticPlates.wall)), scannedPlate = null)
        view.imageArrived(1)
        assertEquals(setOf(1), view.configured.last().detection)
        view.startMarking()
        assertTrue(view.isMarking && view.isAiming && view.crosshairShown && !view.rootShown)
        assertEquals(ARSessionSettings(detection = emptySet(), registration = null, lean = true, thermalLimited = false, walls = false), view.configured.last())
        assertFalse(view.coachingAllowed)
        val crosshairs = ArrayList<CrosshairState>()
        view.onCrosshairChange = { crosshairs += it }
        view.frame()
        assertEquals(CrosshairState.NO_FLOOR, view.crosshair)
        view.floor(-1.4)
        view.frame(dt = 0.1)
        assertEquals(CrosshairState.READY, view.crosshair)
        assertEquals(listOf(CrosshairState.READY), crosshairs)
        assertTrue(Vector.distance(Vec3(0.0, -1.4, 0.0), assertNotNull(view.crosshairAim).point) < 1e-9)

        // "Mark": half a second of the crosshair, every frame.
        val mark = async { view.markCorner() }
        runCurrent()
        repeat(10) { view.frame() }
        advanceTimeBy(MarkCapture.CAPTURE_DURATION_MILLIS + 1)
        val marked = assertNotNull(mark.await())
        assertTrue(Vector.distance(Vec3(0.0, -1.4, 0.0), marked.position) < 1e-9)
        assertEquals(view.eye, marked.seenFrom)
        assertTrue(Vector.distance(Vec3(0.0, -1.4 + ARAlignmentLogic.DISC_OFFSET, 0.0), view.discs.single().first) < 1e-9)
        assertNull(view.discs.single().second)

        // A frame without an aim breaks the next one.
        val broken = async { view.markCorner() }
        runCurrent()
        repeat(5) { view.frame() }
        view.trackingChanged(TrackingStatus.EXCESSIVE_MOTION)
        view.frame()
        view.trackingChanged(TrackingStatus.NORMAL)
        repeat(5) { view.frame() }
        advanceTimeBy(MarkCapture.CAPTURE_DURATION_MILLIS + 1)
        assertNull(broken.await())
        assertEquals(1, view.discs.size)

        view.stopMarking()
        assertFalse(view.isMarking || view.crosshairShown)
        assertTrue(view.discs.isEmpty())
        assertEquals(CrosshairState.NO_FLOOR, view.crosshair)
        assertEquals(PlacementState.ReadyToPlace, view.state)
        assertFalse(view.rootShown, "no alignment to go back to")
        assertEquals(setOf(1), view.configured.last().detection, "plates are watched again")
    }

    @Test
    @DisplayName("Wall aims need the depth sensor, not hot, and allowed")
    fun `Wall aims need the depth sensor, not hot, and allowed`() {
        val view = running(depth = true)
        view.floor(-1.4)
        view.startMarking()
        assertTrue(view.configured.last().walls)
        view.lookDirection = Vec3(1.0, -0.2, 0.0)
        view.wall = LidarAim.Plane.of(point = Vec3(2.0, -0.4, 0.0), normal = Vec3(-1.0, 0.0, 0.0), eye = view.eye)
        view.frame(dt = 0.1)
        assertEquals(CrosshairState.ON_WALL, view.crosshair)
        view.thermalStateChanged(true)
        assertFalse(view.configured.last().walls)
        assertFalse(view.configured.last().estimatesLight)
        view.frame(dt = 0.1)
        assertEquals(CrosshairState.TOO_SHALLOW, view.crosshair)
        view.thermalStateChanged(false)
        view.allowsWallAims = false
        view.frame(dt = 0.1)
        assertEquals(CrosshairState.TOO_SHALLOW, view.crosshair)
    }

    @Test
    @DisplayName("Wall discs keep their offset along the wall when the floor moves")
    fun `Wall discs keep their offset along the wall when the floor moves`() = runTest {
        val view = running(depth = true)
        view.floor(-1.4)
        view.startMarking()
        view.lookDirection = Vec3(1.0, -0.2, 0.0)
        view.wall = LidarAim.Plane.of(point = Vec3(2.0, -0.4, 0.0), normal = Vec3(-1.0, 0.0, 0.0), eye = view.eye)
        view.frame(dt = 0.1)
        val mark = async { view.markCorner() }
        runCurrent()
        repeat(8) { view.frame() }
        advanceTimeBy(MarkCapture.CAPTURE_DURATION_MILLIS + 1)
        val wallMark = assertNotNull(mark.await())
        assertEquals(Vec3(-1.0, 0.0, 0.0), wallMark.normal)
        assertTrue(Vector.distance(Vec3(2.0 - ARAlignmentLogic.DISC_OFFSET, -0.4, 0.0), view.discs.single().first) < 1e-9)
        view.moveMarkDiscs(listOf(wallMark.position))
        assertTrue(Vector.distance(Vec3(2.0 - ARAlignmentLogic.DISC_OFFSET, -0.4, 0.0), view.discs.single().first) < 1e-9, "not 3 mm up")
        view.removeLastMark()
        assertTrue(view.discs.isEmpty())
    }

    @Test
    @DisplayName("Confirming points aligns, anchors a site and glues the floor")
    fun `Confirming points aligns, anchors a site and glues the floor`() {
        val view = running()
        view.floor(-1.4)
        view.startMarking()
        val fit = YawTransform(yaw = 0.2, translation = Vec3(1.0, -1.4, 0.5))
        view.previewAlignment(fit)
        assertEquals(PlacementState.Adjusting(fit), view.state)
        assertTrue(view.rootShown)
        val events = ArrayList<PlateAnchoring.Event>()
        view.onAlignmentEvent = { events += it }
        view.confirmPoints(fit, around = Vec3(1.0, -1.4, 0.0), modelFloorY = 0.0, room = null)
        assertIs<PlateAnchoring.Event.AlignedByPoints>(events.single())
        assertEquals(PlacementState.Locked(fit), view.state)
        assertFalse(view.isMarking)
        assertEquals(1, view.anchors.size, "one session anchor for the new site")
        assertEquals(fit, view.fixableAlignment)
        assertNotNull(view.cameraInModel)

        // The floor settles 2 cm lower: the alignment follows it.
        view.floor(-1.42)
        val glued = assertNotNull(view.state.transform)
        assertEquals(-1.42, glued.translation.y, 1e-9)
        // A plane 30 cm off is something else: no glue.
        view.floor(-1.72, id = "step")
        assertEquals(-1.42, assertNotNull(view.state.transform).translation.y, 1e-9)
    }

    @Test
    @DisplayName("A site anchor the session refused is added once tracking is normal")
    fun `A site anchor the session refused is added once tracking is normal`() {
        val view = running()
        view.anchorsWork = false
        view.startMarking()
        view.confirmPoints(YawTransform.identity, around = Vec3.zero, modelFloorY = 0.0, room = null)
        assertTrue(view.anchors.isEmpty())
        view.anchorsWork = true
        view.frame()
        assertEquals(1, view.anchors.size)
        view.placeManually()
        assertTrue(view.anchors.isEmpty(), "placing by hand drops the sites and their anchors")
    }

    @Test
    @DisplayName("Lost after 2 s relocalizing, an interruption at once, normal restores")
    fun `Lost after 2 s relocalizing, an interruption at once, normal restores`() {
        val view = running()
        view.startMarking()
        view.confirmPoints(YawTransform.identity, around = Vec3.zero, modelFloorY = 0.0, room = null)
        view.trackingChanged(TrackingStatus.EXCESSIVE_MOTION)
        view.frame(dt = 3.0)
        assertEquals(PlateAnchoring.Phase.Manual, view.anchoring.phase, "excessive motion never counts")
        view.trackingChanged(TrackingStatus.RELOCALIZING)
        view.frame(dt = 1.9)
        assertEquals(PlateAnchoring.Phase.Manual, view.anchoring.phase)
        view.frame(dt = 0.2)
        assertEquals(PlateAnchoring.Phase.Lost, view.anchoring.phase)
        view.trackingChanged(TrackingStatus.NORMAL)
        assertEquals(PlateAnchoring.Phase.Manual, view.anchoring.phase, "by hand: back to manual")
        view.sessionInterrupted()
        assertEquals(TrackingStatus.INTERRUPTED, view.tracking)
        assertEquals(PlateAnchoring.Phase.Lost, view.anchoring.phase)
        val configurations = view.configured.size
        view.sessionResumed()
        assertEquals(TrackingStatus.RELOCALIZING, view.tracking)
        assertEquals(configurations + 1, view.configured.size, "resuming reconfigures anyway")
    }

    @Test
    @DisplayName("Plates align the building, then detection stops, registration images go elsewhere")
    fun `Plates align the building, then detection stops, registration images go elsewhere`() {
        val view = running()
        val model = SyntheticPlates.wall
        view.configure(plates = listOf(plate(1, model)), scannedPlate = 1)
        assertEquals(listOf(1), view.loadedPlates)
        view.imageArrived(1)
        assertEquals(1, view.forgets, "detection grew: earlier detections are forgotten")
        val registered = ArrayList<PlateFrame>()
        view.onRegistrationDetection = { frame, _ -> registered += frame }
        view.registrationImage("register-4")
        assertEquals("register-4", view.configured.last().registration)
        val detection = SyntheticPlates.detection(model, truth)
        view.anchorsChanged(AnchorChange(images = listOf(AnchorChange.Image("register-4", detection))))
        assertEquals(1, registered.size)
        assertNull(view.anchoring.alignment, "a registration detection never aligns")

        view.startMarking()
        repeat(AlignmentSmoother.WINDOW_SIZE) {
            view.frame()
            view.anchorsChanged(AnchorChange(images = listOf(AnchorChange.Image("plate-1", detection))))
        }
        assertNull(view.anchoring.alignment, "plates are ignored while marking")
        view.stopMarking()

        val events = ArrayList<PlateAnchoring.Event>()
        view.onAlignmentEvent = { events += it }
        repeat(AlignmentSmoother.WINDOW_SIZE) {
            view.frame()
            view.anchorsChanged(AnchorChange(images = listOf(AnchorChange.Image("plate-1", detection))))
        }
        assertIs<PlateAnchoring.Event.AlignedOnPlate>(events.single())
        assertIs<PlacementState.Locked>(view.state)
        assertEquals(PlateAnchoring.Phase.Aligned, view.anchoring.phase)
        assertTrue(view.configured.last().detection.isEmpty(), "detection off once aligned")
        assertEquals("register-4", view.configured.last().registration)
        view.frame(dt = 1.0)
        val drawn = assertNotNull(view.root)
        assertTrue(Vector.distance(drawn.translation, truth.translation) < 1e-6 && abs(YawTransform.normalized(drawn.yaw - truth.yaw)) < 1e-6)

        val forgets = view.forgets
        view.realign()
        assertTrue(view.forgets > forgets, "re-align forgets the images")
        assertEquals(PlateAnchoring.Phase.Realigning(requested = true), view.anchoring.phase)
        assertEquals(setOf(1), view.configured.last().detection)
    }

    @Test
    @DisplayName("The fallback to manual after 10 s without a plate")
    fun `The fallback to manual after 10 s without a plate`() {
        val view = running()
        view.configure(plates = listOf(plate(1, SyntheticPlates.wall)), scannedPlate = null)
        view.floor(-1.4)
        assertEquals(PlacementState.FindingFloor, view.state, "not manual: plates first")
        assertFalse(view.coachingAllowed)
        val events = ArrayList<PlateAnchoring.Event>()
        view.onAlignmentEvent = { events += it }
        view.frame(dt = PlateAnchoring.FALLBACK_DELAY + 0.1)
        assertEquals(PlateAnchoring.Event.FellBackToManual, events.single())
        assertEquals(PlacementState.ReadyToPlace, view.state)
        assertTrue(view.coachingAllowed)
        assertFalse(view.showsCoaching, "the floor is already found")
    }

    @Test
    @DisplayName("The floor coaching shows only for a manual placement before the floor is found")
    fun `The floor coaching shows only for a manual placement before the floor is found`() {
        val view = FakeAlignment()
        val shown = ArrayList<Boolean>()
        view.onCoachingChange = { shown += it }
        view.configure(plates = emptyList(), scannedPlate = null)
        assertTrue(view.coachingAllowed && view.showsCoaching)
        view.trackingChanged(TrackingStatus.NORMAL)
        view.floor(-1.4)
        assertFalse(view.showsCoaching)
        view.startMarking()
        assertFalse(view.coachingAllowed, "not while aiming")
        assertEquals(listOf(false), shown, "shown from the start (manual, no floor), gone once the floor is found")
    }

    @Test
    @DisplayName("Locate updates ten times a second once placed")
    fun `Locate updates ten times a second once placed`() {
        val view = running()
        val indicators = ArrayList<LocateIndicator?>()
        view.onLocateChange = { indicators += it }
        view.lookDirection = Vec3(0.0, 0.0, -1.0)
        val box = Bounds(min = Vec3(0.0, 0.0, -3.0), max = Vec3(0.2, 0.2, -2.8))
        view.locateTarget = box
        assertEquals(box, view.locateMarker)
        val camera = viewMatrix(Vec3.zero, Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0))
        view.frame(view = camera)
        assertEquals(listOf<LocateIndicator?>(null), indicators, "not placed yet")
        view.floor(0.0)
        view.floorHitPoint = Vec3.zero
        view.place(0.0, 0.0)
        view.frame(dt = 0.05, view = camera)
        assertEquals(1, indicators.size, "within 0.1 s")
        view.frame(dt = 0.06, view = camera)
        val indicator = assertNotNull(indicators.last())
        assertTrue(indicator.isOnScreen)
        view.locateTarget = null
        assertNull(view.locateMarker)
        assertNull(indicators.last())
    }

    @Test
    @DisplayName("Proximity follows the camera in model coordinates, off while marking")
    fun `Proximity follows the camera in model coordinates, off while marking`() {
        val view = running()
        view.floor(-1.4)
        view.floorHitPoint = Vec3(0.0, -1.4, 0.0)
        view.place(0.0, 0.0)
        view.eye = Vec3(1.0, 0.0, 0.0)
        view.frame()
        val placed = assertNotNull(view.state.transform)
        assertEquals(placed.inverseApply(view.eye), view.proximityCamera)
        view.startMarking()
        view.frame()
        assertNull(view.proximityCamera)
    }

    @Test
    @DisplayName("A capture cancelled with its caller doesn't block the next one")
    fun `A capture cancelled with its caller doesn't block the next one`() = runTest {
        val view = running()
        view.floor(-1.4)
        view.startMarking()
        view.frame(dt = 0.1)
        assertEquals(CrosshairState.READY, view.crosshair)
        val cancelled = async { view.markCorner() }
        runCurrent()
        repeat(3) { view.frame() }
        cancelled.cancel()
        runCurrent()
        val mark = async { view.markCorner() }
        runCurrent()
        repeat(10) { view.frame() }
        advanceTimeBy(MarkCapture.CAPTURE_DURATION_MILLIS + 1)
        assertNotNull(mark.await(), "a new capture starts")
        assertEquals(1, view.discs.size)
    }

    @Test
    @DisplayName("Placing on a depth estimate before a plane hides the floor coaching")
    fun `Placing on a depth estimate before a plane hides the floor coaching`() {
        val view = running()
        assertTrue(view.coachingAllowed && view.showsCoaching)
        view.floorHitPoint = Vec3(0.0, -1.4, -2.0)
        view.frame()
        assertTrue(view.place(0.0, 0.0))
        assertFalse(view.hasHorizontalPlane)
        assertFalse(view.coachingAllowed, "a placement exists")
        assertFalse(view.showsCoaching)
        view.reset()
        assertTrue(view.coachingAllowed && view.showsCoaching, "Place again: coaching until the floor is found")
    }
}
