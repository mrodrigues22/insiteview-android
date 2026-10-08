package com.getinsiteview.modelkit.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Synthetic plate detections: a model plate moved by a known transform, turned into the axes an
 * image anchor would report (x = right, y = normal, z = −up), optionally tilted and noisy.
 */
object SyntheticPlates {
    const val MILLIMETRE = 0.001
    const val TENTH_OF_DEGREE = 0.1 * PI / 180

    fun degrees(value: Double): Double = value * PI / 180

    /**
     * A wall plate on a wall facing +Z at 1.5 m (the test-room's plate 1 faces −Z; this one is
     * on another wall, off the origin, so a wrong yaw shows up in the translation too).
     */
    val wall = PlateFrame(position = Vec3(1.2, 1.5, -3.0), normal = Vec3(0.0, 0.0, 1.0), up = Vec3(0.0, 1.0, 0.0))

    /** On the floor, the image's top pointing −Z. */
    val floor = PlateFrame(position = Vec3(-2.0, 0.0, 1.0), normal = Vec3(0.0, 1.0, 0.0), up = Vec3(0.0, 0.0, -1.0))

    /** On the ceiling, the image's top pointing +X. */
    val ceiling = PlateFrame(position = Vec3(0.5, 2.6, 0.5), normal = Vec3(0.0, -1.0, 0.0), up = Vec3(1.0, 0.0, 0.0))

    /** Rotates [v] about the unit [axis] by [angle] (Rodrigues). */
    fun rotate(v: Vec3, axis: Vec3, angle: Double): Vec3 {
        val k = Vector.normalized(axis)
        return v * cos(angle) + Vector.cross(k, v) * sin(angle) + k * Vector.dot(k, v) * (1 - cos(angle))
    }

    /**
     * What the AR session would report for [model] placed in the world by [truth], with the plate
     * tilted about its right axis by [tilt] and rolled about its normal by [roll] (print or mounting
     * errors), and [offset] added to the position (noise).
     */
    fun detection(model: PlateFrame, truth: YawTransform, tilt: Double = 0.0, roll: Double = 0.0, offset: Vec3 = Vec3.zero): PlateFrame {
        var normal = YawTransform.rotate(model.normal, by = truth.yaw)
        var up = YawTransform.rotate(model.up, by = truth.yaw)
        val right = Vector.cross(up, normal)
        normal = rotate(normal, axis = right, angle = tilt)
        up = rotate(up, axis = right, angle = tilt)
        up = rotate(up, axis = normal, angle = roll)
        val position = truth.apply(model.position) + offset
        val x = Vector.cross(up, normal)
        return PlateFrame.fromAnchor(anchorX = x, anchorY = normal, anchorZ = -up, position = position)
    }

    /** A detection with its yaw off by [yawError] (turning the whole plate about vertical). */
    fun detection(model: PlateFrame, truth: YawTransform, yawError: Double, offset: Vec3): PlateFrame =
        detection(model, YawTransform(yaw = truth.yaw + yawError, translation = truth.translation), offset = offset)
            .moved(to = truth.apply(model.position) + offset)
}

fun PlateFrame.moved(to: Vec3): PlateFrame = PlateFrame(position = to, normal = normal, up = up)

/**
 * A small deterministic generator, so noisy tests are repeatable. The same LCG as iOS, and
 * Swift's `Double.random(in:using:)` and `next(upperBound:)`, so the noise is the same numbers.
 */
class SeededRandom(seed: ULong) {
    private var state: ULong = seed

    fun next(): ULong {
        state = state * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL
        return state
    }

    /** Swift's `RandomNumberGenerator.next(upperBound:)` (Lemire's multiply-shift with rejection). */
    private fun next(upperBound: ULong): ULong {
        var random = next()
        var high = Math.unsignedMultiplyHigh(random.toLong(), upperBound.toLong()).toULong()
        var low = random * upperBound
        if (low < upperBound) {
            val t = (0uL - upperBound) % upperBound
            while (low < t) {
                random = next()
                high = Math.unsignedMultiplyHigh(random.toLong(), upperBound.toLong()).toULong()
                low = random * upperBound
            }
        }
        return high
    }

    /** Uniform in −amplitude…amplitude (Swift's `Double.random(in: ClosedRange)`). */
    fun uniform(amplitude: Double): Double {
        val lower = -amplitude
        val delta = amplitude - lower
        val rand = next(upperBound = (1uL shl 53) + 1uL)
        val unit = rand.toDouble() * (Math.ulp(1.0) / 2)
        return delta * unit + lower
    }

    fun vector(amplitude: Double): Vec3 = Vec3(uniform(amplitude), uniform(amplitude), uniform(amplitude))
}

