package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.intersectFloor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Alignment by reference points (docs/PLAN.md §3 "Alignment by reference points"): the user aims
// the phone down at 3 or more floor corners, in any order, never saying which. The app finds which
// model corners they are and fits yaw and translation; a room that fits more than one way (a
// rectangle turned 180°) is offered both ways to choose from. When nothing fits closely, the
// closest fit within a looser bound is offered as approximate: the user checks it by eye. Once
// aligned, `ReferenceAlignment.reanchor(transform, mark, seenFrom, rooms)` corrects drift from one
// corner of any room, and `ReferenceAlignment.turned(fix, previousCorner, previousMark)` the turn
// from two. Pure math, tested on the JVM; `:ar` supplies the marks (the crosshair on the detected
// floor) and `:features` the candidates (the manifest outline). On phones with a depth sensor the
// crosshair also takes walls (a plane: its direction gives the turn) and wall corners at any height
// (docs/PLAN.md §3 "LiDAR marks"), so two marks can be enough: a wall and a corner. Wall points
// (outlets, switches) are still matched here but the app doesn't mark them: they deviate 5–10 cm as
// built, and without depth a plain wall gives no reliable depth.

/** A point in the model the user can mark in the room. */
data class ReferencePoint(
    val id: String,
    val kind: Kind,
    /**
     * Model coordinates: corners on the floor, objects on their wall's surface at their centre's
     * height.
     */
    val position: Vec3,
    /** The element, for objects. */
    val elementID: String?,
    /** Height above the room's floor (0 for corners). */
    val height: Double,
    /**
     * For walls: horizontal, unit length, into the room. [position] is then the wall's middle on
     * the floor.
     */
    val normal: Vec3? = null,
    /** For walls: half the wall's length. */
    val halfLength: Double = 0.0,
) {
    @Serializable
    enum class Kind(val raw: String) {
        /** Where two walls meet the floor. */
        @SerialName("corner")
        CORNER("corner"),

        /** An outlet or switch, on its wall's surface. */
        @SerialName("object")
        OBJECT("object"),

        /** A wall's surface seen from inside the room (marked on phones with a depth sensor). */
        @SerialName("wall")
        WALL("wall"),
    }
}

/** An element that can serve as a reference: its id and its box in model coordinates. */
data class ReferenceObject(val id: String, val bounds: Bounds)

object ReferencePoints {
    /** Element kinds that make good reference points: small, fixed to walls, easy to aim at. */
    val OBJECT_KINDS: Set<String> = setOf("outlet", "data_outlet", "switch")

    /** An object further than this from every wall keeps its own centre. */
    internal const val WALL_SNAP_DISTANCE = 0.3

    /** Walls shorter than this are hard to aim at and say little about the turn. */
    internal const val SHORTEST_WALL = 0.4

    /**
     * The room's corners, its walls (with [walls]: phones with a depth sensor mark them) and its
     * objects' points. Objects go onto their nearest wall's surface (an outlet's box may sit in the
     * wall or stand out of it; the user aims at the cover). Without an outline there are no corners
     * or walls, and objects keep their centres.
     */
    fun make(outline: RoomOutline?, objects: List<ReferenceObject>, floorY: Double, walls: Boolean = false): List<ReferencePoint> {
        val floor = outline?.floorY ?: floorY
        val points = ArrayList<ReferencePoint>()
        for ((index, corner) in (outline?.corners ?: emptyList()).withIndex()) {
            points.add(ReferencePoint(id = "corner-$index", kind = ReferencePoint.Kind.CORNER, position = corner, elementID = null, height = 0.0))
        }
        if (walls && outline != null) {
            for ((index, wall) in outline.walls.withIndex()) {
                if (wall.length < SHORTEST_WALL) continue
                points.add(
                    ReferencePoint(
                        id = "wall-$index", kind = ReferencePoint.Kind.WALL, position = (wall.start + wall.end) / 2.0, elementID = null,
                        height = 0.0, normal = wall.inwardNormal, halfLength = wall.length / 2,
                    ),
                )
            }
        }
        for (obj in objects.sortedWith { a, b -> a.id.compareTo(b.id) }) {
            var position = obj.bounds.center
            val wall = outline?.nearestWall(to = position)
            if (wall != null && abs(wall.signedDistance) <= WALL_SNAP_DISTANCE) {
                position = wall.foot
            }
            points.add(
                ReferencePoint(id = obj.id, kind = ReferencePoint.Kind.OBJECT, position = position, elementID = obj.id, height = position.y - floor),
            )
        }
        return points
    }
}

