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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The AR screen's rules (iOS `ARExperienceModel`/`ARExperienceView`, `PointsAlignment`): which tag,
// coaching line, menu items and panel show, as plain values without Android, so they're tested on
// the JVM (ARExperienceRulesTest). The composables map the values to strings and controls.

/**
 * What the screen reads of the view's [PlateAnchoring] (iOS `model.anchoring`, a value copy):
 * [PlateAnchoring] is a mutable class, so the model keeps this snapshot, taken each time the view
 * reports a change (`onAnchoringChange`), which Compose can compare.
 */
data class ARAnchoringState(
    val phase: PlateAnchoring.Phase,
    val method: PlateAnchoring.Method?,
    val coaching: PlateAnchoring.Coaching,
    /** The plate it's aligned at: number and label. */
    val alignedPlate: Pair<Int, String>?,
    val isManual: Boolean,
    val hasPlates: Boolean,
    val canFix: Boolean,
) {
    companion object {
        fun of(anchoring: PlateAnchoring): ARAnchoringState = ARAnchoringState(
            phase = anchoring.phase,
            method = anchoring.alignment?.method,
            coaching = anchoring.coaching,
            alignedPlate = anchoring.alignedPlate?.let { it.number to it.label },
            isManual = anchoring.isManual,
            hasPlates = anchoring.plates.isNotEmpty(),
            canFix = anchoring.canFix,
        )
    }
}

/** The tag at the top of the AR screen (AEV 608-638). */
sealed interface StatusTag {
    data object MoveMoreSlowly : StatusTag

    data object MoreDetail : StatusTag

    data object FindingYourPlaceAgain : StatusTag

    data object ARUnavailable : StatusTag

    data object AligningByPoints : StatusTag

    data object AlignmentLost : StatusTag

    data object AlignedByPoints : StatusTag

    data class AlignedAtPlate(val number: Int, val label: String) : StatusTag

    data object LookingForPlate : StatusTag

    data object NotPlacedYet : StatusTag

    data object PlacedManuallyAdjusting : StatusTag

    data object PlacedManually : StatusTag
}

/** The coaching line above the See inside panel (AEV 986-1020). */
sealed interface CoachingLine {
    data object LoadingModel : CoachingLine

    data class PointAtScannedPlate(val number: Int, val label: String) : CoachingLine

    data object PointAtAnyPlate : CoachingLine

    data object HoldStill : CoachingLine

    /** Plates: "Alignment lost. Point at a plate again, or move slowly…". */
    data object AlignmentLostPlates : CoachingLine

    /** By hand: "Alignment lost. Move slowly…, or place the model again." */
    data object AlignmentLostByHand : CoachingLine

    data object FindTheFloor : CoachingLine

    data object TapTheFloor : CoachingLine

    data object DragToAdjust : CoachingLine

    data object TapToIdentify : CoachingLine
}

/** The link under the coaching line (AEV 1022-1055). */
enum class CoachingAction {
    /** "Model off? Fix here". */
    FIX_HERE,

    /** "No plate? Align by points". */
    ALIGN_BY_POINTS,

    /** "Place manually instead". */
    PLACE_MANUALLY,
}

/** The ⋮ menu's items, in order (AEV 641-743); dividers go before [LEVEL] and [SAFETY_NOTE]. */
enum class ARMenuItem {
    FIX_HERE,
    SAVE_ROOM,
    ALIGN_BY_POINTS,
    REGISTER_PLATE,
    REALIGN,
    ADJUST_PLACEMENT,
    PLACE_AGAIN,
    PLACE_MANUALLY,
    LEVEL,
    ROOM,
    SAFETY_NOTE,
}

/** The bottom panel, by priority (AEV 747-765). */
enum class ARBottomPanel { REGISTRATION, POINTS, FIX_HERE, ROOM_SAVE, STANDARD }

/** What the crosshair's label says (PA 354-367). */
enum class CrosshairLabel { HOLD_STILL, NO_FLOOR, NOT_TRACKING, TOO_SHALLOW, ON_FLOOR, ON_WALL, ON_CORNER, NO_SURFACE }

