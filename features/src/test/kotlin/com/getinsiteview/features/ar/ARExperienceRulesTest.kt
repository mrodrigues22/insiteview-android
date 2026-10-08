package com.getinsiteview.features.ar

import com.getinsiteview.core.CameraAccess
import com.getinsiteview.modelkit.ar.CrosshairState
import com.getinsiteview.modelkit.ar.FloorPlane
import com.getinsiteview.modelkit.ar.PlacementState
import com.getinsiteview.modelkit.ar.TrackingStatus
import com.getinsiteview.modelkit.geometry.PlateAnchoring
import com.getinsiteview.modelkit.geometry.ReferenceAlignment
import com.getinsiteview.modelkit.geometry.ReferenceMark
import com.getinsiteview.modelkit.geometry.ReferencePoint
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.YawTransform
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class ARExperienceRulesTest {
    private val placed = YawTransform(yaw = 0.0, translation = Vec3.zero)

    private fun anchoring(
        phase: PlateAnchoring.Phase = PlateAnchoring.Phase.Searching,
        method: PlateAnchoring.Method? = null,
        coaching: PlateAnchoring.Coaching = PlateAnchoring.Coaching.PointAtAnyPlate,
        alignedPlate: Pair<Int, String>? = null,
        isManual: Boolean = false,
        hasPlates: Boolean = true,
        canFix: Boolean = false,
    ) = ARAnchoringState(phase, method, coaching, alignedPlate, isManual, hasPlates, canFix)

    // Status tag

    @Test
    fun `tracking problems come first`() {
        val aligned = anchoring(phase = PlateAnchoring.Phase.Aligned, method = PlateAnchoring.Method.Plate(2), alignedPlate = 2 to "Panel")
        assertEquals(StatusTag.MoveMoreSlowly, ARExperienceRules.statusTag(TrackingStatus.EXCESSIVE_MOTION, true, aligned, PlacementState.Locked(placed)))
        assertEquals(StatusTag.MoreDetail, ARExperienceRules.statusTag(TrackingStatus.INSUFFICIENT_FEATURES, false, aligned, PlacementState.FindingFloor))
        assertEquals(StatusTag.FindingYourPlaceAgain, ARExperienceRules.statusTag(TrackingStatus.RELOCALIZING, false, aligned, PlacementState.FindingFloor))
        assertEquals(StatusTag.FindingYourPlaceAgain, ARExperienceRules.statusTag(TrackingStatus.INTERRUPTED, false, aligned, PlacementState.FindingFloor))
        assertEquals(StatusTag.ARUnavailable, ARExperienceRules.statusTag(TrackingStatus.NOT_AVAILABLE, false, aligned, PlacementState.FindingFloor))
    }

    @Test
    fun `points, lost, by points, plate, searching, then the placement`() {
        val tracking = TrackingStatus.NORMAL
        val lost = anchoring(phase = PlateAnchoring.Phase.Lost, alignedPlate = 1 to "A")
        assertEquals(StatusTag.AligningByPoints, ARExperienceRules.statusTag(tracking, true, lost, PlacementState.FindingFloor))
        assertEquals(StatusTag.AlignmentLost, ARExperienceRules.statusTag(tracking, false, lost, PlacementState.FindingFloor))
        val byPoints = anchoring(phase = PlateAnchoring.Phase.Manual, method = PlateAnchoring.Method.Points, isManual = true)
        assertEquals(StatusTag.AlignedByPoints, ARExperienceRules.statusTag(tracking, false, byPoints, PlacementState.Locked(placed)))
        val onPlate = anchoring(phase = PlateAnchoring.Phase.Aligned, method = PlateAnchoring.Method.Plate(2), alignedPlate = 2 to "Electrical panel")
        assertEquals(StatusTag.AlignedAtPlate(2, "Electrical panel"), ARExperienceRules.statusTag(tracking, false, onPlate, PlacementState.Locked(placed)))
        assertEquals(StatusTag.LookingForPlate, ARExperienceRules.statusTag(tracking, false, anchoring(), PlacementState.FindingFloor))
        assertEquals(StatusTag.NotPlacedYet, ARExperienceRules.statusTag(TrackingStatus.INITIALIZING, false, null, PlacementState.ReadyToPlace))
        assertEquals(StatusTag.PlacedManuallyAdjusting, ARExperienceRules.statusTag(tracking, false, null, PlacementState.Adjusting(placed)))
        assertEquals(StatusTag.PlacedManually, ARExperienceRules.statusTag(tracking, false, null, PlacementState.Locked(placed)))
    }

    @Test
    fun `re-align after points reads placed manually, as on iOS`() {
        val realigning = anchoring(phase = PlateAnchoring.Phase.Realigning(requested = true), method = PlateAnchoring.Method.Points)
        assertEquals(StatusTag.PlacedManually, ARExperienceRules.statusTag(TrackingStatus.NORMAL, false, realigning, PlacementState.Locked(placed)))
    }

    // Coaching line

    @Test
    fun `loading the model, then nothing while a panel leads`() {
        assertEquals(CoachingLine.LoadingModel, ARExperienceRules.coachingLine(false, true, null, PlacementState.FindingFloor))
        assertNull(ARExperienceRules.coachingLine(true, true, anchoring(), PlacementState.FindingFloor))
    }

    @Test
    fun `plate coaching follows the anchoring`() {
        fun line(anchoring: ARAnchoringState, placement: PlacementState = PlacementState.FindingFloor) =
            ARExperienceRules.coachingLine(true, false, anchoring, placement)
        assertEquals(
            CoachingLine.PointAtScannedPlate(2, "Panel"),
            line(anchoring(coaching = PlateAnchoring.Coaching.PointAtScannedPlate(2, "Panel"))),
        )
        assertEquals(CoachingLine.PointAtAnyPlate, line(anchoring(coaching = PlateAnchoring.Coaching.PointAtAnyPlate)))
        assertEquals(CoachingLine.HoldStill, line(anchoring(coaching = PlateAnchoring.Coaching.HoldStill(2))))
        assertEquals(CoachingLine.AlignmentLostPlates, line(anchoring(phase = PlateAnchoring.Phase.Lost, coaching = PlateAnchoring.Coaching.AlignmentLost)))
        val aligned = anchoring(phase = PlateAnchoring.Phase.Aligned, coaching = PlateAnchoring.Coaching.None, alignedPlate = 1 to "")
        assertEquals(CoachingLine.TapToIdentify, line(aligned))
        val realigning = anchoring(phase = PlateAnchoring.Phase.Realigning(requested = true), coaching = PlateAnchoring.Coaching.None, alignedPlate = 1 to "")
        assertEquals(CoachingLine.PointAtAnyPlate, line(realigning))
        val notAligned = anchoring(phase = PlateAnchoring.Phase.Realigning(requested = false), coaching = PlateAnchoring.Coaching.None)
        assertEquals(CoachingLine.PointAtAnyPlate, line(notAligned))
    }

    @Test
    fun `placing by hand coaches by placement`() {
        fun line(anchoring: ARAnchoringState?, placement: PlacementState) = ARExperienceRules.coachingLine(true, false, anchoring, placement)
        val manual = anchoring(phase = PlateAnchoring.Phase.Manual, coaching = PlateAnchoring.Coaching.PlaceManually, isManual = true)
        assertEquals(CoachingLine.FindTheFloor, line(null, PlacementState.FindingFloor))
        assertEquals(CoachingLine.TapTheFloor, line(manual, PlacementState.ReadyToPlace))
        assertEquals(CoachingLine.DragToAdjust, line(manual, PlacementState.Adjusting(placed)))
        assertEquals(CoachingLine.TapToIdentify, line(manual, PlacementState.Locked(placed)))
        val lost = anchoring(phase = PlateAnchoring.Phase.Lost, method = PlateAnchoring.Method.Manual, isManual = true)
        assertEquals(CoachingLine.AlignmentLostByHand, line(lost, PlacementState.Locked(placed)))
    }

    // The 4 s offer

    @Test
    fun `no plate is offered after 4 s, at once when lost`() {
        val searching = anchoring()
        fun action(anchoring: ARAnchoringState?, seconds: Double, points: Boolean = true, fix: Boolean = false, aligned: Boolean = false) =
            ARExperienceRules.coachingAction(fix, anchoring, aligned, seconds, points)
        assertNull(action(searching, 3.9))
        assertNull(action(searching, 4.0))
        assertEquals(CoachingAction.ALIGN_BY_POINTS, action(searching, 4.1))
        assertEquals(CoachingAction.PLACE_MANUALLY, action(searching, 4.1, points = false))
        val lost = anchoring(phase = PlateAnchoring.Phase.Lost)
        assertEquals(CoachingAction.ALIGN_BY_POINTS, action(lost, 0.0, aligned = true))
        assertNull(action(searching, 10.0, aligned = true))
        assertNull(action(anchoring(isManual = true), 10.0))
        assertNull(action(null, 10.0))
        assertEquals(CoachingAction.FIX_HERE, action(searching, 0.0, fix = true))
    }

    @Test
    fun `the offer ticks only while pending`() {
        assertTrue(ARExperienceRules.offersNoPlate(anchoring(), isAligned = false))
        assertTrue(ARExperienceRules.offersNoPlate(anchoring(phase = PlateAnchoring.Phase.Lost), isAligned = true))
        assertFalse(ARExperienceRules.offersNoPlate(anchoring(), isAligned = true))
        assertFalse(ARExperienceRules.offersNoPlate(anchoring(isManual = true), isAligned = false))
        assertFalse(ARExperienceRules.offersNoPlate(null, isAligned = false))
    }

    // Menu

    private fun menu(
        canFixHere: Boolean = false,
        canSaveRoom: Boolean = false,
        pointsActive: Boolean = false,
        registrationActive: Boolean = false,
        canAlignByPoints: Boolean = false,
        canManagePlates: Boolean = false,
        hasPlates: Boolean = false,
        isManual: Boolean = true,
        placement: PlacementState = PlacementState.FindingFloor,
        storeyCount: Int = 1,
        roomCount: Int = 0,
    ) = ARExperienceRules.menuItems(
        canFixHere, canSaveRoom, pointsActive, registrationActive, canAlignByPoints, canManagePlates, hasPlates, isManual, placement, storeyCount, roomCount,
    )

    @Test
    fun `the safety note is always there`() {
        assertEquals(listOf(ARMenuItem.SAFETY_NOTE), menu())
    }

    @Test
    fun `every item in order`() {
        assertEquals(
            listOf(
                ARMenuItem.FIX_HERE, ARMenuItem.SAVE_ROOM, ARMenuItem.ALIGN_BY_POINTS, ARMenuItem.REGISTER_PLATE, ARMenuItem.REALIGN,
                ARMenuItem.ADJUST_PLACEMENT, ARMenuItem.PLACE_AGAIN, ARMenuItem.LEVEL, ARMenuItem.ROOM, ARMenuItem.SAFETY_NOTE,
            ),
            menu(
                canFixHere = true, canSaveRoom = true, canAlignByPoints = true, canManagePlates = true, hasPlates = true,
                placement = PlacementState.Locked(placed), storeyCount = 2, roomCount = 3,
            ),
        )
    }

    @Test
    fun `placement items depend on manual and the placement`() {
        assertEquals(listOf(ARMenuItem.PLACE_MANUALLY, ARMenuItem.SAFETY_NOTE), menu(isManual = false, placement = PlacementState.Locked(placed)))
        assertEquals(listOf(ARMenuItem.PLACE_AGAIN, ARMenuItem.SAFETY_NOTE), menu(placement = PlacementState.Adjusting(placed)))
        assertEquals(listOf(ARMenuItem.SAFETY_NOTE), menu(placement = PlacementState.ReadyToPlace))
    }

    @Test
    fun `points and registration hide their own items`() {
        assertEquals(listOf(ARMenuItem.SAFETY_NOTE), menu(pointsActive = true, canAlignByPoints = true))
        assertEquals(listOf(ARMenuItem.SAFETY_NOTE), menu(registrationActive = true, canAlignByPoints = true, canManagePlates = true))
        assertEquals(listOf(ARMenuItem.REGISTER_PLATE, ARMenuItem.SAFETY_NOTE), menu(pointsActive = true, canManagePlates = true))
    }

    @Test
    fun `fix here and room save need nothing else on screen`() {
        val canFix = anchoring(canFix = true)
        assertTrue(ARExperienceRules.canFixHere(true, false, false, false, canFix))
        assertFalse(ARExperienceRules.canFixHere(false, false, false, false, canFix))
        assertFalse(ARExperienceRules.canFixHere(true, true, false, false, canFix))
        assertFalse(ARExperienceRules.canFixHere(true, false, true, false, canFix))
        assertFalse(ARExperienceRules.canFixHere(true, false, false, true, canFix))
        assertFalse(ARExperienceRules.canFixHere(true, false, false, false, anchoring(canFix = false)))
        assertFalse(ARExperienceRules.canFixHere(true, false, false, false, null))
        assertTrue(ARExperienceRules.canSaveRoom(true, true, false, false, false, false))
        assertFalse(ARExperienceRules.canSaveRoom(false, true, false, false, false, false))
        assertFalse(ARExperienceRules.canSaveRoom(true, false, false, false, false, false))
        assertFalse(ARExperienceRules.canSaveRoom(true, true, false, false, false, true))
    }

    // Panels

    @Test
    fun `bottom panel priority`() {
        assertEquals(ARBottomPanel.REGISTRATION, ARExperienceRules.bottomPanel(true, true, true, true))
        assertEquals(ARBottomPanel.POINTS, ARExperienceRules.bottomPanel(false, true, true, true))
        assertEquals(ARBottomPanel.FIX_HERE, ARExperienceRules.bottomPanel(false, false, true, true))
        assertEquals(ARBottomPanel.ROOM_SAVE, ARExperienceRules.bottomPanel(false, false, false, true))
        assertEquals(ARBottomPanel.STANDARD, ARExperienceRules.bottomPanel(false, false, false, false))
    }

    @Test
    fun `adjusting by hand shows fine-tune, otherwise see inside`() {
        assertTrue(ARExperienceRules.showsAdjustingControls(true, PlacementState.Adjusting(placed)))
        assertFalse(ARExperienceRules.showsSeeInside(true, PlacementState.Adjusting(placed), isAligned = false, manifestLoaded = true))
        assertFalse(ARExperienceRules.showsAdjustingControls(false, PlacementState.Adjusting(placed)))
        assertTrue(ARExperienceRules.showsSeeInside(false, PlacementState.Adjusting(placed), isAligned = false, manifestLoaded = true))
        assertFalse(ARExperienceRules.showsSeeInside(true, PlacementState.FindingFloor, isAligned = false, manifestLoaded = false))
    }

    @Test
    fun `aligned and manual derivations`() {
        assertTrue(ARExperienceRules.isManual(null))
        assertFalse(ARExperienceRules.isManual(anchoring()))
        assertTrue(ARExperienceRules.isAligned(anchoring(alignedPlate = 1 to ""), PlacementState.FindingFloor))
        assertTrue(ARExperienceRules.isAligned(null, PlacementState.Locked(placed)))
        assertFalse(ARExperienceRules.isAligned(null, PlacementState.Adjusting(placed)))
    }

    @Test
    fun `the crosshair shows only with something to aim at`() {
        assertTrue(ARExperienceRules.showsCrosshair(true, true, CrosshairState.TOO_SHALLOW, false, null, false))
        assertFalse(ARExperienceRules.showsCrosshair(false, true, CrosshairState.READY, false, null, false))
        assertFalse(ARExperienceRules.showsCrosshair(true, false, CrosshairState.READY, false, null, false))
        assertFalse(ARExperienceRules.showsCrosshair(true, true, CrosshairState.NO_FLOOR, false, null, false))
        assertTrue(ARExperienceRules.showsCrosshair(true, true, CrosshairState.NO_FLOOR, true, null, false))
        assertTrue(ARExperienceRules.showsCrosshair(false, false, null, false, CrosshairState.READY, false))
        assertFalse(ARExperienceRules.showsCrosshair(false, false, null, false, CrosshairState.NOT_TRACKING, false))
        assertEquals(CrosshairLabel.HOLD_STILL, ARExperienceRules.crosshairLabel(CrosshairState.READY, capturing = true))
        assertEquals(CrosshairLabel.ON_FLOOR, ARExperienceRules.crosshairLabel(CrosshairState.READY, capturing = false))
    }

    // Starting storey and automatic points

    @Test
    fun `the starting storey is chosen only once and only with several`() {
        assertTrue(ARExperienceRules.choosesStartingStorey(null, 2))
        assertFalse(ARExperienceRules.choosesStartingStorey(null, 1))
        assertFalse(ARExperienceRules.choosesStartingStorey("s1", 3))
    }

    @Test
    fun `points start without plates or on the fallback`() {
        assertTrue(ARExperienceRules.startsPointsAtConfigure(0, true))
        assertFalse(ARExperienceRules.startsPointsAtConfigure(1, true))
        assertFalse(ARExperienceRules.startsPointsAtConfigure(0, false))
        assertTrue(ARExperienceRules.startsPointsOnFallback(false, true))
        assertFalse(ARExperienceRules.startsPointsOnFallback(true, true))
    }

    // Points

    @Test
    fun `room preselection order`() {
        val eligible = listOf("kitchen", "hall")
        assertEquals("hall", ARExperienceRules.preselectedRoom(eligible, located = "hall", current = "kitchen"))
        assertEquals("kitchen", ARExperienceRules.preselectedRoom(eligible, located = "attic", current = "kitchen"))
        assertNull(ARExperienceRules.preselectedRoom(eligible, located = null, current = "attic"))
        assertEquals("hall", ARExperienceRules.preselectedRoom(listOf("hall"), located = null, current = null))
        assertNull(ARExperienceRules.preselectedRoom(emptyList(), located = "hall", current = "hall"))
    }

    @Test
    fun `marks needed drop to 2 with a wall`() {
        val floor = ReferenceMark(Vec3.zero, ReferenceMark.Surface.FLOOR)
        val edge = ReferenceMark(Vec3.zero, ReferenceMark.Surface.EDGE)
        val wall = ReferenceMark(Vec3.zero, ReferenceMark.Surface.WALL_PLANE, normal = Vec3(1.0, 0.0, 0.0))
        assertEquals(3, ARExperienceRules.marksNeeded(emptyList()))
        assertEquals(3, ARExperienceRules.marksNeeded(listOf(floor, edge)))
        assertEquals(2, ARExperienceRules.marksNeeded(listOf(floor, wall)))
    }

    @Test
    fun `points instruction priority`() {
        assertEquals(PointsInstruction.UNSTEADY, ARExperienceRules.pointsInstruction(true, ReferenceAlignment.NoMatch.TOO_FAR, ReferenceAlignment.Hint.MORE_POINTS))
        assertEquals(PointsInstruction.NO_CORNERS, ARExperienceRules.pointsInstruction(false, ReferenceAlignment.NoMatch.NO_OBJECTS, null))
        assertEquals(PointsInstruction.TOO_FAR, ARExperienceRules.pointsInstruction(false, ReferenceAlignment.NoMatch.TOO_FAR, ReferenceAlignment.Hint.MORE_POINTS))
        assertEquals(PointsInstruction.MORE_POINTS, ARExperienceRules.pointsInstruction(false, null, ReferenceAlignment.Hint.MORE_POINTS))
        assertEquals(PointsInstruction.ANOTHER_WALL, ARExperienceRules.pointsInstruction(false, null, ReferenceAlignment.Hint.SPREAD_OUT))
        assertEquals(PointsInstruction.DEFAULT, ARExperienceRules.pointsInstruction(false, null, null))
    }

    @Test
    fun `fix problems by surface`() {
        assertEquals(FixProblem.NOT_A_WALL, ARExperienceRules.fixProblem(ReferenceMark.Surface.WALL_PLANE))
        assertEquals(FixProblem.NOT_A_CORNER, ARExperienceRules.fixProblem(ReferenceMark.Surface.EDGE))
        assertEquals(FixProblem.NOT_A_CORNER, ARExperienceRules.fixProblem(ReferenceMark.Surface.FLOOR))
    }

    @Test
    fun `copied details are sorted JSON in millimetres`() {
        val mark = ReferenceMark(Vec3(1.23456, -0.0004, 2.0), ReferenceMark.Surface.FLOOR, seenFrom = Vec3(0.0, 1.5, 0.0))
        val moved = mark.copy(position = Vec3(1.2346, 0.01, 2.0))
        val json = ARExperienceRules.copyDetailsJSON(
            room = ARExperienceRules.RoomDetails(id = "e1", name = "Kitchen", hasOutline = true, floorY = 0.0),
            worldFloorY = -1.4567,
            floorPlanes = listOf(FloorPlane(height = 0.2, area = 3.0, isFloor = false), FloorPlane(height = -1.45, area = 1.0, isFloor = false)),
            cameraY = null,
            references = listOf(
                ReferencePoint(id = "corner-0", kind = ReferencePoint.Kind.CORNER, position = Vec3(0.5, 0.0, 0.25), elementID = null, height = 0.0),
            ),
            marks = listOf(mark),
            marksOnFloor = listOf(moved),
            outcome = null,
        )
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(listOf("cameraY", "floorPlanes", "marks", "outcome", "references", "room", "worldFloorY"), root.keys.toList())
        assertEquals(JsonNull, root["cameraY"])
        assertEquals("-1.457", root["worldFloorY"]!!.jsonPrimitive.content)
        assertEquals("none", root["outcome"]!!.jsonPrimitive.content)
        val planes = root["floorPlanes"]!!.jsonArray
        assertEquals(listOf("-1.45", "0.2"), planes.map { it.jsonObject["y"]!!.jsonPrimitive.content })
        assertEquals(listOf("areaM2", "isFloor", "y"), planes[0].jsonObject.keys.toList())
        val markJSON = root["marks"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("marked", "position", "seenFrom", "surface"), markJSON.keys.toList())
        assertEquals(listOf("1.235", "0.0", "2.0"), (markJSON["marked"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("1.235", "0.01", "2.0"), (markJSON["position"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("floor", markJSON["surface"]!!.jsonPrimitive.content)
        val room = root["room"] as JsonObject
        assertEquals(listOf("floorY", "hasOutline", "id", "name"), room.keys.toList())
        assertEquals("Kitchen", room["name"]!!.jsonPrimitive.content)
        val reference = root["references"]!!.jsonArray[0].jsonObject
        assertEquals("corner", reference["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `millimetres round halves away from zero`() {
        assertEquals(0.002, ARExperienceRules.millimetres(0.0015))
        assertEquals(-0.002, ARExperienceRules.millimetres(-0.0015))
        assertEquals(1.0, ARExperienceRules.millimetres(0.99999))
    }

    // Android-only gates

    @Test
    fun `camera access from Android's answers`() {
        assertEquals(CameraAccess.AUTHORIZED, ARExperienceRules.cameraAccess(granted = true, showsRationale = false, askedThisScreen = true))
        assertEquals(CameraAccess.NOT_DETERMINED, ARExperienceRules.cameraAccess(granted = false, showsRationale = false, askedThisScreen = false))
        assertEquals(CameraAccess.NOT_DETERMINED, ARExperienceRules.cameraAccess(granted = false, showsRationale = true, askedThisScreen = true))
        assertEquals(CameraAccess.DENIED, ARExperienceRules.cameraAccess(granted = false, showsRationale = false, askedThisScreen = true))
    }

    @Test
    fun `ARCore availability gate`() {
        assertEquals(ARCoreStep.CHECKING, ARExperienceRules.arCoreStep(ARCoreAvailability.CHECKING))
        assertEquals(ARCoreStep.READY, ARExperienceRules.arCoreStep(ARCoreAvailability.SUPPORTED_INSTALLED))
        assertEquals(ARCoreStep.NEEDS_INSTALL, ARExperienceRules.arCoreStep(ARCoreAvailability.SUPPORTED_NOT_INSTALLED))
        assertEquals(ARCoreStep.UNSUPPORTED, ARExperienceRules.arCoreStep(ARCoreAvailability.UNSUPPORTED))
        assertEquals(ARCoreStep.READY, ARExperienceRules.arCoreStep(ARCoreAvailability.UNKNOWN_ERROR))
    }

    @Test
    fun `room save lengths and angles`() {
        assertEquals("4.2 cm", ARExperienceRules.saveLength(0.042, Locale.US))
        assertEquals("1.3 m", ARExperienceRules.saveLength(1.25001, Locale.US))
        assertEquals("4,2 cm", ARExperienceRules.saveLength(0.042, Locale.forLanguageTag("pt-BR")))
        assertEquals("0.6°", ARExperienceRules.saveAngle(0.6, Locale.US))
    }
}