/** A point the user marked, in AR world coordinates. */
data class ReferenceMark(
    val position: Vec3,
    val surface: Surface,
    /**
     * Where a floor mark was aimed from (the camera): the mark lies on the line of sight from
     * here, so it can follow the detected floor as ARCore refines it ([onFloor]).
     */
    val seenFrom: Vec3? = null,
    /** A wall plane's direction: horizontal, unit length, out of the wall towards the camera. */
    val normal: Vec3? = null,
) {
    @Serializable
    enum class Surface(val raw: String) {
        /** Marked on the floor: a corner. */
        @SerialName("floor")
        FLOOR("floor"),

        /** Marked on a wall: an outlet or switch. */
        @SerialName("wall")
        WALL("wall"),

        /**
         * A wall corner measured at any height by its two walls (depth): matched like a floor
         * corner, its position put on the floor.
         */
        @SerialName("edge")
        EDGE("edge"),

        /** A wall's surface (depth): a point on it and its [ReferenceMark.normal]. */
        @SerialName("wallPlane")
        WALL_PLANE("wallPlane"),
    }

    internal val kind: ReferencePoint.Kind
        get() = when (surface) {
            Surface.FLOOR, Surface.EDGE -> ReferencePoint.Kind.CORNER
            Surface.WALL -> ReferencePoint.Kind.OBJECT
            Surface.WALL_PLANE -> ReferencePoint.Kind.WALL
        }

    /**
     * The mark where its line of sight meets the floor at [height]: the floor as the AR session has
     * it now, which may have moved since the mark was made (6 cm in a device test, 2026-10-06; at
     * the crosshair's angles that moves a mark up to 4 cm sideways). Unchanged for wall marks,
     * marks without a line of sight, or a floor more than [MAXIMUM_FLOOR_CHANGE] away.
     */
    fun onFloor(at: Double): ReferenceMark {
        val height = at
        // A corner measured by its walls is only put on the floor: its line of sight isn't how it was found.
        if (surface == Surface.EDGE) {
            if (!(abs(height - position.y) <= MAXIMUM_FLOOR_CHANGE)) return this
            return copy(position = position.copy(y = height))
        }
        val seenFrom = this.seenFrom
        if (surface != Surface.FLOOR || seenFrom == null || !(abs(height - position.y) <= MAXIMUM_FLOOR_CHANGE)) return this
        val point = ManualAlignment.intersectFloor(origin = seenFrom, direction = position - seenFrom, height = height) ?: return this
        return copy(position = point)
    }

    companion object {
        /**
         * The floor can move this much between marks before a mark stops following it (a bigger
         * change is another plane, not the same floor refined).
         */
        const val MAXIMUM_FLOOR_CHANGE = 0.15
    }
}

/** The marks matched to model points, and the transform they give. */
data class ReferenceFit(
    val transform: YawTransform,
    val matches: List<Match>,
    /** Root mean square of the residuals (metres). */
    val rms: Double,
    val dropped: Dropped?,
    /**
     * The middle of the matched marks, AR world, when walls are among them (a wall's reference is
     * its middle, which can be far from where it was marked).
     */
    internal val markCentre: Vec3? = null,
) {
    data class Match(
        val markIndex: Int,
        val reference: ReferencePoint,
        /** Distance between the mark and where the fit puts its reference (metres). */
        val residual: Double,
    )

    /** A mark left out because it doesn't fit: an outlet away from where the project has it. */
    data class Dropped(
        val markIndex: Int,
        /** The model point of the mark's kind nearest to where the fit puts the mark. */
        val nearest: ReferencePoint?,
        /** How far from it (metres). */
        val distance: Double,
    )

    /** What decides between fits: the RMS, plus a penalty for a dropped mark. */
    internal val score: Double get() = rms + (if (dropped == null) 0.0 else ReferenceAlignment.DROP_PENALTY)

    /** The middle of the matched points, AR world: where the fit was measured. */
    val centre: Vec3
        get() {
            if (markCentre != null) return markCentre
            if (matches.isEmpty()) return transform.translation
            return transform.apply(matches.fold(Vec3.zero) { acc, m -> acc + m.reference.position } / matches.size.toDouble())
        }

    val quality: Quality
        get() {
            if (rms <= ReferenceAlignment.GOOD_RMS) return Quality.GOOD
            return if (rms <= ReferenceAlignment.Tolerances.strict.maximumRMS) Quality.FAIR else Quality.APPROXIMATE
        }

    enum class Quality {
        /** Within 3 cm. */
        GOOD,

        /** Within 6 cm: usable, with a warning. */
        FAIR,

        /**
         * Only the looser bound fitted (the model or the room differ, or marks are off): shown
         * so the user can judge it, with a clear warning.
         */
        APPROXIMATE,
    }
}

object ReferenceAlignment {
    /** Marks needed before matching (2 would leave nothing to check the fit against). */
    const val MINIMUM_MARKS = 3

    /** With a wall among them, 2 marks can place the room: its direction gives the turn. */
    const val MINIMUM_MARKS_WITH_WALL = 2

    /** A fit within this RMS is good. */
    const val GOOD_RMS = 0.03

    /** How closely marks must agree with the model. */
    data class Tolerances(
        /** Two marks' distance must match their candidates' distance within this. */
        val pair: Double,
        /** A wall mark's height above the floor must match its candidate's within this. */
        val height: Double,
        /** Fits beyond this RMS are rejected. */
        val maximumRMS: Double,
        /**
         * Two walls' marked directions must make the model's angle within this, and a fit must
         * turn each wall's direction onto its mark's within it.
         */
        val wallAngle: Double = 3 * PI / 180,
    ) {
        companion object {
            /** The first pass. */
            val strict = Tolerances(pair = 0.15, height = 0.10, maximumRMS = 0.06)

            /**
             * When nothing fits strictly: the closest fit, shown as approximate. Beyond this the
             * marks are on something else (or in another room).
             */
            val relaxed = Tolerances(pair = 0.40, height = 0.25, maximumRMS = 0.20, wallAngle = 6 * PI / 180)
        }
    }

    /**
     * A wall mark may be this far beyond its wall's ends (the aim's error, and doorways cut out of
     * the outline).
     */
    internal const val WALL_OVERHANG = 0.3

    /**
     * Fits that put the camera more than this outside the room are rejected (standing in a
     * doorway is 10–20 cm out).
     */
    internal const val EYE_OUTSIDE = 0.3

    /** Walls whose directions are within this of parallel only constrain one direction. */
    internal const val PARALLEL_WALLS = 15 * PI / 180

    /** Marks must spread at least this far apart horizontally. */
    const val MINIMUM_SPREAD = 1.0

    /** Marks all on one line need at least this much spread (they tell little about the turn). */
    internal const val COLLINEAR_SPREAD = 2.5
    internal const val COLLINEAR_TOLERANCE = 0.2

    /** The score cost of leaving a mark out, so a fit that uses every mark wins when it's good. */
    internal const val DROP_PENALTY = 0.02

    /** The runner-up must be this much worse (and twice the score) for the best fit to stand. */
    internal const val AMBIGUITY_MARGIN = 0.04

