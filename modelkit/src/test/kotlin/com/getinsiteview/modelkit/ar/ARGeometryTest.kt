package com.getinsiteview.modelkit.ar

import com.getinsiteview.modelkit.geometry.AlignmentSite
import com.getinsiteview.modelkit.geometry.LidarAim
import com.getinsiteview.modelkit.geometry.PlateAnchoring
import com.getinsiteview.modelkit.geometry.PlateFrame
import com.getinsiteview.modelkit.geometry.ReferenceMark
import com.getinsiteview.modelkit.geometry.Vec2
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.Vector
import com.getinsiteview.modelkit.geometry.YawTransform
import com.getinsiteview.modelkit.scene.Matrix4
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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

private fun near(a: Double, b: Double, tolerance: Double = 1e-6) = abs(a - b) <= tolerance

private fun near(a: Vec3, b: Vec3, tolerance: Double = 1e-6) = Vector.distance(a, b) <= tolerance

/** A column-major perspective projection (OpenGL/Filament/ARCore convention). */
internal fun perspective(verticalFov: Double, aspect: Double, near: Double = 0.01, far: Double = 1000.0): FloatArray {
    val f = 1 / tan(verticalFov / 2)
    val m = DoubleArray(16)
    m[0] = f / aspect
    m[5] = f
    m[10] = (far + near) / (near - far)
    m[11] = -1.0
    m[14] = 2 * far * near / (near - far)
    return Matrix4.toFloats(m)
}

/** The view matrix (world → camera) of a camera at [eye] looking along [forward] with [up]. */
internal fun viewMatrix(eye: Vec3, forward: Vec3, up: Vec3): FloatArray {
    val f = Vector.normalized(forward)
    val r = Vector.normalized(Vector.cross(f, up))
    val u = Vector.cross(r, f)
    val cameraToWorld = Matrix4.fromColumns(x = r, y = u, z = -f, translation = eye)
    return Matrix4.toFloats(Matrix4.invert(cameraToWorld)!!)
}

@DisplayName("AR value types")
class ARTypesTest {
    @Test
    @DisplayName("Tracking maps ARCore's states like ARKit's")
    fun `Tracking maps ARCore's states like ARKit's`() {
        assertEquals(TrackingStatus.NORMAL, TrackingStatus.of(CameraTrackingState.TRACKING, CameraTrackingFailure.NONE))
        assertEquals(TrackingStatus.NORMAL, TrackingStatus.of(CameraTrackingState.TRACKING, CameraTrackingFailure.NONE, relocalizing = true))
        assertEquals(TrackingStatus.NOT_AVAILABLE, TrackingStatus.of(CameraTrackingState.STOPPED, CameraTrackingFailure.NONE))
        assertEquals(TrackingStatus.INITIALIZING, TrackingStatus.of(CameraTrackingState.PAUSED, CameraTrackingFailure.NONE))
        assertEquals(TrackingStatus.EXCESSIVE_MOTION, TrackingStatus.of(CameraTrackingState.PAUSED, CameraTrackingFailure.EXCESSIVE_MOTION))
        assertEquals(TrackingStatus.INSUFFICIENT_FEATURES, TrackingStatus.of(CameraTrackingState.PAUSED, CameraTrackingFailure.INSUFFICIENT_FEATURES))
        assertEquals(TrackingStatus.INSUFFICIENT_FEATURES, TrackingStatus.of(CameraTrackingState.PAUSED, CameraTrackingFailure.INSUFFICIENT_LIGHT))
        assertEquals(TrackingStatus.NOT_AVAILABLE, TrackingStatus.of(CameraTrackingState.PAUSED, CameraTrackingFailure.CAMERA_UNAVAILABLE))
        assertEquals(TrackingStatus.NOT_AVAILABLE, TrackingStatus.of(CameraTrackingState.PAUSED, CameraTrackingFailure.BAD_STATE))
        // After an interruption, anything short of tracking is relocalizing.
        assertEquals(TrackingStatus.RELOCALIZING, TrackingStatus.of(CameraTrackingState.PAUSED, CameraTrackingFailure.INSUFFICIENT_FEATURES, relocalizing = true))
    }

