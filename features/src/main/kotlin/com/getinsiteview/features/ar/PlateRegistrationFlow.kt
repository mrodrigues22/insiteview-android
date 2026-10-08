package com.getinsiteview.features.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.api.ApiError
import com.getinsiteview.api.BuildingPlate
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.SecondaryActionButton
import com.getinsiteview.features.R
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.geometry.PlateFrame
import com.getinsiteview.modelkit.geometry.PlateRegistration
import com.getinsiteview.modelkit.geometry.PlateSampler
import com.getinsiteview.modelkit.geometry.ReferenceFit
import com.getinsiteview.modelkit.geometry.RoomOutline
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.YawTransform
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Registering a plate on site (admins, docs/PLAN.md §3 "Registering a plate on site"): the plate
 * is stuck wherever is convenient; the building is aligned by reference points and the plate is
 * detected, in any order; its pose in the model is reviewed and saved. Guests then align on it.
 *
 * iOS's `@Observable` model: Compose snapshot state, main thread only.
 *
 * @param plateNumber the scanned plate, chosen without asking; `null` lists the building's plates.
 * @param onPlateChosen the chosen plate's image should be watched in AR.
 */
@Stable
class PlateRegistrationFlow(
    val session: BuildingSession,
    plateNumber: Int?,
    private val onPlateChosen: (BuildingPlate) -> Unit,
) {
    sealed interface Phase {
        data object LoadingPlates : Phase

        data class ChoosingPlate(val plates: List<BuildingPlate>) : Phase

        /** Points and the plate, in any order. */
        data object Collecting : Phase

        data class Review(val pose: PlateRegistration.Pose) : Phase

        data class Saving(val pose: PlateRegistration.Pose) : Phase

        data object Saved : Phase

        data class Failed(val problem: Problem) : Phase
    }

    enum class Problem {
        NOT_ON_A_WALL,
        FORBIDDEN,
        COULD_NOT_LOAD,
    }

    private val preselected: Int? = plateNumber

    var phase: Phase by mutableStateOf(Phase.LoadingPlates)
        private set

    var plate: BuildingPlate? by mutableStateOf(null)
        private set

    /** The building's alignment by points, and the room the camera is in. */
    var fit: ReferenceFit? by mutableStateOf(null)
        private set

    var room: Manifest.Space? by mutableStateOf(null)
        private set

    /**
     * Where the alignment places the building now: the fit's, or after a "Fix here" or a walk to
     * another room, the local alignment's there (`:modelkit` `AlignmentSites`).
     */
    private var transform: YawTransform? = null

    /** A mutable class: [samplerRevision] tells Compose it changed. */
    private val sampler = PlateSampler()
    private var samplerRevision by mutableIntStateOf(0)

    /** The last save failed (offline, server error): the review stays, with "try again". */
    var saveFailed: Boolean by mutableStateOf(false)
        private set

    val samplerIsReady: Boolean
        get() {
            samplerRevision
            return sampler.isReady
        }

    val samplerIsEmpty: Boolean
        get() {
            samplerRevision
            return sampler.samples.isEmpty()
        }

    suspend fun load() {
        phase = Phase.LoadingPlates
        try {
            val plates = session.buildingPlates().sortedBy { it.number }
            val chosen = preselected?.let { number -> plates.firstOrNull { it.number == number } }
            if (chosen != null) choose(chosen) else phase = Phase.ChoosingPlate(plates)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            phase = Phase.Failed(Problem.COULD_NOT_LOAD)
        }
    }

    fun choose(plate: BuildingPlate) {
        this.plate = plate
        sampler.reset()
        samplerRevision += 1
        phase = Phase.Collecting
        onPlateChosen(plate)
        reviewIfReady()
    }

    /** A detection of the plate being registered (ARCore's image pose and the camera). */
    fun observe(frame: PlateFrame, camera: Vec3) {
        if (phase != Phase.Collecting) return
        sampler.add(frame, camera)
        samplerRevision += 1
        reviewIfReady()
    }

    /**
     * The building got aligned by points, placed at [transform] now (the fit's, unless it was
     * corrected since); the camera is in [room].
     */
    fun aligned(fit: ReferenceFit, transform: YawTransform? = null, room: Manifest.Space?) {
        this.fit = fit
        this.transform = transform ?: fit.transform
        this.room = room
        reviewIfReady()
    }

    /**
     * The alignment moved ("Fix here", or another room's local alignment took over), or the camera
     * is in another room (`null`: keep the room). Only while collecting: a review keeps the pose it
     * shows.
     */
    fun alignmentMoved(transform: YawTransform, room: Manifest.Space?) {
        if (phase != Phase.Collecting || fit == null) return
        this.transform = transform
        if (room != null) this.room = room
        reviewIfReady()
    }

    private fun reviewIfReady() {
        if (phase != Phase.Collecting || fit == null) return
        val transform = transform ?: return
        val detection = sampler.pose ?: return
        phase = when (val result = PlateRegistration.pose(of = detection, alignment = transform, outline = room?.let { RoomOutline.of(it) })) {
            is PlateRegistration.Result.Success -> Phase.Review(result.pose)
            is PlateRegistration.Result.Failure -> Phase.Failed(Problem.NOT_ON_A_WALL)
        }
    }

    suspend fun save() {
        val review = phase as? Phase.Review ?: return
        val id = plate?.plateID ?: return
        phase = Phase.Saving(review.pose)
        saveFailed = false
        try {
            session.registerPlate(id, review.pose.frame)
            phase = Phase.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiError && e.code == ApiError.Code.FORBIDDEN) {
                phase = Phase.Failed(Problem.FORBIDDEN)
            } else {
                phase = Phase.Review(review.pose)
                saveFailed = true
            }
        }
    }

    val isSaving: Boolean get() = phase is Phase.Saving

    /** Back to collecting: the plate's samples and the alignment start over. */
    fun startOver() {
        fit = null
        transform = null
        saveFailed = false
        sampler.reset()
        samplerRevision += 1
        phase = if (plate == null) Phase.LoadingPlates else Phase.Collecting
    }

    val plateTitle: String
        get() {
            val plate = plate ?: return ""
            return if (plate.label.isEmpty()) "${plate.number}" else "${plate.number} · ${plate.label}"
        }
}