    /** Fits closer than this are the same alignment found through different matches. */
    internal const val SAME_POSITION = 0.05
    internal const val SAME_YAW = 2 * PI / 180

    /** Assignments tried before giving up (pathological rooms with many identical outlets). */
    internal const val SEARCH_LIMIT = 200_000

    enum class Hint {
        /** Mark another point (anything). */
        MORE_POINTS,

        /** The marks are all on one line: mark a point on another wall. */
        ANOTHER_WALL,

        /** The marks are too close together: mark points further apart. */
        SPREAD_OUT,
    }

    sealed interface Outcome {
        data class NeedsMore(val hint: Hint) : Outcome

        data class Matched(val fit: ReferenceFit) : Outcome

        /**
         * Different fits about as good, best first, that no further corner tells apart (or still
         * after an extra mark): let the user choose.
         */
        data class Ambiguous(val fits: List<ReferenceFit>) : Outcome

        data class NoMatch(val reason: ReferenceAlignment.NoMatch) : Outcome
    }

    enum class NoMatch {
        /**
         * Corners were marked, but the room has none in the model (no outline: the file has no
         * room geometry, or the version was processed before outlines existed).
         */
        NO_CORNERS,

        /** Outlets or switches were marked, but none of the room's are loaded or modelled. */
        NO_OBJECTS,

        /**
         * Not even the looser bound fits: marks on something that isn't in the model, or the
         * wrong room.
         */
        TOO_FAR,
    }

    /**
     * Matches the marks to the room's reference points and fits the alignment.
     * - [worldFloorY]: the floor's height in the AR world, when the AR session has found it; it
     *   lets wall marks be matched by height (switches at 1.10 m aren't outlets at 0.30 m), and
     *   places the building's height from walls alone.
     * - [outline]: the room's outline, when known: fits that put a mark's camera well outside the
     *   room are left out.
     */
    fun solve(marks: List<ReferenceMark>, references: List<ReferencePoint>, worldFloorY: Double?, outline: RoomOutline? = null): Outcome {
        val walls = marks.filter { it.surface == ReferenceMark.Surface.WALL_PLANE }
        val needed = if (walls.isEmpty()) MINIMUM_MARKS else MINIMUM_MARKS_WITH_WALL
        if (marks.size < needed) return Outcome.NeedsMore(Hint.MORE_POINTS)
        if (walls.isEmpty()) {
            val spread = horizontalSpread(marks.map { it.position })
            if (!(spread >= MINIMUM_SPREAD)) return Outcome.NeedsMore(Hint.SPREAD_OUT)
            if (isCollinear(marks.map { it.position }) && spread < COLLINEAR_SPREAD) {
                return Outcome.NeedsMore(Hint.ANOTHER_WALL)
            }
        } else if (walls.size == marks.size && !hasCrossingWalls(walls.mapNotNull { it.normal })) {
            // Parallel walls only: nothing says where along them the room is.
            return Outcome.NeedsMore(Hint.ANOTHER_WALL)
        }

        if (marks.any { it.kind == ReferencePoint.Kind.CORNER || it.kind == ReferencePoint.Kind.WALL } &&
            references.none { it.kind == ReferencePoint.Kind.CORNER }
        ) {
            return Outcome.NoMatch(NoMatch.NO_CORNERS)
        }
        if (marks.any { it.surface == ReferenceMark.Surface.WALL } && references.none { it.kind == ReferencePoint.Kind.OBJECT }) {
            return Outcome.NoMatch(NoMatch.NO_OBJECTS)
        }
        var fits: List<ReferenceFit> = emptyList()
        var used = Tolerances.strict
        for (tolerances in listOf(Tolerances.strict, Tolerances.relaxed)) {
            if (fits.isNotEmpty()) continue
            val candidates = candidates(marks, references, worldFloorY, tolerances)
            val found = assignments(marks, candidates, tolerances, needed = needed, worldFloorY = worldFloorY)
            fits = distinct(found.filter { fit -> outline?.let { seesFromInside(fit, marks, it) } ?: true })
            used = tolerances
        }
        val best = fits.firstOrNull() ?: return Outcome.NoMatch(NoMatch.TOO_FAR)
        // A rival is a different pose that also accounts for every mark, about as good. One that
        // leaves out the mark telling the poses apart (the switch, in a rectangle of corners) isn't.
        val rivals = fits.drop(1).filter { fit ->
            val clearlyWorse = fit.score >= 2 * best.score && fit.score - best.score >= AMBIGUITY_MARGIN
            !clearlyWorse && explainsEveryMark(fit, within = used.pair)
        }
        if (rivals.isEmpty()) return Outcome.Matched(best)
        // The room's own symmetry (a rectangle turned 180°, a square 90°): another corner can't
        // tell the fits apart, so the user chooses now. Otherwise one more mark may.
        val corners = references.filter { it.kind == ReferencePoint.Kind.CORNER }
        val symmetric = marks.all { it.kind != ReferencePoint.Kind.OBJECT } &&
            rivals.all { isSymmetric(best, it, corners, within = used.pair) }
        if (marks.size == needed && !symmetric) {
            return Outcome.NeedsMore(Hint.MORE_POINTS)
        }
        return Outcome.Ambiguous((listOf(best) + rivals).take(MAXIMUM_CHOICES))
    }

    /** At most this many fits to choose from (a square fits 4 ways). */
    internal const val MAXIMUM_CHOICES = 4

    /**
     * Whether two fits put the room's corners in the same places, only swapped: the room's own
     * symmetry, which no further corner resolves.
     */
    internal fun isSymmetric(a: ReferenceFit, b: ReferenceFit, corners: List<ReferencePoint>, within: Double): Boolean {
        if (corners.isEmpty()) return false
        val placed = corners.map { b.transform.apply(it.position) }
        return corners.all { corner ->
            val point = a.transform.apply(corner.position)
            placed.any { horizontalDistance(it, point) <= within }
        }
    }