    @Test
    @DisplayName("The crosshair can aim once the floor is found and tracking is normal, and mark on the floor, a wall or a corner")
    fun `The crosshair can aim once the floor is found and tracking is normal, and mark on the floor, a wall or a corner`() {
        assertFalse(CrosshairState.NO_FLOOR.canAim)
        assertFalse(CrosshairState.NOT_TRACKING.canAim)
        assertTrue(CrosshairState.TOO_SHALLOW.canAim && !CrosshairState.TOO_SHALLOW.canMark)
        assertTrue(CrosshairState.NO_SURFACE.canAim && !CrosshairState.NO_SURFACE.canMark)
        assertTrue(CrosshairState.READY.canMark && CrosshairState.ON_WALL.canMark && CrosshairState.ON_CORNER.canMark)
    }

    @Test
    @DisplayName("Only adjusting and locked placements have a transform")
    fun `Only adjusting and locked placements have a transform`() {
        val t = YawTransform(yaw = 0.3, translation = Vec3(1.0, 0.0, 2.0))
        assertNull(PlacementState.FindingFloor.transform)
        assertNull(PlacementState.ReadyToPlace.transform)
        assertEquals(t, PlacementState.Adjusting(t).transform)
        assertEquals(t, PlacementState.Locked(t).transform)
        assertTrue(PlacementState.Adjusting(t) != PlacementState.Locked(t))
    }
}

@DisplayName("The floor: which plane, and when it moved")
class FloorPlanesTest {
    @Test
    @DisplayName("Small unclassified planes aren't candidates")
    fun `Small unclassified planes aren't candidates`() {
        assertNull(FloorPlanes.candidate(height = 0.0, area = 0.24))
        assertNotNull(FloorPlanes.candidate(height = 0.0, area = 0.25))
        assertNotNull(FloorPlanes.candidate(height = 0.0, area = 0.01, isFloor = true))
    }

    @Test
    @DisplayName("The lowest large plane, unless some are classified floor")
    fun `The lowest large plane, unless some are classified floor`() {
        val planes = FloorPlanes()
        assertNull(planes.worldFloorY)
        planes.update("table", FloorPlane(height = 0.7, area = 1.0, isFloor = false))
        planes.update("floor", FloorPlane(height = -1.2, area = 3.0, isFloor = false))
        assertEquals(-1.2, planes.worldFloorY)
        planes.update("reflection", FloorPlane(height = -1.29, area = 0.25, isFloor = false))
        assertEquals(-1.29, planes.worldFloorY, "without classification the lowest wins (the iOS fallback rule)")
        planes.update("classified", FloorPlane(height = -1.2, area = 3.0, isFloor = true))
        assertEquals(-1.2, planes.worldFloorY, "a classified floor wins over lower unclassified planes")
        planes.remove("classified")
        planes.remove("reflection")
        assertEquals(-1.2, planes.worldFloorY)
        assertEquals(2, planes.candidates.size)
    }

    @Test
    @DisplayName("Floor changes are told from 5 mm")
    fun `Floor changes are told from 5 mm`() {
        val notifier = FloorChangeNotifier()
        assertFalse(notifier.shouldReport(null))
        assertTrue(notifier.shouldReport(-1.0), "the first floor is always told")
        assertFalse(notifier.shouldReport(-1.004))
        assertTrue(notifier.shouldReport(-1.0051))
        assertEquals(-1.0051, notifier.reported)
        assertFalse(notifier.shouldReport(-1.001), "measured from the last told, not the first")
        assertTrue(notifier.shouldReport(-0.99))
    }
}

@DisplayName("The crosshair's target")
class CrosshairTargetingTest {
    private val floorY = -1.4
    private val eye = Vec3(0.0, 0.0, 0.0)

    private fun ray(angleFromDown: Double) = Ray(origin = eye, direction = Vec3(sin(angleFromDown), -cos(angleFromDown), 0.0))

    private fun target(
        ray: Ray?,
        floor: Double? = floorY,
        canTrack: Boolean = true,
        walls: Boolean = false,
        centre: LidarAim.Plane? = null,
        sides: (Vec3) -> LidarAim.Plane? = { null },
    ) = CrosshairTargeting.target(floor, canTrack, ray, walls, { centre }, sides)

    @Test
    @DisplayName("No floor first, even with wall aims, then tracking")
    fun `No floor first, even with wall aims, then tracking`() {
        assertEquals(CrosshairState.NO_FLOOR, target(ray(0.0), floor = null, walls = true, canTrack = false).state)
        assertEquals(CrosshairState.NOT_TRACKING, target(ray(0.0), canTrack = false).state)
        assertEquals(CrosshairState.NOT_TRACKING, target(null).state)
    }

