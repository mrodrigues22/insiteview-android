package com.getinsiteview.features.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.ar.ARAlignmentView
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.SecondaryActionButton
import com.getinsiteview.features.R
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.RoomEntry
import com.getinsiteview.modelkit.ar.CrosshairState
import com.getinsiteview.modelkit.geometry.ReferenceAlignment
import com.getinsiteview.modelkit.geometry.ReferenceFit
import com.getinsiteview.modelkit.geometry.ReferenceMark
import com.getinsiteview.modelkit.geometry.ReferencePoint
import com.getinsiteview.modelkit.geometry.ReferencePoints
import com.getinsiteview.modelkit.geometry.RoomOutline
import com.getinsiteview.modelkit.geometry.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Alignment by reference points on the AR screen (docs/PLAN.md §3 "Alignment by reference
 * points"): pick the room, aim the phone down at 3 of its floor corners in any order, then check
 * the fit (or choose, when the room fits more than one way). The matching is `:modelkit`'s
 * [ReferenceAlignment].
 *
 * iOS's `@Observable` model: Compose snapshot state, main thread only.
 *
 * @param locatedRoomID the room the camera is in under the current alignment, if any. Marks are
 *   made where the user stands, so it comes before the room picked in the AR filters (often the
 *   one aligned in first, which made marks in any other room "not match").
 * @param scope the AR screen's scope ("Details copied" clears itself in it).
 * @param copyToClipboard puts the copied details on the clipboard.
 */
@Stable
class PointsAlignment(
    val session: BuildingSession,
    private val view: ARAlignmentView,
    locatedRoomID: String? = null,
    private val scope: CoroutineScope,
    private val copyToClipboard: (String) -> Unit,
) {
    sealed interface Step {
        /** Marking points (and fixing them: undo, start over). */
        data object Marking : Step

        /** A fit shown in AR: "Does the model line up?" */
        data class Checking(val fit: ReferenceFit) : Step

        /** Fits about as good (a room that's the same turned round); [shown] is the one in AR. */
        data class Choosing(val fits: List<ReferenceFit>, val shown: Int) : Step
    }

    enum class MarkProblem {
        /** The crosshair left the floor, or tracking faltered, during the half second "Mark" takes. */
        UNSTEADY,
    }

    var roomID: String? by mutableStateOf(null)
        private set

    var marks: List<ReferenceMark> by mutableStateOf(emptyList())
        private set

    private var stepState: Step by mutableStateOf(Step.Marking)

    /**
     * Chunk loading waits while marking ([BuildingSession.holdsSceneWork]), and comes back for
     * checking a fit, which needs the model on screen.
     */
    var step: Step
        get() = stepState
        private set(value) {
            stepState = value
            session.holdsSceneWork = value == Step.Marking
        }

    /** What would help after 3 marks that don't settle it. */
    var hint: ReferenceAlignment.Hint? by mutableStateOf(null)
        private set

    /** Why the marks fit nothing in the room. */
    var noMatch: ReferenceAlignment.NoMatch? by mutableStateOf(null)
        private set

    var markProblem: MarkProblem? by mutableStateOf(null)
        private set

    /** What the crosshair is on. */
    var crosshair: CrosshairState by mutableStateOf(view.crosshair)
        private set

    /** "Mark" is averaging the crosshair. */
    var capturing: Boolean by mutableStateOf(false)
        private set

    var detailsCopied: Boolean by mutableStateOf(false)
        private set

    private var lastOutcome: ReferenceAlignment.Outcome? = null
    private var copiedJob: Job? = null

    /** Ended (cancelled or confirmed): the view's callbacks no longer reach it (iOS: `[weak self]`). */
    private var ended = false
    private val crosshairListener: (CrosshairState) -> Unit = { state -> if (!ended) crosshair = state }
    private val floorListener: (Double) -> Unit = { if (!ended) floorMoved() }

    init {
        val eligible = rooms(session).map { it.id }
        roomID = ARExperienceRules.preselectedRoom(eligible, located = locatedRoomID, current = session.arFilters.roomID)
        showRoomLevel()
        view.onCrosshairChange = crosshairListener
        view.onFloorChange = floorListener
        session.holdsSceneWork = true
    }

    /**
     * The screen let go of it (cancelled or confirmed): a later floor change must not preview its
     * fit again (on iOS the closures die with it).
     */
    fun end() {
        ended = true
        copiedJob?.cancel()
        if (view.onCrosshairChange === crosshairListener) view.onCrosshairChange = null
        if (view.onFloorChange === floorListener) view.onFloorChange = null
    }

    // Rooms and their points

    val room: Manifest.Space? get() = roomID?.let { session.manifest?.space(it) }

    val roomName: String get() = room?.displayName ?: ""

    fun selectRoom(id: String?) {
        roomID = id
        showRoomLevel()
        restart()
    }

    /**
     * AR shows the chosen room's level, as the ⋮ room picker does: the fit puts that room's corners
     * on the marks, and a building drawn on another level showed nothing there (the Duplex opened
     * on its foundation).
     */
    private fun showRoomLevel() {
        val roomID = roomID ?: return
        val storey = rooms(session).firstOrNull { it.id == roomID }?.storeyID ?: return
        if (session.arFilters.storeyID == storey) return
        session.setStorey(storey, BuildingSession.SceneMode.AR)
    }

    /** The room has corners in the model (an outline). */
    val hasCorners: Boolean get() = room?.let { RoomOutline.of(it) } != null

    /** The model height of the room's floor, which its corners are on. */
    val floorY: Double get() = room?.floorY ?: 0.0

    /** Walls and corners at any height can be marked (a phone with a depth sensor). */
    val marksWalls: Boolean get() = ARAlignmentView.hasLiDAR

    /** The room's floor corners, and its walls with a depth sensor. */
    val references: List<ReferencePoint>
        get() {
            val room = room ?: return emptyList()
            return ReferencePoints.make(outline = RoomOutline.of(room), objects = emptyList(), floorY = floorY, walls = marksWalls)
        }

    /** Marks needed before matching: 2 once a wall is among them. */
    val marksNeeded: Int get() = ARExperienceRules.marksNeeded(marks)

    // Marking

    val canMark: Boolean get() = step == Step.Marking && !capturing && crosshair.canMark

    /** The crosshair on a corner: half a second of it is the mark. */
    suspend fun mark() {
        if (!canMark) return
        markProblem = null
        capturing = true
        val mark = view.markCorner()
        capturing = false
        if (mark == null) {
            markProblem = MarkProblem.UNSTEADY
            return
        }
        add(mark)
    }

    private fun add(mark: ReferenceMark) {
        marks = marks + mark
        evaluate()
    }

    fun undo() {
        if (marks.isEmpty()) return
        marks = marks.dropLast(1)
        view.removeLastMark()
        evaluate()
    }

    fun restart() {
        marks = emptyList()
        hint = null
        noMatch = null
        markProblem = null
        lastOutcome = null
        step = Step.Marking
        view.clearMarks()
        view.previewAlignment(null)
    }

    /** Another option while choosing between two fits. */
    fun show(index: Int) {
        val choosing = step as? Step.Choosing ?: return
        if (index !in choosing.fits.indices) return
        step = Step.Choosing(choosing.fits, shown = index)
        view.previewAlignment(choosing.fits[index].transform)
    }

    /** The marks on the floor as the AR session has it now: it may have moved since a mark was made. */
    private val marksOnFloor: List<ReferenceMark>
        get() {
            val floorY = view.worldFloorY ?: return marks
            return marks.map { it.onFloor(at = floorY) }
        }

    private fun evaluate() {
        noMatch = null
        hint = null
        val current = marksOnFloor
        view.moveMarkDiscs(current.map { it.position })
        if (current.size < marksNeeded) {
            lastOutcome = null
            step = Step.Marking
            view.previewAlignment(null)
            return
        }
        val outcome = ReferenceAlignment.solve(
            marks = current, references = references, worldFloorY = view.worldFloorY, outline = room?.let { RoomOutline.of(it) },
        )
        lastOutcome = outcome
        when (outcome) {
            is ReferenceAlignment.Outcome.NeedsMore -> {
                hint = outcome.hint
                step = Step.Marking
                view.previewAlignment(null)
            }
            is ReferenceAlignment.Outcome.Matched -> {
                step = Step.Checking(outcome.fit)
                view.previewAlignment(outcome.fit.transform)
            }
            is ReferenceAlignment.Outcome.Ambiguous -> {
                step = Step.Choosing(outcome.fits, shown = 0)
                view.previewAlignment(outcome.fits[0].transform)
            }
            is ReferenceAlignment.Outcome.NoMatch -> {
                noMatch = outcome.reason
                step = Step.Marking
                view.previewAlignment(null)
            }
        }
    }

    /**
     * The AR session refined the floor: the marks follow it, and so does the fit on screen (it rose
     * 6 cm after the last mark in a device test, leaving the preview that much too low).
     */
    private fun floorMoved() {
        val current = marksOnFloor
        view.moveMarkDiscs(current.map { it.position })
        when (val step = step) {
            Step.Marking -> Unit
            is Step.Checking -> {
                val moved = ReferenceAlignment.refit(step.fit, marks = current, worldFloorY = view.worldFloorY)
                this.step = Step.Checking(moved)
                lastOutcome = ReferenceAlignment.Outcome.Matched(moved)
                view.previewAlignment(moved.transform)
            }
            is Step.Choosing -> {
                val moved = step.fits.map { ReferenceAlignment.refit(it, marks = current, worldFloorY = view.worldFloorY) }
                this.step = Step.Choosing(moved, shown = step.shown)
                lastOutcome = ReferenceAlignment.Outcome.Ambiguous(moved)
                view.previewAlignment(moved[step.shown].transform)
            }
        }
    }

    /** "Back to marking" from a fit: keep the marks and add another. */
    fun markAnother() {
        step = Step.Marking
        view.previewAlignment(null)
    }

    // Details

    /**
     * What the matching saw, as JSON on the clipboard (a long press on the counter), so a failed
     * alignment can be reported exactly ([ARExperienceRules.copyDetailsJSON]).
     */
    fun copyDetails() {
        val json = ARExperienceRules.copyDetailsJSON(
            room = ARExperienceRules.RoomDetails(id = room?.id ?: "", name = roomName, hasOutline = hasCorners, floorY = room?.floorY),
            worldFloorY = view.worldFloorY,
            floorPlanes = view.floorPlaneCandidates,
            cameraY = view.cameraY,
            references = references,
            marks = marks,
            marksOnFloor = marksOnFloor,
            outcome = lastOutcome?.toString(),
        )
        copyToClipboard(json)
        detailsCopied = true
        copiedJob?.cancel()
        copiedJob = scope.launch {
            delay(ARExperienceRules.DETAILS_COPIED_MILLIS)
            detailsCopied = false
        }
    }

    companion object {
        /** Rooms that can be aligned by points: those with corners in the model (an outline). */
        fun rooms(session: BuildingSession): List<RoomEntry> = session.rooms().filter { RoomOutline.of(it.space) != null }

        /**
         * Every room with corners in the model, with its outline: "Fix here" may be in any of them
         * (`:modelkit` keeps those on the mark's storey, by floor height).
         */
        fun outlines(session: BuildingSession): List<Pair<Manifest.Space, RoomOutline>> =
            rooms(session).mapNotNull { entry -> RoomOutline.of(entry.space)?.let { entry.space to it } }

        /** The room [camera] (model coordinates) is in, of those with an outline. */
        fun room(around: Vec3?, session: BuildingSession): Manifest.Space? {
            val camera = around ?: return null
            val rooms = outlines(session)
            return RoomOutline.index(containing = camera, outlines = rooms.map { it.second })?.let { rooms[it].first }
        }
    }
}

/**
 * The crosshair in the middle of the screen while aiming at a floor corner (marking, or "Fix
 * here"), and what it's on. It sits exactly on the middle of the whole screen, where the mark's ray
 * goes through the camera view (which ignores the safe area); the label hangs 64 dp below the
 * reticle's top, clear of the floor circle the AR view draws under it.
 */
@Composable
fun PointsCrosshair(state: CrosshairState, capturing: Boolean = false) {
    val label = when (ARExperienceRules.crosshairLabel(state, capturing)) {
        CrosshairLabel.HOLD_STILL -> R.string.hold_still
        CrosshairLabel.NO_FLOOR -> R.string.move_the_phone_slowly_over_the_floor
        CrosshairLabel.NOT_TRACKING -> R.string.move_the_phone_slowly_around_the_room
        CrosshairLabel.TOO_SHALLOW -> R.string.point_the_phone_more_straight_down
        CrosshairLabel.ON_FLOOR -> R.string.on_the_floor
        CrosshairLabel.ON_WALL -> R.string.on_a_wall
        CrosshairLabel.ON_CORNER -> R.string.on_a_corner
        CrosshairLabel.NO_SURFACE -> R.string.aim_at_a_wall_a_corner_or_down_at_the_floor
    }
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        // The reticle, centred.
        Box(
            Modifier
                .size(36.dp)
                .shadow(2.dp, CircleShape, clip = false)
                .border(2.dp, if (state.canMark) Palette.accent else Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        // The label: its top 64 dp below the reticle's top (the reticle's top is 18 dp above centre).
        Text(
            stringResource(label),
            style = IvType.body(12.sp, FontWeight.SemiBold),
            color = Palette.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 64.dp - 18.dp)
                // Centred by the Box: move down by half its height so its top is at that line.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, placeable.height / 2) }
                }
                .background(Palette.surface.copy(alpha = 0.85f), RectangleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * While the app is still finding the floor, or its place in the room: nothing to mark yet, so this
 * replaces the marking controls and the crosshair.
 */
@Composable
fun FindingFloorRow(state: CrosshairState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.muted, strokeWidth = 2.dp)
        Text(
            stringResource(
                if (state == CrosshairState.NO_FLOOR) {
                    R.string.finding_the_floor_move_the_phone_slowly_pointing_at_the_floo
                } else {
                    R.string.finding_your_place_move_the_phone_slowly_around_the_room
                },
            ),
            style = IvType.body(16.sp),
            color = Palette.ink,
        )
    }
}

/** The marking card: which room, how many points, what to do next, and the fit to check. */
@Composable
fun PointsAlignmentPanel(alignment: PointsAlignment, confirm: (ReferenceFit) -> Unit, cancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface.copy(alpha = 0.95f), RectangleShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (alignment.room == null) {
            RoomPicker(alignment, cancel)
        } else {
            when (val step = alignment.step) {
                PointsAlignment.Step.Marking -> Marking(alignment, cancel)
                is PointsAlignment.Step.Checking -> Checking(alignment, step.fit, confirm)
                is PointsAlignment.Step.Choosing -> Choosing(alignment, step.fits, step.shown, confirm)
            }
        }
    }
}

@Composable
private fun roomName(entry: RoomEntry): String = entry.space.displayName ?: stringResource(R.string.unnamed_room)

@Composable
private fun RoomPicker(alignment: PointsAlignment, cancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.which_room_are_you_in), style = IvType.headline, color = Palette.ink)
        Column(
            Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (room in PointsAlignment.rooms(alignment.session)) {
                SecondaryActionButton(text = roomName(room), onClick = { alignment.selectRoom(room.id) })
            }
        }
        TextLink(stringResource(R.string.cancel), cancel)
    }
}

