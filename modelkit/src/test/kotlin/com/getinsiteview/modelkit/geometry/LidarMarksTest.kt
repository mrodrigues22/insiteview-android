package com.getinsiteview.modelkit.geometry

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Phones with a depth sensor (docs/PLAN.md §3 "LiDAR marks"): walls and wall corners at any height as marks. */
object LidarRoom {
    val truth = Rooms.truth
    val worldFloorY = truth.translation.y

    fun references(outline: RoomOutline): List<ReferencePoint> =
        ReferencePoints.make(outline = outline, objects = emptyList(), floorY = outline.floorY, walls = true)

    /**
     * A wall marked at model point [p] (on the wall), facing [inward], seen from model [eye];
     * its direction off by [turn] radians.
     */
    fun wall(p: Vec3, inward: Vec3, eye: Vec3, turn: Double = 0.0): ReferenceMark = ReferenceMark(
        position = truth.apply(p), surface = ReferenceMark.Surface.WALL_PLANE, seenFrom = truth.apply(eye),
        normal = YawTransform.rotate(inward, by = truth.yaw + turn),
    )

    /** A wall corner at model (x, z), measured 1.2 m up and put on the floor. */
    fun edge(x: Double, z: Double, eye: Vec3, noise: Vec3 = Vec3.zero): ReferenceMark =
        ReferenceMark(position = truth.apply(Vec3(x, 0.0, z)) + noise, surface = ReferenceMark.Surface.EDGE, seenFrom = truth.apply(eye))
}

@DisplayName("LiDAR marks: walls and corners at any height")
class LidarMarksTest {
    @Test
    fun `Walls become references with their direction and length`() {
        val references = LidarRoom.references(Rooms.ell)
        val walls = references.filter { it.kind == ReferencePoint.Kind.WALL }
        assertEquals(6, walls.size)
        val west = walls.firstOrNull { it.normal == Vec3(1.0, 0.0, 0.0) }
        assertTrue(west?.position == Vec3(0.0, 0.0, 2.0) && west.halfLength == 2.0)
        assertTrue(ReferencePoints.make(outline = Rooms.ell, objects = emptyList(), floorY = 0.0).all { it.kind == ReferencePoint.Kind.CORNER })
    }

    @Test
    @DisplayName("A wall and two corners place an L-shaped room; a wall and one corner can fit it two ways")
    fun `A wall and two corners place an L-shaped room, a wall and one corner can fit it two ways`() {
        val wall = LidarRoom.wall(Vec3(0.0, 1.4, 1.0), inward = Vec3(1.0, 0.0, 0.0), eye = Vec3(1.5, 1.35, 1.0))
        val corner = LidarRoom.edge(4.0, 4.0, eye = Vec3(3.0, 1.35, 3.0))
        val references = LidarRoom.references(Rooms.ell)
        // The east wall's south half and the corner at the origin fit those two as well.
        assertEquals(
            ReferenceAlignment.Outcome.NeedsMore(ReferenceAlignment.Hint.MORE_POINTS),
            ReferenceAlignment.solve(marks = listOf(wall, corner), references = references, worldFloorY = LidarRoom.worldFloorY, outline = Rooms.ell),
        )

        val marks = listOf(wall, corner, LidarRoom.edge(2.0, 0.0, eye = Vec3(1.0, 1.35, 1.0)))
        val outcome = ReferenceAlignment.solve(marks = marks, references = references, worldFloorY = LidarRoom.worldFloorY, outline = Rooms.ell)
        val fit = assertIs<ReferenceAlignment.Outcome.Matched>(outcome, "Expected a match, got $outcome").fit
        expectClose(fit.transform, LidarRoom.truth)
        assertEquals(ReferenceFit.Quality.GOOD, fit.quality)
        // Where the marks were made, not the wall's middle.
        val middle = marks.fold(Vec3.zero) { acc, m -> acc + m.position } / 3.0
        assertTrue(ReferenceAlignment.horizontalDistance(fit.centre, middle) < 1e-9)
    }