    @Test
    @DisplayName("Within 35 degrees of straight down the floor is ready")
    fun `Within 35 degrees of straight down the floor is ready`() {
        val down = target(ray(0.0))
        assertEquals(CrosshairState.READY, down.state)
        val aim = assertNotNull(down.aim)
        assertTrue(near(aim.point, Vec3(0.0, floorY, 0.0)) && aim.eye == eye && aim.surface == ReferenceMark.Surface.FLOOR)
        val slanted = target(ray(34.9 * PI / 180))
        assertEquals(CrosshairState.READY, slanted.state)
        assertTrue(near(assertNotNull(slanted.aim).point.x, 1.4 * tan(34.9 * PI / 180)))
        assertEquals(CrosshairState.TOO_SHALLOW, target(ray(35.1 * PI / 180)).state)
        assertNull(target(ray(35.1 * PI / 180)).aim)
    }

    @Test
    @DisplayName("With wall aims, a shallow aim takes the wall or the corner")
    fun `With wall aims, a shallow aim takes the wall or the corner`() {
        val shallow = ray(80 * PI / 180)
        assertEquals(CrosshairState.NO_SURFACE, target(shallow, walls = true).state)
        // A wall facing −X at x = 2 (normal towards the camera at the origin).
        val wall = assertNotNull(LidarAim.Plane.of(point = Vec3(2.0, -0.5, 0.0), normal = Vec3(1.0, 0.0, 0.0), eye = eye))
        val onWall = target(shallow, walls = true, centre = wall)
        assertEquals(CrosshairState.ON_WALL, onWall.state)
        assertEquals(ReferenceMark.Surface.WALL_PLANE, onWall.aim?.surface)
        assertTrue(near(assertNotNull(onWall.aim?.normal), Vec3(-1.0, 0.0, 0.0)))
        // The side points: 0.18 m along the wall either side of the middle hit.
        val asked = ArrayList<Vec3>()
        target(shallow, walls = true, centre = wall, sides = { asked += it; null })
        assertEquals(2, asked.size)
        assertTrue(asked.all { near(Vector.distance(it, wall.point), LidarAim.SIDE_REACH) })
        // A second wall facing −Z at z = 0.05 meets the first near the middle: a corner, on the floor.
        val other = assertNotNull(LidarAim.Plane.of(point = Vec3(1.9, -0.5, 0.05), normal = Vec3(0.0, 0.0, 1.0), eye = eye))
        val corner = target(shallow, walls = true, centre = wall, sides = { if (it.z > 0) other else wall })
        assertEquals(CrosshairState.ON_CORNER, corner.state)
        val point = assertNotNull(corner.aim).point
        assertTrue(near(point, Vec3(2.0, floorY, 0.05)), "the corner goes on the floor: $point")
        assertEquals(ReferenceMark.Surface.EDGE, corner.aim?.surface)
    }

    @Test
    @DisplayName("The first raycast hit that is a wall")
    fun `The first raycast hit that is a wall`() {
        val floor = Vec3(1.0, -1.0, 0.0) to Vec3(0.0, 1.0, 0.0)
        val wall = Vec3(2.0, 0.0, 0.0) to Vec3(-1.0, 0.0, 0.0)
        assertNull(CrosshairTargeting.firstWall(listOf(floor), eye))
        assertEquals(Vec3(2.0, 0.0, 0.0), CrosshairTargeting.firstWall(listOf(floor, wall), eye)?.point)
    }

    @Test
    @DisplayName("Fifteen times a second, every frame while capturing")
    fun `Fifteen times a second, every frame while capturing`() {
        assertFalse(CrosshairTargeting.isDue(time = 10.05, lastUpdate = 10.0, capturing = false))
        assertTrue(CrosshairTargeting.isDue(time = 10.07, lastUpdate = 10.0, capturing = false))
        assertTrue(CrosshairTargeting.isDue(time = 10.01, lastUpdate = 10.0, capturing = true))
    }
}

@DisplayName("Mark capture")
class MarkCaptureTest {
    private val eye = Vec3(0.0, 0.0, 0.0)

