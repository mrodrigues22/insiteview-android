package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.Manifest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The generated test room (converter `fixtures/make_test_room.py`, 4.0 × 3.5 m around the
 * origin): its outline, and outlets K-01 to K-03 and the switch as 2 cm-deep boxes standing out
 * of their walls. South is the z = 1.75 wall, west x = −2.
 */
object TestRoom {
    val space = Manifest.Space(
        id = "r1", name = "R1", longName = "Test room", storeyId = "s0", areaM2 = 14.0, floorY = 0.0,
        outline = listOf(listOf(-2.0, -1.75), listOf(-2.0, 1.75), listOf(2.0, 1.75), listOf(2.0, -1.75)),
    )

    val outline: RoomOutline get() = RoomOutline.of(space)!!

    val objects = listOf(
        box("k01", Vec3(-1.0, 0.32, 1.74)),
        box("k02", Vec3(1.99, 0.32, 0.55)),
        box("k03", Vec3(1.0, 0.32, -1.74)),
        box("switch", Vec3(-1.99, 1.10, -0.3)),
    )

    val references: List<ReferencePoint> get() = ReferencePoints.make(outline = outline, objects = objects, floorY = 0.0)

    fun reference(id: String): ReferencePoint = references.first { it.id == id }

    fun box(id: String, centre: Vec3): ReferenceObject {
        val half = Vec3(0.04, 0.04, 0.04)
        return ReferenceObject(id = id, bounds = Bounds(min = centre - half, max = centre + half))
    }

    /** Where the room is in the AR world. */
    val truth = YawTransform(yaw = SyntheticPlates.degrees(37.0), translation = Vec3(3.2, -1.4, 0.8))

    /** The mark someone makes aiming at [id], [offset] off (world metres). */
    fun mark(id: String, offset: Vec3 = Vec3.zero): ReferenceMark {
        val reference = reference(id)
        return ReferenceMark(
            position = truth.apply(reference.position) + offset,
            surface = if (reference.kind == ReferencePoint.Kind.CORNER) ReferenceMark.Surface.FLOOR else ReferenceMark.Surface.WALL,
        )
    }
}

@DisplayName("Room outline")
class RoomOutlineTest {
    @Test
    fun `corners on the floor, inward normals into the room`() {
        val outline = TestRoom.outline
        assertEquals(4, outline.corners.size)
        assertTrue(outline.corners.all { it.y == 0.0 })
        assertEquals(4, outline.walls.size)
        for (wall in outline.walls) {
            val inside = (wall.start + wall.end) / 2.0 + wall.inwardNormal * 0.1
            assertTrue(abs(inside.x) < 2 && abs(inside.z) < 1.75, "$wall")
            assertTrue(abs(Vector.length(wall.inwardNormal) - 1) < 1e-12)
        }
    }

    @Test
    fun `no outline without room geometry`() {
        assertNull(RoomOutline.of(Manifest.Space(id = "r", name = null, longName = null, storeyId = null, areaM2 = null)))
        val space = TestRoom.space.copy(outline = listOf(listOf(0.0, 0.0), listOf(1.0, 0.0)))
        assertNull(RoomOutline.of(space))
    }

    @Test
    fun `a point's wall, distance and corners, facing the wall from inside`() {
        val position = assertNotNull(TestRoom.outline.nearestWall(to = Vec3(-1.0, 1.4, 1.70)))
        assertEquals(Vec3(0.0, 0.0, -1.0), position.wall.inwardNormal)
        assertTrue(abs(position.signedDistance - 0.05) < 1e-9)
        assertEquals(Vec3(-1.0, 1.4, 1.75), position.foot)
        // Facing south from inside, west (x = −2) is on the right.
        assertTrue(abs(position.fromRightCorner - 1.0) < 1e-9)
        assertTrue(abs(position.fromLeftCorner - 3.0) < 1e-9)
    }
}