/** The points panel's instruction (PA 526-553); the texts differ with wall marks (depth sensor). */
enum class PointsInstruction { UNSTEADY, NO_CORNERS, TOO_FAR, MORE_POINTS, ANOTHER_WALL, DEFAULT }

/** ARCore on this phone (`ArCoreApk.Availability`, without ARCore). */
enum class ARCoreAvailability { CHECKING, SUPPORTED_INSTALLED, SUPPORTED_NOT_INSTALLED, UNSUPPORTED, UNKNOWN_ERROR }

/** The gate before the preflight (Android only: iOS AR needs no install). */
enum class ARCoreStep { CHECKING, READY, NEEDS_INSTALL, UNSUPPORTED }

object ARExperienceRules {
    /** "No plate? Align by points" shows this long after AR opened (AEV 1034). */
    const val NO_PLATE_OFFER_DELAY = 4.0

    /** "Details copied" shows this long (PA 302-305). */
    const val DETAILS_COPIED_MILLIS = 2_000L

    /** Far from the alignment it was measured from: a warning on the room-save card (AEV 902). */
    const val FAR_BASE_DISTANCE = 8.0

    /** First match wins: tracking, points, lost, by points, plate, searching, then the placement. */
    fun statusTag(tracking: TrackingStatus, pointsActive: Boolean, anchoring: ARAnchoringState?, placement: PlacementState): StatusTag {
        when (tracking) {
            TrackingStatus.EXCESSIVE_MOTION -> return StatusTag.MoveMoreSlowly
            TrackingStatus.INSUFFICIENT_FEATURES -> return StatusTag.MoreDetail
            TrackingStatus.RELOCALIZING, TrackingStatus.INTERRUPTED -> return StatusTag.FindingYourPlaceAgain
            TrackingStatus.NOT_AVAILABLE -> return StatusTag.ARUnavailable
            TrackingStatus.NORMAL, TrackingStatus.INITIALIZING -> Unit
        }
        if (pointsActive) return StatusTag.AligningByPoints
        if (anchoring != null) {
            if (anchoring.phase == PlateAnchoring.Phase.Lost) return StatusTag.AlignmentLost
            if (anchoring.method == PlateAnchoring.Method.Points && anchoring.phase == PlateAnchoring.Phase.Manual) {
                return StatusTag.AlignedByPoints
            }
            anchoring.alignedPlate?.let { (number, label) -> return StatusTag.AlignedAtPlate(number, label) }
            if (anchoring.phase == PlateAnchoring.Phase.Searching) return StatusTag.LookingForPlate
        }
        // As on iOS, "Re-align" after an alignment by points (phase realigning) falls through to
        // the placement: "Placed manually" (docs/ios-ar-reference.md §10.12).
        return when (placement) {
            PlacementState.FindingFloor, PlacementState.ReadyToPlace -> StatusTag.NotPlacedYet
            is PlacementState.Adjusting -> StatusTag.PlacedManuallyAdjusting
            is PlacementState.Locked -> StatusTag.PlacedManually
        }
    }

    /** iOS `isManual`: manual placement until the anchoring says otherwise. */
    fun isManual(anchoring: ARAnchoringState?): Boolean = anchoring?.isManual ?: true

    /** iOS `isAligned`: on a plate, or a placement locked. */
    fun isAligned(anchoring: ARAnchoringState?, placement: PlacementState): Boolean =
        anchoring?.alignedPlate != null || placement is PlacementState.Locked