    // Fix here

    /** A "Fix here" that found its corner. */
    data class CornerFix(
        /** The alignment moved so the corner lands on the mark. */
        val transform: YawTransform,
        /** The corner, model coordinates. */
        val corner: Vec3,
        /** Which of the rooms given it's a corner of. */
        val room: Int,
        /** The mark, AR world coordinates. */
        val mark: Vec3,
        /** That room's id, when the rooms came with theirs ([RoomCorrections.reanchor]). */
        val spaceID: String? = null,
        /**
         * A corner, or a wall (depth): then [corner] is the mark's foot on the model's wall, and
         * the fix turned the building onto the wall's direction.
         */
        val kind: Kind = Kind.CORNER,
    ) {
        enum class Kind {
            CORNER,
            WALL,
        }
    }

    /**
     * A "Fix here" mark further than this from every corner (where the drifted alignment puts
     * them) isn't on a corner. It was 50 cm before the line of sight told the corners on either
     * side of a wall apart.
     */
    const val REANCHOR_REACH = 0.75

    /**
     * How far behind a corner's walls the camera may seem and still face it: the alignment's turn
     * error over the camera's distance from the mark (1–2° over up to a metre). From the next room
     * the camera is behind them by its own distance from the wall, typically 20–60 cm.
     */
    internal const val FACING_TOLERANCE = 0.05

    /** Corners closer than this are one spot: two open-plan rooms meeting without a wall. */
    internal const val SAME_CORNER = 0.03

    /** Corners of two rooms closer than this are on either side of one wall. */
    internal const val ACROSS_WALL = 0.6

    /**
     * Of two corners across a wall, the camera must face one this much better (it's as far on
     * one's side as behind the other's: twice its distance from the wall, less the aim's error).
     */
    internal const val CLEARLY_FACING = 0.02

    /** Rooms whose floor is further than this from the mark's model height are on another storey. */
    internal const val REANCHOR_FLOOR_TOLERANCE = 0.5

    private data class CornerCandidate(val corner: Vec3, val room: Int, val distance: Double, val margin: Double)

    /**
     * "Fix here" (docs/PLAN.md §3): one corner marked near the user re-anchors a drifted
     * alignment there. The model corner nearest the mark under [transform] is taken as the one
     * marked; the rotation stays and the building moves horizontally so that corner lands on the
     * mark.
     *
     * Room outlines are the walls' inside faces, so most corners have another room's corner a
     * wall's thickness away, behind the wall. Drift moves the mark and the camera together, so
     * the line of sight `seenFrom − mark` tells them apart however far the alignment drifted: put
     * on the corner, it must start in that corner's room ([RoomOutline.Corner.isFacing]).
     * Without it, the nearest corner had to be the only one near, which failed once drift passed
     * half a wall's thickness: everywhere but the room aligned in. Of two corners across a wall,
     * the one the camera clearly faces better is taken: the other's margin is the same, negated.
     *
     * `null` when no corner faces the camera within [REANCHOR_REACH], the next different one is
     * less than twice as far (the mark doesn't say which corner it is), or the camera is right
     * over the wall between two rooms' corners (neither side faces it clearly).
     */
    fun reanchor(transform: YawTransform, mark: Vec3, seenFrom: Vec3?, rooms: List<RoomOutline>): CornerFix? {
        val eye = seenFrom
        val markInModel = transform.inverseApply(mark)
        // The line of sight in model terms: unlike the mark, it doesn't drift.
        val sight = eye?.let { transform.inverseRotate(Vec3(it.x - mark.x, 0.0, it.z - mark.z)) }
        var candidates = ArrayList<CornerCandidate>()
        for ((room, outline) in rooms.withIndex()) {
            if (!(abs(markInModel.y - outline.floorY) <= REANCHOR_FLOOR_TOLERANCE)) continue
            for (index in outline.corners.indices) {
                val corner = outline.corner(at = index) ?: continue
                val distance = horizontalDistance(corner.position, markInModel)
                val margin = sight?.let { corner.facingMargin(corner.position + it) } ?: 0.0
                if (!(distance <= REANCHOR_REACH && margin >= -FACING_TOLERANCE)) continue
                candidates.add(CornerCandidate(corner.position, room, distance, margin))
            }
        }
        fun across(a: CornerCandidate, b: CornerCandidate): Boolean {
            if (a.room == b.room) return false
            val apart = horizontalDistance(a.corner, b.corner)
            return apart > SAME_CORNER && apart <= ACROSS_WALL
        }
        if (sight != null) {
            val all = candidates
            candidates = ArrayList(
                all.filter { candidate -> all.none { across(it, candidate) && it.margin >= candidate.margin + CLEARLY_FACING } },
            )
        }
        candidates.sortBy { it.distance }
        val first = candidates.firstOrNull() ?: return null
        val others = candidates.drop(1).filter { horizontalDistance(it.corner, first.corner) > SAME_CORNER }
        val runnerUp = others.firstOrNull()
        if (runnerUp != null && runnerUp.distance < 2 * first.distance) {
            return null
        }
        if (sight != null && others.any { across(it, first) }) {
            return null
        }
        val delta = (mark - transform.apply(first.corner)).copy(y = 0.0)
        return CornerFix(transform = transform.translated(by = delta), corner = first.corner, room = first.room, mark = mark)
    }

    /**
     * A wall mark further than this from every wall (where the drifted alignment puts them)
     * isn't on one.
     */
    const val WALL_REACH = 0.3

    /** The marked wall's direction must be within this of the model wall's under the alignment. */
    internal const val WALL_FACING = 10 * PI / 180

    /** A wall fix turns the building by at most this; more is a wrong wall. */
    internal const val MAXIMUM_WALL_TURN = 5 * PI / 180

