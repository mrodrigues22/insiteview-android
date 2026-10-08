package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.Manifest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Plate anchoring (docs/PLAN.md §3 "Alignment solver", IOS-M2-04): the 4-DoF solver, smoothing
// with outlier rejection, and the re-anchoring blend. Pure math on `Vec3` so it runs and is tested
// on the JVM; `:ar` feeds it ARCore image poses.
//
// `Vector` (dot, cross, length, normalized, distance), declared in PlateAlignment.swift on iOS,
// lives in Vectors.kt. `YawTransform.interpolated` and `yawDifference`, extensions here on iOS, are
// members of `YawTransform` (ManualAlignment.kt).

/**
 * A plate's frame: the centre of its image, the normal out of the surface it's on, and the
 * image's up direction. Right = up × normal.
 */
data class PlateFrame(val position: Vec3, val normal: Vec3, val up: Vec3) {
    val right: Vec3 get() = Vector.cross(up, normal)

    /**
     * Plates on floors and ceilings (normal within 45° of vertical) take their heading from the
     * image's up; plates on walls from the normal.
     */
    val surface: Surface
        get() = if (abs(Vector.normalized(normal).y) > HORIZONTAL_SURFACE_THRESHOLD) Surface.HORIZONTAL else Surface.WALL

    enum class Surface {
        WALL,
        HORIZONTAL,
    }

    companion object {
        /** sin(45°). */
        internal val HORIZONTAL_SURFACE_THRESHOLD = sqrt(0.5)

        /**
         * The frame of a detected image from its anchor's axes (the columns of the image anchor's
         * transform). ARKit lays the image in the anchor's x–z plane with +y out of the image and
         * the image's top towards −z, so right = x, up = −z, normal = y. (iOS `init(anchorX:anchorY:anchorZ:position:)`;
         * ARCore's augmented image pose uses the same axes.)
         */
        @Suppress("UNUSED_PARAMETER")
        fun fromAnchor(anchorX: Vec3, anchorY: Vec3, anchorZ: Vec3, position: Vec3): PlateFrame =
            PlateFrame(position = position, normal = anchorY, up = -anchorZ)
    }
}

/** The plate's frame in model coordinates (call after `Manifest.validate()`). */
val Manifest.Plate.frame: PlateFrame
    get() = PlateFrame(position = Vec3.of(position), normal = Vec3.of(normal), up = Vec3.of(up))

/** The 4-DoF solver: yaw about +Y and a translation, from one plate seen in the world. */
object PlateAlignment {
    /**
     * A heading vector shorter than this once flattened (more than 60° off the plane it should
     * lie in) can't give a reliable yaw: the detection is rejected.
     */
    internal const val MINIMUM_HORIZONTAL_LENGTH = 0.5

    /**
     * `world = RotY(θ) · model + t`, with θ the signed angle about +Y from the model plate's
     * heading to the detected one (normals for wall plates, up vectors for floor and ceiling
     * plates) and `t = p_w − RotY(θ) · p_m`. A slightly tilted plate doesn't tilt the building.
     * `null` when the detection doesn't fit the plate (e.g. a wall plate seen lying flat).
     */
    fun solve(model: PlateFrame, world: PlateFrame): YawTransform? {
        val surface = model.surface
        if (world.surface != surface) return null
        val modelHeading = heading(model, surface) ?: return null
        val worldHeading = heading(world, surface) ?: return null
        val yaw = YawTransform.normalized(angle(worldHeading) - angle(modelHeading))
        return YawTransform(yaw = yaw, translation = world.position - YawTransform.rotate(model.position, by = yaw))
    }

    /** The yaw only, for smoothing (same conventions as [solve]). */
    fun yaw(model: PlateFrame, world: PlateFrame): Double? = solve(model, world)?.yaw

    /** The horizontal vector the yaw is measured on, unit length. */
    internal fun heading(frame: PlateFrame, surface: PlateFrame.Surface): Vec3? {
        val vector = if (surface == PlateFrame.Surface.WALL) frame.normal else frame.up
        val flat = Vec3(vector.x, 0.0, vector.z)
        val length = Vector.length(flat)
        val total = Vector.length(vector)
        if (!(total > 1e-9 && length / total >= MINIMUM_HORIZONTAL_LENGTH)) return null
        return flat / length
    }

    /** Heading angle about +Y: `(0, 0, 1)` → 0, `(1, 0, 0)` → π/2, matching [YawTransform.rotate]. */
    fun angle(flat: Vec3): Double = atan2(flat.x, flat.z)
}

/**
 * Smoothing for one plate (docs/PLAN.md §3 "Smoothing"): the last [WINDOW_SIZE] detections,
 * outliers more than [POSITION_TOLERANCE] or [ANGLE_TOLERANCE] from the median dropped, then
 * the median position and the circular mean of the yaw.
 *
 * A mutable class (a Swift struct with mutating methods); [copy] gives an independent one.
 */
class AlignmentSmoother(val plate: Int, val model: PlateFrame) {
    private val window = ArrayList<Sample>(WINDOW_SIZE + 1)

