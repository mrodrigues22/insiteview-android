package com.getinsiteview.modelkit.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Locate in AR")
class LocateGuideTest {
    /** A 60° × 90° view: focal = 1 / tan(half angle). */
    private val focalX = 1 / tan(30 * PI / 180)
    private val focalY = 1 / tan(45 * PI / 180)

    private fun near(a: Double, b: Double, tolerance: Double = 1e-9): Boolean = abs(a - b) <= tolerance

    @Test
    @DisplayName("Straight ahead: on screen, in the middle")
    fun `Straight ahead - on screen, in the middle`() {
        val indicator = LocateGuide.indicator(cameraSpacePoint = Vec3(0.0, 0.0, -3.0), distance = 2.9, focalX = focalX, focalY = focalY)
        assertTrue(indicator.isOnScreen && indicator.arrowAngle == null && indicator.distance == 2.9)
        val point = assertNotNull(indicator.screenPoint)
        assertTrue(near(point.x, 0.5) && near(point.y, 0.5))

        val upperRight = LocateGuide.indicator(cameraSpacePoint = Vec3(0.5, 1.0, -2.0), distance = 2.0, focalX = focalX, focalY = focalY)
        val corner = assertNotNull(upperRight.screenPoint)
        assertTrue(corner.x > 0.5 && corner.y < 0.5, "right of and above the centre (y grows down)")
    }

    @Test
    @DisplayName("Off to the side or above: the arrow points there")
    fun `Off to the side or above - the arrow points there`() {
        val right = LocateGuide.indicator(cameraSpacePoint = Vec3(5.0, 0.0, -1.0), distance = 5.0, focalX = focalX, focalY = focalY)
        assertFalse(right.isOnScreen)
        assertTrue(near(assertNotNull(right.arrowAngle), 0.0))
        val above = LocateGuide.indicator(cameraSpacePoint = Vec3(0.0, 4.0, -1.0), distance = 4.0, focalX = focalX, focalY = focalY)
        assertTrue(near(assertNotNull(above.arrowAngle), PI / 2))
        val lowerLeft = LocateGuide.indicator(cameraSpacePoint = Vec3(-3.0, -3.0, -1.0), distance = 4.0, focalX = focalX, focalY = focalY)
        val angle = assertNotNull(lowerLeft.arrowAngle)
        assertTrue(angle < -PI / 2 && angle > -PI, "down and to the left")
        val edge = LocateGuide.indicator(
            cameraSpacePoint = Vec3(0.55, 0.0, -1.0), distance = 1.0, focalX = focalX, focalY = focalY, margin = 0.1,
        )
        assertFalse(edge.isOnScreen, "inside the view but in the margin counts as off screen")
    }

    @Test
    @DisplayName("Behind the camera: turn left or right")
    fun `Behind the camera - turn left or right`() {
        val behindRight = LocateGuide.indicator(cameraSpacePoint = Vec3(0.2, 1.0, 3.0), distance = 3.0, focalX = focalX, focalY = focalY)
        assertFalse(behindRight.isOnScreen)
        assertTrue(near(assertNotNull(behindRight.arrowAngle), 0.0))
        val behindLeft = LocateGuide.indicator(cameraSpacePoint = Vec3(-0.2, 0.0, 3.0), distance = 3.0, focalX = focalX, focalY = focalY)
        assertTrue(near(assertNotNull(behindLeft.arrowAngle), PI))
    }

    @Test
    fun `Distance to the element's box, through the alignment`() {
        val box = Bounds(min = Vec3(1.0, 0.0, 1.0), max = Vec3(2.0, 1.0, 3.0))
        assertTrue(near(LocateGuide.distance(from = Vec3(1.5, 0.5, 2.0), to = box), 0.0), "inside")
        assertTrue(near(LocateGuide.distance(from = Vec3(0.0, 0.5, 2.0), to = box), 1.0))
        assertTrue(near(LocateGuide.distance(from = Vec3(4.0, 4.0, 9.0), to = box), 7.0), "(2, 3, 6) from the corner")
        // The model turned a quarter turn and moved: the camera is brought into model space.
        val transform = YawTransform(yaw = PI / 2, translation = Vec3(10.0, 0.0, 0.0))
        val camera = transform.apply(Vec3(0.0, 0.5, 2.0))
        assertTrue(near(LocateGuide.distance(camera = camera, to = box, placedBy = transform), 1.0, 1e-9))
    }

    @Test
    fun `The arrow sits on the inset edge in its direction`() {
        val right = LocateGuide.edgePosition(angle = 0.0, width = 400.0, height = 800.0, inset = 40.0)
        assertTrue(near(right.x, 360.0) && near(right.y, 400.0))
        val up = LocateGuide.edgePosition(angle = PI / 2, width = 400.0, height = 800.0, inset = 40.0)
        assertTrue(near(up.x, 200.0) && near(up.y, 40.0))
        val diagonal = LocateGuide.edgePosition(angle = -PI / 4, width = 400.0, height = 800.0, inset = 40.0)
        assertTrue(near(diagonal.x, 360.0) && near(diagonal.y, 560.0), "down-right hits the right edge first")
    }

    @Test
    @DisplayName("The pulse: 1 → 1.35 → 1")
    fun `The pulse - 1 to 1,35 to 1`() {
        assertTrue(near(LocateGuide.pulseScale(at = 0.0), 1.0))
        assertTrue(near(LocateGuide.pulseScale(at = LocateGuide.PULSE_PERIOD / 2), 1.35))
        assertTrue(near(LocateGuide.pulseScale(at = LocateGuide.PULSE_PERIOD * 3), 1.0, 1e-9))
    }
}
