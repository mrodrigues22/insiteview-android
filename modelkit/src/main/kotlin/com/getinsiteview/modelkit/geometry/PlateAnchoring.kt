package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.Manifest
import java.util.UUID
import kotlin.math.PI
import kotlin.math.max

/**
 * The AR alignment flow as a state machine (IOS-M2-04, M2-05, M2-10), independent of ARCore so
 * it's tested on the JVM. `:ar` feeds it image poses, tracking changes, camera moves and
 * the clock (seconds, e.g. the AR frame's timestamp), then reads [coaching], [wantsImageDetection]
 * and [transform].
 *
 * - Plates first: detections of any placed plate are smoothed ([AlignmentSmoother]); the
 *   first converged plate aligns the building and image detection stops (power).
 * - No placed plates, or [FALLBACK_DELAY] without a detection: manual placement.
 *   Detection keeps running, so a plate seen later still aligns the building.
 * - "Re-align", or walking more than [REANCHOR_DISTANCE] from where it aligned: detection
 *   runs again and a converged plate blends the building over 0.5 s.
 * - Tracking lost: coaching comes back until tracking recovers or a plate aligns again.
 * - Local alignments ([AlignmentSites]): each alignment, automatic re-anchoring and "Fix here"
 *   is kept where it was measured, and the one nearest the camera places the building. `:ar` ties
 *   each to an AR anchor and reports the anchors' moves ([siteAnchorsMoved]).
 * - Room corrections ([RoomCorrections]): alignments are made against the rooms and plates
 *   where they really are (the as-built frame), and the building is drawn with the correction of
 *   the room the camera is in ([transform]), gliding when the camera walks into another.
 *   A "Fix here" that measures its room from another alignment leaves a [RoomObservation].
 *
 * A mutable class (a Swift struct with mutating methods); [copy] gives an independent one.
 *
 * @param plates the manifest's placed plates.
 * @param scannedPlate the plate number from the invocation URL, if any.
 * @param at when AR opened (seconds).
 * @param corrections the rooms and their corrections.
 */
