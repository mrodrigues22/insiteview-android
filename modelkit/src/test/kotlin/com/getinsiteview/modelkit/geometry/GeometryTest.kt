package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.NodeNames
import com.getinsiteview.modelkit.floorHeight
import com.getinsiteview.modelkit.intersectFloor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private fun close(a: Vec3, b: Vec3, tolerance: Double = 1e-9): Boolean =
    abs(a.x - b.x) <= tolerance && abs(a.y - b.y) <= tolerance && abs(a.z - b.z) <= tolerance

private fun close(a: Double, b: Double, tolerance: Double = 1e-9): Boolean = abs(a - b) <= tolerance

private const val DEGREE = PI / 180

@DisplayName("Bounds and picking shapes")
class BoundsTest {
    @Test
    fun `Centre, extents, radius and union`() {
        val box = Bounds(min = Vec3(-2.0, 0.0, -1.0), max = Vec3(2.0, 3.0, 1.0))
        assertEquals(Vec3(0.0, 1.5, 0.0), box.center)
        assertEquals(Vec3(4.0, 3.0, 2.0), box.extents)
        assertTrue(close(box.radius, sqrt(16.0 + 9 + 4) / 2))
        val other = Bounds(min = Vec3(1.0, -1.0, 0.0), max = Vec3(5.0, 1.0, 4.0))
        assertEquals(Bounds(min = Vec3(-2.0, -1.0, -1.0), max = Vec3(5.0, 3.0, 4.0)), box.union(other))
        assertEquals(box.union(other), Bounds.union(listOf(box, other)))
        assertNull(Bounds.union(emptyList()))
    }

    @Test
    @DisplayName("Thin runs get a grown box; diagonal system runs get their convex hull")
    fun `Thin runs get a grown box, diagonal system runs get their convex hull`() {
        // A 16 mm conduit along X.
        val conduit = Bounds(min = Vec3(0.0, 1.0, 0.0), max = Vec3(3.0, 1.016, 0.016))
        assertEquals(
            PickingShape.Box(center = conduit.center, size = Vec3(3.0, PickingShape.MINIMUM_SIZE, PickingShape.MINIMUM_SIZE)),
            PickingShape.forElement(bounds = conduit, isSystemElement = true),
        )
        // A pipe at 45° in the XY plane.
        val diagonal = Bounds(min = Vec3(0.0, 0.0, 0.0), max = Vec3(3.0, 3.0, 0.05))
        assertEquals(PickingShape.ConvexHull, PickingShape.forElement(bounds = diagonal, isSystemElement = true))
        // A wall is architecture: always a box.
        val wall = Bounds(min = Vec3(0.0, 0.0, 0.0), max = Vec3(4.0, 2.6, 0.1))
        assertEquals(PickingShape.Box(center = wall.center, size = Vec3(4.0, 2.6, 0.1)), PickingShape.forElement(bounds = wall, isSystemElement = false))
        // A duct is thick enough for its box.
        val duct = Bounds(min = Vec3(0.0, 0.0, 0.0), max = Vec3(5.0, 0.6, 0.3))
        assertEquals(PickingShape.Box(center = duct.center, size = duct.extents), PickingShape.forElement(bounds = duct, isSystemElement = true))
    }
}

@DisplayName("Orbit camera")
class OrbitCameraTest {
    @Test
    fun `Position from yaw, pitch and distance`() {
        val front = OrbitCamera(target = Vec3.zero, yaw = 0.0, pitch = 0.0, distance = 5.0)
        assertTrue(close(front.position, Vec3(0.0, 0.0, 5.0)))
        assertTrue(close(front.forward, Vec3(0.0, 0.0, -1.0)))
        assertTrue(close(front.right, Vec3(1.0, 0.0, 0.0)))
        assertTrue(close(front.up, Vec3(0.0, 1.0, 0.0)))

        val side = OrbitCamera(target = Vec3(1.0, 2.0, 3.0), yaw = PI / 2, pitch = 0.0, distance = 2.0)
        assertTrue(close(side.position, Vec3(3.0, 2.0, 3.0)))
        assertTrue(close(side.right, Vec3(0.0, 0.0, -1.0)))

        val above = OrbitCamera(target = Vec3.zero, yaw = 0.0, pitch = 45 * DEGREE, distance = sqrt(2.0))
        assertTrue(close(above.position, Vec3(0.0, 1.0, 1.0)))
        assertTrue(above.up.y > 0 && above.up.z < 0)
    }

