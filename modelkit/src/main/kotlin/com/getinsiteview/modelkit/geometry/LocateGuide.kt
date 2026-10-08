package com.getinsiteview.modelkit.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where to look for an element in AR (IOS-M3-05, "Locate in AR"). */
data class LocateIndicator(
    /** Metres from the camera to the nearest point of the element's box (0 inside it). */
    val distance: Double,
    /** The element's centre is in view (inside the margin). */
    val isOnScreen: Boolean,
    /** The centre in normalized view coordinates (x right, y down, 0…1) while on screen. */
    val screenPoint: Vec2?,
    /**
     * Off screen: which way to turn, as a screen-space angle in radians (0 right, π/2 up, π left,
     * −π/2 down). Behind the camera it's straight left or right, whichever turn is shorter.
     */
    val arrowAngle: Double?,
)

/**
 * The math behind Locate in AR: distance to the element, whether it's in view, and the
 * off-screen arrow. `:ar` feeds it the AR camera's view and projection matrices every frame.
 */
object LocateGuide {
    /**
     * Fraction of the half-screen kept clear at the edges: closer to the edge counts as off
     * screen, so the arrow shows before the element is hidden under the controls.
     */
    const val DEFAULT_MARGIN = 0.1

    /**
     * @param cameraSpacePoint the element's centre in camera space (the view matrix: x right, y up,
     *   the camera looks down −z).
     * @param distance from [distance] (camera, box, transform).
     * @param focalX the projection matrix's `[0][0]` (1 / tan(half field of view)) for the view's
     *   orientation and size; [focalY] its `[1][1]`.
     */
    fun indicator(
        cameraSpacePoint: Vec3,
        distance: Double,
        focalX: Double,
        focalY: Double,
        margin: Double = DEFAULT_MARGIN,
    ): LocateIndicator {
        val point = cameraSpacePoint
        if (!(point.z < -1e-6)) {
            // Behind the camera (or level with it): turn left or right.
            return LocateIndicator(distance = distance, isOnScreen = false, screenPoint = null, arrowAngle = if (point.x >= 0) 0.0 else PI)
        }
        val depth = -point.z
        val x = focalX * point.x / depth
        val y = focalY * point.y / depth
        val limit = 1 - margin
        if (abs(x) <= limit && abs(y) <= limit) {
            return LocateIndicator(distance = distance, isOnScreen = true, screenPoint = Vec2((x + 1) / 2, (1 - y) / 2), arrowAngle = null)
        }
        return LocateIndicator(distance = distance, isOnScreen = false, screenPoint = null, arrowAngle = atan2(y, x))
    }

    /** Distance from a point to an axis-aligned box; 0 inside it. */
    fun distance(from: Vec3, to: Bounds): Double {
        val point = from
        val box = to
        val clamped = Vec3(
            min(max(point.x, box.min.x), box.max.x),
            min(max(point.y, box.min.y), box.max.y),
            min(max(point.z, box.min.z), box.max.z),
        )
        return Vector.distance(point, clamped)
    }

    /**
     * Distance from the camera (world) to an element's box (model coordinates) placed in the
     * room by the alignment. The box stays axis-aligned in model space, so the camera is moved
     * into it instead of the box into the world.
     */
    fun distance(camera: Vec3, to: Bounds, placedBy: YawTransform): Double = distance(from = placedBy.inverseApply(camera), to = to)

    /**
     * Where the off-screen arrow goes: on a rectangle [inset] inside the view's edges, in the
     * direction of [angle] from the centre. View coordinates in points (px on Android), y down.
     */
    fun edgePosition(angle: Double, width: Double, height: Double, inset: Double): Vec2 {
        val halfWidth = max(0.0, width / 2 - inset)
        val halfHeight = max(0.0, height / 2 - inset)
        val dx = cos(angle)
        val dy = -sin(angle)
        var scale = Double.POSITIVE_INFINITY
        if (abs(dx) > 1e-9) scale = min(scale, halfWidth / abs(dx))
        if (abs(dy) > 1e-9) scale = min(scale, halfHeight / abs(dy))
        if (!scale.isFinite()) scale = 0.0
        return Vec2(width / 2 + dx * scale, height / 2 + dy * scale)
    }

    /** The pulse's period in seconds. */
    const val PULSE_PERIOD = 1.2

    /** The locate marker's scale over time: 1 → 1.35 → 1 every [PULSE_PERIOD]. */
    fun pulseScale(at: Double): Double {
        val phase = (at / PULSE_PERIOD) % 1.0
        return 1 + 0.35 * (0.5 - 0.5 * cos(2 * PI * phase))
    }
}
