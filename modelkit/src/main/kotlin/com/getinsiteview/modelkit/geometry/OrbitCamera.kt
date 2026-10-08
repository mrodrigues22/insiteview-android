package com.getinsiteview.modelkit.geometry

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The 3D viewer's orbit camera (IOS-M1-08): it circles a target point at a distance, with yaw
 * around +Y and pitch above the horizon. Gestures change these values and the view places a
 * camera at [position] looking at [target]. Pure math, so it's tested on the JVM.
 *
 * A mutable class (a Swift struct with mutating methods); [copy] gives an independent one.
 */
class OrbitCamera(
    var target: Vec3,
    /** Radians around +Y. 0 puts the camera on +Z looking toward −Z. */
    var yaw: Double = DEFAULT_YAW,
    pitch: Double = DEFAULT_PITCH,
    distance: Double,
    var pitchRange: ClosedFloatingPointRange<Double> = (-10 * PI / 180)..(89 * PI / 180),
    var distanceRange: ClosedFloatingPointRange<Double> = 0.3..500.0,
) {
    /** Radians above the horizon, clamped to [pitchRange]. */
    var pitch: Double = clamp(pitch, pitchRange)
        set(value) {
            field = clamp(value, pitchRange)
        }

    /** Metres from the target, clamped to [distanceRange]. */
    var distance: Double = clamp(distance, distanceRange)
        set(value) {
            field = clamp(value, distanceRange)
        }

    /** Unit vector from the target toward the camera. */
    val offsetDirection: Vec3 get() = Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw))

    val position: Vec3 get() = target + offsetDirection * distance

    /** Unit vector the camera looks along. */
    val forward: Vec3 get() = -offsetDirection

    /** Screen right, horizontal. */
    val right: Vec3 get() = Vec3(cos(yaw), 0.0, -sin(yaw))

    /** Screen up: perpendicular to [forward] and [right]. */
    val up: Vec3
        get() {
            val f = forward
            val r = right
            return Vec3(r.y * f.z - r.z * f.y, r.z * f.x - r.x * f.z, r.x * f.y - r.y * f.x)
        }

    /**
     * One-finger drag: [dx], [dy] in radians (the view scales points to radians).
     * Dragging right turns the model right; dragging down looks from higher up.
     */
    fun orbit(dx: Double, dy: Double) {
        yaw -= dx
        pitch += dy
    }

    /** Pinch: a scale above 1 moves closer. */
    fun zoom(scale: Double) {
        if (!(scale > 0) || !scale.isFinite()) return
        distance /= scale
    }

    /**
     * Two-finger drag: [dx], [dy] as fractions of the view height, so the model follows the
     * fingers at any distance for a vertical field of view [fieldOfView] (radians).
     */
    fun pan(dx: Double, dy: Double, fieldOfView: Double) {
        val metresPerViewHeight = 2 * distance * tan(fieldOfView / 2)
        target -= right * (dx * metresPerViewHeight)
        target += up * (dy * metresPerViewHeight)
    }

    /** Keeps the view but refits the distance, e.g. after changing the storey filter. */
    fun refit(bounds: Bounds, verticalFieldOfView: Double, aspectRatio: Double, margin: Double = 1.1) {
        target = bounds.center
        val fitted = fittingDistance(
            radius = bounds.radius, verticalFieldOfView = verticalFieldOfView, aspectRatio = aspectRatio, margin = margin,
        )
        if (fitted > distanceRange.endInclusive) {
            distanceRange = distanceRange.start..(fitted * 4)
        }
        distance = fitted
    }

    /** An independent copy (Swift value semantics). */
    fun copy(): OrbitCamera = OrbitCamera(target, yaw, pitch, distance, pitchRange, distanceRange)

    override fun equals(other: Any?): Boolean =
        other is OrbitCamera && target == other.target && yaw == other.yaw && pitch == other.pitch &&
            distance == other.distance && pitchRange == other.pitchRange && distanceRange == other.distanceRange

    override fun hashCode(): Int {
        var h = target.hashCode()
        h = h * 31 + (yaw + 0.0).hashCode()
        h = h * 31 + (pitch + 0.0).hashCode()
        h = h * 31 + (distance + 0.0).hashCode()
        h = h * 31 + pitchRange.hashCode()
        return h * 31 + distanceRange.hashCode()
    }

    override fun toString(): String = "OrbitCamera(target=$target, yaw=$yaw, pitch=$pitch, distance=$distance)"

    companion object {
        /** A three-quarter view from above: 35° yaw, 30° pitch. */
        const val DEFAULT_YAW = 35 * PI / 180
        const val DEFAULT_PITCH = 30 * PI / 180

        private fun clamp(value: Double, range: ClosedFloatingPointRange<Double>): Double =
            min(max(value, range.start), range.endInclusive)

        /**
         * A camera that shows the whole box: centred on it, far enough that its bounding sphere
         * fits the narrower field of view, with [margin] extra room.
         */
        fun fitting(
            bounds: Bounds,
            verticalFieldOfView: Double,
            aspectRatio: Double,
            yaw: Double = DEFAULT_YAW,
            pitch: Double = DEFAULT_PITCH,
            margin: Double = 1.1,
        ): OrbitCamera {
            val distance = fittingDistance(
                radius = bounds.radius, verticalFieldOfView = verticalFieldOfView, aspectRatio = aspectRatio, margin = margin,
            )
            val maxDistance = max(500.0, distance * 4)
            return OrbitCamera(target = bounds.center, yaw = yaw, pitch = pitch, distance = distance, distanceRange = 0.3..maxDistance)
        }

        /** Distance at which a sphere of [radius] fills the narrower of the two fields of view. */
        fun fittingDistance(radius: Double, verticalFieldOfView: Double, aspectRatio: Double, margin: Double = 1.1): Double {
            val horizontal = 2 * atan(tan(verticalFieldOfView / 2) * aspectRatio)
            val narrowest = min(verticalFieldOfView, horizontal)
            return max(radius, 0.1) / sin(narrowest / 2) * margin
        }
    }
}