    @Test
    fun `Pitch and distance stay in range`() {
        val camera = OrbitCamera(target = Vec3.zero, yaw = 0.0, pitch = 0.0, distance = 10.0)
        camera.orbit(dx = 0.3, dy = 3.0)
        assertTrue(close(camera.yaw, -0.3))
        assertTrue(close(camera.pitch, 89 * DEGREE))
        camera.orbit(dx = 0.0, dy = -10.0)
        assertTrue(close(camera.pitch, -10 * DEGREE))
        camera.zoom(scale = 1000.0)
        assertEquals(0.3, camera.distance)
        camera.zoom(scale = 0.0001)
        assertEquals(500.0, camera.distance)
        camera.zoom(scale = 0.0)
        camera.zoom(scale = Double.POSITIVE_INFINITY)
        assertEquals(500.0, camera.distance)
    }

    @Test
    fun `Panning follows the fingers at any distance`() {
        val camera = OrbitCamera(target = Vec3.zero, yaw = 0.0, pitch = 0.0, distance = 10.0)
        val fov = 60 * DEGREE
        val viewHeight = 2 * 10 * tan(fov / 2)
        camera.pan(dx = 0.5, dy = 0.0, fieldOfView = fov)
        assertTrue(close(camera.target, Vec3(-0.5 * viewHeight, 0.0, 0.0)))
        camera.pan(dx = 0.0, dy = 0.25, fieldOfView = fov)
        assertTrue(close(camera.target, Vec3(-0.5 * viewHeight, 0.25 * viewHeight, 0.0)))
    }

    @Test
    fun `Fitting shows the whole model in the narrower field of view`() {
        val bounds = Bounds(min = Vec3(-2.15, -0.2, -1.9), max = Vec3(2.15, 2.6, 1.9))
        val fov = 60 * DEGREE
        val portrait = OrbitCamera.fitting(bounds, verticalFieldOfView = fov, aspectRatio = 0.5)
        val landscape = OrbitCamera.fitting(bounds, verticalFieldOfView = fov, aspectRatio = 2.0)
        assertEquals(bounds.center, portrait.target)
        assertTrue(close(landscape.distance, bounds.radius / sin(fov / 2) * 1.1))
        assertTrue(portrait.distance > landscape.distance)
        // The bounding sphere fits: its angular radius is within half the horizontal FOV.
        val horizontal = 2 * atan(tan(fov / 2) * 0.5)
        assertTrue(asin(bounds.radius / portrait.distance) <= horizontal / 2 + 1e-9)

        val camera = portrait.copy()
        val far = Bounds(min = Vec3(-400.0, 0.0, -400.0), max = Vec3(400.0, 10.0, 400.0))
        camera.refit(far, verticalFieldOfView = fov, aspectRatio = 0.5)
        assertEquals(far.center, camera.target)
        assertTrue(camera.distance > 500)
    }
}

@DisplayName("Manual alignment")
class ManualAlignmentTest {
    @Test
    @DisplayName("Yaw rotation maps model +Z to (sin θ, 0, cos θ), and apply/inverse round-trip")
    fun `Yaw rotation maps model +Z to (sin theta, 0, cos theta), and apply and inverse round-trip`() {
        assertTrue(close(YawTransform.rotate(Vec3(0.0, 0.0, 1.0), by = PI / 2), Vec3(1.0, 0.0, 0.0)))
        val transform = YawTransform(yaw = 30 * DEGREE, translation = Vec3(1.0, 0.5, -2.0))
        val point = Vec3(0.3, 1.2, -4.0)
        assertTrue(close(transform.inverseApply(transform.apply(point)), point))
        assertTrue(close(YawTransform.normalized(3 * PI), PI))
        assertTrue(close(YawTransform.normalized(-3 * PI / 2), PI / 2))
    }

    @Test
    fun `The model's front faces the camera`() {
        // Camera looking along −Z: the model's +Z points back at the camera.
        assertTrue(close(ManualAlignment.yaw(facing = Vec3(0.0, 0.0, -1.0)), 0.0))
        // Camera looking along +X: the model's +Z must point to −X.
        val yaw = ManualAlignment.yaw(facing = Vec3(1.0, -0.3, 0.0))
        assertTrue(close(YawTransform.rotate(Vec3(0.0, 0.0, 1.0), by = yaw), Vec3(-1.0, 0.0, 0.0)))
    }

    @Test
    fun `Placing a storey floor on the tapped plane`() {
        val hit = Vec3(0.4, -1.35, -2.0)
        val transform = ManualAlignment.placingFloor(elevation = 2.8, at = hit, cameraForward = Vec3(1.0, -0.5, 0.0))
        // The storey floor centre (model (0, elevation, 0)) lands on the plane point.
        assertTrue(close(transform.apply(Vec3(0.0, 2.8, 0.0)), hit))
        // Anything on that floor is at plane height.
        assertTrue(close(transform.apply(Vec3(1.5, 2.8, -0.7)).y, hit.y))
    }

    @Test
    fun `Dragging moves along the floor only`() {
        val start = YawTransform(yaw = 0.3, translation = Vec3(1.0, -1.4, 2.0))
        val moved = ManualAlignment.dragged(start, from = Vec3(0.0, -1.4, 0.0), to = Vec3(0.5, -1.2, -0.25))
        assertTrue(close(moved.translation, Vec3(1.5, -1.4, 1.75)))
        assertEquals(start.yaw, moved.yaw)
    }