    private fun floor(x: Double, z: Double = 0.0) = FloorAim(point = Vec3(x, -1.4, z), eye = eye)

    @Test
    @DisplayName("Broken or too few samples fail")
    fun `Broken or too few samples fail`() {
        val capture = MarkCapture()
        repeat(4) { capture.add(floor(0.0)) }
        assertNull(capture.result())
        capture.add(floor(0.0))
        assertNotNull(capture.result())
        capture.add(null)
        assertTrue(capture.broken)
        assertNull(capture.result(), "one frame without an aim breaks it")
    }

    @Test
    @DisplayName("Floor samples beyond 2 cm of the median are left out")
    fun `Floor samples beyond 2 cm of the median are left out`() {
        val samples = listOf(0.0, 0.002, -0.002, 0.004, 0.001, 0.05).map { floor(it) }
        val mark = assertNotNull(MarkCapture.reduce(samples, broken = false))
        assertTrue(near(mark.point.x, 0.001, 1e-9), "the 5 cm sample is hand shake: ${mark.point}")
        assertEquals(ReferenceMark.Surface.FLOOR, mark.surface)
        val shaky = listOf(0.0, 0.03, 0.06, 0.09, 0.12, 0.15).map { floor(it) }
        assertNull(MarkCapture.reduce(shaky, broken = false), "fewer than 5 within 2 cm")
    }

    @Test
    @DisplayName("Walls and corners average, a floor sample among them fails")
    fun `Walls and corners average, a floor sample among them fails`() {
        val wall = List(5) { FloorAim(point = Vec3(2.0, -0.5, 0.001 * it), eye = eye, surface = ReferenceMark.Surface.WALL_PLANE, normal = Vec3(-1.0, 0.0, 0.0)) }
        val mark = assertNotNull(MarkCapture.reduce(wall, broken = false))
        assertEquals(ReferenceMark.Surface.WALL_PLANE, mark.surface)
        assertTrue(near(assertNotNull(mark.normal), Vec3(-1.0, 0.0, 0.0)))
        assertNull(MarkCapture.reduce(wall + floor(0.0), broken = false))
        val corners = List(5) { FloorAim(point = Vec3(2.0, -1.4, 0.002 * it), eye = Vec3(0.0, 0.1 * it, 0.0), surface = ReferenceMark.Surface.EDGE) }
        val corner = assertNotNull(MarkCapture.reduce(corners, broken = false))
        assertEquals(ReferenceMark.Surface.EDGE, corner.surface)
        assertTrue(near(corner.eye, Vec3(0.0, 0.2, 0.0)), "the eye is the mean")
        assertNull(MarkCapture.reduce(corners.take(3) + wall.take(2), broken = false), "walls and corners don't mix")
    }

    @Test
    @DisplayName("The median of an even count is the mean of the middle two")
    fun `The median of an even count is the mean of the middle two`() {
        val median = MarkCapture.median(listOf(Vec3(1.0, 0.0, 4.0), Vec3(3.0, 1.0, 2.0), Vec3(2.0, 2.0, 3.0), Vec3(10.0, 3.0, 1.0)))
        assertEquals(Vec3(2.5, 1.5, 2.5), median)
    }
}

@DisplayName("Anchor math and screen geometry")
class AnchorMathTest {
    @Test
    @DisplayName("An image pose gives right = x, up = −z, normal = y")
    fun `An image pose gives right = x, up = −z, normal = y`() {
        // An image on a wall facing +Z: x right, y out of the wall, z down the image.
        val m = Matrix4.toFloats(
            Matrix4.fromColumns(x = Vec3(1.0, 0.0, 0.0), y = Vec3(0.0, 0.0, 1.0), z = Vec3(0.0, -1.0, 0.0), translation = Vec3(1.0, 1.5, -2.0)),
        )
        val frame = PlateFrame.fromAnchorTransform(m)
        assertTrue(near(frame.normal, Vec3(0.0, 0.0, 1.0)) && near(frame.up, Vec3(0.0, 1.0, 0.0)))
        assertTrue(near(frame.right, Vec3(1.0, 0.0, 0.0)))
        assertEquals(Vec3(1.0, 1.5, -2.0), frame.position)
        assertEquals(PlateFrame.Surface.WALL, frame.surface)
    }