@DisplayName("Reference points")
class ReferencePointsTest {
    @Test
    fun `corners, and objects moved onto their wall's surface at their height`() {
        val references = TestRoom.references
        assertEquals(4, references.count { it.kind == ReferencePoint.Kind.CORNER })
        assertEquals(4, references.count { it.kind == ReferencePoint.Kind.OBJECT })
        val k01 = TestRoom.reference("k01")
        assertTrue(abs(k01.position.z - 1.75) < 1e-9 && abs(k01.position.x + 1.0) < 1e-9)
        assertTrue(abs(k01.height - 0.32) < 1e-9)
        val light = TestRoom.reference("switch")
        assertTrue(abs(light.position.x + 2) < 1e-9 && abs(light.height - 1.10) < 1e-9)
        assertEquals("switch", light.elementID)
    }

    @Test
    @DisplayName("without an outline: no corners, objects keep their centres")
    fun `without an outline - no corners, objects keep their centres`() {
        val references = ReferencePoints.make(outline = null, objects = TestRoom.objects, floorY = 0.0)
        assertTrue(references.all { it.kind == ReferencePoint.Kind.OBJECT })
        val k01 = references.firstOrNull { it.id == "k01" }
        assertEquals(true, k01?.let { Vector.distance(it.position, Vec3(-1.0, 0.32, 1.74)) < 1e-9 })
    }
}

@DisplayName("Reference alignment: fit and matching")
class ReferenceAlignmentTest {
    @Test
    fun `the fit recovers yaw and translation exactly`() {
        for (yaw in listOf(-179.0, -90.0, -12.0, 0.0, 45.0, 133.0, 180.0)) {
            val truth = YawTransform(yaw = SyntheticPlates.degrees(yaw), translation = Vec3(-4.0, 0.7, 2.5))
            val model = TestRoom.references.map { it.position }
            val world = model.map { truth.apply(it) }
            expectClose(ReferenceAlignment.fit(model = model, world = world), truth, position = 1e-9, angle = 1e-9)
        }
    }

    @Test
    @DisplayName("two outlets and a corner, any order, without saying which: matched")
    fun `two outlets and a corner, any order, without saying which - matched`() {
        val marks = listOf(TestRoom.mark("switch"), TestRoom.mark("corner-2"), TestRoom.mark("k01"))
        val outcome = ReferenceAlignment.solve(marks = marks, references = TestRoom.references, worldFloorY = -1.4)
        val fit = assertIs<ReferenceAlignment.Outcome.Matched>(outcome, "Expected a match, got $outcome").fit
        expectClose(fit.transform, TestRoom.truth)
        assertEquals(listOf("switch", "corner-2", "k01"), fit.matches.map { it.reference.id })
        assertNull(fit.dropped)
        assertEquals(ReferenceFit.Quality.GOOD, fit.quality)
    }

    @Test
    @DisplayName("a centimetre of noise on each mark stays within 2 cm and 1°")
    fun `a centimetre of noise on each mark stays within 2 cm and 1 deg`() {
        val random = SeededRandom(seed = 7uL)
        repeat(20) {
            val marks = listOf("k02", "switch", "corner-0", "k03").map { TestRoom.mark(it, offset = random.vector(0.01)) }
            val outcome = ReferenceAlignment.solve(marks = marks, references = TestRoom.references, worldFloorY = -1.4)
            val fit = assertIs<ReferenceAlignment.Outcome.Matched>(outcome, "Expected a match, got $outcome").fit
            expectClose(fit.transform, TestRoom.truth, position = 0.02, angle = SyntheticPlates.degrees(1.0))
        }
    }

    @Test
    @DisplayName("three corners of a rectangle fit two ways that no corner tells apart: both offered now")
    fun `three corners of a rectangle fit two ways that no corner tells apart - both offered now`() {
        val marks = listOf(TestRoom.mark("corner-0"), TestRoom.mark("corner-1"), TestRoom.mark("corner-2"))
        val corners = ReferencePoints.make(outline = TestRoom.outline, objects = emptyList(), floorY = 0.0)
        val outcome = ReferenceAlignment.solve(marks = marks, references = corners, worldFloorY = -1.4)
        val fits = assertIs<ReferenceAlignment.Outcome.Ambiguous>(outcome, "Expected two fits").fits
        assertEquals(2, fits.size)
        assertTrue(fits.any { it.transform.yawDifference(to = TestRoom.truth) < 1e-6 })
    }

