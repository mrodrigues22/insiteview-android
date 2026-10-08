package com.getinsiteview.features.ar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.West
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.getinsiteview.api.ApiError
import com.getinsiteview.ar.ARAlignmentScene
import com.getinsiteview.ar.ARAlignmentView
import com.getinsiteview.core.ARPreflightStep
import com.getinsiteview.core.AnalyticsEvent
import com.getinsiteview.core.CameraAccess
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.SecondaryActionButton
import com.getinsiteview.features.R
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.features.objectcard.ObjectCardContext
import com.getinsiteview.features.objectcard.ObjectCardSheet
import com.getinsiteview.features.onboarding.ARExplainerView
import com.getinsiteview.features.onboarding.CameraPermissionView
import com.getinsiteview.features.onboarding.currentCameraAccess
import com.getinsiteview.features.viewer.StoreyPicker
import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.RoomEntry
import com.getinsiteview.modelkit.ar.CrosshairState
import com.getinsiteview.modelkit.ar.PlacementState
import com.getinsiteview.modelkit.ar.TrackingStatus
import com.getinsiteview.modelkit.geometry.LocateGuide
import com.getinsiteview.modelkit.geometry.LocateIndicator
import com.getinsiteview.modelkit.geometry.ManualAlignment
import com.getinsiteview.modelkit.geometry.PlateAnchoring
import com.getinsiteview.modelkit.geometry.ReferenceAlignment
import com.getinsiteview.modelkit.geometry.ReferenceFit
import com.getinsiteview.modelkit.geometry.ReferenceMark
import com.getinsiteview.modelkit.geometry.RoomObservation
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.UnavailableException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Seconds on a monotonic clock (iOS `Date()` for `openedAt`). */
internal fun monotonicSeconds(): Double = System.nanoTime() / 1e9