fun expectClose(
    actual: YawTransform?,
    expected: YawTransform,
    position: Double = SyntheticPlates.MILLIMETRE,
    angle: Double = SyntheticPlates.TENTH_OF_DEGREE,
) {
    if (actual == null) fail("Expected a transform")
    assertTrue(actual.yawDifference(to = expected) <= angle, "yaw ${actual.yaw} vs ${expected.yaw}")
    assertTrue(
        Vector.distance(actual.translation, expected.translation) <= position,
        "translation ${actual.translation} vs ${expected.translation}",
    )
}

@DisplayName("Plate alignment: the 4-DoF solver")
class PlateAlignmentSolverTest {
    @Test
    @DisplayName("ARImageAnchor axes: right = x, up = −z, normal = y, and right = up × normal")
    fun `ARImageAnchor axes - right = x, up = -z, normal = y, and right = up x normal`() {
        val frame = PlateFrame.fromAnchor(
            anchorX = Vec3(1.0, 0.0, 0.0), anchorY = Vec3(0.0, 0.0, 1.0), anchorZ = Vec3(0.0, -1.0, 0.0), position = Vec3.zero,
        )
        assertEquals(Vec3(0.0, 0.0, 1.0), frame.normal)
        assertEquals(Vec3(0.0, 1.0, 0.0), frame.up)
        assertEquals(Vec3(1.0, 0.0, 0.0), frame.right)
        assertEquals(PlateFrame.Surface.WALL, frame.surface)
    }

    @ParameterizedTest
    @ValueSource(doubles = [-180.0, -135.0, -90.0, -30.0, 0.0, 17.0, 45.0, 90.0, 120.0, 179.5])
    @DisplayName("Wall plates: known yaw and translation back within 1 mm and 0.1°")
    fun `Wall plates - known yaw and translation back within 1 mm and 0,1 deg`(degrees: Double) {
        val truth = YawTransform(yaw = SyntheticPlates.degrees(degrees), translation = Vec3(3.25, -0.02, -7.5))
        val world = SyntheticPlates.detection(SyntheticPlates.wall, truth)
        expectClose(PlateAlignment.solve(model = SyntheticPlates.wall, world = world), truth)
    }

    @ParameterizedTest
    @ValueSource(doubles = [-180.0, -135.0, -90.0, -30.0, 0.0, 17.0, 45.0, 90.0, 120.0, 179.5])
    fun `Floor and ceiling plates use the image's up`(degrees: Double) {
        val truth = YawTransform(yaw = SyntheticPlates.degrees(degrees), translation = Vec3(-1.0, 0.4, 2.0))
        for (model in listOf(SyntheticPlates.floor, SyntheticPlates.ceiling)) {
            assertEquals(PlateFrame.Surface.HORIZONTAL, model.surface)
            val world = SyntheticPlates.detection(model, truth)
            expectClose(PlateAlignment.solve(model = model, world = world), truth)
        }
    }

    @Test
    fun `A slightly tilted or rolled plate doesn't tilt or turn the building`() {
        val truth = YawTransform(yaw = SyntheticPlates.degrees(63.0), translation = Vec3(0.4, 0.0, 1.1))
        // Tilt (top leaning out) keeps the normal's heading: the yaw is exact.
        val tilted = SyntheticPlates.detection(SyntheticPlates.wall, truth, tilt = SyntheticPlates.degrees(8.0))
        expectClose(PlateAlignment.solve(model = SyntheticPlates.wall, world = tilted), truth)
        // Roll about the normal (a plate hung crooked) doesn't move a wall plate's normal at all.
        val rolled = SyntheticPlates.detection(SyntheticPlates.wall, truth, roll = SyntheticPlates.degrees(10.0))
        expectClose(PlateAlignment.solve(model = SyntheticPlates.wall, world = rolled), truth)
        // The result is a pure yaw: the model's up stays the world's up.
        val solved = assertNotNull(PlateAlignment.solve(model = SyntheticPlates.wall, world = tilted))
        assertEquals(Vec3(0.0, 1.0, 0.0), solved.apply(Vec3(0.0, 1.0, 0.0)) - solved.apply(Vec3.zero))
    }

    @Test
    fun `A detection on the wrong kind of surface is rejected`() {
        val truth = YawTransform(yaw = 0.3, translation = Vec3.zero)
        // The wall plate seen lying flat (e.g. a reflection or a copy on a table).
        val flat = PlateFrame(position = Vec3(0.0, 0.8, 0.0), normal = Vec3(0.0, 1.0, 0.0), up = Vec3(0.0, 0.0, -1.0))
        assertNull(PlateAlignment.solve(model = SyntheticPlates.wall, world = flat))
        // A floor plate seen on a wall.
        val upright = SyntheticPlates.detection(SyntheticPlates.wall, truth)
        assertNull(PlateAlignment.solve(model = SyntheticPlates.floor, world = upright))
    }