    /**
     * What to do now: plate or floor coaching, alignment lost, or the tap hint once aligned.
     * `null` while points, registration or "Fix here" have their own panel.
     */
    fun coachingLine(
        manifestLoaded: Boolean,
        panelActive: Boolean,
        anchoring: ARAnchoringState?,
        placement: PlacementState,
    ): CoachingLine? {
        if (!manifestLoaded) return CoachingLine.LoadingModel
        if (panelActive) return null
        if (anchoring != null && !anchoring.isManual) {
            when (val coaching = anchoring.coaching) {
                is PlateAnchoring.Coaching.PointAtScannedPlate -> return CoachingLine.PointAtScannedPlate(coaching.number, coaching.label)
                PlateAnchoring.Coaching.PointAtAnyPlate -> return CoachingLine.PointAtAnyPlate
                is PlateAnchoring.Coaching.HoldStill -> return CoachingLine.HoldStill
                PlateAnchoring.Coaching.AlignmentLost -> return CoachingLine.AlignmentLostPlates
                PlateAnchoring.Coaching.PlaceManually, PlateAnchoring.Coaching.None -> Unit
            }
            if (anchoring.phase == PlateAnchoring.Phase.Realigning(requested = true)) return CoachingLine.PointAtAnyPlate
            return if (isAligned(anchoring, placement)) CoachingLine.TapToIdentify else CoachingLine.PointAtAnyPlate
        }
        if (anchoring?.phase == PlateAnchoring.Phase.Lost) return CoachingLine.AlignmentLostByHand
        return when (placement) {
            PlacementState.FindingFloor -> CoachingLine.FindTheFloor
            PlacementState.ReadyToPlace -> CoachingLine.TapTheFloor
            is PlacementState.Adjusting -> CoachingLine.DragToAdjust
            is PlacementState.Locked -> CoachingLine.TapToIdentify
        }
    }

    /**
     * Whether the screen should re-check [coachingAction] every second: the "No plate?" offer is
     * pending (plates searched for and not aligned, or lost). iOS's `TimelineView(.periodic(by: 1))`.
     */
    fun offersNoPlate(anchoring: ARAnchoringState?, isAligned: Boolean): Boolean =
        anchoring != null && !anchoring.isManual && (!isAligned || anchoring.phase == PlateAnchoring.Phase.Lost)

    /**
     * The link under the coaching line: "Model off? Fix here" when it can fix; otherwise, while
     * plates are searched for, after [NO_PLATE_OFFER_DELAY] (at once when lost), "No plate? Align
     * by points" when a room has corners, else "Place manually instead".
     *
     * @param secondsSinceOpened since AR opened (iOS `openedAt`), as re-read every second.
     */
    fun coachingAction(
        canFixHere: Boolean,
        anchoring: ARAnchoringState?,
        isAligned: Boolean,
        secondsSinceOpened: Double,
        canAlignByPoints: Boolean,
    ): CoachingAction? {
        if (canFixHere) return CoachingAction.FIX_HERE
        if (!offersNoPlate(anchoring, isAligned)) return null
        if (secondsSinceOpened > NO_PLATE_OFFER_DELAY || anchoring?.phase == PlateAnchoring.Phase.Lost) {
            return if (canAlignByPoints) CoachingAction.ALIGN_BY_POINTS else CoachingAction.PLACE_MANUALLY
        }
        return null
    }

    /** iOS `canFixHere`: started, nothing else on screen, and the anchoring can fix. */
    fun canFixHere(started: Boolean, pointsActive: Boolean, registrationActive: Boolean, fixActive: Boolean, anchoring: ARAnchoringState?): Boolean =
        started && !pointsActive && !registrationActive && !fixActive && anchoring?.canFix == true

    /** iOS `canSaveRoom`: an admin fixed a room this session, and nothing else is on screen. */
    fun canSaveRoom(
        canManageRoomCorrections: Boolean,
        hasRoomFix: Boolean,
        pointsActive: Boolean,
        registrationActive: Boolean,
        fixActive: Boolean,
        roomSaveActive: Boolean,
    ): Boolean = canManageRoomCorrections && hasRoomFix && !pointsActive && !registrationActive && !fixActive && !roomSaveActive