    /** World plate positions and solved yaws, oldest first. */
    val samples: List<Sample> get() = window.toList()

    /** Detections the solver rejected (wrong surface, degenerate heading). */
    var rejected: Int = 0
        private set

    data class Sample(val position: Vec3, val yaw: Double)

    data class Solution(
        val transform: YawTransform,
        /** Samples kept after outlier rejection. */
        val inliers: Int,
        /** Largest inlier distance from the median position (metres). */
        val positionSpread: Double,
        /** Largest inlier yaw difference from the circular mean (radians). */
        val yawSpread: Double,
    )

    /** Whether no detection is kept (cheaper than `samples.isEmpty()`). */
    val isEmpty: Boolean get() = window.isEmpty()

    /** Adds a detection. Returns the solution once a full window has enough inliers. */
    fun add(world: PlateFrame): Solution? {
        val yaw = PlateAlignment.yaw(model, world)
        if (yaw == null) {
            rejected += 1
            return null
        }
        window.add(Sample(world.position, yaw))
        while (window.size > WINDOW_SIZE) window.removeAt(0)
        if (window.size != WINDOW_SIZE) return null
        return solution()
    }

    /** The smoothed solution of the current window, or `null` without enough inliers. */
    fun solution(): Solution? {
        if (window.isEmpty()) return null
        val medianPosition = median(window.map { it.position })
        val medianYaw = circularMedian(window.map { it.yaw })
        val inliers = window.filter {
            Vector.length(it.position - medianPosition) <= POSITION_TOLERANCE &&
                abs(YawTransform.normalized(it.yaw - medianYaw)) <= ANGLE_TOLERANCE
        }
        if (inliers.size < min(MINIMUM_INLIERS, window.size)) return null
        val position = median(inliers.map { it.position })
        val yaw = circularMean(inliers.map { it.yaw })
        val transform = YawTransform(yaw = yaw, translation = position - YawTransform.rotate(model.position, by = yaw))
        return Solution(
            transform = transform,
            inliers = inliers.size,
            positionSpread = inliers.maxOfOrNull { Vector.length(it.position - position) } ?: 0.0,
            yawSpread = inliers.maxOfOrNull { abs(YawTransform.normalized(it.yaw - yaw)) } ?: 0.0,
        )
    }

    fun reset() {
        window.clear()
        rejected = 0
    }

    /** An independent copy (Swift value semantics). */
    fun copy(): AlignmentSmoother {
        val copy = AlignmentSmoother(plate, model)
        copy.window.addAll(window)
        copy.rejected = rejected
        return copy
    }

    companion object {
        const val WINDOW_SIZE = 10

        /** 3 cm. */
        const val POSITION_TOLERANCE = 0.03

        /** 2°. */
        const val ANGLE_TOLERANCE = 2 * PI / 180

        /** Inliers needed out of a full window to converge. */
        const val MINIMUM_INLIERS = 6

        // Statistics

        /** Component-wise median. */
        @JvmName("medianOfPoints")
        internal fun median(points: List<Vec3>): Vec3 =
            Vec3(median(points.map { it.x }), median(points.map { it.y }), median(points.map { it.z }))

        internal fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
        }

        /** Mean direction of angles, so 179° and −179° average to 180°, not 0°. */
        internal fun circularMean(angles: List<Double>): Double {
            val s = angles.fold(0.0) { acc, a -> acc + sin(a) }
            val c = angles.fold(0.0) { acc, a -> acc + cos(a) }
            return if (abs(s) < 1e-12 && abs(c) < 1e-12) (angles.firstOrNull() ?: 0.0) else atan2(s, c)
        }

        /** Median of the angles' offsets from their circular mean (robust to a few wild ones). */
        internal fun circularMedian(angles: List<Double>): Double {
            val reference = circularMean(angles)
            return YawTransform.normalized(reference + median(angles.map { YawTransform.normalized(it - reference) }))
        }
    }
}

/**
 * Moving from one alignment to another over [duration] (re-anchoring on another plate), so
 * the building glides instead of jumping (docs/PLAN.md §3 "Re-anchoring"). Times in seconds.
 */
data class AlignmentBlend(
    val from: YawTransform,
    val to: YawTransform,
    /**
     * Model point that travels in a straight line (the new plate), so the building turns about
     * what the user is looking at rather than about the model origin.
     */
    val pivot: Vec3,
    val start: Double,
    val duration: Double = DURATION,
) {
    fun isFinished(at: Double): Boolean = at >= start + duration

    fun transform(at: Double): YawTransform {
        if (!(duration > 0)) return to
        val linear = ((at - start) / duration).coerceAtLeast(0.0).coerceAtMost(1.0)
        // Smoothstep: eases in and out.
        val fraction = linear * linear * (3 - 2 * linear)
        return YawTransform.interpolated(from = from, to = to, fraction = fraction, pivot = pivot)
    }

    companion object {
        const val DURATION = 0.5
    }
}