    @Test
    @DisplayName("a rectangle turned 180° is the room's own symmetry; a quarter turn isn't")
    fun `a rectangle turned 180 deg is the room's own symmetry, a quarter turn isn't`() {
        val corners = ReferencePoints.make(outline = TestRoom.outline, objects = emptyList(), floorY = 0.0)
        fun fit(transform: YawTransform) = ReferenceFit(transform = transform, matches = emptyList(), rms = 0.0, dropped = null)
        val turned = TestRoom.truth.rotated(by = PI, about = TestRoom.truth.apply(Vec3.zero))
        val quarter = TestRoom.truth.rotated(by = PI / 2, about = TestRoom.truth.apply(Vec3.zero))
        assertTrue(ReferenceAlignment.isSymmetric(fit(TestRoom.truth), fit(turned), corners = corners, within = 0.15))
        assertFalse(ReferenceAlignment.isSymmetric(fit(TestRoom.truth), fit(quarter), corners = corners, within = 0.15))
    }

    @Test
    @DisplayName("still ambiguous after a fourth corner: both fits offered")
    fun `still ambiguous after a fourth corner - both fits offered`() {
        val marks = listOf("corner-0", "corner-1", "corner-2", "corner-3").map { TestRoom.mark(it) }
        val outcome = ReferenceAlignment.solve(marks = marks, references = TestRoom.references, worldFloorY = null)
        val fits = assertIs<ReferenceAlignment.Outcome.Ambiguous>(outcome, "Expected two fits").fits
        assertEquals(2, fits.size)
        assertTrue(fits.any { it.transform.yawDifference(to = TestRoom.truth) < 1e-6 })
    }

    @Test
    fun `an outlet 12 cm off the project is dropped and named`() {
        // Along the south wall: the electrician put K-01 12 cm further east.
        val shifted = YawTransform.rotate(Vec3(0.12, 0.0, 0.0), by = TestRoom.truth.yaw)
        val marks = listOf(TestRoom.mark("k01", offset = shifted), TestRoom.mark("switch"), TestRoom.mark("corner-2"), TestRoom.mark("corner-3"))
        val outcome = ReferenceAlignment.solve(marks = marks, references = TestRoom.references, worldFloorY = -1.4)
        val fit = assertIs<ReferenceAlignment.Outcome.Matched>(outcome, "Expected a match, got $outcome").fit
        expectClose(fit.transform, TestRoom.truth)
        val dropped = assertNotNull(fit.dropped)
        assertEquals(0, dropped.markIndex)
        assertEquals("k01", dropped.nearest?.id)
        assertTrue(abs(dropped.distance - 0.12) < 0.005)
    }

    @Test
    @DisplayName("a switch isn't taken for an outlet: heights tell them apart")
    fun `a switch isn't taken for an outlet - heights tell them apart`() {
        // Mark K-02 and K-03 and the switch: only the switch is at 1.10 m.
        val marks = listOf(TestRoom.mark("k02"), TestRoom.mark("k03"), TestRoom.mark("switch"))
        val outcome = ReferenceAlignment.solve(marks = marks, references = TestRoom.references, worldFloorY = -1.4)
        val fit = assertIs<ReferenceAlignment.Outcome.Matched>(outcome, "Expected a match").fit
        assertEquals(listOf("k02", "k03", "switch"), fit.matches.map { it.reference.id })
    }