/** A small accent link ("Cancel", "Mark another corner"). */
@Composable
internal fun TextLink(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        text,
        style = IvType.body(14.sp, FontWeight.SemiBold),
        color = if (enabled) Palette.accent else Palette.muted,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RoomLine(alignment: PointsAlignment) {
    var expanded by remember { mutableStateOf(false) }
    val changeRoom = stringResource(R.string.change_room)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(
                Modifier
                    .heightIn(min = 36.dp)
                    .clickable(onClickLabel = changeRoom, onClick = { expanded = true }),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.MeetingRoom, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                Text(alignment.roomName, style = IvType.body(15.sp, FontWeight.SemiBold), color = Palette.accent)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (room in PointsAlignment.rooms(alignment.session)) {
                    DropdownMenuItem(
                        text = { Text(roomName(room)) },
                        onClick = {
                            expanded = false
                            alignment.selectRoom(room.id)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // A long press copies what the matching saw, for reporting a failed alignment.
        val counter = if (alignment.detailsCopied) {
            stringResource(R.string.details_copied)
        } else {
            stringResource(R.string.n_of_n, minOf(alignment.marks.size, alignment.marksNeeded), alignment.marksNeeded)
        }
        Text(
            counter,
            style = IvType.mono(14.sp),
            color = Palette.muted,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { alignment.copyDetails() }),
        )
    }
}

@Composable
private fun Marking(alignment: PointsAlignment, cancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // iOS `.sensoryFeedback(.impact, trigger: marks.count)`: on each change, not the first.
    val count = alignment.marks.size
    val lastCount = remember { IntHolder(count) }
    LaunchedEffect(count) {
        if (count != lastCount.value) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        lastCount.value = count
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RoomLine(alignment)
        // Nothing can be marked until the floor is found and tracking is normal.
        if (alignment.crosshair.canAim || alignment.capturing) {
            Text(instructionString(alignment), style = IvType.body(16.sp), color = Palette.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryActionButton(
                    text = stringResource(R.string.undo),
                    onClick = { alignment.undo() },
                    enabled = alignment.marks.isNotEmpty() && !alignment.capturing,
                    modifier = Modifier.weight(1f),
                )
                PrimaryActionButton(
                    text = stringResource(if (alignment.capturing) R.string.hold_still else R.string.mark),
                    onClick = { scope.launch { alignment.mark() } },
                    enabled = alignment.canMark,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            FindingFloorRow(alignment.crosshair)
        }
        TextLink(stringResource(R.string.cancel), cancel)
    }
}

/** A value read and written in effects, not Compose state. */
internal class IntHolder(var value: Int)

@Composable
private fun instructionString(alignment: PointsAlignment): String {
    val walls = alignment.marksWalls
    val instruction = ARExperienceRules.pointsInstruction(
        unsteady = alignment.markProblem == PointsAlignment.MarkProblem.UNSTEADY, noMatch = alignment.noMatch, hint = alignment.hint,
    )
    return when (instruction) {
        PointsInstruction.UNSTEADY -> stringResource(
            if (walls) R.string.couldn_t_hold_the_mark_steady_keep_the_circle_on_the_same_wa else R.string.couldn_t_hold_the_mark_steady_keep_the_circle_on_the_corner,
        )
        PointsInstruction.NO_CORNERS -> stringResource(R.string.this_room_has_no_corners_in_the_model)
        PointsInstruction.TOO_FAR -> stringResource(
            if (walls) R.string.these_marks_don_t_match_x_check_the_room_or_undo_the_last_ma else R.string.these_corners_don_t_match_x_check_the_room_or_undo_the_last,
            alignment.roomName,
        )
        PointsInstruction.MORE_POINTS -> stringResource(if (walls) R.string.mark_one_more_corner_or_wall else R.string.mark_one_more_corner)
        PointsInstruction.ANOTHER_WALL -> stringResource(
            if (walls) R.string.mark_a_corner_or_a_wall_that_crosses_the_others else R.string.mark_corners_further_apart_on_different_walls,
        )
        PointsInstruction.DEFAULT -> stringResource(
            if (walls) R.string.aim_at_a_wall_or_a_corner_of_the_room_at_any_height_or_down else R.string.stand_by_a_floor_corner_point_the_phone_down_at_it_with_the,
        )
    }
}

@Composable
private fun Checking(alignment: PointsAlignment, fit: ReferenceFit, confirm: (ReferenceFit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RoomLine(alignment)
        Text(stringResource(R.string.does_the_model_line_up_with_the_room), style = IvType.headline, color = Palette.ink)
        FitSummary(alignment, fit)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryActionButton(stringResource(R.string.start_over), onClick = { alignment.restart() }, modifier = Modifier.weight(1f))
            PrimaryActionButton(stringResource(R.string.it_matches), onClick = { confirm(fit) }, modifier = Modifier.weight(1f))
        }
        TextLink(stringResource(if (alignment.marksWalls) R.string.mark_another else R.string.mark_another_corner), { alignment.markAnother() })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Choosing(alignment: PointsAlignment, fits: List<ReferenceFit>, shown: Int, confirm: (ReferenceFit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RoomLine(alignment)
        Text(stringResource(R.string.the_room_fits_more_than_one_way_which_one_lines_up), style = IvType.headline, color = Palette.ink)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            fits.indices.forEach { index ->
                SegmentedButton(
                    selected = index == shown,
                    onClick = { alignment.show(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = fits.size),
                ) {
                    Text(stringResource(R.string.option_n, index + 1))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryActionButton(stringResource(R.string.start_over), onClick = { alignment.restart() }, modifier = Modifier.weight(1f))
            PrimaryActionButton(stringResource(R.string.this_one), onClick = { confirm(fits[shown]) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FitSummary(alignment: PointsAlignment, fit: ReferenceFit) {
    val units = alignment.session.units
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val walls = fit.matches.any { it.reference.kind == ReferencePoint.Kind.WALL }
        Text(
            stringResource(
                if (walls) R.string.matched_n_marks_average_error_x else R.string.matched_n_corners_average_error_x,
                fit.matches.size,
                units.distance(fit.rms),
            ),
            style = IvType.body(15.sp),
            color = Palette.ink,
        )
        when (fit.quality) {
            ReferenceFit.Quality.GOOD -> Unit
            ReferenceFit.Quality.FAIR -> Text(
                stringResource(R.string.fair_fit_check_that_the_model_lines_up_before_relying_on_it),
                style = IvType.body(13.sp),
                color = Palette.warn,
            )
            ReferenceFit.Quality.APPROXIMATE -> Text(
                stringResource(R.string.approximate_fit_it_may_be_off_by_about_x_check_that_the_mode, units.distance(fit.rms)),
                style = IvType.body(13.sp),
                color = Palette.warn,
            )
        }
        if (fit.dropped != null) {
            Text(stringResource(R.string.one_corner_was_left_out_it_didn_t_match_the_project), style = IvType.body(13.sp), color = Palette.muted)
        }
    }
}