    @Test
    fun `Twisting turns about the pivot, which stays put`() {
        val start = YawTransform(yaw = 0.0, translation = Vec3(2.0, 0.0, 0.0))
        val pivot = Vec3(3.0, 0.0, 1.0)
        val modelPointAtPivot = start.inverseApply(pivot)
        val turned = start.rotated(by = 25 * DEGREE, about = pivot)
        assertTrue(close(turned.apply(modelPointAtPivot), pivot))
        assertTrue(close(turned.yaw, 25 * DEGREE))
    }

    @Test
    @DisplayName("Fine-tune steps are relative to the camera: 1 cm and 0.5°")
    fun `Fine-tune steps are relative to the camera - 1 cm and 0,5 deg`() {
        val start = YawTransform(yaw = 0.0, translation = Vec3.zero)
        val forward = Vec3(0.0, -0.4, -1.0) // looking along −Z, tilted down
        val pivot = Vec3(0.0, 0.0, -2.0)
        fun step(nudge: ManualAlignment.Nudge): YawTransform =
            ManualAlignment.nudged(start, nudge, cameraForward = forward, pivot = pivot)
        assertTrue(close(step(ManualAlignment.Nudge.FORWARD).translation, Vec3(0.0, 0.0, -0.01)))
        assertTrue(close(step(ManualAlignment.Nudge.BACK).translation, Vec3(0.0, 0.0, 0.01)))
        assertTrue(close(step(ManualAlignment.Nudge.RIGHT).translation, Vec3(0.01, 0.0, 0.0)))
        assertTrue(close(step(ManualAlignment.Nudge.LEFT).translation, Vec3(-0.01, 0.0, 0.0)))
        assertTrue(close(step(ManualAlignment.Nudge.UP).translation, Vec3(0.0, 0.01, 0.0)))
        assertTrue(close(step(ManualAlignment.Nudge.DOWN).translation, Vec3(0.0, -0.01, 0.0)))
        assertTrue(close(step(ManualAlignment.Nudge.TURN_LEFT).yaw, 0.5 * DEGREE))
        assertTrue(close(step(ManualAlignment.Nudge.TURN_RIGHT).yaw, -0.5 * DEGREE))
        assertTrue(close(step(ManualAlignment.Nudge.TURN_LEFT).apply(start.inverseApply(pivot)), pivot))
        // Straight down: fall back to −Z instead of dividing by zero.
        val down = ManualAlignment.nudged(start, ManualAlignment.Nudge.FORWARD, cameraForward = Vec3(0.0, -1.0, 0.0), pivot = pivot)
        assertTrue(close(down.translation, Vec3(0.0, 0.0, -0.01)))
    }
}

@DisplayName("Node names and floor rays")
class NodeNameTest {
    @Test
    fun `Element, storey and chunk names`() {
        assertTrue(NodeNames.isElement("e22dff25b5a264301813afd2f07c0ff68"))
        assertFalse(NodeNames.isElement("e22dff25b5a264301813afd2f07c0ff6"))
        assertFalse(NodeNames.isElement("E22DFF25B5A264301813AFD2F07C0FF68"))
        assertFalse(NodeNames.isElement("x22dff25b5a264301813afd2f07c0ff68"))
        assertFalse(NodeNames.isElement("mesh_0"))
        assertEquals(0, NodeNames.storeyOrder("s0"))
        assertEquals(12, NodeNames.storeyOrder("s12"))
        assertNull(NodeNames.storeyOrder("s"))
        assertNull(NodeNames.storeyOrder("s1a"))
        assertNull(NodeNames.storeyOrder("storey"))
        assertEquals("chunk_electrical", NodeNames.chunkRoot("electrical"))
    }

    @Test
    fun `A camera ray meets the floor plane in front of it`() {
        val hit = assertNotNull(ManualAlignment.intersectFloor(origin = Vec3(0.0, 1.5, 0.0), direction = Vec3(0.0, -1.0, -1.0), height = 0.0))
        assertTrue(close(hit, Vec3(0.0, 0.0, -1.5)))
        assertNull(ManualAlignment.intersectFloor(origin = Vec3(0.0, 1.5, 0.0), direction = Vec3(0.0, 1.0, -1.0), height = 0.0))
        assertNull(ManualAlignment.intersectFloor(origin = Vec3(0.0, 1.5, 0.0), direction = Vec3(1.0, 0.0, 0.0), height = 0.0))
        assertEquals(-4.2 + 2.8, ManualAlignment.floorHeight(YawTransform(yaw = 0.0, translation = Vec3(0.0, -4.2, 0.0)), elevation = 2.8))
    }
}
