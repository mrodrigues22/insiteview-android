package com.getinsiteview.modelkit.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

// Registering a plate on site (docs/PLAN.md §3 "Registering a plate on site"): with the building
// aligned by reference points, the plate stuck wherever was convenient is detected, its pose is
// taken into model coordinates and snapped onto the room's wall, and the app saves it with
// `PATCH /v1/plates/{id}`. From then on guests align on that plate. Pure math, tested on the JVM.

/**
 * Detections of the plate being registered, kept only when close and seen nearly head-on,
 * reduced to their median.
 *
 * A mutable class (a Swift struct with mutating methods); [copy] gives an independent one.
 */
class PlateSampler {
    private val window = ArrayList<PlateFrame>(WINDOW_SIZE + 1)

    val samples: List<PlateFrame> get() = window.toList()

    /** Detections left out (too far or too oblique), for the coaching ("come closer"). */
    var skipped: Int = 0
        private set

    /** Adds a detection seen from [camera] (world). Returns whether it was kept. */
    fun add(frame: PlateFrame, camera: Vec3): Boolean {
        val toCamera = camera - frame.position
        val distance = Vector.length(toCamera)
        val facing = if (distance > 1e-6) Vector.dot(Vector.normalized(frame.normal), toCamera / distance) else 0.0
        if (!(distance <= MAXIMUM_DISTANCE && facing >= cos(MAXIMUM_ANGLE))) {
            skipped += 1
            return false
        }
        window.add(frame)
        while (window.size > WINDOW_SIZE) window.removeAt(0)
        return true
    }

    val isReady: Boolean get() = window.size >= MINIMUM_SAMPLES

    /**
     * The median pose (component-wise; directions renormalized and made perpendicular), once
     * [isReady].
     */
    val pose: PlateFrame?
        get() {
            if (!isReady) return null
            val position = AlignmentSmoother.median(window.map { it.position })
            val normal = Vector.normalized(AlignmentSmoother.median(window.map { Vector.normalized(it.normal) }))
            var up = AlignmentSmoother.median(window.map { Vector.normalized(it.up) })
            up = Vector.normalized(up - normal * Vector.dot(up, normal))
            return PlateFrame(position = position, normal = normal, up = up)
        }

    fun reset() {
        window.clear()
        skipped = 0
    }

    /** An independent copy (Swift value semantics). */
    fun copy(): PlateSampler {
        val copy = PlateSampler()
        copy.window.addAll(window)
        copy.skipped = skipped
        return copy
    }

    companion object {
        /** Further than this, the detected pose of a 50 mm plate gets noisy. */
        const val MAXIMUM_DISTANCE = 1.5

        /** Seen more obliquely than this (between the camera and the plate's normal), likewise. */
        const val MAXIMUM_ANGLE = 45 * PI / 180
        const val WINDOW_SIZE = 15

        /** Samples needed for a pose. */
        const val MINIMUM_SAMPLES = 8
    }
}

object PlateRegistration {
    /** A wall plate this close to its wall's surface goes onto it. */
    const val WALL_SNAP_DISTANCE = 0.08

    /**
     * Further in front than this (but facing the same way) it's on something else, like a panel
     * door: it keeps its measured position.
     */
    const val MAXIMUM_STAND_OFF = 0.5

    /** The plate's normal must be this close to its wall's. */
    const val WALL_SNAP_ANGLE = 15 * PI / 180

    /** Off the surface, as the web placement tool does, so it doesn't flicker into the wall. */
    const val SURFACE_OFFSET = 0.003

    data class Pose(
        /** Model coordinates, ready for `PATCH /v1/plates/{id}`. */
        val frame: PlateFrame,
        /** Where it is on its wall (wall plates in a room with an outline). */
        val wall: RoomOutline.WallPosition?,
        /** Height of the plate's centre above the room's floor. */
        val heightAboveFloor: Double?,
        /** Whether it was moved onto the wall's surface. */
        val snappedToWall: Boolean,
    )

    enum class Problem {
        /**
         * A wall plate facing no wall of the chosen room, or behind one: the wrong room was
         * picked, or the alignment is off.
         */
        NOT_ON_A_WALL_OF_THE_ROOM,
    }

    /** Swift's `Result<Pose, Problem>`. */
    sealed interface Result {
        data class Success(val pose: Pose) : Result

        data class Failure(val problem: Problem) : Result

        /** The pose, or throws [ProblemException] (Swift's `Result.get()`). */
        fun get(): Pose = when (this) {
            is Success -> pose
            is Failure -> throw ProblemException(problem)
        }
    }

    class ProblemException(val problem: Problem) : Exception(problem.name)

    /**
     * The plate's pose in model coordinates from its (sampled) detection and the building's
     * alignment. Wall plates turn exactly vertical with up = +Y, and go onto the wall they face
     * when they're on it; plates on floors, ceilings or under sinks keep a horizontal up.
     */
    fun pose(of: PlateFrame, alignment: YawTransform, outline: RoomOutline?): Result {
        val detection = of
        var position = alignment.inverseApply(detection.position)
        var normal = alignment.inverseRotate(Vector.normalized(detection.normal))
        var up = alignment.inverseRotate(Vector.normalized(detection.up))
        val height = outline?.let { position.y - it.floorY }

        if (detection.surface != PlateFrame.Surface.WALL) {
            normal = Vec3(0.0, if (normal.y >= 0) 1.0 else -1.0, 0.0)
            val flat = Vec3(up.x, 0.0, up.z)
            up = if (Vector.length(flat) > 1e-6) Vector.normalized(flat) else Vec3(0.0, 0.0, -1.0)
            return Result.Success(Pose(PlateFrame(position, normal, up), wall = null, heightAboveFloor = height, snappedToWall = false))
        }

        normal = Vector.normalized(Vec3(normal.x, 0.0, normal.z))
        up = Vec3(0.0, 1.0, 0.0)
        if (outline == null) {
            return Result.Success(Pose(PlateFrame(position, normal, up), wall = null, heightAboveFloor = null, snappedToWall = false))
        }
        val facing = outline.walls
            .filter { Vector.dot(it.inwardNormal, normal) >= cos(WALL_SNAP_ANGLE) }
            .map { outline.position(of = position, on = it) }
            .filter { it.signedDistance >= -WALL_SNAP_DISTANCE && it.signedDistance <= MAXIMUM_STAND_OFF && isAlongWall(it, position) }
            .minByOrNull { abs(it.signedDistance) }
            ?: return Result.Failure(Problem.NOT_ON_A_WALL_OF_THE_ROOM)
        normal = facing.wall.inwardNormal
        val snapped = abs(facing.signedDistance) <= WALL_SNAP_DISTANCE
        if (snapped) {
            position = facing.foot + normal * SURFACE_OFFSET
        }
        return Result.Success(
            Pose(PlateFrame(position, normal, up), wall = facing, heightAboveFloor = height, snappedToWall = snapped),
        )
    }

    /** The point's foot is on the wall itself, not just on its line (10 cm of slack at the ends). */
    private fun isAlongWall(position: RoomOutline.WallPosition, point: Vec3): Boolean {
        val wall = position.wall
        val along = Vec3(wall.end.x - wall.start.x, 0.0, wall.end.z - wall.start.z)
        val length = Vector.length(along)
        val t = Vector.dot(Vec3(point.x - wall.start.x, 0.0, point.z - wall.start.z), along / length)
        return t >= -0.1 && t <= length + 0.1
    }
}