    @Test
    fun `Headings follow YawTransform's rotation convention`() {
        assertEquals(0.0, PlateAlignment.angle(Vec3(0.0, 0.0, 1.0)))
        assertTrue(abs(PlateAlignment.angle(Vec3(1.0, 0.0, 0.0)) - PI / 2) < 1e-12)
        val turned = YawTransform.rotate(Vec3(0.0, 0.0, 1.0), by = 0.7)
        assertTrue(abs(PlateAlignment.angle(turned) - 0.7) < 1e-12)
    }
}

@DisplayName("Plate alignment: smoothing and outliers")
class AlignmentSmootherTest {
    private val truth = YawTransform(yaw = SyntheticPlates.degrees(-42.0), translation = Vec3(2.0, 0.01, -4.0))

    @Test
    fun `Nothing until the window is full`() {
        val smoother = AlignmentSmoother(plate = 1, model = SyntheticPlates.wall)
        val world = SyntheticPlates.detection(SyntheticPlates.wall, truth)
        repeat(AlignmentSmoother.WINDOW_SIZE - 1) {
            assertNull(smoother.add(world))
        }
        val solution = smoother.add(world)
        expectClose(solution?.transform, truth)
        assertEquals(AlignmentSmoother.WINDOW_SIZE, solution?.inliers)
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 7, 42, 2026])
    fun `Noise within tolerance averages out`(seed: Long) {
        val random = SeededRandom(seed = seed.toULong())
        val smoother = AlignmentSmoother(plate = 1, model = SyntheticPlates.wall)
        var solution: AlignmentSmoother.Solution? = null
        repeat(AlignmentSmoother.WINDOW_SIZE) {
            val world = SyntheticPlates.detection(
                SyntheticPlates.wall, truth, yawError = SyntheticPlates.degrees(random.uniform(0.8)), offset = random.vector(0.006),
            )
            solution = smoother.add(world)
        }
        val result = assertNotNull(solution)
        assertEquals(AlignmentSmoother.WINDOW_SIZE, result.inliers)
        // The plate's own position lands within 1 cm, the yaw within half a degree.
        val plate = result.transform.apply(SyntheticPlates.wall.position)
        assertTrue(Vector.distance(plate, truth.apply(SyntheticPlates.wall.position)) < 0.01)
        assertTrue(result.transform.yawDifference(to = truth) < SyntheticPlates.degrees(0.5))
    }

    @Test
    @DisplayName("Outliers more than 3 cm or 2° from the median are dropped")
    fun `Outliers more than 3 cm or 2 deg from the median are dropped`() {
        val random = SeededRandom(seed = 99uL)
        val smoother = AlignmentSmoother(plate = 1, model = SyntheticPlates.wall)
        var solution: AlignmentSmoother.Solution? = null
        for (index in 0 until AlignmentSmoother.WINDOW_SIZE) {
            val world = when (index) {
                2 -> SyntheticPlates.detection(SyntheticPlates.wall, truth, yawError = 0.0, offset = Vec3(0.12, 0.0, 0.0))
                5 -> SyntheticPlates.detection(SyntheticPlates.wall, truth, yawError = SyntheticPlates.degrees(9.0), offset = Vec3.zero)
                8 -> SyntheticPlates.detection(SyntheticPlates.wall, truth, yawError = SyntheticPlates.degrees(-4.0), offset = Vec3(0.0, 0.05, 0.05))
                else -> SyntheticPlates.detection(SyntheticPlates.wall, truth, yawError = 0.0, offset = random.vector(0.002))
            }
            solution = smoother.add(world)
        }
        val result = assertNotNull(solution)
        assertEquals(7, result.inliers)
        expectClose(result.transform, truth, position = 0.005, angle = SyntheticPlates.TENTH_OF_DEGREE)
        assertTrue(result.positionSpread <= AlignmentSmoother.POSITION_TOLERANCE)
        assertTrue(result.yawSpread <= AlignmentSmoother.ANGLE_TOLERANCE)
    }

    @Test
    @DisplayName("Too many outliers: no lock until enough good detections slide in")
    fun `Too many outliers - no lock until enough good detections slide in`() {
        val smoother = AlignmentSmoother(plate = 1, model = SyntheticPlates.wall)
        val good = SyntheticPlates.detection(SyntheticPlates.wall, truth)
        // Every other detection is 20 cm off in a different direction.
        for (index in 0 until AlignmentSmoother.WINDOW_SIZE) {
            val world = if (index % 2 == 0) good else good.moved(to = good.position + Vec3(0.2 * (index % 3 - 1).toDouble(), 0.2, 0.0))
            assertNull(smoother.add(world))
        }
        var converged: AlignmentSmoother.Solution? = null
        for (i in 0 until AlignmentSmoother.WINDOW_SIZE) {
            if (converged != null) continue
            converged = smoother.add(good)
        }
        expectClose(converged?.transform, truth)
    }

    @Test
    @DisplayName("Yaws around ±180° average across the wrap")
    fun `Yaws around +-180 deg average across the wrap`() {
        val truth = YawTransform(yaw = SyntheticPlates.degrees(179.8), translation = Vec3(1.0, 0.0, 1.0))
        val smoother = AlignmentSmoother(plate = 1, model = SyntheticPlates.wall)
        var solution: AlignmentSmoother.Solution? = null
        for (index in 0 until AlignmentSmoother.WINDOW_SIZE) {
            // Alternating +0.5° and −0.5°: half the samples read about −179.7°.
            val error = SyntheticPlates.degrees(if (index % 2 == 0) 0.5 else -0.5)
            solution = smoother.add(SyntheticPlates.detection(SyntheticPlates.wall, truth, yawError = error, offset = Vec3.zero))
        }
        val result = assertNotNull(solution)
        assertEquals(AlignmentSmoother.WINDOW_SIZE, result.inliers)
        expectClose(result.transform, truth, position = 0.002)
    }

    @Test
    fun `Detections the solver can't use are counted, not kept`() {
        val smoother = AlignmentSmoother(plate = 1, model = SyntheticPlates.wall)
        val flat = PlateFrame(position = Vec3.zero, normal = Vec3(0.0, 1.0, 0.0), up = Vec3(0.0, 0.0, -1.0))
        assertNull(smoother.add(flat))
        assertEquals(1, smoother.rejected)
        assertTrue(smoother.samples.isEmpty())
    }

    @Test
    fun `Circular statistics`() {
        val near180 = listOf(SyntheticPlates.degrees(179.0), SyntheticPlates.degrees(-179.0))
        assertTrue(abs(abs(AlignmentSmoother.circularMean(near180)) - PI) < 1e-9)
        assertEquals(2.0, AlignmentSmoother.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, AlignmentSmoother.median(listOf(4.0, 1.0, 2.0, 3.0)))
        val angles = listOf(0.1, 0.11, 0.12, 2.5)
        assertTrue(abs(AlignmentSmoother.circularMedian(angles) - 0.115) < 0.02)
    }
}

