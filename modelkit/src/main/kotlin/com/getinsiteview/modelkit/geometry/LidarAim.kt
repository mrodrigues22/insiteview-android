package com.getinsiteview.modelkit.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * What the crosshair is on with the depth sensor (docs/PLAN.md §3 "LiDAR marks"): a wall, or a
 * wall corner at any height. `:ar` raycasts the middle of the screen and two points either side
 * of it along the wall; this decides from the planes they hit. Pure geometry, tested on the JVM.
 */
object LidarAim {
    /** A vertical surface the AR session found under a raycast: a point on it and its direction. */
    @ConsistentCopyVisibility
    data class Plane private constructor(
        val point: Vec3,
        /** Horizontal, unit length, out of the surface towards the camera. */
        val normal: Vec3,
    ) {
        /** Along the wall, horizontal. */
        val tangent: Vec3 get() = Vec3(-normal.z, 0.0, normal.x)

        companion object {
            /**
             * A raycast hit as a wall: its normal made horizontal and turned towards [eye]. `null` when
             * the surface isn't near vertical (more than 20° off). (iOS `init?(point:normal:eye:)`.)
             */
            fun of(point: Vec3, normal: Vec3, eye: Vec3): Plane? {
                val length = Vector.length(normal)
                if (!(length > 1e-9 && abs(normal.y / length) <= sin(MAXIMUM_TILT))) return null
                var flat = Vector.normalized(Vec3(normal.x, 0.0, normal.z))
                if (Vector.dot(Vec3(eye.x - point.x, 0.0, eye.z - point.z), flat) < 0) {
                    flat = -flat
                }
                return Plane(point = point, normal = flat)
            }
        }
    }

    sealed interface Target {
        /** On a wall: the hit and the wall's direction. */
        data class Wall(val plane: Plane) : Target

        /** On a corner: where its two walls meet, at the hit's height. */
        data class Corner(val point: Vec3) : Target
    }

    /** Walls tilted more than this aren't walls. */
    internal const val MAXIMUM_TILT = 20 * PI / 180

    /**
     * The side points go this far along the wall from the middle hit: far enough that each lands
     * clearly on one wall of a corner.
     */
    const val SIDE_REACH = 0.18

    /** Two walls meeting at less than this, or more than its supplement, aren't a corner. */
    internal const val CORNER_ANGLE = 45 * PI / 180

    /** The walls' meeting line must be this close to the middle hit, seen from above. */
    internal const val CORNER_REACH = 0.1

    /** While "Mark" averages: samples further than these from their median are hand shake. */
    const val CAPTURE_DISTANCE = 0.015
    const val CAPTURE_ANGLE = 2 * PI / 180

    /**
     * What the crosshair is on: a corner when the side hits are on two walls meeting near the
     * middle hit, else the wall under the middle.
     */
    fun target(centre: Plane?, left: Plane?, right: Plane?): Target? {
        if (centre == null) return null
        if (left != null && right != null) {
            val corner = corner(left, right, near = centre.point)
            if (corner != null) return Target.Corner(corner)
        }
        return Target.Wall(centre)
    }

    /**
     * Where two walls meet, at [near]'s height: `null` unless they meet at 45–135° within
     * [CORNER_REACH] of [near] seen from above.
     */
    fun corner(a: Plane, b: Plane, near: Vec3): Vec3? {
        // n · x = n · p for each wall, in x and z.
        val determinant = a.normal.x * b.normal.z - a.normal.z * b.normal.x
        if (!(abs(determinant) >= sin(CORNER_ANGLE))) return null
        val da = Vector.dot(a.normal, a.point)
        val db = Vector.dot(b.normal, b.point)
        val x = (da * b.normal.z - db * a.normal.z) / determinant
        val z = (a.normal.x * db - b.normal.x * da) / determinant
        val point = Vec3(x, near.y, z)
        if (!(Vector.length(Vec3(point.x - near.x, 0.0, point.z - near.z)) <= CORNER_REACH)) return null
        return point
    }

    /**
     * Half a second of the crosshair as one mark: every sample on the same kind of thing, those
     * within [CAPTURE_DISTANCE] and [CAPTURE_ANGLE] of the median kept and averaged. `null` when
     * fewer than [minimum] remain.
     */
    fun average(samples: List<Target>, minimum: Int): Target? {
        val walls = samples.mapNotNull { (it as? Target.Wall)?.plane }
        val corners = samples.mapNotNull { (it as? Target.Corner)?.point }
        if (walls.size == samples.size) {
            val middle = median(walls.map { it.point })
            val heading = AlignmentSmoother.circularMedian(walls.map { PlateAlignment.angle(it.normal) })
            val kept = walls.filter {
                Vector.distance(it.point, middle) <= CAPTURE_DISTANCE &&
                    abs(YawTransform.normalized(PlateAlignment.angle(it.normal) - heading)) <= CAPTURE_ANGLE
            }
            if (kept.size < minimum) return null
            val normal = Vector.normalized(kept.fold(Vec3.zero) { acc, p -> acc + p.normal })
            val point = kept.fold(Vec3.zero) { acc, p -> acc + p.point } / kept.size.toDouble()
            return Plane.of(point = point, normal = normal, eye = point + normal)?.let { Target.Wall(it) }
        }
        if (corners.size == samples.size) {
            val middle = median(corners)
            val kept = corners.filter { Vector.distance(it, middle) <= CAPTURE_DISTANCE }
            if (kept.size < minimum) return null
            return Target.Corner(kept.fold(Vec3.zero) { acc, p -> acc + p } / kept.size.toDouble())
        }
        return null
    }

    private fun median(points: List<Vec3>): Vec3 = AlignmentSmoother.median(points)
}
