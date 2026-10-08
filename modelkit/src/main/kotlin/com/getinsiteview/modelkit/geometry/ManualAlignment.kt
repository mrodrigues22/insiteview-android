package com.getinsiteview.modelkit.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the building sits in the AR world: a rotation about +Y (yaw) and a translation.
 * ARCore's world and the model are both gravity-aligned and Y up, so these 4 degrees of freedom
 * are all alignment needs (docs/PLAN.md §3 "Alignment solver"). `world = RotY(yaw) · model + translation`.
 *
 * The yaw is wrapped to (−π, π] on construction. Equality is IEEE (`-0.0 == 0.0`), as Swift's.
 */
class YawTransform(yaw: Double, val translation: Vec3) {
    val yaw: Double = normalized(yaw)

    /** Model → world. */
    fun apply(p: Vec3): Vec3 = rotate(p, by = yaw) + translation

    /** World → model. */
    fun inverseApply(p: Vec3): Vec3 = rotate(p - translation, by = -yaw)

    /** World → model for a direction (no translation), e.g. a detected plate's normal. */
    fun inverseRotate(direction: Vec3): Vec3 = rotate(direction, by = -yaw)

    /** The transform that undoes this one. */
    val inverse: YawTransform get() = YawTransform(yaw = -yaw, translation = -rotate(translation, by = -yaw))

    /** This transform, then [next]: a point goes to `next.apply(apply(p))`. */
    fun then(next: YawTransform): YawTransform =
        YawTransform(yaw = yaw + next.yaw, translation = rotate(translation, by = next.yaw) + next.translation)

    /** The transform with the building moved by [delta] (world metres). */
    fun translated(by: Vec3): YawTransform = YawTransform(yaw = yaw, translation = translation + by)

    /**
     * The transform with the building turned by [angle] about a vertical axis through [about]
     * (world), e.g. the point under a two-finger twist.
     */
    fun rotated(by: Double, about: Vec3): YawTransform {
        val moved = about + rotate(translation - about, by = by)
        return YawTransform(yaw = yaw + by, translation = moved)
    }

    /** Angle between two transforms' yaws (radians, 0…π). (iOS: an extension in PlateAlignment.swift.) */
    fun yawDifference(to: YawTransform): Double = abs(normalized(to.yaw - yaw))

    fun copy(yaw: Double = this.yaw, translation: Vec3 = this.translation): YawTransform = YawTransform(yaw, translation)

    override fun equals(other: Any?): Boolean = other is YawTransform && yaw == other.yaw && translation == other.translation

    override fun hashCode(): Int = (yaw + 0.0).hashCode() * 31 + translation.hashCode()

    override fun toString(): String = "YawTransform(yaw=$yaw, translation=$translation)"

    companion object {
        val identity = YawTransform(yaw = 0.0, translation = Vec3.zero)

        /** Rotates a vector about +Y: `(0, 0, 1)` → `(sin θ, 0, cos θ)`. */
        fun rotate(v: Vec3, by: Double): Vec3 {
            val c = cos(by)
            val s = sin(by)
            return Vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z)
        }

        /** Wraps an angle to (−π, π]. */
        fun normalized(angle: Double): Double {
            if (!angle.isFinite()) return 0.0
            var a = angle % (2 * PI)
            if (a <= -PI) a += 2 * PI
            if (a > PI) a -= 2 * PI
            return a
        }

        /**
         * In between two transforms: the yaw along the shorter way round, and [pivot] (a model
         * point) on the straight line between where each transform puts it. 0 → [from], 1 → [to].
         * (iOS: an extension in PlateAlignment.swift.)
         */
        fun interpolated(from: YawTransform, to: YawTransform, fraction: Double, pivot: Vec3): YawTransform {
            val yaw = from.yaw + normalized(to.yaw - from.yaw) * fraction
            val start = from.apply(pivot)
            val end = to.apply(pivot)
            val point = start + (end - start) * fraction
            return YawTransform(yaw = yaw, translation = point - rotate(pivot, by = yaw))
        }
    }
}

/**
 * Manual alignment in AR (IOS-M1-09, docs/PLAN.md §3 "Manual fallback"): put the chosen storey's
 * floor on a detected plane, then drag, twist and nudge until the model matches the room.
 */