    @Test
    @DisplayName("A yaw transform round-trips through its matrix")
    fun `A yaw transform round-trips through its matrix`() {
        for (yaw in listOf(0.0, 0.7, -2.5, PI)) {
            val transform = YawTransform(yaw = yaw, translation = Vec3(0.5, -1.0, 3.0))
            val back = YawTransform.fromAnchorTransform(transform.matrix)
            assertTrue(near(YawTransform.normalized(back.yaw - transform.yaw), 0.0, 1e-6), "yaw $yaw → ${back.yaw}")
            assertTrue(near(back.translation, transform.translation, 1e-6))
            // The matrix moves points as the transform does.
            val p = Vec3(1.0, 2.0, -0.5)
            assertTrue(near(Matrix4.transformPoint(Matrix4.of(transform.matrix), p), transform.apply(p), 1e-5))
        }
    }

    @Test
    @DisplayName("A tilted anchor keeps only its heading, z straight up falls back to x")
    fun `A tilted anchor keeps only its heading, z straight up falls back to x`() {
        val c = cos(0.4)
        val s = sin(0.4)
        // z = (0, 1, 0): yaw from x = (cos θ, 0, −sin θ).
        val m = Matrix4.toFloats(Matrix4.fromColumns(x = Vec3(c, 0.0, -s), y = Vec3(0.0, 0.0, 1.0), z = Vec3(0.0, 1.0, 0.0), translation = Vec3.zero))
        assertTrue(near(YawTransform.fromAnchorTransform(m).yaw, 0.4, 1e-6))
    }

    @Test
    @DisplayName("The quaternion turns about +Y by the yaw")
    fun `The quaternion turns about +Y by the yaw`() {
        val q = YawTransform(yaw = PI / 2, translation = Vec3.zero).quaternion
        assertTrue(near(q[1].toDouble(), sqrt(0.5), 1e-6) && near(q[3].toDouble(), sqrt(0.5), 1e-6) && q[0] == 0f && q[2] == 0f)
    }

    @Test
    @DisplayName("The ray through the middle of the screen goes where the camera looks, projection inverts it")
    fun `The ray through the middle of the screen goes where the camera looks, projection inverts it`() {
        val eye = Vec3(1.0, 1.5, 2.0)
        val forward = Vector.normalized(Vec3(0.2, -1.0, -0.3))
        val view = viewMatrix(eye, forward, up = Vec3(0.0, 0.0, -1.0))
        val projection = perspective(verticalFov = 60 * PI / 180, aspect = 0.5)
        val centre = assertNotNull(ScreenGeometry.ray(500.0, 1000.0, 1000.0, 2000.0, view, projection))
        assertTrue(near(centre.origin, eye, 1e-4), "${centre.origin}")
        assertTrue(near(centre.direction, forward, 1e-4), "${centre.direction}")
        val corner = assertNotNull(ScreenGeometry.ray(100.0, 300.0, 1000.0, 2000.0, view, projection))
        val point = corner.origin + corner.direction * 3.0
        val screen = assertNotNull(ScreenGeometry.project(point, 1000.0, 2000.0, view, projection))
        assertTrue(near(screen.x, 100.0, 1e-2) && near(screen.y, 300.0, 1e-2), "$screen")
        assertNull(ScreenGeometry.project(eye - forward, 1000.0, 2000.0, view, projection), "behind the camera")
        assertNull(ScreenGeometry.ray(1.0, 1.0, 0.0, 0.0, view, projection))
        // Camera space: straight ahead is −z.
        val ahead = ScreenGeometry.cameraSpacePoint(eye + forward * 2.0, view)
        assertTrue(near(ahead, Vec3(0.0, 0.0, -2.0), 1e-4), "$ahead")
    }

    @Test
    @DisplayName("A flat marker stands up to face a wall's normal")
    fun `A flat marker stands up to face a wall's normal`() {
        for (normal in listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0))) {
            val q = ScreenGeometry.rotationFromUp(normal)
            // Rotate +Y by q: v' = v + 2w(q×v) + 2q×(q×v).
            val u = Vec3(q[0].toDouble(), q[1].toDouble(), q[2].toDouble())
            val w = q[3].toDouble()
            val v = Vec3(0.0, 1.0, 0.0)
            val t = Vector.cross(u, v) * 2.0
            val rotated = v + t * w + Vector.cross(u, t)
            assertTrue(near(rotated, normal, 1e-6), "$normal → $rotated")
        }
    }
}