    @Test
    @DisplayName("too few, too close together, or all in a line: asks for more")
    fun `too few, too close together, or all in a line - asks for more`() {
        val references = TestRoom.references
        assertEquals(
            ReferenceAlignment.Outcome.NeedsMore(ReferenceAlignment.Hint.MORE_POINTS),
            ReferenceAlignment.solve(marks = listOf(TestRoom.mark("k01"), TestRoom.mark("switch")), references = references, worldFloorY = null),
        )
        val close = (0 until 3).map { ReferenceMark(position = Vec3(it * 0.2, 0.0, 0.0), surface = ReferenceMark.Surface.FLOOR) }
        assertEquals(
            ReferenceAlignment.Outcome.NeedsMore(ReferenceAlignment.Hint.SPREAD_OUT),
            ReferenceAlignment.solve(marks = close, references = references, worldFloorY = null),
        )
        val line = (0 until 3).map { ReferenceMark(position = Vec3(it * 0.8, 0.3, 0.02 * it), surface = ReferenceMark.Surface.WALL) }
        assertEquals(
            ReferenceAlignment.Outcome.NeedsMore(ReferenceAlignment.Hint.ANOTHER_WALL),
            ReferenceAlignment.solve(marks = line, references = references, worldFloorY = null),
        )
    }

    @Test
    @DisplayName("marks that fit nothing in the room: no match")
    fun `marks that fit nothing in the room - no match`() {
        val marks = listOf(
            ReferenceMark(position = Vec3(0.0, 0.0, 0.0), surface = ReferenceMark.Surface.FLOOR),
            ReferenceMark(position = Vec3(7.0, 0.0, 0.5), surface = ReferenceMark.Surface.FLOOR),
            ReferenceMark(position = Vec3(1.0, 0.0, 9.0), surface = ReferenceMark.Surface.FLOOR),
        )
        assertEquals(
            ReferenceAlignment.Outcome.NoMatch(ReferenceAlignment.NoMatch.TOO_FAR),
            ReferenceAlignment.solve(marks = marks, references = TestRoom.references, worldFloorY = 0.0),
        )
    }

    @Test
    @DisplayName("a room 25 cm longer than its model: the closest fit, offered as approximate")
    fun `a room 25 cm longer than its model - the closest fit, offered as approximate`() {
        // The real room is 4.25 m along x where the model says 4.0 m: no strict fit exists.
        fun stretched(id: String): ReferenceMark {
            val reference = TestRoom.reference(id)
            val real = Vec3(reference.position.x * 1.0625, reference.position.y, reference.position.z)
            return ReferenceMark(
                position = TestRoom.truth.apply(real),
                surface = if (reference.kind == ReferencePoint.Kind.CORNER) ReferenceMark.Surface.FLOOR else ReferenceMark.Surface.WALL,
            )
        }
        val marks = listOf("corner-1", "corner-2", "corner-3", "switch").map { stretched(it) }
        val outcome = ReferenceAlignment.solve(marks = marks, references = TestRoom.references, worldFloorY = -1.4)
        val fit = assertIs<ReferenceAlignment.Outcome.Matched>(outcome, "Expected an approximate match").fit
        assertEquals(ReferenceFit.Quality.APPROXIMATE, fit.quality)
        assertTrue(
            fit.rms > ReferenceAlignment.Tolerances.strict.maximumRMS && fit.rms <= ReferenceAlignment.Tolerances.relaxed.maximumRMS,
        )
        assertTrue(fit.transform.yawDifference(to = TestRoom.truth) < SyntheticPlates.degrees(2.0))
    }

    @Test
    @DisplayName("corners marked in a room without an outline, or wall points with no outlets: says which")
    fun `corners marked in a room without an outline, or wall points with no outlets - says which`() {
        val objectsOnly = ReferencePoints.make(outline = null, objects = TestRoom.objects, floorY = 0.0)
        val corners = listOf("corner-0", "corner-1", "corner-2").map { TestRoom.mark(it) }
        assertEquals(
            ReferenceAlignment.Outcome.NoMatch(ReferenceAlignment.NoMatch.NO_CORNERS),
            ReferenceAlignment.solve(marks = corners, references = objectsOnly, worldFloorY = -1.4),
        )
        val cornersOnly = ReferencePoints.make(outline = TestRoom.outline, objects = emptyList(), floorY = 0.0)
        val walls = listOf("k01", "k02", "switch").map { TestRoom.mark(it) }
        assertEquals(
            ReferenceAlignment.Outcome.NoMatch(ReferenceAlignment.NoMatch.NO_OBJECTS),
            ReferenceAlignment.solve(marks = walls, references = cornersOnly, worldFloorY = -1.4),
        )
    }
}