class PlateAnchoring(
    plates: List<Manifest.Plate>,
    /** From `/b/{code}/{plate}`: the plate the guest just scanned. */
    val scannedPlate: Int?,
    at: Double,
    corrections: RoomCorrections = RoomCorrections.none,
) {
    sealed interface Method {
        data class Plate(val number: Int) : Method

        /** Dragged and twisted on the floor. */
        data object Manual : Method

        /** Floor corners marked with the crosshair ([ReferenceAlignment]). */
        data object Points : Method

        /** Placed by the user rather than on a plate: the floor placement's actions stay. */
        val isByHand: Boolean get() = this == Manual || this == Points
    }

    /** What the building is aligned with, and where. */
    data class Alignment(
        val method: Method,
        val transform: YawTransform,
        /** Camera position when it aligned, for automatic re-anchoring. */
        val cameraPosition: Vec3?,
    )

    sealed interface Phase {
        /** Looking for a plate; no alignment yet. */
        data object Searching : Phase

        /** Manual placement (no plates, or none found in time). */
        data object Manual : Phase

        /** Aligned; image detection off. */
        data object Aligned : Phase

        /** Aligned, and looking for a plate to re-anchor on ("Re-align" or a big move). */
        data class Realigning(val requested: Boolean) : Phase

        /** Tracking lost after aligning. */
        data object Lost : Phase
    }

    /** What the coaching line says. */
    sealed interface Coaching {
        /** "Point your camera at the Insite View plate you scanned" (plate invocation, A-04). */
        data class PointAtScannedPlate(val number: Int, val label: String) : Coaching

        /** "Point your camera at an Insite View plate". */
        data object PointAtAnyPlate : Coaching

        /** A plate is in view: "Hold still…". */
        data class HoldStill(val number: Int) : Coaching

        /** Manual placement: the AR view's own floor coaching. */
        data object PlaceManually : Coaching

        /** Tracking was lost: "Alignment lost. Point at a plate or move slowly to find your place." */
        data object AlignmentLost : Coaching

        /** Aligned; nothing to say beyond the status tag. */
        data object None : Coaching
    }

    /** Something the AR screen reports or records. */
    sealed interface Event {
        /**
         * Aligned on a plate. [firstIn] is the time since AR opened when it's the session's
         * first alignment (`ar_aligned`), else `null`.
         */
        data class AlignedOnPlate(val plate: Int, val firstIn: Double?) : Event

        /** Manual placement finished. Same [firstIn] rule. */
        data class AlignedManually(val firstIn: Double?) : Event

        /** Aligned by marking reference points. Same [firstIn] rule. */
        data class AlignedByPoints(val firstIn: Double?) : Event

        /** Moved to manual placement because no plate was found in time. */
        data object FellBackToManual : Event
    }

    /** The plates as in the model, to move again when the corrections change. */
    private val modelPlates: List<Manifest.Plate> = plates

    /** The rooms and their corrections. */
    var corrections: RoomCorrections = corrections
        private set

    /** The placed plates where they really are (moved with their room's correction). */
    var plates: Map<Int, Manifest.Plate> = platesByNumber(plates, corrections)
        private set

    /** The room the camera is in (as built): the building is drawn with its correction. */
    var cameraRoom: String? = null
        private set

    /**
     * The last "Fix here" that measured its room from another alignment, and how many so far
     * (the AR screen sends each one to the API).
     */
    var lastObservation: RoomObservation? = null
        private set
    var observationCount: Int = 0
        private set

    var phase: Phase = if (plates.isEmpty()) Phase.Manual else Phase.Searching
        private set

    /** The alignment placing the building now: its transform is the current site's. */
    var alignment: Alignment? = null
        private set

    private var siteList = AlignmentSites()

    /** The local alignments; the one nearest the camera places the building. (A copy.) */
    val sites: AlignmentSites get() = siteList.copy()

    private var camera: Vec3? = null
    private var smoothers = HashMap<Int, AlignmentSmoother>()
    private var blend: AlignmentBlend? = null

    /** The correction gliding from one room's to another's (model → as built). */
    private var roomBlend: AlignmentBlend? = null
    private var openedAt: Double = at
    private var phaseSince: Double = at
    private var hasReportedFirstAlignment = false
    private var lastDetection: Pair<Int, Double>? = null

    // Reading

    /** Whether the AR session should look for the plates' reference images. */
    val wantsImageDetection: Boolean
        get() {
            if (plates.isEmpty()) return false
            return when (phase) {
                Phase.Searching, Phase.Manual, is Phase.Realigning, Phase.Lost -> true
                Phase.Aligned -> false
            }
        }

    val coaching: Coaching
        get() {
            val phase = phase
            if (phase == Phase.Lost) return Coaching.AlignmentLost
            val detecting = lastDetection
            if (detecting != null && smoothers[detecting.first]?.isEmpty == false &&
                (phase == Phase.Searching || phase == Phase.Realigning(requested = true))
            ) {
                return Coaching.HoldStill(number = detecting.first)
            }
            return when (phase) {
                Phase.Searching, Phase.Realigning(requested = true) -> {
                    val plate = scannedPlate?.let { plates[it] }
                    if (plate != null) Coaching.PointAtScannedPlate(number = plate.number, label = plate.label) else Coaching.PointAtAnyPlate
                }
                Phase.Manual -> if (alignment == null) Coaching.PlaceManually else Coaching.None
                else -> Coaching.None
            }
        }

    /** The plate the building is aligned at, for "Aligned at plate 2 · Electrical panel". */
    val alignedPlate: Manifest.Plate?
        get() = (alignment?.method as? Method.Plate)?.let { plates[it.number] }

    /**
     * Whether manual placement drives the transform (the AR view's drag, twist and nudge).
     * Still true while tracking is lost after a manual placement: the screen keeps the floor
     * placement's coaching and actions instead of asking for a plate.
     */
    val isManual: Boolean
        get() = when (phase) {
            Phase.Manual -> alignedPlate == null
            Phase.Lost -> alignment?.method?.isByHand == true
            Phase.Searching, Phase.Aligned, is Phase.Realigning -> false
        }

    /**
     * Where the building is drawn now: the camera's room's correction, then the alignment
     * (each blending after a change).
     */
    fun transform(at: Double): YawTransform? = asBuilt(at)?.let { roomCorrection(at).then(it) }

    /**
     * The alignment of the as-built building (blending after a re-anchor). Alignments are
     * measured, blended and fixed in this frame.
     */
    private fun asBuilt(at: Double): YawTransform? {
        val blend = blend
        if (blend != null && !blend.isFinished(at)) {
            return blend.transform(at)
        }
        return alignment?.transform
    }

    /** The correction the building is drawn with: the camera's room's (gliding to it). */
    private fun roomCorrection(at: Double): YawTransform {
        val roomBlend = roomBlend
        if (roomBlend != null && !roomBlend.isFinished(at)) {
            return roomBlend.transform(at)
        }
        return corrections.transform(of = cameraRoom)
    }

    /** Whether a blend is still running (the AR view keeps updating the root until it ends). */
    fun isBlending(at: Double): Boolean =
        (blend?.let { !it.isFinished(at) } ?: false) || (roomBlend?.let { !it.isFinished(at) } ?: false)

    /** "Fix here" can correct the alignment: by points, or on a plate, and nothing else going on. */
    val canFix: Boolean
        get() {
            val alignment = alignment ?: return false
            return (phase == Phase.Manual && alignment.method == Method.Points) ||
                (phase == Phase.Aligned && alignment.method is Method.Plate)
        }

    // Inputs

    /** A tracked image anchor for plate [plate], with the camera's position. */
    fun observe(plate: Int, world: PlateFrame, camera: Vec3? = null, at: Double): Event? {
        val number = plate
        val time = at
        if (!wantsImageDetection) return null
        val placed = plates[number] ?: return null
        lastDetection = number to time
        val smoother = smoothers.getOrPut(number) { AlignmentSmoother(plate = number, model = placed.frame) }
        val solution = smoother.add(world) ?: return null
        return align(placed, solution.transform, camera, time)
    }

    /** Manual placement finished ("Done"). Plates keep being watched in case one is found later. */
    fun placedManually(transform: YawTransform, camera: Vec3? = null, at: Double): Event {
        val time = at
        alignment = Alignment(method = Method.Manual, transform = transform, cameraPosition = camera)
        // Placed by hand: the gestures move the whole building, in the world frame.
        siteList.removeAll()
        blend = null
        setCameraRoom(null, time, glide = false)
        setPhase(Phase.Manual, time)
        return Event.AlignedManually(firstIn = firstAlignment(time))
    }

    /**
     * Aligned by marking reference points: like a manual placement (plates keep being watched,
     * and a plate seen later re-anchors), reported as `points`. [around] is where the marks are
     * (world), the site of this alignment: the camera's position, or the model origin, without it.
     * [fitted] was fitted to the corners of [room] as in the model, so it draws that room where
     * it is; the alignment is its as-built equivalent (the room's correction undone first).
     */
    fun alignedByPoints(
        fitted: YawTransform,
        around: Vec3? = null,
        camera: Vec3? = null,
        room: String? = null,
        at: Double,
    ): Event {
        val time = at
        val transform = corrections.transform(of = room).inverse.then(fitted)
        alignment = Alignment(method = Method.Points, transform = transform, cameraPosition = camera)
        siteList.startOver(
            with = AlignmentSite(
                method = Method.Points, transform = transform, around = around ?: camera ?: transform.translation, camera = camera,
                at = time, room = room,
            ),
        )
        blend = null
        setCameraRoom(room, time, glide = false)
        setPhase(Phase.Manual, time)
        return Event.AlignedByPoints(firstIn = firstAlignment(time))
    }

    /**
     * "Fix here" (docs/PLAN.md §3): a corner marked where the building drifted off. It becomes a
     * new site there, so the fix holds around it and leaves the other sites' rooms as they were;
     * the building glides onto it, turning about the corner. Right after a fix 2.5–6 m away, both
     * corners straighten the turn too, for every site between them. Returns `false`, changing
     * nothing, unless [canFix].
     *
     * Measured from an alignment made in another room (or on a plate), the fix also says where
     * its room really is: [lastObservation]. A second corner of the same room right after says
     * its turn as well, measured from the first one's alignment. A fix in the room the building
     * was aligned or fixed in says nothing new about that room.
     */
    fun fixedAlignment(fix: ReferenceAlignment.CornerFix, camera: Vec3? = null, at: Double): Boolean {
        val time = at
        if (!canFix) return false
        var alignment = alignment ?: return false
        val before = alignment.transform
        var target = fix.transform
        var straightened: Pair<Vec3, Double>? = null
        var turnedInRoom = false
        // A wall fix sets the turn itself; two corners in a row straighten it.
        if (fix.kind == ReferenceAlignment.CornerFix.Kind.CORNER) {
            val previous = siteList.all.lastOrNull { it.fix != null }
            val previousFix = previous?.fix
            if (previous != null && previousFix != null && previousFix.kind == ReferenceAlignment.CornerFix.Kind.CORNER &&
                time - previous.createdAt <= TURN_WINDOW
            ) {
                val turned = ReferenceAlignment.turned(
                    fix, previousCorner = previousFix.corner, previousMark = previous.anchorMove.apply(previousFix.mark),
                )
                if (turned != null) {
                    target = turned
                    straightened = Pair(
                        (previous.point + fix.mark) / 2.0,
                        ReferenceAlignment.horizontalDistance(previous.point, fix.mark) / 2 + TURN_REACH,
                    )
                    turnedInRoom = fix.spaceID != null && previousFix.spaceID == fix.spaceID
                }
            }
        }

        // What the fix measures its room from: the alignment placing the building when it's another
        // room's (or a plate's); a fix in a room already fixed from elsewhere carries that on.
        val current = siteList.current
        var base: AlignmentSite.ObservationBase? = null
        var turnMeasured = fix.kind == ReferenceAlignment.CornerFix.Kind.WALL || turnedInRoom
        val room = fix.spaceID
        if (room != null) {
            val carried = current?.observationBase
            if (current != null && current.room == room && carried != null) {
                base = carried
                turnMeasured = turnMeasured || current.turnMeasured
            } else if (current?.room != room) {
                base = AlignmentSite.ObservationBase(transform = before, room = current?.room, point = current?.point ?: fix.mark)
            }
        }
        if (base != null) {
            val observation = corrections.observation(
                fix, base = base.transform, baseRoom = base.room, basePoint = base.point, newTurn = if (turnMeasured) target.yaw else null,
            )
            if (observation != null) {
                lastObservation = observation
                observationCount += 1
            }
        }

        asBuilt(time)?.let { blend = AlignmentBlend(from = it, to = target, pivot = fix.corner, start = time) }
        siteList.add(
            AlignmentSite(
                method = alignment.method, transform = target, around = fix.mark, camera = camera, at = time, fix = fix,
                room = fix.spaceID, observationBase = base, turnMeasured = base != null && turnMeasured,
            ),
        )
        if (straightened != null) {
            siteList.retarget(around = straightened.first, within = straightened.second, to = target)
        }
        alignment = alignment.copy(transform = target, cameraPosition = camera ?: alignment.cameraPosition)
        this.alignment = alignment
        return true
    }

    /**
     * New corrections (an admin saved one, or the manifest came again): plates move with their
     * rooms, and each site moves so the room it was measured in stays where it is on screen. The
     * building glides to the result.
     */
    fun updateCorrections(new: RoomCorrections, at: Double) {
        val time = at
        if (new == corrections) return
        val old = corrections
        val builtBefore = asBuilt(time)
        val correctionBefore = roomCorrection(time)
        corrections = new
        plates = platesByNumber(modelPlates, new)
        smoothers = HashMap()
        siteList.retargetEach { site ->
            val room = site.room
            if (room == null || old.transform(of = room) == new.transform(of = room)) {
                null
            } else {
                new.transform(of = room).inverse.then(old.transform(of = room)).then(site.transform)
            }
        }
        val alignment = alignment
        if (alignment != null) {
            val site = siteList.current
            this.alignment = if (site != null) alignment.copy(transform = site.transform) else alignment
        }
        val camera = camera
        val pivot = if (builtBefore != null && camera != null) builtBefore.inverseApply(camera) else Vec3.zero
        val after = this.alignment?.transform
        if (builtBefore != null && after != null && builtBefore != after) {
            blend = AlignmentBlend(from = builtBefore, to = after, pivot = pivot, start = time)
        }
        val correctionAfter = corrections.transform(of = cameraRoom)
        roomBlend = if (correctionBefore == correctionAfter) {
            null
        } else {
            AlignmentBlend(from = correctionBefore, to = correctionAfter, pivot = pivot, start = time)
        }
    }

    /**
     * The floor glue on an alignment by points: every site up or down by [by] (one floor), the
     * building gliding there. Returns `false`, changing nothing, when the building isn't aligned
     * by points (a plate took over, or it's being placed by hand).
     */
    fun raisedPointsAlignment(by: Double, at: Double): Boolean {
        val time = at
        val alignment = alignment
        if (phase != Phase.Manual || alignment == null || alignment.method != Method.Points) return false
        val raised = alignment.transform.translated(by = Vec3(0.0, by, 0.0))
        asBuilt(time)?.let { blend = AlignmentBlend(from = it, to = raised, pivot = Vec3.zero, start = time) }
        siteList.raise(by = by)
        this.alignment = alignment.copy(transform = raised)
        return true
    }

    /** The AR session's latest poses for the sites' anchors (`:ar`). The building follows the current site. */
    fun siteAnchorsMoved(poses: Map<UUID, YawTransform>, at: Double) {
        var moved = false
        for ((id, pose) in poses) {
            if (siteList.anchorMoved(id, to = pose)) moved = true
        }
        if (moved) {
            followSites(at)
        }
    }

    /** "Place manually": manual placement takes over, starting from the current alignment. */
    fun switchToManual(at: Double) {
        val time = at
        // The manual placement starts where the building is drawn.
        val drawn = transform(at = time)
        blend = null
        siteList.removeAll()
        setCameraRoom(null, time, glide = false)
        val alignment = alignment
        if (alignment != null) {
            this.alignment = Alignment(method = Method.Manual, transform = drawn ?: alignment.transform, cameraPosition = alignment.cameraPosition)
        }
        setPhase(Phase.Manual, time)
    }

    /** The manual placement was cleared ("Place again"). */
    fun clearedManualPlacement(at: Double) {
        if (phase != Phase.Manual || alignedPlate != null) return
        alignment = null
        siteList.removeAll()
        setCameraRoom(null, at, glide = false)
    }

    /** Call every frame or so: the fallback to manual and the re-align timeout. */
    fun tick(at: Double): Event? {
        val time = at
        if (blend?.isFinished(time) == true) {
            blend = null
        }
        if (roomBlend?.isFinished(time) == true) {
            roomBlend = null
        }
        val phase = phase
        if (phase == Phase.Searching && time - phaseSince >= FALLBACK_DELAY && !isDetecting(time)) {
            setPhase(Phase.Manual, time)
            return Event.FellBackToManual
        }
        if (phase is Phase.Realigning && time - phaseSince >= REALIGN_TIMEOUT && !isDetecting(time)) {
            setPhase(if (alignment?.method?.isByHand == true) Phase.Manual else Phase.Aligned, time)
        }
        return null
    }

    /** "Re-align": look for a plate again (the current alignment stays until one converges). */
    fun requestRealign(at: Double) {
        if (plates.isEmpty()) return
        smoothers = HashMap()
        lastDetection = null
        setPhase(if (alignment == null) Phase.Searching else Phase.Realigning(requested = true), at)
    }

    /**
     * Where the camera is (world): the site nearest it places the building. Far enough from where
     * it aligned, detection turns back on quietly so another plate can correct drift.
     */
    fun cameraMoved(to: Vec3, at: Double) {
        val position = to
        val time = at
        camera = position
        followSites(time)
        followCameraRoom(time)
        val from = alignment?.cameraPosition
        if (phase != Phase.Aligned || from == null || !(Vector.distance(from, position) > REANCHOR_DISTANCE)) return
        smoothers = HashMap()
        setPhase(Phase.Realigning(requested = false), time)
    }

    /** Tracking became limited (relocalizing) or the session was interrupted. */
    fun trackingLost(at: Double) {
        if (alignment == null || phase == Phase.Lost) return
        smoothers = HashMap()
        lastDetection = null
        setPhase(Phase.Lost, at)
    }

    /** Tracking is normal again: the AR session relocalized, so the old alignment holds. */
    fun trackingRestored(at: Double) {
        if (phase != Phase.Lost) return
        setPhase(if (alignment?.method?.isByHand == true) Phase.Manual else Phase.Aligned, at)
        // Anchors that moved while relocalizing.
        followSites(at)
    }

    /** An independent copy (Swift value semantics). */
    fun copy(): PlateAnchoring {
        val copy = PlateAnchoring(modelPlates, scannedPlate, openedAt, corrections)
        copy.plates = plates
        copy.cameraRoom = cameraRoom
        copy.lastObservation = lastObservation
        copy.observationCount = observationCount
        copy.phase = phase
        copy.alignment = alignment
        copy.siteList = siteList.copy()
        copy.camera = camera
        copy.smoothers = HashMap(smoothers.mapValues { it.value.copy() })
        copy.blend = blend
        copy.roomBlend = roomBlend
        copy.openedAt = openedAt
        copy.phaseSince = phaseSince
        copy.hasReportedFirstAlignment = hasReportedFirstAlignment
        copy.lastDetection = lastDetection
        return copy
    }

    // Helpers

    private fun align(plate: Manifest.Plate, transform: YawTransform, camera: Vec3?, time: Double): Event {
        val number = plate.number
        val room = modelPlates.firstOrNull { it.number == number }?.let { corrections.room(ofPlate = it.frame) }
        val hadAlignment = alignment != null
        asBuilt(time)?.let { blend = AlignmentBlend(from = it, to = transform, pivot = plate.frame.position, start = time) }
        alignment = Alignment(method = Method.Plate(number), transform = transform, cameraPosition = camera)
        setCameraRoom(room ?: cameraRoom, time, glide = hadAlignment)
        // Re-anchoring on the way (a big move) adds to the sites; anything else is a new alignment.
        val site = AlignmentSite(
            method = Method.Plate(number), transform = transform, around = transform.apply(plate.frame.position), camera = camera,
            at = time, room = room,
        )
        if (phase == Phase.Realigning(requested = false)) {
            siteList.add(site)
        } else {
            siteList.startOver(with = site)
        }
        smoothers = HashMap()
        lastDetection = null
        setPhase(Phase.Aligned, time)
        return Event.AlignedOnPlate(plate = number, firstIn = firstAlignment(time))
    }

    /**
     * The site nearest the camera places the building: when another takes over, or the AR session
     * moved the current one's anchor, the building glides there about the camera's model point. Not
     * while tracking is lost: the camera's position means nothing then.
     */
    private fun followSites(time: Double) {
        if (phase == Phase.Lost) return
        var alignment = alignment ?: return
        val camera = camera
        if (camera != null && siteList.select(near = camera)) {
            val site = siteList.current
            if (site != null) {
                alignment = alignment.copy(method = site.method, cameraPosition = site.camera ?: alignment.cameraPosition)
            }
        }
        val target = siteList.current?.transform
        if (target != null) {
            val pivot = target.inverseApply(camera ?: target.translation)
            val off = Vector.distance(alignment.transform.apply(pivot), target.apply(pivot))
            if (off >= FOLLOW_DISTANCE || alignment.transform.yawDifference(to = target) >= FOLLOW_ANGLE) {
                asBuilt(time)?.let { blend = AlignmentBlend(from = it, to = target, pivot = pivot, start = time) }
                alignment = alignment.copy(transform = target)
            }
        }
        this.alignment = alignment
    }

    /**
     * The camera's room, once it's well inside another ([RoomCorrections.room] containing):
     * the building glides to that room's correction. Not while tracking is lost, nor for a
     * placement by hand (it isn't measured against any room).
     */
    private fun followCameraRoom(time: Double) {
        if (phase == Phase.Lost) return
        val camera = camera ?: return
        val alignment = alignment ?: return
        if (alignment.method == Method.Manual || corrections.rooms.isEmpty()) return
        val room = corrections.room(containing = alignment.transform.inverseApply(camera), previous = cameraRoom)
        setCameraRoom(room, time, glide = true)
    }

    private fun setCameraRoom(room: String?, time: Double, glide: Boolean) {
        if (room == cameraRoom) return
        val from = roomCorrection(time)
        cameraRoom = room
        val to = corrections.transform(of = room)
        if (!glide || from == to) {
            roomBlend = null
            return
        }
        val camera = camera
        val alignment = alignment
        val pivot = if (camera != null && alignment != null) alignment.transform.inverseApply(camera) else Vec3.zero
        roomBlend = AlignmentBlend(from = from, to = to, pivot = pivot, start = time)
    }

    private fun firstAlignment(time: Double): Double? {
        if (hasReportedFirstAlignment) return null
        hasReportedFirstAlignment = true
        return max(0.0, time - openedAt)
    }

    private fun setPhase(phase: Phase, time: Double) {
        this.phase = phase
        phaseSince = time
    }

    /** A plate was seen in the last second: don't time out while it's converging. */
    private fun isDetecting(time: Double): Boolean {
        val lastDetection = lastDetection ?: return false
        return time - lastDetection.second < 1
    }

    companion object {
        /** Manual placement takes over after this long without a plate detection (seconds). */
        const val FALLBACK_DELAY = 10.0

        /** Automatic re-anchoring once the camera is this far from where it last aligned. */
        const val REANCHOR_DISTANCE = 4.0

        /** A re-align that finds no plate in this long gives up (the old alignment stays). */
        const val REALIGN_TIMEOUT = 15.0

        /**
         * The building follows a site (its anchor moved, or another site took over) once it's off by
         * this much at the camera, or turned by [FOLLOW_ANGLE]; smaller moves wait until they add up.
         */
        const val FOLLOW_DISTANCE = 0.02
        const val FOLLOW_ANGLE = 0.25 * PI / 180

        /**
         * A "Fix here" this soon after the previous one also straightens the turn from both corners
         * ([ReferenceAlignment.turned]); later, tracking may have drifted between the two marks.
         */
        const val TURN_WINDOW = 120.0

        /**
         * The straightened turn takes over the sites between the two corners: within half their
         * distance, plus this, of the middle (the room's first site, at the corners first marked).
         */
        const val TURN_REACH = 0.5

        private fun platesByNumber(plates: List<Manifest.Plate>, corrections: RoomCorrections): Map<Int, Manifest.Plate> {
            val byNumber = LinkedHashMap<Int, Manifest.Plate>()
            for (plate in plates) byNumber.putIfAbsent(plate.number, corrections.corrected(plate))
            return byNumber
        }
    }
}