@DisplayName("Plate alignment: re-anchoring blend")
class AlignmentBlendTest {
    @Test
    fun `Starts at the old alignment, ends at the new one, pivot on a straight line`() {
        val from = YawTransform(yaw = SyntheticPlates.degrees(10.0), translation = Vec3(1.0, 0.0, 1.0))
        val to = YawTransform(yaw = SyntheticPlates.degrees(12.0), translation = Vec3(1.05, 0.0, 0.98))
        val pivot = Vec3(3.0, 1.5, -2.0)
        val blend = AlignmentBlend(from = from, to = to, pivot = pivot, start = 100.0)
        expectClose(blend.transform(at = 99.0), from, position = 1e-9, angle = 1e-9)
        expectClose(blend.transform(at = 100.0), from, position = 1e-9, angle = 1e-9)
        expectClose(blend.transform(at = 100.5), to, position = 1e-9, angle = 1e-9)
        assertTrue(!blend.isFinished(at = 100.49))
        assertTrue(blend.isFinished(at = 100.5))

        val middle = blend.transform(at = 100.25)
        val expected = (from.apply(pivot) + to.apply(pivot)) / 2.0
        assertTrue(Vector.distance(middle.apply(pivot), expected) < 1e-9)
        assertTrue(abs(middle.yaw - SyntheticPlates.degrees(11.0)) < 1e-9)
    }

    @Test
    @DisplayName("Turns the short way across ±180°")
    fun `Turns the short way across +-180 deg`() {
        val from = YawTransform(yaw = SyntheticPlates.degrees(179.0), translation = Vec3.zero)
        val to = YawTransform(yaw = SyntheticPlates.degrees(-179.0), translation = Vec3.zero)
        val halfway = YawTransform.interpolated(from = from, to = to, fraction = 0.5, pivot = Vec3.zero)
        assertTrue(abs(abs(halfway.yaw) - PI) < 1e-9)
    }
}