@DisplayName("Fix here and the floor glue")
class DriftCorrectionTest {
    private val corners = ReferencePoints.make(outline = TestRoom.outline, objects = emptyList(), floorY = 0.0)

    @Test
    fun `a corner marked after 12 cm of drift moves the building back onto it, keeping the turn`() {
        // Tracking drifted: the real corner is now 12 cm from where the alignment puts it.
        val drift = Vec3(0.09, 0.03, -0.08)
        val drifted = TestRoom.truth.translated(by = -drift)
        val mark = TestRoom.mark("corner-2").position
        val fix = assertNotNull(ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = null, rooms = listOf(TestRoom.outline)))
        assertEquals(drifted.yaw, fix.transform.yaw)
        assertEquals(drifted.translation.y, fix.transform.translation.y)
        assertTrue(ReferenceAlignment.horizontalDistance(fix.transform.apply(TestRoom.reference("corner-2").position), mark) < 1e-9)
        assertTrue(fix.corner == TestRoom.reference("corner-2").position && fix.room == 0 && fix.mark == mark)
    }

    @Test
    fun `a mark far from every corner, or as near two corners, isn't taken`() {
        val a = TestRoom.truth.apply(TestRoom.reference("corner-1").position)
        val b = TestRoom.truth.apply(TestRoom.reference("corner-2").position)
        assertNull(ReferenceAlignment.reanchor(TestRoom.truth, mark = (a + b) / 2.0, seenFrom = null, rooms = listOf(TestRoom.outline)))
        // A 30 cm jog in the south-east corner: three corners close together.
        val space = TestRoom.space.copy(
            outline = listOf(
                listOf(-2.0, -1.75), listOf(-2.0, 1.75), listOf(1.7, 1.75), listOf(1.7, 1.45), listOf(2.0, 1.45), listOf(2.0, -1.75),
            ),
        )
        val jogged = assertNotNull(RoomOutline.of(space))
        val between = TestRoom.truth.apply(Vec3(1.85, 0.0, 1.6))
        assertNull(ReferenceAlignment.reanchor(TestRoom.truth, mark = between, seenFrom = null, rooms = listOf(jogged)))
        val onJog = TestRoom.truth.apply(Vec3(1.71, 0.0, 1.46))
        assertNotNull(ReferenceAlignment.reanchor(TestRoom.truth, mark = onJog, seenFrom = null, rooms = listOf(jogged)))
    }

    @Test
    fun `a corner marked on a floor estimate 6 cm low lands on the corner once the floor is refined`() {
        // Aimed down at corner 2 from 1.3 m above the floor, 0.6 m back and 0.4 m to the side.
        val corner = TestRoom.mark("corner-2").position
        val eye = corner + Vec3(0.4, 1.3, 0.6)
        // The AR session had the floor 6 cm low: the mark is where the line of sight met that plane.
        val low = corner.y - 0.06
        val marked = eye + (corner - eye) * ((eye.y - low) / (eye.y - corner.y))
        val mark = ReferenceMark(position = marked, surface = ReferenceMark.Surface.FLOOR, seenFrom = eye)
        assertTrue(Vector.distance(marked, corner) > 0.04)
        assertTrue(Vector.distance(mark.onFloor(at = corner.y).position, corner) < 1e-9)
    }

    @Test
    fun `marks without a line of sight, wall marks, or a floor that jumped too far stay put`() {
        val point = Vec3(1.0, -1.4, 2.0)
        val eye = Vec3(1.2, -0.1, 2.5)
        assertEquals(point, ReferenceMark(position = point, surface = ReferenceMark.Surface.FLOOR).onFloor(at = -1.35).position)
        assertEquals(point, ReferenceMark(position = point, surface = ReferenceMark.Surface.WALL, seenFrom = eye).onFloor(at = -1.35).position)
        assertEquals(point, ReferenceMark(position = point, surface = ReferenceMark.Surface.FLOOR, seenFrom = eye).onFloor(at = -1.2).position)
        val followed = ReferenceMark(position = point, surface = ReferenceMark.Surface.FLOOR, seenFrom = eye).onFloor(at = -1.35).position
        assertTrue(abs(followed.y + 1.35) < 1e-12)
    }

    @Test
    fun `refitting after the floor moved keeps which corner is which and follows the marks`() {
        val marks = listOf("corner-0", "corner-1", "corner-2").map { TestRoom.mark(it) }
        val outcome = ReferenceAlignment.solve(marks = marks, references = corners, worldFloorY = -1.4)
        val fits = assertIs<ReferenceAlignment.Outcome.Ambiguous>(outcome, "Expected the rectangle's two fits").fits
        val fit = assertNotNull(fits.firstOrNull { it.transform.yawDifference(to = TestRoom.truth) < 1e-6 })
        val shift = Vec3(0.03, 0.06, -0.02)
        val moved = marks.map { ReferenceMark(position = it.position + shift, surface = ReferenceMark.Surface.FLOOR) }
        val refitted = ReferenceAlignment.refit(fit, marks = moved)
        assertEquals(fit.matches.map { it.reference.id }, refitted.matches.map { it.reference.id })
        expectClose(refitted.transform, TestRoom.truth.translated(by = shift))
        assertTrue(refitted.rms < 1e-9)
    }

    @Test
    fun `the floor glue moves the building up or down onto the detected floor, within bounds`() {
        val transform = YawTransform(yaw = 0.4, translation = Vec3(1.0, -1.40, 2.0))
        val glued = assertNotNull(ManualAlignment.gluedToFloor(transform, modelFloorY = 0.2, worldFloorY = -1.17))
        assertTrue(abs(glued.translation.y + 1.37) < 1e-9)
        assertTrue(glued.yaw == transform.yaw && glued.translation.x == 1.0 && glued.translation.z == 2.0)
        // Already there (plane noise), and a plane too far off to be this floor.
        assertNull(ManualAlignment.gluedToFloor(transform, modelFloorY = 0.2, worldFloorY = -1.198))
        assertNull(ManualAlignment.gluedToFloor(transform, modelFloorY = 0.2, worldFloorY = -1.45))
    }
}