    private data class WallCandidate(val room: Int, val wall: RoomOutline.Wall, val foot: Vec3, val distance: Double)

    /**
     * "Fix here" against a wall (phones with a depth sensor, docs/PLAN.md §3 "LiDAR marks"): the
     * wall's direction turns the building about the mark, then the building moves across the wall
     * so the wall passes through the mark; where along the wall stays. One wall gives the turn
     * precisely, which two corners only give 2.5–6 m apart.
     *
     * The model wall is the nearest one under [transform] facing the same way within 10°, seen
     * from its room's side (the next room's face of the same wall faces the other way). `null`
     * when none is within [WALL_REACH], another is less than twice as far, or the turn would be
     * over 5°.
     */
    fun reanchor(transform: YawTransform, wall: Vec3, normal: Vec3, seenFrom: Vec3?, rooms: List<RoomOutline>): CornerFix? {
        val mark = wall
        val eye = seenFrom
        val marked = Vector.normalized(flat(normal))
        val markInModel = transform.inverseApply(mark)
        val normalInModel = transform.inverseRotate(marked)
        val eyeInModel = eye?.let { transform.inverseApply(it) }
        val candidates = ArrayList<WallCandidate>()
        for ((room, outline) in rooms.withIndex()) {
            if ((markInModel.y - outline.floorY) !in -REANCHOR_FLOOR_TOLERANCE..3.0) continue
            for (modelWall in outline.walls) {
                if (modelWall.length < ReferencePoints.SHORTEST_WALL) continue
                if (!(angleBetween(modelWall.inwardNormal, normalInModel) <= WALL_FACING)) continue
                val direction = flat(modelWall.end - modelWall.start) / modelWall.length
                val offset = flat(markInModel - modelWall.start)
                val along = Vector.dot(offset, direction)
                val across = Vector.dot(offset, modelWall.inwardNormal)
                if (!(along >= -WALL_OVERHANG && along <= modelWall.length + WALL_OVERHANG && abs(across) <= WALL_REACH)) continue
                if (eyeInModel != null && Vector.dot(flat(eyeInModel - modelWall.start), modelWall.inwardNormal) <= 0) continue
                val foot = Vec3(modelWall.start.x + direction.x * along, markInModel.y, modelWall.start.z + direction.z * along)
                candidates.add(WallCandidate(room, modelWall, foot, abs(across)))
            }
        }
        candidates.sortBy { it.distance }
        val first = candidates.firstOrNull() ?: return null
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null && runnerUp.distance < 2 * max(first.distance, 0.01)) {
            return null
        }
        val turn = signedAngle(from = YawTransform.rotate(first.wall.inwardNormal, by = transform.yaw), to = marked)
        if (!(abs(turn) <= MAXIMUM_WALL_TURN)) return null
        val turned = transform.rotated(by = turn, about = mark)
        val gap = Vector.dot(flat(mark - turned.apply(first.wall.start)), marked)
        return CornerFix(
            transform = turned.translated(by = marked * gap), corner = first.foot, room = first.room, mark = mark,
            kind = CornerFix.Kind.WALL,
        )
    }

    /**
     * Two fixes this far apart (model metres) give the turn; closer, a centimetre on either
     * corner is too much of an angle (1.2° at 1 m), and further they're rarely in one room.
     */
    val TURN_BASELINE = 2.5..6.0

    /** The marks' distance must match the corners' within this, or one mark is off. */
    internal const val TURN_LENGTH_TOLERANCE = 0.04

    /** A turn bigger than this from two fixes is a wrong mark, not the alignment's error. */
    internal const val MAXIMUM_TURN_CORRECTION = 3 * PI / 180

    /**
     * "Fix here" at a second corner right after the first (docs/PLAN.md §3): the two corners give
     * the turn as well. The yaw is fitted to both; then the building moves so the new corner lands
     * exactly on its mark, as a fix does, keeping its height. [previousMark] must be in today's
     * world (moved with its anchor). `null` when the corners are closer or further apart than
     * [TURN_BASELINE], the marks disagree with the model's distance, or the turn would change by
     * more than 3°.
     */
    fun turned(fix: CornerFix, previousCorner: Vec3, previousMark: Vec3): YawTransform? {
        val modelLength = horizontalDistance(fix.corner, previousCorner)
        if (modelLength !in TURN_BASELINE) return null
        if (!(abs(horizontalDistance(fix.mark, previousMark) - modelLength) <= TURN_LENGTH_TOLERANCE)) return null
        val fitted = fit(model = listOf(previousCorner, fix.corner), world = listOf(previousMark, fix.mark)) ?: return null
        if (!(fitted.yawDifference(to = fix.transform) <= MAXIMUM_TURN_CORRECTION)) return null
        val turned = YawTransform(yaw = fitted.yaw, translation = fix.transform.translation)
        val delta = (fix.mark - turned.apply(fix.corner)).copy(y = 0.0)
        return turned.translated(by = delta)
    }

    /**
     * Every mark is near a model point of its kind under the fit: the matched ones, and the
     * left-out one (if any) near some other point.
     */
    internal fun explainsEveryMark(fit: ReferenceFit, within: Double): Boolean =
        fit.matches.all { it.residual <= within } && (fit.dropped?.let { it.distance <= within } ?: true)

    /**
     * The least-squares yaw and translation taking [model] points onto [world] points.
     * `null` when the points don't constrain the turn (all at one spot).
     */
    fun fit(model: List<Vec3>, world: List<Vec3>): YawTransform? {
        if (model.size != world.size || model.size < 2) return null
        val count = model.size.toDouble()
        val modelCentre = model.fold(Vec3.zero) { acc, p -> acc + p } / count
        val worldCentre = world.fold(Vec3.zero) { acc, p -> acc + p } / count
        var sine = 0.0
        var cosine = 0.0
        for ((m, w) in model.zip(world)) {
            val a = m - modelCentre
            val b = w - worldCentre
            // Maximises Σ b · RotY(θ) a, with RotY as in [YawTransform.rotate].
            sine += b.x * a.z - b.z * a.x
            cosine += b.x * a.x + b.z * a.z
        }
        if (!(abs(sine) > 1e-12 || abs(cosine) > 1e-12)) return null
        val yaw = atan2(sine, cosine)
        return YawTransform(yaw = yaw, translation = worldCentre - YawTransform.rotate(modelCentre, by = yaw))
    }

    // Matching

    /** For each mark, the references of its kind; wall marks also by height above the floor. */
    internal fun candidates(
        marks: List<ReferenceMark>,
        references: List<ReferencePoint>,
        worldFloorY: Double?,
        tolerances: Tolerances,
    ): List<List<ReferencePoint>> = marks.map { mark ->
        references.filter { reference ->
            if (reference.kind != mark.kind) {
                false
            } else if (mark.surface != ReferenceMark.Surface.WALL || worldFloorY == null) {
                true
            } else {
                abs((mark.position.y - worldFloorY) - reference.height) <= tolerances.height
            }
        }
    }

    /**
     * Every consistent assignment of marks to distinct candidates (one mark may be left out
     * when there are more than [needed]), fitted, within the tolerances' RMS, best score first.
     */
    internal fun assignments(
        marks: List<ReferenceMark>,
        candidates: List<List<ReferencePoint>>,
        tolerances: Tolerances,
        needed: Int = MINIMUM_MARKS,
        worldFloorY: Double? = null,
    ): List<ReferenceFit> {
        val skipsAllowed = if (marks.size > needed) 1 else 0
        val fits = ArrayList<ReferenceFit>()
        val chosen = ArrayList<ReferencePoint?>()
        val used = HashSet<String>()
        var tried = 0

        fun consistent(reference: ReferencePoint, index: Int): Boolean {
            for ((other, previous) in chosen.withIndex()) {
                if (previous == null) continue
                if (!pairFits(marks[index], reference, marks[other], previous, tolerances)) return false
            }
            return true
        }

        fun search(index: Int, skips: Int) {
            if (tried >= SEARCH_LIMIT) return
            if (index == marks.size) {
                tried += 1
                val fit = evaluate(marks, chosen, candidates, needed = needed, tolerances = tolerances, worldFloorY = worldFloorY)
                if (fit != null && fit.rms <= tolerances.maximumRMS) {
                    fits.add(fit)
                }
                return
            }
            for (reference in candidates[index]) {
                if (used.contains(reference.id) || !consistent(reference, index)) continue
                chosen.add(reference)
                used.add(reference.id)
                search(index + 1, skips)
                used.remove(reference.id)
                chosen.removeAt(chosen.size - 1)
            }
            if (skips < skipsAllowed) {
                chosen.add(null)
                search(index + 1, skips + 1)
                chosen.removeAt(chosen.size - 1)
            }
        }

        search(0, 0)
        return fits.sortedBy { it.score }
    }

    /**
     * The same matches fitted again to marks that moved (they followed the floor as the AR session
     * refined it): the transform, residuals and RMS change, which corner is which doesn't. The fit
     * unchanged when a match's mark is missing.
     */
    fun refit(fit: ReferenceFit, marks: List<ReferenceMark>, worldFloorY: Double? = null): ReferenceFit {
        if (!fit.matches.all { it.markIndex in marks.indices }) return fit
        val transform = mixedFit(fit.matches.map { marks[it.markIndex] to it.reference }, worldFloorY) ?: return fit
        val matches = fit.matches.map { match -> match.copy(residual = residual(marks[match.markIndex], match.reference, under = transform)) }
        val rms = sqrt(matches.map { it.residual * it.residual }.fold(0.0) { acc, v -> acc + v } / matches.size.toDouble())
        var dropped = fit.dropped
        val nearest = dropped?.nearest
        if (dropped != null && dropped.markIndex in marks.indices && nearest != null) {
            dropped = dropped.copy(distance = Vector.distance(nearest.position, transform.inverseApply(marks[dropped.markIndex].position)))
        }
        return fit.copy(transform = transform, matches = matches, rms = rms, dropped = dropped)
    }

    private fun evaluate(
        marks: List<ReferenceMark>,
        chosen: List<ReferencePoint?>,
        candidates: List<List<ReferencePoint>>,
        needed: Int = MINIMUM_MARKS,
        tolerances: Tolerances = Tolerances.strict,
        worldFloorY: Double? = null,
    ): ReferenceFit? {
        val pairs = chosen.withIndex().mapNotNull { (index, reference) -> reference?.let { index to it } }
        if (pairs.size < needed) return null
        val transform = mixedFit(pairs.map { marks[it.first] to it.second }, worldFloorY) ?: return null
        // Each wall turned onto its mark's direction, and the mark on the wall, not past its ends.
        for ((index, reference) in pairs) {
            if (reference.kind != ReferencePoint.Kind.WALL) continue
            val normal = reference.normal ?: return null
            val marked = marks[index].normal ?: return null
            if (!(angleBetween(YawTransform.rotate(normal, by = transform.yaw), marked) <= tolerances.wallAngle)) return null
            val along = abs(Vector.dot(transform.inverseApply(marks[index].position) - reference.position, Vec3(-normal.z, 0.0, normal.x)))
            if (along > reference.halfLength + WALL_OVERHANG) return null
        }
        val matches = pairs.map { (index, reference) ->
            ReferenceFit.Match(markIndex = index, reference = reference, residual = residual(marks[index], reference, under = transform))
        }
        val rms = sqrt(matches.map { it.residual * it.residual }.fold(0.0) { acc, v -> acc + v } / matches.size.toDouble())
        var dropped: ReferenceFit.Dropped? = null
        val skipped = chosen.indexOfFirst { it == null }
        if (skipped >= 0) {
            val modelPoint = transform.inverseApply(marks[skipped].position)
            val nearest = candidates[skipped].minByOrNull { Vector.distance(it.position, modelPoint) }
            dropped = ReferenceFit.Dropped(
                markIndex = skipped, nearest = nearest,
                distance = nearest?.let { Vector.distance(it.position, modelPoint) } ?: Double.POSITIVE_INFINITY,
            )
        }
        val hasWalls = pairs.any { it.second.kind == ReferencePoint.Kind.WALL }
        val markCentre = if (hasWalls) pairs.fold(Vec3.zero) { acc, p -> acc + marks[p.first].position } / pairs.size.toDouble() else null
        return ReferenceFit(transform = transform, matches = matches, rms = rms, dropped = dropped, markCentre = markCentre)
    }

    /**
     * Whether two marks agree with two references the way the model has them: points by their
     * distance, a point and a wall by the point's distance from the wall, two walls by the
     * angle between them (and, near parallel, the distance between them).
     */
    internal fun pairFits(
        markA: ReferenceMark,
        referenceA: ReferencePoint,
        markB: ReferenceMark,
        referenceB: ReferencePoint,
        tolerances: Tolerances,
    ): Boolean {
        val aIsWall = referenceA.kind == ReferencePoint.Kind.WALL
        val bIsWall = referenceB.kind == ReferencePoint.Kind.WALL
        if (!aIsWall && !bIsWall) {
            val measured = Vector.distance(markA.position, markB.position)
            val modelled = Vector.distance(referenceA.position, referenceB.position)
            return abs(measured - modelled) <= tolerances.pair
        }
        if (aIsWall != bIsWall) {
            val wallMark = if (aIsWall) markA else markB
            val wall = if (aIsWall) referenceA else referenceB
            val pointMark = if (aIsWall) markB else markA
            val point = if (aIsWall) referenceB else referenceA
            val marked = wallMark.normal ?: return false
            val normal = wall.normal ?: return false
            val measured = Vector.dot(flat(pointMark.position - wallMark.position), marked)
            val modelled = Vector.dot(flat(point.position - wall.position), normal)
            return abs(measured - modelled) <= tolerances.pair
        }
        val markedA = markA.normal ?: return false
        val markedB = markB.normal ?: return false
        val normalA = referenceA.normal ?: return false
        val normalB = referenceB.normal ?: return false
        val measured = signedAngle(from = markedA, to = markedB)
        val modelled = signedAngle(from = normalA, to = normalB)
        if (!(abs(YawTransform.normalized(measured - modelled)) <= tolerances.wallAngle)) return false
        if (abs(sin(modelled)) < sin(PARALLEL_WALLS)) {
            val apart = Vector.dot(flat(markB.position - markA.position), markedA)
            val modelledApart = Vector.dot(flat(referenceB.position - referenceA.position), normalA)
            return abs(apart - modelledApart) <= tolerances.pair
        }
        return true
    }

    /**
     * The yaw and translation that put the references on their marks: the turn from walls'
     * directions and from points (a weighted mean of the two), then the move by least squares (a
     * point fixes both horizontal directions, a wall only the one across it). The height comes
     * from points, else from the detected floor. `null` when the marks don't fix it.
     */
    internal fun mixedFit(pairs: List<Pair<ReferenceMark, ReferencePoint>>, worldFloorY: Double?): YawTransform? {
        val points = pairs.filter { it.second.kind != ReferencePoint.Kind.WALL }
        val walls = pairs.filter { it.second.kind == ReferencePoint.Kind.WALL }
        if (walls.isEmpty()) {
            return fit(model = points.map { it.second.position }, world = points.map { it.first.position })
        }
        // Turn: each wall's direction onto its mark's (weight 1), and the points' own turn (weight
        // one per point beyond the first).
        var sine = 0.0
        var cosine = 0.0
        for ((mark, reference) in walls) {
            val normal = reference.normal ?: return null
            val marked = mark.normal ?: return null
            val angle = signedAngle(from = normal, to = marked)
            sine += sin(angle)
            cosine += cos(angle)
        }
        if (points.size >= 2) {
            val pointFit = fit(model = points.map { it.second.position }, world = points.map { it.first.position })
            if (pointFit != null) {
                val weight = (points.size - 1).toDouble()
                sine += weight * sin(pointFit.yaw)
                cosine += weight * cos(pointFit.yaw)
            }
        }
        val yaw = atan2(sine, cosine)
        // Move: rows (1, 0) and (0, 1) per point, the mark's normal per wall.
        var xx = 0.0
        var xz = 0.0
        var zz = 0.0
        var bx = 0.0
        var bz = 0.0
        fun row(a: Vec3, b: Double) {
            xx += a.x * a.x
            xz += a.x * a.z
            zz += a.z * a.z
            bx += a.x * b
            bz += a.z * b
        }
        for ((mark, reference) in points) {
            val gap = mark.position - YawTransform.rotate(reference.position, by = yaw)
            row(Vec3(1.0, 0.0, 0.0), gap.x)
            row(Vec3(0.0, 0.0, 1.0), gap.z)
        }
        for ((mark, reference) in walls) {
            val marked = mark.normal ?: return null
            row(marked, Vector.dot(flat(mark.position - YawTransform.rotate(reference.position, by = yaw)), marked))
        }
        val determinant = xx * zz - xz * xz
        if (!(abs(determinant) > 1e-6)) return null
        val tx = (bx * zz - bz * xz) / determinant
        val tz = (bz * xx - bx * xz) / determinant
        val ty = when {
            points.isNotEmpty() -> points.map { it.first.position.y - it.second.position.y }.fold(0.0) { acc, v -> acc + v } / points.size.toDouble()
            worldFloorY != null -> worldFloorY - walls[0].second.position.y
            else -> 0.0
        }
        return YawTransform(yaw = yaw, translation = Vec3(tx, ty, tz))
    }

    /**
     * How far a mark is from where [under] puts its reference: the distance for points, the
     * distance from the wall's plane for walls.
     */
    internal fun residual(mark: ReferenceMark, reference: ReferencePoint, under: YawTransform): Double {
        val transform = under
        val normal = reference.normal
        if (reference.kind != ReferencePoint.Kind.WALL || normal == null) {
            return Vector.distance(transform.apply(reference.position), mark.position)
        }
        val worldNormal = YawTransform.rotate(normal, by = transform.yaw)
        return abs(Vector.dot(flat(mark.position - transform.apply(reference.position)), worldNormal))
    }

    /** Whether every mark's camera is inside the room under the fit (or just outside: a doorway). */
    internal fun seesFromInside(fit: ReferenceFit, marks: List<ReferenceMark>, outline: RoomOutline): Boolean = marks.all { mark ->
        val eye = mark.seenFrom
        if (eye == null) {
            true
        } else {
            val inModel = fit.transform.inverseApply(eye)
            outline.contains(inModel) || (outline.nearestWall(to = inModel)?.let { -it.signedDistance } ?: Double.POSITIVE_INFINITY) <= EYE_OUTSIDE
        }
    }

    /** Whether some two of the walls cross (aren't near parallel), so together they fix both directions. */
    internal fun hasCrossingWalls(normals: List<Vec3>): Boolean {
        for (i in normals.indices) {
            for (j in normals.indices) {
                if (j > i && abs(sin(signedAngle(from = normals[i], to = normals[j]))) >= sin(PARALLEL_WALLS)) return true
            }
        }
        return false
    }

    /** The turn about +Y taking [from]'s direction onto [to]'s, seen from above (−π…π]. */
    internal fun signedAngle(from: Vec3, to: Vec3): Double =
        YawTransform.normalized(PlateAlignment.angle(flat(to)) - PlateAlignment.angle(flat(from)))

    internal fun angleBetween(a: Vec3, b: Vec3): Double = abs(signedAngle(from = a, to = b))

    internal fun flat(v: Vec3): Vec3 = Vec3(v.x, 0.0, v.z)

    /**
     * The best fit for each different alignment, best score first. Fits are the same alignment
     * when they agree on every mark both use (one only leaves a mark out), or when different
     * matches put the model in the same place (a double outlet modelled as two elements).
     */
    internal fun distinct(fits: List<ReferenceFit>): List<ReferenceFit> {
        val kept = ArrayList<ReferenceFit>()
        for (fit in fits) {
            if (kept.any { agree(it, fit) || same(it.transform, fit.transform, near = fit.matches) }) continue
            kept.add(fit)
        }
        return kept
    }

    private fun agree(a: ReferenceFit, b: ReferenceFit): Boolean {
        val references = HashMap<Int, String>()
        for (match in a.matches) references.putIfAbsent(match.markIndex, match.reference.id)
        return b.matches.all { match -> references[match.markIndex]?.let { it == match.reference.id } ?: true }
    }

    /** Whether two transforms put the matched model points in the same place. */
    private fun same(a: YawTransform, b: YawTransform, near: List<ReferenceFit.Match>): Boolean {
        if (!(a.yawDifference(to = b) <= SAME_YAW)) return false
        return near.all { Vector.distance(a.apply(it.reference.position), b.apply(it.reference.position)) <= SAME_POSITION }
    }

    // Geometry of the marks

    /** The largest horizontal distance between two points. */
    internal fun horizontalSpread(points: List<Vec3>): Double {
        var widest = 0.0
        for (i in points.indices) {
            for (j in points.indices) {
                if (j > i) widest = max(widest, horizontalDistance(points[i], points[j]))
            }
        }
        return widest
    }

    /**
     * Whether every point is within [COLLINEAR_TOLERANCE] of the line through the two
     * furthest apart (seen from above).
     */
    internal fun isCollinear(points: List<Vec3>): Boolean {
        var ends: Pair<Vec3, Vec3>? = null
        var widest = 0.0
        for (i in points.indices) {
            for (j in points.indices) {
                if (j <= i) continue
                val distance = horizontalDistance(points[i], points[j])
                if (distance > widest) {
                    widest = distance
                    ends = points[i] to points[j]
                }
            }
        }
        if (ends == null || !(widest > 1e-6)) return true
        val (a, b) = ends
        val direction = Vec3(b.x - a.x, 0.0, b.z - a.z) / widest
        return points.all { point ->
            val offset = Vec3(point.x - a.x, 0.0, point.z - a.z)
            Vector.length(offset - direction * Vector.dot(offset, direction)) <= COLLINEAR_TOLERANCE
        }
    }

    fun horizontalDistance(a: Vec3, b: Vec3): Double = Vector.length(Vec3(a.x - b.x, 0.0, a.z - b.z))
}

/** The spread of repeated marks of one spot (the device test, docs/spikes/reference-points.md). */
data class MarkSpread(
    val count: Int,
    val mean: Vec3,
    /** The furthest mark from the mean (metres). */
    val maximum: Double,
    /** Root mean square distance from the mean (metres). */
    val rms: Double,
) {
    companion object {
        /** iOS `init?(_ points:)`: `null` for no points. */
        fun of(points: List<Vec3>): MarkSpread? {
            if (points.isEmpty()) return null
            val mean = points.fold(Vec3.zero) { acc, p -> acc + p } / points.size.toDouble()
            val distances = points.map { Vector.distance(it, mean) }
            return MarkSpread(
                count = points.size,
                mean = mean,
                maximum = distances.maxOrNull() ?: 0.0,
                rms = sqrt(distances.map { it * it }.fold(0.0) { acc, v -> acc + v } / points.size.toDouble()),
            )
        }
    }
}