@DisplayName("Session settings, image names and site anchors")
class ARSessionSettingsTest {
    private fun settings(detection: Set<Int> = emptySet(), registration: String? = null, lean: Boolean = false, hot: Boolean = false, walls: Boolean = false) =
        ARSessionSettings(detection = detection, registration = registration, lean = lean, thermalLimited = hot, walls = walls)

    @Test
    @DisplayName("Images are forgotten when detection grows or a new registration image comes")
    fun `Images are forgotten when detection grows or a new registration image comes`() {
        assertFalse(ARSessionSettings.forgetsImages(null, settings()))
        assertTrue(ARSessionSettings.forgetsImages(null, settings(detection = setOf(1))))
        assertFalse(ARSessionSettings.forgetsImages(settings(detection = setOf(1, 2)), settings(detection = setOf(1))))
        assertTrue(ARSessionSettings.forgetsImages(settings(detection = setOf(1)), settings(detection = setOf(1, 2))))
        assertTrue(ARSessionSettings.forgetsImages(settings(), settings(registration = "register-3")))
        assertFalse(ARSessionSettings.forgetsImages(settings(registration = "register-3"), settings(registration = "register-3")))
        assertFalse(ARSessionSettings.forgetsImages(settings(registration = "register-3"), settings()))
    }

    @Test
    @DisplayName("What the configuration turns on")
    fun `What the configuration turns on`() {
        assertEquals(listOf("plate-1", "plate-2", "register-7"), settings(detection = setOf(2, 1), registration = "register-7").imageNames)
        assertTrue(settings().estimatesLight)
        assertFalse(settings(lean = true).estimatesLight)
        assertFalse(settings(hot = true).estimatesLight)
        assertTrue(settings(walls = true).findsVerticalPlanes)
        assertTrue(ARSessionSettings.aimsAtWalls(hasDepthSensor = true, thermalLimited = false, allowsWallAims = true))
        assertFalse(ARSessionSettings.aimsAtWalls(hasDepthSensor = false, thermalLimited = false, allowsWallAims = true))
        assertFalse(ARSessionSettings.aimsAtWalls(hasDepthSensor = true, thermalLimited = true, allowsWallAims = true))
        assertFalse(ARSessionSettings.aimsAtWalls(hasDepthSensor = true, thermalLimited = false, allowsWallAims = false))
    }

    @Test
    @DisplayName("When hot, the smallest format at 30 fps or less")
    fun `When hot, the smallest format at 30 fps or less`() {
        data class Format(val fps: Int, val width: Int, val height: Int)
        val formats = listOf(Format(60, 640, 480), Format(30, 1920, 1080), Format(30, 1280, 720), Format(24, 1440, 1080))
        assertEquals(Format(30, 1280, 720), CoolCameraFormat.choose(formats, { it.fps }, { it.width.toLong() * it.height }))
        assertNull(CoolCameraFormat.choose(listOf(Format(60, 640, 480)), { it.fps }, { it.width.toLong() * it.height }))
    }

    @Test
    @DisplayName("Image names route to registration or plates")
    fun `Image names route to registration or plates`() {
        assertEquals("plate-12", PlateImageName.plate(12))
        assertEquals("register-3", PlateImageName.register(3))
        assertEquals(PlateImageName.Route.Plate(12), PlateImageName.route("plate-12"))
        assertEquals(PlateImageName.Route.Registration, PlateImageName.route("register-3"))
        assertEquals(PlateImageName.Route.Other, PlateImageName.route("plate-x"))
        assertEquals(PlateImageName.Route.Other, PlateImageName.route("logo"))
    }

    @Test
    @DisplayName("Site anchors follow the sites")
    fun `Site anchors follow the sites`() {
        val keep = AlignmentSite(method = PlateAnchoring.Method.Points, transform = YawTransform.identity, around = Vec3.zero, camera = null, at = 0.0)
        val add = AlignmentSite(method = PlateAnchoring.Method.Points, transform = YawTransform.identity, around = Vec3(5.0, 0.0, 0.0), camera = null, at = 1.0)
        val gone = UUID.randomUUID()
        val plan = SiteAnchors.plan(existing = setOf(keep.id, gone), sites = listOf(keep, add))
        assertEquals(listOf(gone), plan.remove)
        assertEquals(listOf(add.id), plan.add.map { it.id })
    }
}