    @Test
    @DisplayName("A wall and two corners with 1° and 1 cm of noise put the corners within 2.5 cm, turned within 0.75°")
    fun `A wall and two corners with 1 deg and 1 cm of noise put the corners within 2,5 cm, turned within 0,75 deg`() {
        val marks = listOf(
            LidarRoom.wall(Vec3(0.0, 1.4, 1.0), inward = Vec3(1.0, 0.0, 0.0), eye = Vec3(1.5, 1.35, 1.0), turn = SyntheticPlates.degrees(1.0)),
            LidarRoom.edge(4.0, 4.0, eye = Vec3(3.0, 1.35, 3.0), noise = Vec3(0.01, 0.0, -0.008)),
            LidarRoom.edge(4.0, 2.0, eye = Vec3(3.0, 1.35, 3.0), noise = Vec3(-0.006, 0.0, 0.01)),
        )
        val outcome = ReferenceAlignment.solve(
            marks = marks, references = LidarRoom.references(Rooms.ell), worldFloorY = LidarRoom.worldFloorY, outline = Rooms.ell,
        )
        val fit = assertIs<ReferenceAlignment.Outcome.Matched>(outcome, "Expected a match").fit
        assertTrue(fit.transform.yawDifference(to = LidarRoom.truth) <= SyntheticPlates.degrees(0.75))
        // The marked corners; further away the turn's error adds up (0.6° is 5 cm at 4.5 m).
        for (corner in listOf(Vec3(4.0, 0.0, 4.0), Vec3(4.0, 0.0, 2.0))) {
            assertTrue(Vector.distance(fit.transform.apply(corner), LidarRoom.truth.apply(corner)) <= 0.025)
        }
    }

    @Test
    @DisplayName("Two walls alone don't say which corner they meet at; parallel walls don't say where along them")
    fun `Two walls alone don't say which corner they meet at, parallel walls don't say where along them`() {
        val references = LidarRoom.references(TestRoom.outline)
        val twoWalls = listOf(
            LidarRoom.wall(Vec3(-2.0, 1.4, 0.5), inward = Vec3(1.0, 0.0, 0.0), eye = Vec3(-1.0, 1.35, 0.8)),
            LidarRoom.wall(Vec3(-1.0, 1.4, 1.75), inward = Vec3(0.0, 0.0, -1.0), eye = Vec3(-1.0, 1.35, 0.8)),
        )
        assertEquals(
            ReferenceAlignment.Outcome.NeedsMore(ReferenceAlignment.Hint.MORE_POINTS),
            ReferenceAlignment.solve(marks = twoWalls, references = references, worldFloorY = LidarRoom.worldFloorY, outline = TestRoom.outline),
        )
        val parallel = listOf(
            LidarRoom.wall(Vec3(-2.0, 1.4, 0.5), inward = Vec3(1.0, 0.0, 0.0), eye = Vec3(0.0, 1.35, 0.0)),
            LidarRoom.wall(Vec3(2.0, 1.4, -0.5), inward = Vec3(-1.0, 0.0, 0.0), eye = Vec3(0.0, 1.35, 0.0)),
        )
        assertEquals(
            ReferenceAlignment.Outcome.NeedsMore(ReferenceAlignment.Hint.ANOTHER_WALL),
            ReferenceAlignment.solve(marks = parallel, references = references, worldFloorY = LidarRoom.worldFloorY),
        )
    }

    @Test
    fun `A corner measured at height goes on the floor as ARKit refines it, along no line of sight`() {
        val edge = ReferenceMark(position = Vec3(1.0, 0.0, 2.0), surface = ReferenceMark.Surface.EDGE, seenFrom = Vec3(0.0, 1.4, 0.0))
        assertEquals(Vec3(1.0, 0.05, 2.0), edge.onFloor(at = 0.05).position)
        assertEquals(edge, edge.onFloor(at = 0.4))
    }

    @Test
    fun `Fix here on a wall turns the building onto it and moves it across, not along`() {
        val rooms = listOf(Rooms.a, Rooms.b)
        // Drifted 4 cm east and 2° off.
        val drifted = Rooms.drifted(Vec3(0.04, 0.0, 0.0))
            .rotated(by = SyntheticPlates.degrees(2.0), about = Rooms.truth.apply(Vec3(2.0, 0.0, 1.5)))
        val onWall = Vec3(0.0, 1.4, 1.2)
        val mark = Rooms.truth.apply(onWall)
        val normal = YawTransform.rotate(Vec3(1.0, 0.0, 0.0), by = Rooms.truth.yaw)
        val fix = assertNotNull(ReferenceAlignment.reanchor(drifted, wall = mark, normal = normal, seenFrom = Rooms.eye(1.0, 1.2), rooms = rooms))
        assertTrue(fix.kind == ReferenceAlignment.CornerFix.Kind.WALL && fix.room == 0)
        assertTrue(abs(fix.transform.yaw - Rooms.truth.yaw) < 1e-9)
        // The wall goes through the mark.
        val wallPoint = fix.transform.apply(Vec3(0.0, 1.4, 0.0))
        assertTrue(abs(Vector.dot(Vec3(mark.x - wallPoint.x, 0.0, mark.z - wallPoint.z), normal)) < 1e-9)
    }