/** Preference writes outlive the AR screen (closing right after "Got it" must still save it). */
private val preferenceWrites = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * State for the AR screen (iOS `ARExperienceModel`): onboarding (IOS-M2-07), plate anchoring with
 * alignment by reference points and the manual fallback (IOS-M2-04/05), registering plates on site
 * (admins), See inside (M2-06), first aid (M2-10) and the AR analytics (M2-09).
 *
 * A plain state holder made per AR screen (iOS's `@Observable` model): Compose snapshot state, main
 * thread only. It holds the [ARAlignmentView] (iOS: the `ARView`), made on first use.
 *
 * @param scope the AR screen's scope (registration loads, saves, "Details copied").
 */
@Stable
class ARExperienceModel(val session: BuildingSession, context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    val claimID: UUID = UUID.randomUUID()

    var placement: PlacementState by mutableStateOf(PlacementState.FindingFloor)
        private set
    var tracking: TrackingStatus by mutableStateOf(TrackingStatus.INITIALIZING)
        private set

    /** The view's anchoring as last reported (`null` until it first changes, as on iOS). */
    var anchoring: ARAnchoringState? by mutableStateOf(null)
        private set

    /** `null` while the preferences load. */
    var preflight: ARPreflightStep? by mutableStateOf(null)
        private set

    /** Bumped on each alignment, for the success haptic. */
    var alignments: Int by mutableIntStateOf(0)
        private set

    /** When AR opened (monotonic seconds), for offering "No plate?" while plates are searched for. */
    var openedAt: Double by mutableDoubleStateOf(monotonicSeconds())
        private set
    var showsFineTune: Boolean by mutableStateOf(false)
    var showsSafetyNote: Boolean by mutableStateOf(false)
    var seeInsideExpanded: Boolean by mutableStateOf(false)

    /** The floor coaching card (iOS `ARCoachingOverlayView`): placing by hand before the first placement. */
    var showsFloorCoaching: Boolean by mutableStateOf(false)
        private set

    /** Locate in AR: distance and the off-screen arrow (IOS-M3-05); `null` until placed. */
    var locateIndicator: LocateIndicator? by mutableStateOf(null)
        private set

    /** Alignment by reference points, while it's on screen. */
    var points: PointsAlignment? by mutableStateOf(null)
        private set

    /** "Fix here", while aiming at a corner. */
    var fixHere: FixHere? by mutableStateOf(null)
        private set

    /**
     * Rooms on this level, and whether one can be aligned by points: worked out when the manifest
     * or the level changes, not on every recomposition.
     */
    var rooms: List<RoomEntry> by mutableStateOf(emptyList())
        private set
    var canAlignByPoints: Boolean by mutableStateOf(false)
        private set

    /** Registering a plate on site (admins). */
    var registration: PlateRegistrationFlow? by mutableStateOf(null)
        private set

    /**
     * The last "Fix here" this session that measured where its room is (from an alignment made in
     * another room or on a plate): an admin can save it for everyone.
     */
    var roomFix: RoomObservation? by mutableStateOf(null)
        private set

    /** Saving a room's correction, while its card is up (admins). */
    var roomSave: RoomSave? by mutableStateOf(null)
        private set

    /** ARCore couldn't start (Android only); [sessionAttempt] counts the retries. */
    var sessionFailed: Boolean by mutableStateOf(false)
        private set
    var sessionAttempt: Int by mutableIntStateOf(0)
        private set

    var started: Boolean by mutableStateOf(false)
        private set

    /** The 3D viewer's SceneView is gone: the camera view can start. */
    private var readyForCamera by mutableStateOf(false)

    /** Puts copied details on the clipboard (set by the screen). */
    var copyToClipboard: (String) -> Unit = {}

    /**
     * The last confirmed alignment by points, which a registration started afterwards reuses (with
     * where the building is now: "Fix here" and the local alignments move it).
     */
    private var lastPoints: Pair<ReferenceFit, Manifest.Space?>? = null
    private var alignmentView: ARAlignmentView? = null
    private var platesConfigured = false
    private var claimedScene = false
    private var hasSeenExplainer: Boolean? = null
    private var hasSeenSafetyNote = false
    private var camera = CameraAccess.NOT_DETERMINED

    // Onboarding

    /** Reads the once-per-device flags, then the first step. */
    suspend fun loadPreferences(camera: CameraAccess) {
        val preferences = session.preferences
        hasSeenSafetyNote = preferences.hasSeenSafetyNote()
        hasSeenExplainer = preferences.hasSeenARExplainer()
        refreshPreflight(camera)
    }

    fun finishExplainer() {
        hasSeenExplainer = true
        val preferences = session.preferences
        preferenceWrites.launch { preferences.setHasSeenARExplainer(true) }
        refreshPreflight(camera)
    }

    /** Also after a permission answer, and after coming back from the settings. */
    fun refreshPreflight(camera: CameraAccess) {
        this.camera = camera
        val seen = hasSeenExplainer ?: return
        preflight = ARPreflightStep.next(hasSeenExplainer = seen, camera = camera)
    }

    // Session

    val view: ARAlignmentView
        get() = alignmentView ?: ARAlignmentView(appContext).also { view ->
            view.onStateChange = { placement = it }
            view.onTrackingChange = { tracking = it }
            view.onAnchoringChange = { new ->
                anchoring = ARAnchoringState.of(new)
                alignmentMoved()
            }
            view.onAlignmentEvent = { handle(it) }
            view.onLocateChange = { locateIndicator = it }
            view.onCoachingChange = { showsFloorCoaching = it }
            view.onSessionFailed = { error ->
                // Only failures that stop the camera (a bad image database is left out of detection).
                if (error is UnavailableException || error is CameraNotAvailableException || error is FatalException) sessionFailed = true
            }
            showsFloorCoaching = view.showsCoaching
            alignmentView = view
        }

    /** Points the AR view at the element being located (once its chunk is in the scene). */
    fun updateLocateTarget() {
        if (!started) return
        view.locateTarget = session.locateBounds()
        if (session.locating == null) locateIndicator = null
    }

    /**
     * Takes the building's scene for AR. The 3D viewer's SceneView leaves when the navigation
     * transition ends ([cameraCanStart]); iOS waits up to a second for its `.nonAR` views instead.
     */
    fun claimScene() {
        if (claimedScene) return
        claimedScene = true
        session.claimScene(claimID, BuildingSession.SceneMode.AR)
    }

    /** The previous screen (and its SceneView) is gone. */
    fun cameraCanStart() {
        readyForCamera = true
    }

    val canShowCamera: Boolean get() = started || readyForCamera

    fun start() {
        if (started) return
        started = true
        openedAt = monotonicSeconds()
        claimScene()
        chooseStartingStorey()
        updateFloorElevation()
        refreshRooms()
        configurePlatesIfNeeded()
        view.attach(session.scene)
        view.run()
        updateLocateTarget()
        session.select(session.locating)
        session.track { AnalyticsEvent.arOpened(it) }
        if (!hasSeenSafetyNote) showsSafetyNote = true
    }

    /** "Try again" after ARCore failed: a new AR scene and session. */
    fun retrySession() {
        sessionFailed = false
        sessionAttempt += 1
    }

    /**
     * Plates come from the manifest, which may still be loading when AR opens. Without placed
     * plates, marking points starts right away when the room allows it.
     */
    fun configurePlatesIfNeeded() {
        if (!started || platesConfigured || session.manifest == null) return
        platesConfigured = true
        // The manifest came after AR opened: the level wasn't chosen yet.
        chooseStartingStorey()
        updateFloorElevation()
        refreshRooms()
        view.configure(plates = session.plates, scannedPlate = session.scannedPlate, corrections = session.roomCorrections)
        if (ARExperienceRules.startsPointsAtConfigure(session.plates.size, canAlignByPoints)) startPoints()
    }

    /**
     * The floor goes on the lowest level with rooms (`Manifest.startingStorey`) unless the guest
     * picked another; one level shows all.
     */
    private fun chooseStartingStorey() {
        val manifest = session.manifest ?: return
        if (!ARExperienceRules.choosesStartingStorey(session.arFilters.storeyID, manifest.storeys.size)) return
        val storey = manifest.startingStorey ?: return
        session.setStorey(storey.id, BuildingSession.SceneMode.AR)
    }

    // Points and registration

    fun refreshRooms() {
        rooms = session.rooms()
        canAlignByPoints = PointsAlignment.rooms(session).isNotEmpty()
    }

    fun startPoints() {
        if (!started || points != null) return
        endFixHere()
        // Where the camera is, before marking hides the building.
        val located = PointsAlignment.room(around = view.cameraInModel, session = session)
        view.startMarking()
        points = PointsAlignment(session, view, located?.id, scope, copyToClipboard = { copyToClipboard(it) })
    }

    /** Back to the alignment there was before marking, if any. */
    fun cancelPoints() {
        val points = points ?: return
        points.end()
        view.stopMarking()
        this.points = null
        session.holdsSceneWork = false
    }

    fun confirmPoints(fit: ReferenceFit) {
        val points = points
        val room = points?.room
        view.confirmPoints(fit.transform, around = fit.centre, modelFloorY = points?.floorY ?: room?.floorY ?: 0.0, room = room?.id)
        points?.end()
        this.points = null
        session.holdsSceneWork = false
        lastPoints = fit to room
        registration?.aligned(fit, room = room)
    }

    // Fix here

    /** "Fix here", while aiming at the nearest corner (iOS `FixHere`). */
    data class FixHere(
        val crosshair: CrosshairState,
        val capturing: Boolean = false,
        val problem: FixProblem? = null,
    )

    /** Aligned by points or on a plate, and nothing else on screen. */
    val canFixHere: Boolean
        get() = ARExperienceRules.canFixHere(started, points != null, registration != null, fixHere != null, anchoring)

    fun startFixHere() {
        if (!started || !canFixHere) return
        view.startFixing()
        fixHere = FixHere(crosshair = view.crosshair)
        view.onCrosshairChange = { state -> fixHere = fixHere?.copy(crosshair = state) }
        session.holdsSceneWork = true
    }

    /**
     * "Fix here" after aligning by points or on a plate (docs/PLAN.md §3): the model drifted off;
     * aim at the floor corner nearest you, in any room, and the building moves back onto it there.
     * A second corner of the room right after straightens the turn too.
     */
    suspend fun fix() {
        val state = fixHere ?: return
        if (!state.crosshair.canMark || state.capturing) return
        fixHere = fixHere?.copy(capturing = true, problem = null)
        // Cleared even when the card's scope is cancelled mid-capture (iOS's `Task` outlives the view).
        val aim = try {
            view.captureFloorAim()
        } finally {
            fixHere = fixHere?.copy(capturing = false)
        }
        val current = view.fixableAlignment
        if (aim == null || current == null) {
            fixHere = fixHere?.copy(problem = FixProblem.UNSTEADY)
            return
        }
        // Against the rooms where they really are; the line of sight tells a corner from the next
        // room's behind the wall. A wall (depth sensor) turns the building onto it.
        val corrections = view.anchoring.corrections
        val normal = aim.normal
        val fix: ReferenceAlignment.CornerFix? = if (aim.surface == ReferenceMark.Surface.WALL_PLANE && normal != null) {
            corrections.reanchor(current, wall = aim.point, normal = normal, seenFrom = aim.eye)
        } else {
            corrections.reanchor(current, mark = aim.point, seenFrom = aim.eye)
        }
        if (fix == null) {
            fixHere = fixHere?.copy(problem = ARExperienceRules.fixProblem(aim.surface))
            return
        }
        view.fixAlignment(fix)?.let { observation ->
            session.recordRoomObservation(observation, lidar = ARAlignmentView.hasLiDAR)
            // A wall gives the room's turn, not where it is along the wall: not enough to save.
            if (observation.hasTranslation) roomFix = observation
        }
        alignments += 1
        endFixHere()
    }

    fun endFixHere() {
        if (fixHere == null) return
        view.stopFixing()
        view.onCrosshairChange = null
        fixHere = null
        session.holdsSceneWork = false
    }

    // Room corrections (admins)

    /** Saving where a room really is for everyone (docs/PLAN.md §3 "Room corrections"). */
    data class RoomSave(val observation: RoomObservation, val status: Status = Status.REVIEW) {
        enum class Status {
            REVIEW,
            SAVING,
            SAVED,
            FAILED,

            /** Not an admin after all (403). */
            FORBIDDEN,
        }
    }

    /** An admin fixed a room from an alignment elsewhere this session, and nothing else is on screen. */
    val canSaveRoom: Boolean
        get() = ARExperienceRules.canSaveRoom(
            session.canManageRoomCorrections, roomFix != null, points != null, registration != null, fixHere != null, roomSave != null,
        )

    fun startRoomSave() {
        val roomFix = roomFix
        if (!canSaveRoom || roomFix == null) return
        roomSave = RoomSave(roomFix)
    }

    suspend fun saveRoom() {
        val save = roomSave ?: return
        if (save.status != RoomSave.Status.REVIEW && save.status != RoomSave.Status.FAILED) return
        roomSave = save.copy(status = RoomSave.Status.SAVING)
        try {
            session.saveRoomCorrection(save.observation.spaceID, save.observation.correction)
            view.updateCorrections(session.roomCorrections)
            roomSave = roomSave?.copy(status = RoomSave.Status.SAVED)
            roomFix = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val forbidden = e is ApiError && e.code == ApiError.Code.FORBIDDEN
            roomSave = roomSave?.copy(status = if (forbidden) RoomSave.Status.FORBIDDEN else RoomSave.Status.FAILED)
        }
    }

    fun endRoomSave() {
        roomSave = null
    }

    /** The room's name, for the save card. */
    fun roomName(id: String?): String? = id?.let { session.manifest?.space(it)?.displayName }

    /** How far a room's new correction moves it from the one in use (metres at its pivot, degrees). */
    fun change(observation: RoomObservation): Pair<Double, Double> {
        val change = session.roomCorrections.change(of = observation.spaceID, to = observation.correction)
        return change.shift to change.turn * 180 / PI
    }

    /** Registers plate [plateNumber] (the scanned one) or a plate chosen from the list. */
    fun startRegistration(plateNumber: Int?) {
        if (!started || !session.canManagePlates || registration != null) return
        val arView = this.view
        val flow = PlateRegistrationFlow(session, plateNumber) { plate ->
            val url = plate.imageURL ?: return@PlateRegistrationFlow
            arView.watchPlate(number = plate.number, imageURL = url, physicalWidth = plate.sizeMm / 1000.0)
        }
        registration = flow
        arView.onRegistrationDetection = { frame, camera -> flow.observe(frame, camera) }
        val last = lastPoints
        if (last != null && anchoring?.method == PlateAnchoring.Method.Points) {
            val located = PointsAlignment.room(around = arView.cameraInModel, session = session)
            flow.aligned(last.first, transform = arView.drawnAlignment, room = located ?: last.second)
        } else {
            startPoints()
        }
        scope.launch { flow.load() }
    }

    fun endRegistration() {
        view.stopWatchingPlate()
        view.onRegistrationDetection = null
        registration = null
        cancelPoints()
    }

    /**
     * The alignment by points moved (a fix, or another room's local alignment took over): a
     * registration collecting detections places the plate with it, in the room the camera is in.
     */
    private fun alignmentMoved() {
        val registration = registration ?: return
        if (anchoring?.method != PlateAnchoring.Method.Points) return
        val transform = view.drawnAlignment ?: return
        registration.alignmentMoved(transform, room = PointsAlignment.room(around = view.cameraInModel, session = session))
    }

    /** "Test now": align on the plates again, the new one included, as a guest would. */
    fun testRegisteredPlate() {
        val number = registration?.plate?.number
        endRegistration()
        view.configure(plates = session.plates, scannedPlate = number ?: session.scannedPlate, corrections = session.roomCorrections)
        view.realign()
    }

    fun stop() {
        readyForCamera = false
        if (started) {
            if (registration != null) endRegistration()
            cancelPoints()
            endFixHere()
            session.holdsSceneWork = false
            started = false
            view.onTap = null
            view.pause()
            view.detach()
            locateIndicator = null
        }
        if (claimedScene) {
            claimedScene = false
            session.select(null)
            session.locate(null)
            session.releaseScene(claimID)
        }
    }

    /** The screen is gone: [stop], then the view's downloads and thermal updates. */
    fun dispose() {
        stop()
        alignmentView?.release()
    }

    fun updateFloorElevation() {
        if (!started) return
        val storeyID = session.arFilters.storeyID
        view.floorElevation = storeyID?.let { session.manifest?.storey(it)?.elevation } ?: 0.0
        refreshRooms()
    }

    private fun handle(event: PlateAnchoring.Event) {
        when (event) {
            is PlateAnchoring.Event.AlignedOnPlate -> {
                alignments += 1
                event.firstIn?.let { firstIn -> session.track { AnalyticsEvent.arAligned(AnalyticsEvent.AlignmentMethod.PLATE, firstIn.seconds, it) } }
            }
            is PlateAnchoring.Event.AlignedManually -> {
                alignments += 1
                event.firstIn?.let { firstIn -> session.track { AnalyticsEvent.arAligned(AnalyticsEvent.AlignmentMethod.MANUAL, firstIn.seconds, it) } }
            }
            is PlateAnchoring.Event.AlignedByPoints -> {
                alignments += 1
                event.firstIn?.let { firstIn -> session.track { AnalyticsEvent.arAligned(AnalyticsEvent.AlignmentMethod.POINTS, firstIn.seconds, it) } }
            }
            // No plate in time: marking points when the room allows it, else the floor placement.
            PlateAnchoring.Event.FellBackToManual ->
                if (ARExperienceRules.startsPointsOnFallback(points != null, canAlignByPoints)) startPoints()
        }
    }

    fun dismissSafetyNote() {
        hasSeenSafetyNote = true
        val preferences = session.preferences
        preferenceWrites.launch { preferences.setHasSeenSafetyNote(true) }
        showsSafetyNote = false
    }

    // Derived

    val isManual: Boolean get() = ARExperienceRules.isManual(anchoring)

    val hasPlates: Boolean get() = anchoring?.hasPlates ?: false

    val isAligned: Boolean get() = ARExperienceRules.isAligned(anchoring, placement)
}

/**
 * The AR experience (A-03/A-04/A-05): ARCore's check (Android only), onboarding, then plate
 * coaching ("Point your camera at the Insite View plate you scanned") and alignment, or the floor
 * placement; See inside; tap an element for its card. The top tag says how it's aligned: "Aligned
 * at plate 2 · Electrical panel".
 *
 * iOS presents it as a full-screen cover; here it's the building's AR destination
 * (`buildingDestinations(arScreen = …)`), and [onClose] pops it.
 */
@Composable
fun ARExperienceView(session: BuildingSession, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session) { ARExperienceModel(session, context, scope) }
    SideEffect { model.copyToClipboard = { text -> copyToClipboard(context, text) } }
    DisposableEffect(model) {
        onDispose { model.dispose() }
    }
    // Access ended mid-session: back to the landing, which shows why.
    val problem = session.accessProblem
    LaunchedEffect(problem) {
        if (problem != null) onClose()
    }
    ARCoreGate(onClose = onClose) {
        ARPreflight(model, onClose)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Insite View", text))
}

/** The preflight (iOS `ARPreflightStep`): explainer once per device, the camera, then AR. */
@Composable
private fun ARPreflight(model: ARExperienceModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var asked by remember { mutableStateOf(false) }
    fun cameraNow(): CameraAccess = currentCameraAccess(context, activity, askedThisScreen = asked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        model.refreshPreflight(cameraNow())
    }
    LaunchedEffect(model) { model.loadPreferences(cameraNow()) }
    // Also after coming back from the settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshPreflight(cameraNow()) }

    val step = model.preflight
    if (step == null) {
        Box(Modifier.fillMaxSize().background(Palette.background))
        return
    }
    when (step) {
        ARPreflightStep.Explainer -> ARExplainerView { model.finishExplainer() }
        ARPreflightStep.CameraExplainer, is ARPreflightStep.CameraDenied -> CameraPermissionView(
            step = step,
            requestAccess = { launcher.launch(android.Manifest.permission.CAMERA) },
            close = onClose,
        )
        ARPreflightStep.Ready -> ARContent(model, onClose)
    }
}

// AR

@Composable
private fun ARContent(model: ARExperienceModel, onClose: () -> Unit) {
    val session = model.session
    var selection by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(model) {
        model.claimScene()
        onDispose { model.stop() }
    }
    // The previous screen's 3D view (one Filament engine) leaves when the navigation transition
    // ends, which resumes this entry; a couple of frames later the camera view starts (docs/PLAN.md
    // §3 "AR": no fixed wait).
    LaunchedEffect(model) {
        lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        repeat(2) { withFrameNanos { } }
        model.cameraCanStart()
    }
    KeepScreenOn()
    HideStatusBar()

    LaunchedEffect(session.arFilters.storeyID) { model.updateFloorElevation() }
    LaunchedEffect(session.manifest != null) { model.configurePlatesIfNeeded() }
    LaunchedEffect(session.locating) {
        model.updateLocateTarget()
        session.select(session.locating)
    }
    LaunchedEffect(session.sceneRevision) {
        model.updateLocateTarget()
        model.refreshRooms()
    }
    LaunchedEffect(model.alignments) {
        if (model.alignments > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (model.canShowCamera) {
            if (model.sessionFailed) {
                ARCoreMessage(
                    title = stringResource(R.string.ar_isn_t_available_right_now),
                    message = stringResource(R.string.ar_couldn_t_start_try_again_or_explore_the_model_in_3d),
                    primary = stringResource(R.string.try_again),
                    onPrimary = { model.retrySession() },
                    onClose = onClose,
                )
            } else {
                key(model.sessionAttempt) {
                    ARAlignmentScene(model.view, Modifier.fillMaxSize())
                }
                DisposableEffect(model) {
                    model.view.onTap = { node -> selection = session.scene.elementID(node) }
                    model.start()
                    onDispose { }
                }
            }
        }
        // (A moment, while the 3D view goes: black.)

        if (!model.sessionFailed) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopBar(model, onClose)
                LocateChip(model)
                Spacer(Modifier.weight(1f))
                BottomPanel(model)
            }

            LocateArrow(model)

            // Only once there's something to aim at (the panel says what to do until then).
            val points = model.points
            val fix = model.fixHere
            if (points != null && points.room != null && points.step == PointsAlignment.Step.Marking && (points.crosshair.canAim || points.capturing)) {
                PointsCrosshair(points.crosshair, points.capturing)
            } else if (fix != null && (fix.crosshair.canAim || fix.capturing)) {
                PointsCrosshair(fix.crosshair, fix.capturing)
            }

            // iOS `ARCoachingOverlayView` (horizontal plane): placing by hand before the first placement.
            // As on iOS, also while the model loads (the anchoring starts manual, without plates).
            if (model.canShowCamera && model.showsFloorCoaching) {
                FloorCoachingCard(Modifier.align(Alignment.Center))
            }

            AnimatedVisibility(model.showsSafetyNote, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                SafetyNoteCard(Modifier.padding(24.dp)) { model.dismissSafetyNote() }
            }
        }
    }

    ObjectCardSheet(
        selection = selection,
        onSelectionChange = { selection = it },
        session = session,
        context = ObjectCardContext.AR,
    )
}