/**
 * The registration card on the AR screen. While the building isn't aligned yet it hosts the points
 * card; the plate's state shows above it.
 */
@Composable
fun PlateRegistrationPanel(
    flow: PlateRegistrationFlow,
    points: PointsAlignment?,
    confirmPoints: (ReferenceFit) -> Unit,
    cancelPoints: () -> Unit,
    startPoints: () -> Unit,
    testNow: () -> Unit,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val phase = flow.phase) {
            PlateRegistrationFlow.Phase.LoadingPlates -> Card {
                CircularProgressIndicator(Modifier.size(24.dp), color = Palette.muted, strokeWidth = 2.dp)
                TextLink(stringResource(R.string.cancel), close)
            }
            is PlateRegistrationFlow.Phase.ChoosingPlate -> Card { PlateList(flow, phase.plates, close) }
            PlateRegistrationFlow.Phase.Collecting -> {
                Card {
                    Text(stringResource(R.string.register_plate_x, flow.plateTitle), style = IvType.headline, color = Palette.ink)
                    ChecklistRow(done = flow.fit != null) {
                        stringResource(if (flow.fit != null) R.string.corners_marked else R.string.mark_3_floor_corners)
                    }
                    ChecklistRow(done = flow.samplerIsReady) {
                        stringResource(
                            when {
                                flow.samplerIsReady -> R.string.plate_seen
                                flow.samplerIsEmpty -> R.string.point_the_phone_at_the_plate_from_about_1_m
                                else -> R.string.hold_still_on_the_plate
                            },
                        )
                    }
                    if (flow.fit == null && points == null) {
                        PrimaryActionButton(stringResource(R.string.mark_corners), onClick = startPoints)
                    }
                    TextLink(stringResource(R.string.cancel), close)
                }
                if (points != null && flow.fit == null) {
                    PointsAlignmentPanel(points, confirm = confirmPoints, cancel = cancelPoints)
                }
            }
            is PlateRegistrationFlow.Phase.Review -> Card { Review(flow, phase.pose, startPoints, close) { scope.launch { flow.save() } } }
            is PlateRegistrationFlow.Phase.Saving -> Card { Review(flow, phase.pose, startPoints, close) { scope.launch { flow.save() } } }
            PlateRegistrationFlow.Phase.Saved -> Card {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Verified, contentDescription = null, tint = Palette.accent)
                    Text(
                        stringResource(R.string.position_saved_guests_now_align_on_plate_x, flow.plateTitle),
                        style = IvType.body(15.sp, FontWeight.SemiBold),
                        color = Palette.ink,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryActionButton(stringResource(R.string.done), onClick = close, modifier = Modifier.weight(1f))
                    PrimaryActionButton(stringResource(R.string.test_now), onClick = testNow, modifier = Modifier.weight(1f))
                }
            }
            is PlateRegistrationFlow.Phase.Failed -> Card {
                Text(
                    stringResource(
                        when (phase.problem) {
                            PlateRegistrationFlow.Problem.NOT_ON_A_WALL -> R.string.the_plate_doesn_t_seem_to_be_on_a_wall_of_this_room_check_th
                            PlateRegistrationFlow.Problem.FORBIDDEN -> R.string.only_admins_can_register_plates
                            PlateRegistrationFlow.Problem.COULD_NOT_LOAD -> R.string.couldn_t_load_the_plates_check_your_connection
                        },
                    ),
                    style = IvType.body(15.sp),
                    color = Palette.ink,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryActionButton(stringResource(R.string.close), onClick = close, modifier = Modifier.weight(1f))
                    when (phase.problem) {
                        PlateRegistrationFlow.Problem.NOT_ON_A_WALL -> PrimaryActionButton(
                            stringResource(R.string.start_over),
                            onClick = {
                                flow.startOver()
                                startPoints()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        PlateRegistrationFlow.Problem.COULD_NOT_LOAD -> PrimaryActionButton(
                            stringResource(R.string.try_again),
                            onClick = { scope.launch { flow.load() } },
                            modifier = Modifier.weight(1f),
                        )
                        PlateRegistrationFlow.Problem.FORBIDDEN -> Unit
                    }
                }
            }
        }
    }
}