    @Test
    @DisplayName("A wall fix refuses the next room's face of the wall, and a turn over 5°")
    fun `A wall fix refuses the next room's face of the wall, and a turn over 5 deg`() {
        val rooms = listOf(Rooms.a, Rooms.b)
        // A's east wall seen from inside A faces west; B's face of that wall faces east.
        val mark = Rooms.truth.apply(Vec3(4.0, 1.4, 1.5))
        val facingWest = YawTransform.rotate(Vec3(-1.0, 0.0, 0.0), by = Rooms.truth.yaw)
        val fix = ReferenceAlignment.reanchor(Rooms.truth, wall = mark, normal = facingWest, seenFrom = Rooms.eye(3.0, 1.5), rooms = rooms)
        assertEquals(0, fix?.room)
        val fromB = ReferenceAlignment.reanchor(
            Rooms.truth, wall = Rooms.truth.apply(Vec3(4.15, 1.4, 1.5)), normal = -facingWest, seenFrom = Rooms.eye(5.0, 1.5), rooms = rooms,
        )
        assertEquals(1, fromB?.room)
        val turned = YawTransform.rotate(Vec3(-1.0, 0.0, 0.0), by = Rooms.truth.yaw + SyntheticPlates.degrees(7.0))
        assertNull(ReferenceAlignment.reanchor(Rooms.truth, wall = mark, normal = turned, seenFrom = Rooms.eye(3.0, 1.5), rooms = rooms))
    }

    @Test
    @DisplayName("Walls face the camera; tilted surfaces aren't walls")
    fun `Walls face the camera, tilted surfaces aren't walls`() {
        val plane = assertNotNull(LidarAim.Plane.of(point = Vec3(0.0, 1.0, 0.0), normal = Vec3(-1.0, 0.05, 0.0), eye = Vec3(2.0, 1.4, 0.0)))
        assertTrue(Vector.distance(plane.normal, Vec3(1.0, 0.0, 0.0)) < 1e-12)
        assertNull(LidarAim.Plane.of(point = Vec3.zero, normal = Vec3(0.0, 1.0, 0.2), eye = Vec3(0.0, 1.0, 1.0)))
    }

    @Test
    @DisplayName("Two walls meeting near the middle hit are a corner; one wall, or walls meeting far away, aren't")
    fun `Two walls meeting near the middle hit are a corner, one wall, or walls meeting far away, aren't`() {
        val eye = Vec3(1.0, 1.4, 1.0)
        val west = assertNotNull(LidarAim.Plane.of(point = Vec3(0.0, 1.2, 0.18), normal = Vec3(1.0, 0.0, 0.0), eye = eye))
        val north = assertNotNull(LidarAim.Plane.of(point = Vec3(0.18, 1.2, 0.0), normal = Vec3(0.0, 0.0, 1.0), eye = eye))
        val centre = assertNotNull(LidarAim.Plane.of(point = Vec3(0.0, 1.2, 0.03), normal = Vec3(1.0, 0.0, 0.0), eye = eye))
        val target = LidarAim.target(centre = centre, left = west, right = north)
        val point = assertIs<LidarAim.Target.Corner>(target, "Expected a corner").point
        assertTrue(Vector.distance(point, Vec3(0.0, 1.2, 0.0)) < 1e-12)
        assertEquals(LidarAim.Target.Wall(centre), LidarAim.target(centre = centre, left = west, right = west))
        val far = assertNotNull(LidarAim.Plane.of(point = Vec3(0.18, 1.2, -0.5), normal = Vec3(0.0, 0.0, 1.0), eye = eye))
        assertEquals(LidarAim.Target.Wall(centre), LidarAim.target(centre = centre, left = west, right = far))
        assertNull(LidarAim.target(centre = null, left = west, right = north))
    }

    @Test
    @DisplayName("Half a second of aims: hand shake left out, a mix of wall and corner refused")
    fun `Half a second of aims - hand shake left out, a mix of wall and corner refused`() {
        val eye = Vec3(2.0, 1.4, 0.0)
        val walls = (0 until 8).map { index ->
            assertNotNull(LidarAim.Plane.of(point = Vec3(0.0, 1.2, 0.002 * index), normal = Vec3(1.0, 0.0, 0.001 * (index - 4)), eye = eye))
        }
        val shaky = assertNotNull(LidarAim.Plane.of(point = Vec3(0.0, 1.2, 0.3), normal = Vec3(1.0, 0.0, 0.0), eye = eye))
        val averaged = LidarAim.average(walls.map { LidarAim.Target.Wall(it) } + LidarAim.Target.Wall(shaky), minimum = 5)
        val plane = assertIs<LidarAim.Target.Wall>(averaged, "Expected a wall").plane
        assertTrue(abs(plane.point.z - 0.007) < 1e-9)
        assertNull(LidarAim.average(walls.map { LidarAim.Target.Wall(it) } + LidarAim.Target.Corner(Vec3.zero), minimum = 5))
    }
}