/** The screen stays on while AR runs: sleeping interrupts the session (iOS `isIdleTimerDisabled`). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** iOS `.statusBarHidden(preflight == .ready)`. */
@Composable
private fun HideStatusBar() {
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(activity, view) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

/** A round translucent button over the camera (close, ⋮). */
@Composable
private fun CircleButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(Palette.surface.copy(alpha = 0.85f), CircleShape)
            .clickable(onClickLabel = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.ink)
    }
}

// Top

@Composable
private fun TopBar(model: ARExperienceModel, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        CircleButton(Icons.Filled.Close, stringResource(R.string.close), onClose)
        Spacer(Modifier.weight(1f))
        Text(
            statusTagText(model),
            style = IvType.body(13.sp, FontWeight.Medium),
            color = Palette.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .widthIn(max = 240.dp)
                .background(Palette.surface.copy(alpha = 0.85f), RectangleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        ARMenu(model)
    }
}

@Composable
private fun statusTagText(model: ARExperienceModel): String =
    when (val tag = ARExperienceRules.statusTag(model.tracking, model.points != null, model.anchoring, model.placement)) {
        StatusTag.MoveMoreSlowly -> stringResource(R.string.move_more_slowly)
        StatusTag.MoreDetail -> stringResource(R.string.point_at_a_surface_with_more_detail)
        StatusTag.FindingYourPlaceAgain -> stringResource(R.string.finding_your_place_again)
        StatusTag.ARUnavailable -> stringResource(R.string.ar_isn_t_available_right_now)
        StatusTag.AligningByPoints -> stringResource(R.string.aligning_by_points)
        StatusTag.AlignmentLost -> stringResource(R.string.alignment_lost)
        StatusTag.AlignedByPoints -> stringResource(R.string.aligned_by_points)
        is StatusTag.AlignedAtPlate -> stringResource(R.string.aligned_at_plate_n_x, tag.number, tag.label)
        StatusTag.LookingForPlate -> stringResource(R.string.looking_for_a_plate)
        StatusTag.NotPlacedYet -> stringResource(R.string.not_placed_yet)
        StatusTag.PlacedManuallyAdjusting -> stringResource(R.string.placed_manually_adjusting)
        StatusTag.PlacedManually -> stringResource(R.string.placed_manually)
    }

private enum class MenuPage { CLOSED, MAIN, LEVEL, ROOM }

/** ⋮: fix, admin items, align by points, re-align, placement, level, room, and the safety note (IOS-M2-10). */
@Composable
private fun ARMenu(model: ARExperienceModel) {
    val session = model.session
    var page by remember { mutableStateOf(MenuPage.CLOSED) }
    val items = ARExperienceRules.menuItems(
        canFixHere = model.canFixHere,
        canSaveRoom = model.canSaveRoom,
        pointsActive = model.points != null,
        registrationActive = model.registration != null,
        canAlignByPoints = model.canAlignByPoints,
        canManagePlates = session.canManagePlates,
        hasPlates = model.hasPlates,
        isManual = model.isManual,
        placement = model.placement,
        storeyCount = session.manifest?.storeys?.size ?: 0,
        roomCount = model.rooms.size,
    )
    fun close() {
        page = MenuPage.CLOSED
    }
    Box {
        CircleButton(Icons.Filled.MoreVert, stringResource(R.string.more)) { page = MenuPage.MAIN }
        DropdownMenu(expanded = page != MenuPage.CLOSED, onDismissRequest = ::close) {
            when (page) {
                MenuPage.CLOSED, MenuPage.MAIN -> {
                    // iOS: the actions, a divider, Level and Room, a divider, the safety note.
                    val groups = listOf(
                        items.filter { it != ARMenuItem.LEVEL && it != ARMenuItem.ROOM && it != ARMenuItem.SAFETY_NOTE },
                        items.filter { it == ARMenuItem.LEVEL || it == ARMenuItem.ROOM },
                        items.filter { it == ARMenuItem.SAFETY_NOTE },
                    ).filter { it.isNotEmpty() }
                    groups.forEachIndexed { index, group ->
                        if (index > 0) HorizontalDivider()
                        for (item in group) {
                            MenuItem(item) {
                                when (item) {
                                    ARMenuItem.LEVEL -> page = MenuPage.LEVEL
                                    ARMenuItem.ROOM -> page = MenuPage.ROOM
                                    else -> {
                                        close()
                                        perform(model, item)
                                    }
                                }
                            }
                        }
                    }
                }
                MenuPage.LEVEL -> StoreyPicker(session, BuildingSession.SceneMode.AR, onPicked = ::close)
                MenuPage.ROOM -> {
                    DropdownMenuItem(text = { Text(stringResource(R.string.room), color = Palette.muted) }, onClick = {}, enabled = false)
                    val selected = session.arFilters.roomID
                    CheckedItem(stringResource(R.string.whole_level), selected == null) {
                        session.focusRoom(null, BuildingSession.SceneMode.AR)
                        close()
                    }
                    for (room in model.rooms) {
                        CheckedItem(room.space.displayName ?: stringResource(R.string.unnamed_room), selected == room.id) {
                            session.focusRoom(room.id, BuildingSession.SceneMode.AR)
                            close()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckedItem(title: String, isSelected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        onClick = onClick,
        trailingIcon = { if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.accent) },
    )
}

@Composable
private fun MenuItem(item: ARMenuItem, onClick: () -> Unit) {
    val (title, icon) = when (item) {
        ARMenuItem.FIX_HERE -> R.string.fix_here to Icons.Outlined.CenterFocusStrong
        ARMenuItem.SAVE_ROOM -> R.string.save_this_room_s_adjustment to Icons.Outlined.SaveAlt
        ARMenuItem.ALIGN_BY_POINTS -> R.string.align_by_points to Icons.Outlined.GpsFixed
        ARMenuItem.REGISTER_PLATE -> R.string.register_a_plate_here to Icons.Outlined.QrCodeScanner
        ARMenuItem.REALIGN -> R.string.re_align to Icons.Outlined.GpsFixed
        ARMenuItem.ADJUST_PLACEMENT -> R.string.adjust_placement to Icons.Outlined.OpenWith
        ARMenuItem.PLACE_AGAIN -> R.string.place_again to Icons.Outlined.Replay
        ARMenuItem.PLACE_MANUALLY -> R.string.place_manually to Icons.Outlined.PanTool
        ARMenuItem.LEVEL -> R.string.level to Icons.Outlined.Layers
        ARMenuItem.ROOM -> R.string.room to Icons.Outlined.MeetingRoom
        ARMenuItem.SAFETY_NOTE -> R.string.safety_note to Icons.Outlined.WarningAmber
    }
    DropdownMenuItem(
        text = { Text(stringResource(title)) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
    )
}

private fun perform(model: ARExperienceModel, item: ARMenuItem) {
    when (item) {
        ARMenuItem.FIX_HERE -> model.startFixHere()
        ARMenuItem.SAVE_ROOM -> model.startRoomSave()
        ARMenuItem.ALIGN_BY_POINTS -> model.startPoints()
        ARMenuItem.REGISTER_PLATE -> model.startRegistration(plateNumber = null)
        ARMenuItem.REALIGN -> model.view.realign()
        ARMenuItem.ADJUST_PLACEMENT -> model.view.unlock()
        ARMenuItem.PLACE_AGAIN -> model.view.reset()
        ARMenuItem.PLACE_MANUALLY -> model.view.placeManually()
        ARMenuItem.SAFETY_NOTE -> model.showsSafetyNote = true
        ARMenuItem.LEVEL, ARMenuItem.ROOM -> Unit
    }
}

// Bottom

@Composable
private fun BottomPanel(model: ARExperienceModel) {
    val registration = model.registration
    val points = model.points
    val fix = model.fixHere
    val save = model.roomSave
    when (ARExperienceRules.bottomPanel(registration != null, points != null, fix != null, save != null)) {
        ARBottomPanel.REGISTRATION -> if (registration != null) {
            PlateRegistrationPanel(
                flow = registration,
                points = points,
                confirmPoints = { model.confirmPoints(it) },
                cancelPoints = { model.cancelPoints() },
                startPoints = { model.startPoints() },
                testNow = { model.testRegisteredPlate() },
                close = { model.endRegistration() },
            )
        }
        ARBottomPanel.POINTS -> if (points != null) {
            PointsAlignmentPanel(points, confirm = { model.confirmPoints(it) }, cancel = { model.cancelPoints() })
        }
        ARBottomPanel.FIX_HERE -> if (fix != null) FixHereCard(model, fix)
        ARBottomPanel.ROOM_SAVE -> if (save != null) RoomSaveCard(model, save)
        ARBottomPanel.STANDARD -> StandardPanel(model)
    }
}

@Composable
private fun StandardPanel(model: ARExperienceModel) {
    val session = model.session
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val scanned = session.scannedPlate
        if (session.canManagePlates && session.scannedPlateIsUnplaced && scanned != null) {
            UnplacedPlateCard(model, scanned)
        }
        val coaching = ARExperienceRules.coachingLine(
            manifestLoaded = session.manifest != null,
            panelActive = model.points != null || model.registration != null || model.fixHere != null,
            anchoring = model.anchoring,
            placement = model.placement,
        )
        if (coaching != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.surface.copy(alpha = 0.85f), RectangleShape)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(coachingText(coaching), style = IvType.body(16.sp, FontWeight.Medium), color = Palette.ink, textAlign = TextAlign.Center)
                CoachingActions(model)
            }
        }

        if (ARExperienceRules.showsAdjustingControls(model.isManual, model.placement)) {
            if (model.showsFineTune) FineTunePad(model)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryActionButton(
                    stringResource(R.string.fine_tune),
                    onClick = { model.showsFineTune = !model.showsFineTune },
                    modifier = Modifier.weight(1f),
                )
                PrimaryActionButton(
                    stringResource(R.string.done),
                    onClick = {
                        model.view.lock()
                        model.showsFineTune = false
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (ARExperienceRules.showsSeeInside(model.isManual, model.placement, model.isAligned, session.manifest != null)) {
            SeeInsidePanel(session, model.seeInsideExpanded) { model.seeInsideExpanded = it }
        }
    }
}

@Composable
private fun coachingText(line: CoachingLine): String = when (line) {
    CoachingLine.LoadingModel -> stringResource(R.string.loading_the_model)
    is CoachingLine.PointAtScannedPlate ->
        stringResource(R.string.point_your_camera_at_the_insite_view_plate_you_scanned_plate, line.number, line.label)
    CoachingLine.PointAtAnyPlate -> stringResource(R.string.point_your_camera_at_an_insite_view_plate)
    CoachingLine.HoldStill -> stringResource(R.string.hold_still)
    CoachingLine.AlignmentLostPlates -> stringResource(R.string.alignment_lost_point_at_a_plate_again_or_move_slowly_so_your_phone)
    CoachingLine.AlignmentLostByHand -> stringResource(R.string.alignment_lost_move_slowly_so_your_phone_finds_its_place_or)
    CoachingLine.FindTheFloor -> stringResource(R.string.move_your_phone_slowly_to_find_the_floor)
    CoachingLine.TapTheFloor -> stringResource(R.string.tap_the_floor_where_you_re_standing)
    CoachingLine.DragToAdjust -> stringResource(R.string.drag_to_move_and_twist_to_turn_until_the_model_matches_the_r)
    CoachingLine.TapToIdentify -> stringResource(R.string.tap_a_pipe_cable_or_fixture_to_see_what_it_is)
}

/**
 * "Model off? Fix here", or while plates are searched for, after 4 s (at once when lost), "No
 * plate? Align by points" or "Place manually instead". Re-checked every second while the offer is
 * pending (iOS `TimelineView(.periodic(by: 1))`).
 */
@Composable
private fun CoachingActions(model: ARExperienceModel) {
    val ticking = ARExperienceRules.offersNoPlate(model.anchoring, model.isAligned)
    var now by remember { mutableDoubleStateOf(monotonicSeconds()) }
    LaunchedEffect(ticking) {
        while (ticking) {
            now = monotonicSeconds()
            delay(1_000)
        }
    }
    val action = ARExperienceRules.coachingAction(
        canFixHere = model.canFixHere,
        anchoring = model.anchoring,
        isAligned = model.isAligned,
        secondsSinceOpened = now - model.openedAt,
        canAlignByPoints = model.canAlignByPoints,
    ) ?: return
    when (action) {
        CoachingAction.FIX_HERE -> TextLink(stringResource(R.string.model_off_fix_here), { model.startFixHere() })
        CoachingAction.ALIGN_BY_POINTS -> TextLink(stringResource(R.string.no_plate_align_by_points), { model.startPoints() })
        CoachingAction.PLACE_MANUALLY -> TextLink(stringResource(R.string.place_manually_instead), { model.view.placeManually() })
    }
}

/** Admins who scanned a plate that isn't in the model yet: register it here. */
@Composable
private fun UnplacedPlateCard(model: ARExperienceModel, number: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface.copy(alpha = 0.85f), RectangleShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.plate_n_isn_t_in_the_model_yet, number), style = IvType.body(16.sp, FontWeight.Medium), color = Palette.ink)
        PrimaryActionButton(stringResource(R.string.register_its_position), onClick = { model.startRegistration(plateNumber = number) })
    }
}

/** "Fix here": aim at the nearest corner and tap Fix. */
@Composable
private fun FixHereCard(model: ARExperienceModel, fix: ARExperienceModel.FixHere) {
    val scope = rememberCoroutineScope()
    val canAim = fix.crosshair.canAim || fix.capturing
    val lidar = ARAlignmentView.hasLiDAR
    Card {
        if (canAim) {
            Text(
                stringResource(
                    when (fix.problem) {
                        FixProblem.UNSTEADY -> R.string.couldn_t_hold_the_mark_steady_keep_the_circle_on_the_corner
                        FixProblem.NOT_A_CORNER -> R.string.that_isn_t_near_a_corner_of_the_model_aim_at_the_corner_near
                        FixProblem.NOT_A_WALL -> R.string.that_isn_t_near_a_wall_of_the_model_aim_at_the_wall_nearest
                        null -> if (lidar) {
                            R.string.model_off_aim_at_the_corner_or_wall_nearest_you_with_the_cir
                        } else {
                            R.string.model_off_point_the_phone_down_at_the_floor_corner_nearest_y
                        }
                    },
                ),
                style = IvType.body(16.sp),
                color = Palette.ink,
            )
            // Two fixes in a row, 2.5–6 m apart, also correct the turn (`PlateAnchoring.TURN_WINDOW`);
            // a wall does it by itself.
            Text(
                stringResource(
                    if (lidar) {
                        R.string.a_wall_also_straightens_the_model_so_does_a_second_corner_of
                    } else {
                        R.string.fix_a_second_corner_of_this_room_right_after_to_straighten_t
                    },
                ),
                style = IvType.body(13.sp),
                color = Palette.muted,
            )
        } else {
            FindingFloorRow(fix.crosshair)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryActionButton(
                stringResource(R.string.cancel),
                onClick = { model.endFixHere() },
                enabled = !fix.capturing,
                modifier = Modifier.weight(1f),
            )
            if (canAim) {
                PrimaryActionButton(
                    stringResource(if (fix.capturing) R.string.hold_still else R.string.fix),
                    onClick = { scope.launch { model.fix() } },
                    enabled = fix.crosshair.canMark && !fix.capturing,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** An admin saves where the room they just fixed really is, for everyone who opens the building. */
@Composable
private fun RoomSaveCard(model: ARExperienceModel, save: ARExperienceModel.RoomSave) {
    val scope = rememberCoroutineScope()
    val observation = save.observation
    val locale = androidx.core.os.ConfigurationCompat.getLocales(androidx.compose.ui.platform.LocalConfiguration.current).get(0) ?: Locale.ROOT
    val name = model.roomName(observation.spaceID) ?: ""
    val (shift, turn) = model.change(observation)
    val status = save.status
    Card {
        when (status) {
            ARExperienceModel.RoomSave.Status.SAVED -> Text(
                stringResource(R.string.saved_everyone_will_see_this_room_where_it_really_is),
                style = IvType.body(16.sp, FontWeight.Medium),
                color = Palette.ink,
            )
            ARExperienceModel.RoomSave.Status.FORBIDDEN -> Text(
                stringResource(R.string.only_admins_can_save_room_adjustments),
                style = IvType.body(16.sp),
                color = Palette.ink,
            )
            else -> {
                Text(stringResource(R.string.save_where_x_really_is, name), style = IvType.headline, color = Palette.ink)
                Text(
                    stringResource(R.string.everyone_who_opens_this_building_in_ar_will_start_with_this),
                    style = IvType.body(16.sp),
                    color = Palette.ink,
                )
                Text(
                    stringResource(
                        R.string.moves_x_and_turns_x_from_the_saved_position,
                        ARExperienceRules.saveLength(shift, locale),
                        ARExperienceRules.saveAngle(turn, locale),
                    ),
                    style = IvType.body(13.sp),
                    color = Palette.ink,
                )
                val base = model.roomName(observation.baseSpaceID)
                val distance = ARExperienceRules.saveLength(observation.baseDistance, locale)
                Text(
                    if (base != null) {
                        stringResource(R.string.measured_from_x_x_away, base, distance)
                    } else {
                        stringResource(R.string.measured_from_a_plate_x_away, distance)
                    },
                    style = IvType.body(13.sp),
                    color = Palette.muted,
                )
                if (observation.baseDistance > ARExperienceRules.FAR_BASE_DISTANCE) {
                    Text(
                        stringResource(R.string.far_from_where_you_aligned_the_phone_may_have_lost_precision),
                        style = IvType.body(13.sp),
                        color = Palette.warn,
                    )
                }
                if (observation.turn == null) {
                    Text(
                        stringResource(R.string.to_include_its_turn_fix_a_second_corner_of_this_room_right_a),
                        style = IvType.body(13.sp),
                        color = Palette.muted,
                    )
                }
                if (status == ARExperienceModel.RoomSave.Status.FAILED) {
                    Text(
                        stringResource(R.string.couldn_t_save_check_your_connection_and_try_again),
                        style = IvType.body(13.sp),
                        color = Palette.accent,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (status) {
                ARExperienceModel.RoomSave.Status.SAVED, ARExperienceModel.RoomSave.Status.FORBIDDEN ->
                    PrimaryActionButton(stringResource(R.string.done), onClick = { model.endRoomSave() })
                else -> {
                    val saving = status == ARExperienceModel.RoomSave.Status.SAVING
                    SecondaryActionButton(
                        stringResource(R.string.cancel),
                        onClick = { model.endRoomSave() },
                        enabled = !saving,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryActionButton(
                        stringResource(if (saving) R.string.saving else R.string.save_for_everyone),
                        onClick = { scope.launch { model.saveRoom() } },
                        enabled = !saving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Locate in AR (IOS-M3-05): which element, how far in the user's units, and a way to stop. */
@Composable
private fun LocateChip(model: ARExperienceModel) {
    val session = model.session
    val id = session.locating ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface.copy(alpha = 0.85f), RectangleShape)
            .padding(start = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.GpsFixed, contentDescription = null, tint = Palette.accent)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(session.index[id]?.let { session.displayName(it) } ?: "", style = IvType.body(15.sp, FontWeight.SemiBold), color = Palette.ink)
            val indicator = model.locateIndicator
            Text(
                when {
                    indicator == null -> stringResource(R.string.line_the_model_up_with_the_room_to_find_it)
                    indicator.isOnScreen -> stringResource(R.string.x_away, session.units.distance(indicator.distance))
                    else -> stringResource(R.string.x_away_follow_the_arrow, session.units.distance(indicator.distance))
                },
                style = IvType.body(13.sp),
                color = Palette.muted,
            )
        }
        val stop = stringResource(R.string.stop_locating)
        Box(
            Modifier
                .size(44.dp)
                .clickable(role = Role.Button) { session.locate(null) }
                .semantics { contentDescription = stop },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Cancel, contentDescription = null, tint = Palette.muted)
        }
    }
}

/** The off-screen arrow on the screen's edge, pointing the way to turn. */
@Composable
private fun LocateArrow(model: ARExperienceModel) {
    BoxWithConstraints(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val indicator = model.locateIndicator
        val angle = indicator?.arrowAngle
        if (model.session.locating != null && indicator != null && !indicator.isOnScreen && angle != null) {
            val shown by animateFloatAsState(angle.toFloat(), animationSpec = tween(durationMillis = 150, easing = EaseOut), label = "arrow")
            val position = LocateGuide.edgePosition(
                angle = shown.toDouble(), width = maxWidth.value.toDouble(), height = maxHeight.value.toDouble(), inset = 48.0,
            )
            Box(
                Modifier
                    .offset(x = (position.x - 24).dp, y = (position.y - 24).dp)
                    .size(48.dp)
                    .rotate(-(shown * 180 / PI).toFloat())
                    .background(Palette.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.East, contentDescription = null, tint = Palette.onAccent, modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** "Fine-tune": ±1 cm and ±0.5° steps relative to where the camera looks; buttons repeat while held. */
@Composable
private fun FineTunePad(model: ARExperienceModel) {
    val units = model.session.units
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface.copy(alpha = 0.85f), RectangleShape)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.each_tap_moves_x_or_turns_x, units.nudge(ManualAlignment.NUDGE_DISTANCE), units.angle(0.5)),
            style = IvType.body(12.sp),
            color = Palette.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NudgeButton(model, ManualAlignment.Nudge.TURN_LEFT, Icons.AutoMirrored.Filled.RotateLeft, R.string.turn_left)
            NudgeButton(model, ManualAlignment.Nudge.FORWARD, Icons.Filled.ArrowUpward, R.string.move_away)
            NudgeButton(model, ManualAlignment.Nudge.TURN_RIGHT, Icons.AutoMirrored.Filled.RotateRight, R.string.turn_right)
            NudgeButton(model, ManualAlignment.Nudge.UP, Icons.Filled.KeyboardArrowUp, R.string.raise)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NudgeButton(model, ManualAlignment.Nudge.LEFT, Icons.Filled.West, R.string.move_left)
            NudgeButton(model, ManualAlignment.Nudge.BACK, Icons.Filled.ArrowDownward, R.string.move_closer)
            NudgeButton(model, ManualAlignment.Nudge.RIGHT, Icons.Filled.East, R.string.move_right)
            NudgeButton(model, ManualAlignment.Nudge.DOWN, Icons.Filled.KeyboardArrowDown, R.string.lower)
        }
    }
}

/** iOS `.buttonRepeatBehavior(.enabled)`: one step on press, then repeating while held. */
@Composable
private fun NudgeButton(model: ARExperienceModel, nudge: ManualAlignment.Nudge, icon: ImageVector, label: Int) {
    val description = stringResource(label)
    Box(
        Modifier
            .size(width = 52.dp, height = 44.dp)
            .background(Palette.surface.copy(alpha = 0.8f), RectangleShape)
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    model.view.nudge(nudge)
                    true
                }
            }
            .pointerInput(nudge) {
                detectTapGestures(
                    onPress = {
                        coroutineScope {
                            val repeating = launch {
                                model.view.nudge(nudge)
                                delay(NUDGE_REPEAT_DELAY_MILLIS)
                                while (true) {
                                    model.view.nudge(nudge)
                                    delay(NUDGE_REPEAT_INTERVAL_MILLIS)
                                }
                            }
                            tryAwaitRelease()
                            repeating.cancel()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.ink)
    }
}

private const val NUDGE_REPEAT_DELAY_MILLIS = 400L
private const val NUDGE_REPEAT_INTERVAL_MILLIS = 80L

/** First aid (docs/PLAN.md §3, IOS-M2-10): shown on first AR use, then from the ⋮ menu. A card, not a sheet. */
@Composable
private fun SafetyNoteCard(modifier: Modifier = Modifier, dismiss: () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.surface, RectangleShape)
            .padding(20.dp)
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.warn)
            Text(stringResource(R.string.before_you_drill_or_cut), style = IvType.headline, color = Palette.ink)
        }
        Text(stringResource(R.string.the_model_shows_the_design_always_confirm_before_drilling_or), style = IvType.body(17.sp), color = Palette.ink)
        PrimaryActionButton(stringResource(R.string.got_it), onClick = dismiss)
    }
}

/**
 * iOS `ARCoachingOverlayView` with the horizontal-plane goal: shown while placing by hand before
 * the first placement, until the floor is found and tracking is normal.
 */
@Composable
private fun FloorCoachingCard(modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(horizontal = 32.dp)
            .background(Palette.surface.copy(alpha = 0.85f), RectangleShape)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.muted, strokeWidth = 2.dp)
        Text(stringResource(R.string.move_the_phone_slowly_over_the_floor), style = IvType.body(15.sp, FontWeight.Medium), color = Palette.ink)
    }
}