/** The AR screen's cards: a light panel over the camera. */
@Composable
internal fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface.copy(alpha = 0.95f), RectangleShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PlateList(flow: PlateRegistrationFlow, plates: List<BuildingPlate>, close: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.which_plate_are_you_registering), style = IvType.headline, color = Palette.ink)
        Column(
            Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (plate in plates) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.background, RectangleShape)
                        .clickable { flow.choose(plate) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(stringResource(R.string.plate_n, plate.number), style = IvType.body(15.sp, FontWeight.SemiBold), color = Palette.ink)
                    if (plate.label.isNotEmpty()) Text(plate.label, style = IvType.body(13.sp), color = Palette.ink)
                    Text(
                        stringResource(if (plate.placed) R.string.has_a_position_registering_replaces_it else R.string.not_in_the_model_yet),
                        style = IvType.body(12.sp),
                        color = Palette.muted,
                    )
                }
            }
        }
        TextLink(stringResource(R.string.cancel), close)
    }
}

@Composable
private fun ChecklistRow(done: Boolean, label: @Composable () -> String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (done) Palette.accent else Palette.muted,
        )
        Text(label(), style = IvType.body(15.sp), color = Palette.ink)
    }
}

@Composable
private fun Review(
    flow: PlateRegistrationFlow,
    pose: PlateRegistration.Pose,
    startPoints: () -> Unit,
    close: () -> Unit,
    save: () -> Unit,
) {
    val units = flow.session.units
    Text(stringResource(R.string.plate_x, flow.plateTitle), style = IvType.headline, color = Palette.ink)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pose.heightAboveFloor?.let { height ->
            Text(stringResource(R.string.centre_x_above_the_floor, units.distance(height)), style = IvType.body(15.sp), color = Palette.ink)
        }
        pose.wall?.let { wall ->
            Text(
                stringResource(
                    R.string.x_from_the_left_corner_x_from_the_right_corner,
                    units.distance(wall.fromLeftCorner),
                    units.distance(wall.fromRightCorner),
                ),
                style = IvType.body(15.sp),
                color = Palette.ink,
            )
        }
        flow.fit?.let { fit ->
            if (fit.quality == ReferenceFit.Quality.GOOD) {
                Text(stringResource(R.string.alignment_good_average_error_x, units.distance(fit.rms)), style = IvType.body(15.sp), color = Palette.ink)
            } else {
                Text(
                    stringResource(R.string.alignment_fair_average_error_x_marking_more_points_helps, units.distance(fit.rms)),
                    style = IvType.body(15.sp),
                    color = Palette.warn,
                )
            }
        }
    }
    if (flow.saveFailed) {
        Text(
            stringResource(R.string.couldn_t_save_the_position_check_your_connection_and_try_aga),
            style = IvType.body(13.sp),
            color = Palette.warn,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryActionButton(
            stringResource(R.string.start_over),
            onClick = {
                flow.startOver()
                startPoints()
            },
            enabled = !flow.isSaving,
            modifier = Modifier.weight(1f),
        )
        if (flow.isSaving) {
            Row(Modifier.weight(1f).heightIn(min = 46.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Palette.accent, strokeWidth = 2.dp)
            }
        } else {
            PrimaryActionButton(
                stringResource(if (flow.saveFailed) R.string.try_again else R.string.save_position),
                onClick = save,
                modifier = Modifier.weight(1f),
            )
        }
    }
    TextLink(stringResource(R.string.cancel), close)
}