    /** The ⋮ menu (AEV 641-743). */
    fun menuItems(
        canFixHere: Boolean,
        canSaveRoom: Boolean,
        pointsActive: Boolean,
        registrationActive: Boolean,
        canAlignByPoints: Boolean,
        canManagePlates: Boolean,
        hasPlates: Boolean,
        isManual: Boolean,
        placement: PlacementState,
        storeyCount: Int,
        roomCount: Int,
    ): List<ARMenuItem> = buildList {
        if (canFixHere) add(ARMenuItem.FIX_HERE)
        if (canSaveRoom) add(ARMenuItem.SAVE_ROOM)
        if (!pointsActive && !registrationActive && canAlignByPoints) add(ARMenuItem.ALIGN_BY_POINTS)
        if (canManagePlates && !registrationActive) add(ARMenuItem.REGISTER_PLATE)
        if (hasPlates) add(ARMenuItem.REALIGN)
        if (isManual) {
            if (placement is PlacementState.Locked) add(ARMenuItem.ADJUST_PLACEMENT)
            if (placement.transform != null) add(ARMenuItem.PLACE_AGAIN)
        } else {
            add(ARMenuItem.PLACE_MANUALLY)
        }
        if (storeyCount > 1) add(ARMenuItem.LEVEL)
        if (roomCount > 0) add(ARMenuItem.ROOM)
        add(ARMenuItem.SAFETY_NOTE)
    }

    fun bottomPanel(registrationActive: Boolean, pointsActive: Boolean, fixActive: Boolean, roomSaveActive: Boolean): ARBottomPanel = when {
        registrationActive -> ARBottomPanel.REGISTRATION
        pointsActive -> ARBottomPanel.POINTS
        fixActive -> ARBottomPanel.FIX_HERE
        roomSaveActive -> ARBottomPanel.ROOM_SAVE
        else -> ARBottomPanel.STANDARD
    }

    /** The standard panel's controls: [Fine-tune] [Done] while adjusting by hand (AEV 784). */
    fun showsAdjustingControls(isManual: Boolean, placement: PlacementState): Boolean = isManual && placement is PlacementState.Adjusting

    /** Otherwise See inside, once aligned or the model is there (AEV 803). */
    fun showsSeeInside(isManual: Boolean, placement: PlacementState, isAligned: Boolean, manifestLoaded: Boolean): Boolean =
        !showsAdjustingControls(isManual, placement) && (isAligned || manifestLoaded)

    /** iOS `chooseStartingStorey`: only when no level is chosen and the model has more than one. */
    fun choosesStartingStorey(currentStoreyID: String?, storeyCount: Int): Boolean = currentStoreyID == null && storeyCount > 1

    /** No placed plates: marking starts right away when a room allows it (AEV 167). */
    fun startsPointsAtConfigure(placedPlates: Int, canAlignByPoints: Boolean): Boolean = placedPlates == 0 && canAlignByPoints

    /** The fallback to manual: marking points when the room allows it (AEV 442-446). */
    fun startsPointsOnFallback(pointsActive: Boolean, canAlignByPoints: Boolean): Boolean = !pointsActive && canAlignByPoints

    /** The crosshair over the camera: aiming with something to aim at (AEV 547-551). */
    fun showsCrosshair(
        pointsRoomChosen: Boolean,
        pointsMarking: Boolean,
        pointsCrosshair: CrosshairState?,
        pointsCapturing: Boolean,
        fixCrosshair: CrosshairState?,
        fixCapturing: Boolean,
    ): Boolean {
        if (pointsCrosshair != null && pointsRoomChosen && pointsMarking && (pointsCrosshair.canAim || pointsCapturing)) return true
        return fixCrosshair != null && (fixCrosshair.canAim || fixCapturing)
    }

    fun crosshairLabel(state: CrosshairState, capturing: Boolean): CrosshairLabel {
        if (capturing) return CrosshairLabel.HOLD_STILL
        return when (state) {
            CrosshairState.NO_FLOOR -> CrosshairLabel.NO_FLOOR
            CrosshairState.NOT_TRACKING -> CrosshairLabel.NOT_TRACKING
            CrosshairState.TOO_SHALLOW -> CrosshairLabel.TOO_SHALLOW
            CrosshairState.READY -> CrosshairLabel.ON_FLOOR
            CrosshairState.ON_WALL -> CrosshairLabel.ON_WALL
            CrosshairState.ON_CORNER -> CrosshairLabel.ON_CORNER
            CrosshairState.NO_SURFACE -> CrosshairLabel.NO_SURFACE
        }
    }