@DisplayName("Mark spread")
class MarkSpreadTest {
    @Test
    fun `spread of repeated marks`() {
        val spread = assertNotNull(MarkSpread.of(listOf(Vec3(0.0, 0.0, 0.0), Vec3(0.02, 0.0, 0.0))))
        assertTrue(Vector.distance(spread.mean, Vec3(0.01, 0.0, 0.0)) < 1e-12)
        assertTrue(abs(spread.maximum - 0.01) < 1e-12 && abs(spread.rms - 0.01) < 1e-12)
        assertNull(MarkSpread.of(emptyList()))
    }
}

@DisplayName("Plate registration on site")
class PlateRegistrationTest {
    @Test
    @DisplayName("only close, head-on detections count; the median once there are enough")
    fun `only close, head-on detections count, the median once there are enough`() {
        val sampler = PlateSampler()
        val world = SyntheticPlates.detection(model, TestRoom.truth)
        val inFront = world.position + world.normal * 0.8
        val tooFar = sampler.add(world, camera = world.position + world.normal * 3.0)
        val tooOblique = sampler.add(world, camera = world.position + YawTransform.rotate(world.normal, by = SyntheticPlates.degrees(70.0)) * 0.8)
        assertTrue(!tooFar && !tooOblique)
        repeat(PlateSampler.MINIMUM_SAMPLES - 1) {
            val kept = sampler.add(world, camera = inFront)
            assertTrue(kept)
        }
        assertNull(sampler.pose)
        sampler.add(world, camera = inFront)
        val pose = assertNotNull(sampler.pose)
        assertTrue(Vector.distance(pose.position, world.position) < 1e-9)
        assertEquals(2, sampler.skipped)
    }

