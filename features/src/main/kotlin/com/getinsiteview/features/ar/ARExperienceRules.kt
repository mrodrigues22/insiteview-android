package com.getinsiteview.features.ar

import com.getinsiteview.core.CameraAccess
import com.getinsiteview.modelkit.ar.CrosshairState
import com.getinsiteview.modelkit.ar.FloorPlane
import com.getinsiteview.modelkit.ar.PlacementState
import com.getinsiteview.modelkit.ar.TrackingStatus
import com.getinsiteview.modelkit.geometry.PlateAnchoring
import com.getinsiteview.modelkit.geometry.ReferenceAlignment
import com.getinsiteview.modelkit.geometry.ReferenceFit
import com.getinsiteview.modelkit.geometry.ReferenceMark
import com.getinsiteview.modelkit.geometry.ReferencePoint
import com.getinsiteview.modelkit.geometry.Vec3
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
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
     * explainer asks again. A denial with no rationale after asking (on this screen or any earlier
     * one: the answer is remembered) is "Don't ask again".
     *
     * @param askedThisScreen a request was answered on this screen, or on an earlier one.
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
     * planes; metres rounded to the millimetre. The text is what iOS's `JSONSerialization` writes
     * with `[.prettyPrinted, .sortedKeys]` ([appleJSON]), and [outcome] is written as Swift's
     * `String(describing:)` ([outcomeDescription]), so reports from both apps compare.
     */
    fun copyDetailsJSON(
        room: RoomDetails,
        worldFloorY: Double?,
        floorPlanes: List<FloorPlane>,
        cameraY: Double?,
        references: List<ReferencePoint>,
        marks: List<ReferenceMark>,
        marksOnFloor: List<ReferenceMark>,
        outcome: ReferenceAlignment.Outcome?,
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
                "outcome" to JsonPrimitive(outcome?.let { outcomeDescription(it) } ?: "none"),
            ),
        )
        return appleJSON(details)
    }

    /** Swift's `(v * 1000).rounded() / 1000`: to the millimetre, halves away from zero. */
    internal fun millimetres(value: Double): Double {
        val scaled = abs(value * 1000)
        val rounded = floor(scaled + 0.5)
        if (rounded == 0.0) return 0.0
        return (if (value < 0) -rounded else rounded) / 1000
    }

    private fun number(value: Double?): JsonElement = value?.let { JsonPrimitive(millimetres(it)) } ?: JsonNull

    private fun vector(v: Vec3): JsonElement = JsonArray(listOf(v.x, v.y, v.z).map { JsonPrimitive(millimetres(it)) })

    /**
     * iOS `JSONSerialization` with `[.prettyPrinted, .sortedKeys]`: two-space indents, `"key" : value`,
     * keys sorted, empty containers as `[\n\n]` on their own indent, `/` escaped, whole numbers
     * without a fraction (`2`, not `2.0`) and other numbers in their shortest form.
     */
    internal fun appleJSON(element: JsonElement): String = StringBuilder().also { writeApple(element, 0, it) }.toString()

    private fun writeApple(element: JsonElement, indent: Int, out: StringBuilder) {
        val pad = "  ".repeat(indent)
        val inner = "  ".repeat(indent + 1)
        when (element) {
            is JsonObject -> {
                out.append("{\n")
                if (element.isEmpty()) out.append('\n')
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(",\n")
                    out.append(inner).append(appleString(key)).append(" : ")
                    writeApple(value, indent + 1, out)
                }
                if (element.isNotEmpty()) out.append('\n')
                out.append(pad).append('}')
            }
            is JsonArray -> {
                out.append("[\n")
                if (element.isEmpty()) out.append('\n')
                element.forEachIndexed { index, value ->
                    if (index > 0) out.append(",\n")
                    out.append(inner)
                    writeApple(value, indent + 1, out)
                }
                if (element.isNotEmpty()) out.append('\n')
                out.append(pad).append(']')
            }
            JsonNull -> out.append("null")
            is JsonPrimitive -> when {
                element.isString -> out.append(appleString(element.content))
                element.content == "true" || element.content == "false" -> out.append(element.content)
                else -> out.append(appleNumber(element.content.toDouble()))
            }
        }
    }

    private fun appleString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '/' -> append("\\/")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u").append(String.format(Locale.ROOT, "%04x", c.code)) else append(c)
            }
        }
        append('"')
    }

    /** `JSONSerialization`'s numbers: `2` for 2.0, `-1.457`, the shortest digits that read back. */
    private fun appleNumber(value: Double): String {
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        val (digits, exponent) = shortestDigits(abs(value))
        return (if (value < 0) "-" else "") + plainDecimal(digits, exponent, wholeSuffix = "")
    }

    // Swift's `String(describing:)` of IVModelKit's `ReferenceAlignment.Outcome`

    /**
     * The outcome as iOS copies it (`String(describing: outcome)`): the case name, and its payload
     * as Swift's `debugPrint` writes it: qualified type names (`IVModelKit.ReferenceFit(…)`), every
     * stored property in declaration order (`label: value`), `Optional(…)`/`nil`, quoted strings,
     * `SIMD3<Double>(x, y, z)` and Swift's shortest doubles ([swiftDouble]).
     */
    fun outcomeDescription(outcome: ReferenceAlignment.Outcome): String = when (outcome) {
        is ReferenceAlignment.Outcome.NeedsMore -> "needsMore(IVModelKit.ReferenceAlignment.Hint.${swiftCase(outcome.hint)})"
        is ReferenceAlignment.Outcome.Matched -> "matched(${fitDescription(outcome.fit)})"
        is ReferenceAlignment.Outcome.Ambiguous -> "ambiguous([${outcome.fits.joinToString(", ") { fitDescription(it) }}])"
        is ReferenceAlignment.Outcome.NoMatch -> "noMatch(IVModelKit.ReferenceAlignment.NoMatch.${swiftCase(outcome.reason)})"
    }

    private fun swiftCase(hint: ReferenceAlignment.Hint): String = when (hint) {
        ReferenceAlignment.Hint.MORE_POINTS -> "morePoints"
        ReferenceAlignment.Hint.ANOTHER_WALL -> "anotherWall"
        ReferenceAlignment.Hint.SPREAD_OUT -> "spreadOut"
    }

    private fun swiftCase(reason: ReferenceAlignment.NoMatch): String = when (reason) {
        ReferenceAlignment.NoMatch.NO_CORNERS -> "noCorners"
        ReferenceAlignment.NoMatch.NO_OBJECTS -> "noObjects"
        ReferenceAlignment.NoMatch.TOO_FAR -> "tooFar"
    }

    private fun fitDescription(fit: ReferenceFit): String {
        // `markCentre` is internal to :modelkit; it's set exactly when walls are among the matches
        // (`evaluate`, kept by `refit`), and `centre` returns it then.
        val markCentre = if (fit.matches.any { it.reference.kind == ReferencePoint.Kind.WALL }) fit.centre else null
        return "IVModelKit.ReferenceFit(transform: IVModelKit.YawTransform(yaw: ${swiftDouble(fit.transform.yaw)}, " +
            "translation: ${simd(fit.transform.translation)}), " +
            "matches: [${fit.matches.joinToString(", ") { matchDescription(it) }}], " +
            "rms: ${swiftDouble(fit.rms)}, " +
            "dropped: ${optional(fit.dropped?.let { droppedDescription(it) })}, " +
            "markCentre: ${optional(markCentre?.let { simd(it) })})"
    }

    private fun matchDescription(match: ReferenceFit.Match): String =
        "IVModelKit.ReferenceFit.Match(markIndex: ${match.markIndex}, reference: ${pointDescription(match.reference)}, " +
            "residual: ${swiftDouble(match.residual)})"

    private fun droppedDescription(dropped: ReferenceFit.Dropped): String =
        "IVModelKit.ReferenceFit.Dropped(markIndex: ${dropped.markIndex}, nearest: ${optional(dropped.nearest?.let { pointDescription(it) })}, " +
            "distance: ${swiftDouble(dropped.distance)})"

    private fun pointDescription(point: ReferencePoint): String =
        "IVModelKit.ReferencePoint(id: ${swiftString(point.id)}, kind: IVModelKit.ReferencePoint.Kind.${point.kind.raw}, " +
            "position: ${simd(point.position)}, elementID: ${optional(point.elementID?.let { swiftString(it) })}, " +
            "height: ${swiftDouble(point.height)}, normal: ${optional(point.normal?.let { simd(it) })}, " +
            "halfLength: ${swiftDouble(point.halfLength)})"

    private fun optional(described: String?): String = described?.let { "Optional($it)" } ?: "nil"

    private fun simd(v: Vec3): String = "SIMD3<Double>(${swiftDouble(v.x)}, ${swiftDouble(v.y)}, ${swiftDouble(v.z)})"

    /** Swift's `String.debugDescription`: quoted, with `\"`, `\'`, `\\`, `\n`, `\r`, `\t`, `\0` and `\u{…}` escapes. */
    private fun swiftString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\'' -> append("\\'")
                '\u0000' -> append("\\0")
                else -> if (c < ' ' || c == '\u007F') append("\\u{").append(Integer.toHexString(c.code)).append('}') else append(c)
            }
        }
        append('"')
    }

    /**
     * Swift's `Double.description`: the shortest digits that read back as the same double (the
     * closest of them to its exact value), `1.0`, `0.25`, `-0.0`; exponential below 0.0001 or
     * above 2^54 (`1e-05`, `1.5e-07`); `inf`, `-inf`, `nan`.
     */
    internal fun swiftDouble(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        val sign = if (value < 0 || (value == 0.0 && 1 / value < 0)) "-" else ""
        if (value == 0.0) return "${sign}0.0"
        val magnitude = abs(value)
        val (digits, exponent) = shortestDigits(magnitude)
        // Swift's decimal exponent: value = 0.d₁d₂… × 10^(exponent + 1).
        if (exponent + 1 < -3 || magnitude > TWO_TO_54) {
            val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
            val power = abs(exponent).toString().padStart(2, '0')
            return "$sign${mantissa}e${if (exponent < 0) "-" else "+"}$power"
        }
        return sign + plainDecimal(digits, exponent, wholeSuffix = ".0")
    }

    private const val TWO_TO_54 = 18014398509481984.0

    /**
     * The shortest significant digits of [magnitude] (> 0) that read back as it, the closest to its
     * exact value when several do, and the power of ten of the first digit (`1.25` → "125", 0).
     */
    private fun shortestDigits(magnitude: Double): Pair<String, Int> {
        val exact = BigDecimal(magnitude)
        for (precision in 1..17) {
            val best = listOf(RoundingMode.HALF_EVEN, RoundingMode.FLOOR, RoundingMode.CEILING)
                .map { exact.round(MathContext(precision, it)) }
                .filter { it.toDouble() == magnitude }
                .minByOrNull { it.subtract(exact).abs() }
                ?: continue
            val unscaled = best.unscaledValue().abs().toString()
            val exponent = unscaled.length - 1 - best.scale()
            return unscaled.trimEnd('0').ifEmpty { "0" } to exponent
        }
        val fallback = exact.round(MathContext(17))
        val unscaled = fallback.unscaledValue().abs().toString()
        return unscaled.trimEnd('0') to unscaled.length - 1 - fallback.scale()
    }

    /** [digits] × 10^[exponent] (first digit's power) without an exponent: "0.0125", "1.25", "300" + [wholeSuffix]. */
    private fun plainDecimal(digits: String, exponent: Int, wholeSuffix: String): String = when {
        exponent < 0 -> "0." + "0".repeat(-exponent - 1) + digits
        digits.length <= exponent + 1 -> digits + "0".repeat(exponent + 1 - digits.length) + wholeSuffix
        else -> digits.substring(0, exponent + 1) + "." + digits.substring(exponent + 1)
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