    /** The points instruction: a failed mark, then why nothing matched, then the hint, else how to mark. */
    fun pointsInstruction(unsteady: Boolean, noMatch: ReferenceAlignment.NoMatch?, hint: ReferenceAlignment.Hint?): PointsInstruction {
        if (unsteady) return PointsInstruction.UNSTEADY
        when (noMatch) {
            ReferenceAlignment.NoMatch.NO_CORNERS, ReferenceAlignment.NoMatch.NO_OBJECTS -> return PointsInstruction.NO_CORNERS
            ReferenceAlignment.NoMatch.TOO_FAR -> return PointsInstruction.TOO_FAR
            null -> Unit
        }
        return when (hint) {
            ReferenceAlignment.Hint.MORE_POINTS -> PointsInstruction.MORE_POINTS
            ReferenceAlignment.Hint.ANOTHER_WALL, ReferenceAlignment.Hint.SPREAD_OUT -> PointsInstruction.ANOTHER_WALL
            null -> PointsInstruction.DEFAULT
        }
    }

    /**
     * The room marking starts in (PA 55-66): where the camera is under the current alignment
     * (marks are made where the user stands), else the room picked in AR's filters, else the only
     * one; `null` shows the room picker. Only rooms with corners ([eligible]) count.
     */
    fun preselectedRoom(eligible: List<String>, located: String?, current: String?): String? = when {
        located != null && located in eligible -> located
        current != null && current in eligible -> current
        eligible.size == 1 -> eligible[0]
        else -> null
    }

    /** Marks needed before matching: 3, or 2 once a wall is among them (PA 137-139). */
    fun marksNeeded(marks: List<ReferenceMark>): Int =
        if (marks.any { it.surface == ReferenceMark.Surface.WALL_PLANE }) ReferenceAlignment.MINIMUM_MARKS_WITH_WALL else ReferenceAlignment.MINIMUM_MARKS

    /** "Fix here" found nothing: a wall aim missed a wall, any other a corner (AEV 268). */
    fun fixProblem(surface: ReferenceMark.Surface): FixProblem =
        if (surface == ReferenceMark.Surface.WALL_PLANE) FixProblem.NOT_A_WALL else FixProblem.NOT_A_CORNER

    /** The ARCore gate's step for an availability (Android only). */
    fun arCoreStep(availability: ARCoreAvailability): ARCoreStep = when (availability) {
        ARCoreAvailability.CHECKING -> ARCoreStep.CHECKING
        ARCoreAvailability.SUPPORTED_INSTALLED -> ARCoreStep.READY
        ARCoreAvailability.SUPPORTED_NOT_INSTALLED -> ARCoreStep.NEEDS_INSTALL
        ARCoreAvailability.UNSUPPORTED -> ARCoreStep.UNSUPPORTED
        // ARCore couldn't tell (offline on first use): try; the session reports a real failure.
        ARCoreAvailability.UNKNOWN_ERROR -> ARCoreStep.READY
    }

    /**
     * The camera permission as iOS reads it, from Android's answers (docs/PLAN.md §3 "AR": no
     * restricted; "Don't ask again" → denied with a Settings link). Before a request, or after a
     * denial the system will still ask about (it shows a rationale), it's "not determined": the
     * explainer asks again. A denial with no rationale after asking is "Don't ask again".
     *
     * @param askedThisScreen a request was answered on this screen.
     */
    fun cameraAccess(granted: Boolean, showsRationale: Boolean, askedThisScreen: Boolean): CameraAccess = when {
        granted -> CameraAccess.AUTHORIZED
        showsRationale -> CameraAccess.NOT_DETERMINED
        askedThisScreen -> CameraAccess.DENIED
        else -> CameraAccess.NOT_DETERMINED
    }