    @Test
    fun `a slightly tilted plate 1 cm into the wall goes onto the wall, upright`() {
        val detection = SyntheticPlates.detection(
            model, TestRoom.truth, tilt = SyntheticPlates.degrees(3.0),
            offset = YawTransform.rotate(Vec3(0.0, 0.0, 0.01), by = TestRoom.truth.yaw),
        )
        val pose = PlateRegistration.pose(of = detection, alignment = TestRoom.truth, outline = TestRoom.outline).get()
        assertTrue(pose.snappedToWall)
        assertTrue(Vector.distance(pose.frame.position, model.position) < 1e-6)
        assertTrue(Vector.distance(pose.frame.normal, Vec3(0.0, 0.0, -1.0)) < 1e-9)
        assertEquals(Vec3(0.0, 1.0, 0.0), pose.frame.up)
        assertTrue(abs((pose.heightAboveFloor ?: 0.0) - 1.4) < 1e-6)
        assertTrue(abs((pose.wall?.fromRightCorner ?: 0.0) - 1.5) < 1e-6)
    }

    @Test
    @DisplayName("on a panel door 20 cm out from the wall: keeps its position, faces the room")
    fun `on a panel door 20 cm out from the wall - keeps its position, faces the room`() {
        val onDoor = PlateFrame(position = Vec3(-0.5, 1.4, 1.55), normal = Vec3(0.05, 0.0, -1.0), up = Vec3(0.0, 1.0, 0.0))
        val detection = SyntheticPlates.detection(onDoor, TestRoom.truth)
        val pose = PlateRegistration.pose(of = detection, alignment = TestRoom.truth, outline = TestRoom.outline).get()
        assertFalse(pose.snappedToWall)
        assertTrue(Vector.distance(pose.frame.position, onDoor.position) < 1e-6)
        assertTrue(Vector.distance(pose.frame.normal, Vec3(0.0, 0.0, -1.0)) < 1e-9)
    }

    @Test
    @DisplayName("facing no wall of the room, or behind one: refused")
    fun `facing no wall of the room, or behind one - refused`() {
        val outside = PlateFrame(position = Vec3(-0.5, 1.4, 2.0), normal = Vec3(0.0, 0.0, -1.0), up = Vec3(0.0, 1.0, 0.0))
        val sideways = PlateFrame(position = Vec3(0.0, 1.4, 1.7), normal = Vec3(1.0, 0.0, 0.0), up = Vec3(0.0, 1.0, 0.0))
        for (frame in listOf(outside, sideways)) {
            val result = PlateRegistration.pose(
                of = SyntheticPlates.detection(frame, TestRoom.truth), alignment = TestRoom.truth, outline = TestRoom.outline,
            )
            assertEquals(PlateRegistration.Result.Failure(PlateRegistration.Problem.NOT_ON_A_WALL_OF_THE_ROOM), result)
        }
    }

    @Test
    fun `a plate on the floor keeps a horizontal up`() {
        val detection = SyntheticPlates.detection(SyntheticPlates.floor, TestRoom.truth)
        val pose = PlateRegistration.pose(of = detection, alignment = TestRoom.truth, outline = TestRoom.outline).get()
        assertEquals(Vec3(0.0, 1.0, 0.0), pose.frame.normal)
        assertTrue(Vector.distance(pose.frame.up, Vec3(0.0, 0.0, -1.0)) < 1e-9)
        assertNull(pose.wall)
    }

    companion object {
        /** A plate on the south wall, 1.5 m from the west corner, 1.40 m up. */
        val model = PlateFrame(position = Vec3(-0.5, 1.4, 1.75 - 0.003), normal = Vec3(0.0, 0.0, -1.0), up = Vec3(0.0, 1.0, 0.0))
    }
}
