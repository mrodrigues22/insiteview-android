package com.getinsiteview.modelkit.ar

import com.getinsiteview.modelkit.geometry.LidarAim
import com.getinsiteview.modelkit.geometry.ReferenceMark
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.Vector

/**
 * "Mark" (or "Fix"): the crosshair averaged over half a second, leaving out hand shake (iOS
 * `ARAlignmentView.captureFloorAim()`, AAV 703-737). Every crosshair update while capturing adds
 * its aim; one with no aim breaks the capture.
 *
 * The crosshair is where the ray through the middle of the screen meets the detected floor, taken
 * only when the phone points nearly straight down. The first crosshair took shallow aims from
 * metres away, where the floor plane's few centimetres of error became a mark that slid as the user
 * moved; laying the phone on the spot instead covered the camera, so each mark was taken with
 * tracking blind and jumped once the phone was lifted (device tests, 2026-10-06).
 *
 * A mutable class (the view's `captureSamples` and `captureBroken`).
 */
class MarkCapture {
    private val collected = ArrayList<FloorAim>()

    val samples: List<FloorAim> get() = collected.toList()

    /** The aim or tracking didn't hold for a frame meanwhile. */
    var broken: Boolean = false
        private set

    /** A crosshair update: its aim, or `null` when it had none. */
    fun add(aim: FloorAim?) {
        if (aim != null) collected.add(aim) else broken = true
    }

    /** The mark, or `null` when the capture didn't hold (see [reduce]). */
    fun result(): FloorAim? = reduce(collected, broken)

    companion object {
        /** "Mark" averages the crosshair this long. */
        const val CAPTURE_DURATION_MILLIS = 500L
        const val MINIMUM_CAPTURE_SAMPLES = 5

        /** Samples further than this from their median are hand shake, left out. */
        const val CAPTURE_TOLERANCE = 0.02

        /**
         * The captured aims as one: `null` when broken or fewer than [MINIMUM_CAPTURE_SAMPLES].
         *
         * - A wall or a corner (depth sensor): every sample on the same kind of thing, averaged by
         *   [LidarAim.average] (1.5 cm, 2°); a floor sample among them fails it. The eye is the mean.
         * - The floor: samples within [CAPTURE_TOLERANCE] of the component-wise median, at least
         *   [MINIMUM_CAPTURE_SAMPLES] of them, averaged with their eyes.
         */
        fun reduce(samples: List<FloorAim>, broken: Boolean): FloorAim? {
            if (broken || samples.size < MINIMUM_CAPTURE_SAMPLES) return null
            if (samples.any { it.surface != ReferenceMark.Surface.FLOOR }) {
                val targets = samples.mapNotNull { sample ->
                    when (sample.surface) {
                        ReferenceMark.Surface.EDGE -> LidarAim.Target.Corner(sample.point)
                        ReferenceMark.Surface.WALL_PLANE -> sample.normal
                            ?.let { LidarAim.Plane.of(point = sample.point, normal = it, eye = sample.eye) }
                            ?.let { LidarAim.Target.Wall(it) }
                        ReferenceMark.Surface.FLOOR, ReferenceMark.Surface.WALL -> null
                    }
                }
                if (targets.size != samples.size) return null
                val target = LidarAim.average(targets, minimum = MINIMUM_CAPTURE_SAMPLES) ?: return null
                val eye = samples.fold(Vec3.zero) { sum, it -> sum + it.eye } / samples.size.toDouble()
                return when (target) {
                    is LidarAim.Target.Corner -> FloorAim(point = target.point, eye = eye, surface = ReferenceMark.Surface.EDGE)
                    is LidarAim.Target.Wall -> FloorAim(
                        point = target.plane.point, eye = eye, surface = ReferenceMark.Surface.WALL_PLANE, normal = target.plane.normal,
                    )
                }
            }
            val median = median(samples.map { it.point })
            val kept = samples.filter { Vector.distance(it.point, median) <= CAPTURE_TOLERANCE }
            if (kept.size < MINIMUM_CAPTURE_SAMPLES) return null
            val count = kept.size.toDouble()
            return FloorAim(
                point = kept.fold(Vec3.zero) { sum, it -> sum + it.point } / count,
                eye = kept.fold(Vec3.zero) { sum, it -> sum + it.eye } / count,
            )
        }

        /** The component-wise median; the mean of the middle two for an even count. */
        fun median(points: List<Vec3>): Vec3 {
            fun median(values: List<Double>): Double {
                val sorted = values.sorted()
                val middle = sorted.size / 2
                return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
            }
            return Vec3(median(points.map { it.x }), median(points.map { it.y }), median(points.map { it.z }))
        }
    }
}