    /** "4.2 cm" / "1.3 m" for the room-save card (AEV 957-960): cm below 1 m, one decimal. */
    fun saveLength(metres: Double, locale: Locale): String =
        if (metres < 1) String.format(locale, "%.1f cm", metres * 100) else String.format(locale, "%.1f m", metres)

    /** "0.6°" (AEV 963-966). */
    fun saveAngle(degrees: Double, locale: Locale): String = String.format(locale, "%.1f°", degrees)

    // Copy details (PA 259-306)

    /** The room in the copied details. */
    data class RoomDetails(val id: String, val name: String, val hasOutline: Boolean, val floorY: Double?)

    /**
     * What the matching saw, as JSON for the clipboard (a long press on the marks counter), so a
     * failed alignment can be reported exactly: the room's model points, the marks (where they are
     * now on the floor, and where they were marked), the floor height and the planes it was chosen
     * from, the camera's height, and the outcome. Model coordinates for points, world for marks and
     * planes; metres rounded to the millimetre; keys sorted.
     */
    fun copyDetailsJSON(
        room: RoomDetails,
        worldFloorY: Double?,
        floorPlanes: List<FloorPlane>,
        cameraY: Double?,
        references: List<ReferencePoint>,
        marks: List<ReferenceMark>,
        marksOnFloor: List<ReferenceMark>,
        outcome: String?,
    ): String {
        val details = JsonObject(
            mapOf(
                "room" to JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(room.id),
                        "name" to JsonPrimitive(room.name),
                        "hasOutline" to JsonPrimitive(room.hasOutline),
                        "floorY" to number(room.floorY),
                    ),
                ),
                "worldFloorY" to number(worldFloorY),
                "floorPlanes" to JsonArray(
                    floorPlanes.sortedBy { it.height }.map { plane ->
                        JsonObject(mapOf("y" to number(plane.height), "areaM2" to number(plane.area), "isFloor" to JsonPrimitive(plane.isFloor)))
                    },
                ),
                "cameraY" to number(cameraY),
                "references" to JsonArray(
                    references.map { reference ->
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(reference.id),
                                "kind" to JsonPrimitive(reference.kind.raw),
                                "position" to vector(reference.position),
                            ),
                        )
                    },
                ),
                "marks" to JsonArray(
                    marks.zip(marksOnFloor).map { (mark, current) ->
                        JsonObject(
                            mapOf(
                                "surface" to JsonPrimitive(mark.surface.raw),
                                "position" to vector(current.position),
                                "marked" to vector(mark.position),
                                "seenFrom" to (mark.seenFrom?.let { vector(it) } ?: JsonNull),
                            ),
                        )
                    },
                ),
                "outcome" to JsonPrimitive(outcome ?: "none"),
            ),
        )
        return prettyJson.encodeToString(JsonElement.serializer(), sorted(details))
    }

    private val prettyJson = Json { prettyPrint = true }

    /** Swift's `(v * 1000).rounded() / 1000`: to the millimetre, halves away from zero. */
    internal fun millimetres(value: Double): Double {
        val scaled = abs(value * 1000)
        val rounded = floor(scaled + 0.5)
        return (if (value < 0) -rounded else rounded) / 1000
    }

    private fun number(value: Double?): JsonElement = value?.let { JsonPrimitive(millimetres(it)) } ?: JsonNull

    private fun vector(v: Vec3): JsonElement = JsonArray(listOf(v.x, v.y, v.z).map { JsonPrimitive(millimetres(it)) })

    /** iOS `.sortedKeys`. */
    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(element.map { sorted(it) })
        else -> element
    }
}

/** Why "Fix here" didn't fix (iOS `FixHere.Problem`). */
enum class FixProblem {
    /** The aim or tracking didn't hold while "Fix" averaged the crosshair. */
    UNSTEADY,

    /** No corner of the model near the mark, or two about as near. */
    NOT_A_CORNER,

    /** No wall of the model near the mark facing the same way (depth sensor). */
    NOT_A_WALL,
}