object ManualAlignment {
    /** One "Fine-tune" step: 1 cm. */
    const val NUDGE_DISTANCE = 0.01

    /** One "Fine-tune" turn: 0.5°. */
    const val NUDGE_ANGLE = 0.5 * PI / 180

    enum class Nudge {
        /** Away from the camera, along the floor. */
        FORWARD,
        BACK,
        LEFT,
        RIGHT,

        /** The floor height, for a plane detected a little high or low. */
        UP,
        DOWN,

        /** Counter-clockwise seen from above. */
        TURN_LEFT,
        TURN_RIGHT,
    }

    /**
     * Yaw that turns the model's front (+Z) toward the camera, from the camera's forward vector
     * (the negated third column of the camera transform). Horizontal part only.
     */
    fun yaw(facing: Vec3): Double = atan2(-facing.x, -facing.z)

    /**
     * The first placement: the storey's floor (model height [elevation]) on the tapped
     * plane point, the model's origin (its horizontal centre) at that point, facing the camera.
     */
    fun placingFloor(elevation: Double, at: Vec3, cameraForward: Vec3): YawTransform {
        val yaw = yaw(facing = cameraForward)
        val anchor = YawTransform.rotate(Vec3(0.0, elevation, 0.0), by = yaw)
        return YawTransform(yaw = yaw, translation = at - anchor)
    }

    /**
     * The floor glue after aligning by points (docs/PLAN.md §3): the transform moved up or down
     * so the model's floor (model height [modelFloorY]) sits on the detected floor at
     * [worldFloorY], taking out vertical drift. `null` when it's already there (under
     * [FLOOR_GLUE_MINIMUM]) or the plane is too far off to be the same floor (over [maximum]: a
     * plane found under a bed, or a step down).
     */
    fun gluedToFloor(
        transform: YawTransform,
        modelFloorY: Double,
        worldFloorY: Double,
        maximum: Double = FLOOR_GLUE_MAXIMUM,
    ): YawTransform? {
        val change = worldFloorY - (transform.translation.y + modelFloorY)
        if (!(abs(change) >= FLOOR_GLUE_MINIMUM && abs(change) <= maximum)) return null
        return transform.translated(by = Vec3(0.0, change, 0.0))
    }

    /** Floor glue: smaller changes are left alone (the plane's own noise). */
    const val FLOOR_GLUE_MINIMUM = 0.005
    const val FLOOR_GLUE_MAXIMUM = 0.15

    /** Dragging along the floor: moves by the horizontal difference between two plane hits. */
    fun dragged(transform: YawTransform, from: Vec3, to: Vec3): YawTransform {
        val delta = (to - from).copy(y = 0.0)
        return transform.translated(by = delta)
    }

    /**
     * One fine-tune step relative to where the camera looks, turning about [pivot]
     * (the model point in the middle of the screen, or the placement point).
     */
    fun nudged(transform: YawTransform, nudge: Nudge, cameraForward: Vec3, pivot: Vec3): YawTransform {
        var forward = Vec3(cameraForward.x, 0.0, cameraForward.z)
        val length = sqrt(forward.x * forward.x + forward.z * forward.z)
        forward = if (length > 1e-6) forward / length else Vec3(0.0, 0.0, -1.0)
        val right = Vec3(-forward.z, 0.0, forward.x)
        val step = NUDGE_DISTANCE
        return when (nudge) {
            Nudge.FORWARD -> transform.translated(by = forward * step)
            Nudge.BACK -> transform.translated(by = -forward * step)
            Nudge.RIGHT -> transform.translated(by = right * step)
            Nudge.LEFT -> transform.translated(by = -right * step)
            Nudge.UP -> transform.translated(by = Vec3(0.0, step, 0.0))
            Nudge.DOWN -> transform.translated(by = Vec3(0.0, -step, 0.0))
            Nudge.TURN_LEFT -> transform.rotated(by = NUDGE_ANGLE, about = pivot)
            Nudge.TURN_RIGHT -> transform.rotated(by = -NUDGE_ANGLE, about = pivot)
        }
    }
}
