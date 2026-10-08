package com.getinsiteview.modelkit.geometry

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Rooms side by side, seen from above (x east, z south), outlines on the walls' inside faces as
 * the converter writes them: A is 4 × 3 m; B is east of it across a 15 cm wall; C is where B is,
 * open to A (no wall); A upstairs is A one storey up; the closet is in A's north-west corner.
 */
object Rooms {
    fun outline(points: List<List<Double>>, floorY: Double = 0.0): RoomOutline =
        RoomOutline(corners = points.map { Vec3(it[0], floorY, it[1]) }, floorY = floorY)

    val a = outline(listOf(listOf(0.0, 0.0), listOf(0.0, 3.0), listOf(4.0, 3.0), listOf(4.0, 0.0)))
    val b = outline(listOf(listOf(4.15, 0.0), listOf(4.15, 3.0), listOf(8.0, 3.0), listOf(8.0, 0.0)))
    val openPlan = outline(listOf(listOf(4.0, 0.0), listOf(4.0, 3.0), listOf(8.0, 3.0), listOf(8.0, 0.0)))
    val aUpstairs = outline(listOf(listOf(0.0, 0.0), listOf(0.0, 3.0), listOf(4.0, 3.0), listOf(4.0, 0.0)), floorY = 3.0)
    val closet = outline(listOf(listOf(0.0, 0.0), listOf(0.0, 1.0), listOf(1.0, 1.0), listOf(1.0, 0.0)))

    /** An L: the reflex corner is (2, 2). */
    val ell = outline(
        listOf(listOf(0.0, 0.0), listOf(0.0, 4.0), listOf(4.0, 4.0), listOf(4.0, 2.0), listOf(2.0, 2.0), listOf(2.0, 0.0)),
    )

    /** Where the building is in the AR world. */
    val truth = YawTransform(yaw = SyntheticPlates.degrees(-23.0), translation = Vec3(1.5, -1.3, 2.2))

    /** The corners either side of the A–B wall, on the z = 3 wall. */
    val cornerA = Vec3(4.0, 0.0, 3.0)
    val cornerB = Vec3(4.15, 0.0, 3.0)

    /**
     * The alignment after drift: the model [offset] (model metres) off the real building, so a
     * mark on a real corner `c` shows at `c + offset` in the model.
     */
    fun drifted(offset: Vec3, transform: YawTransform = truth): YawTransform =
        transform.translated(by = -YawTransform.rotate(offset, by = transform.yaw))

    /** A mark on the real corner [c]. */
    fun mark(c: Vec3): Vec3 = truth.apply(c)

    /** The camera at model (x, z), 1.35 m up, in the world. */
    fun eye(x: Double, z: Double): Vec3 = truth.apply(Vec3(x, 1.35, z))
}

@DisplayName("Room outline: corners, inside, which room")
class RoomCornerTest {
    @Test
    @DisplayName("corners: convex, reflex, and which side faces a point")
    fun `corners - convex, reflex, and which side faces a point`() {
        val corner = assertNotNull(Rooms.a.corner(at = 2))
        assertTrue(corner.position == Rooms.cornerA && corner.isConvex)
        assertTrue(abs(corner.facingMargin(Vec3(3.5, 1.4, 2.6)) - 0.4) < 1e-12)
        assertTrue(corner.isFacing(Vec3(3.5, 1.4, 2.6), tolerance = 0.0))
        assertFalse(corner.isFacing(Vec3(4.3, 1.4, 2.6), tolerance = 0.05))
        assertTrue(corner.isFacing(Vec3(4.03, 1.4, 2.6), tolerance = 0.05))

        val reflex = assertNotNull(Rooms.ell.corner(at = 4))
        assertTrue(reflex.position == Vec3(2.0, 0.0, 2.0) && !reflex.isConvex)
        // From either leg of the L, not from outside it.
        assertTrue(reflex.isFacing(Vec3(1.0, 1.4, 1.0), tolerance = 0.0))
        assertTrue(reflex.isFacing(Vec3(3.0, 1.4, 3.0), tolerance = 0.0))
        assertFalse(reflex.isFacing(Vec3(3.0, 1.4, 1.0), tolerance = 0.0))
        assertEquals(5, Rooms.ell.corners.indices.count { Rooms.ell.corner(at = it)?.isConvex == true })
    }

    @Test
    @DisplayName("an outline point where the wall goes straight on or turns back isn't a corner; a repeated one is one corner")
    fun `an outline point where the wall goes straight on or turns back isn't a corner, a repeated one is one corner`() {
        val straight = Rooms.outline(
            listOf(listOf(0.0, 0.0), listOf(0.0, 1.5), listOf(0.0, 3.0), listOf(0.0, 3.0), listOf(4.0, 3.0), listOf(4.0, 0.0)),
        )
        assertNull(straight.corner(at = 1))
        val repeated = assertNotNull(straight.corner(at = 2))
        assertTrue(repeated.position == Vec3(0.0, 0.0, 3.0) && repeated.isConvex)
        assertEquals(repeated.position, straight.corner(at = 3)?.position)
        val spike = Rooms.outline(listOf(listOf(0.0, 0.0), listOf(0.0, 3.0), listOf(0.0, 1.5), listOf(4.0, 1.5), listOf(4.0, 0.0)))
        assertNull(spike.corner(at = 1))
        assertNull(spike.corner(at = 9))
    }

    @Test
    @DisplayName("area, inside, and the room a point is in: the smallest around it, on its storey")
    fun `area, inside, and the room a point is in - the smallest around it, on its storey`() {
        assertTrue(abs(Rooms.a.area - 12) < 1e-12)
        assertTrue(abs(TestRoom.outline.area - 14) < 1e-12)
        assertTrue(Rooms.ell.contains(Vec3(1.0, 0.0, 3.0)) && Rooms.ell.contains(Vec3(3.0, 0.0, 3.0)))
        assertTrue(!Rooms.ell.contains(Vec3(3.0, 0.0, 1.0)) && !Rooms.ell.contains(Vec3(-1.0, 0.0, 1.0)))
        val rooms = listOf(Rooms.a, Rooms.closet, Rooms.b, Rooms.aUpstairs)
        assertEquals(1, RoomOutline.index(containing = Vec3(0.5, 1.4, 0.5), outlines = rooms))
        assertEquals(0, RoomOutline.index(containing = Vec3(2.0, 1.4, 2.0), outlines = rooms))
        assertEquals(2, RoomOutline.index(containing = Vec3(6.0, 1.4, 2.0), outlines = rooms))
        assertEquals(3, RoomOutline.index(containing = Vec3(2.0, 4.4, 2.0), outlines = rooms))
        assertNull(RoomOutline.index(containing = Vec3(4.07, 1.4, 2.0), outlines = rooms))
    }
}

@DisplayName("Fix here in every room")
class FixHereTest {
    private val rooms = listOf(Rooms.a, Rooms.b)

    @Test
    @DisplayName("in the next room: the line of sight tells the corners either side of a wall apart (the reported bug)")
    fun `in the next room - the line of sight tells the corners either side of a wall apart (the reported bug)`() {
        // 12 cm of drift towards A: B's corner shows 3 cm from A's.
        val drifted = Rooms.drifted(Vec3(-0.12, 0.0, 0.0))
        val mark = Rooms.mark(Rooms.cornerB)
        // Without the line of sight A's corner, behind the wall, is taken: 15 cm off.
        val blind = assertNotNull(ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = null, rooms = rooms))
        assertEquals(0, blind.room)
        // From B, 45 cm from both walls.
        val fix = assertNotNull(ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = Rooms.eye(4.6, 2.55), rooms = rooms))
        assertTrue(fix.room == 1 && fix.corner == Rooms.cornerB && fix.mark == mark)
        expectClose(fix.transform, Rooms.truth)
    }

    @Test
    fun `8 cm of drift, refused before as two corners about as near, is fixed`() {
        val drifted = Rooms.drifted(Vec3(-0.08, 0.0, 0.0))
        val mark = Rooms.mark(Rooms.cornerB)
        assertNull(ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = null, rooms = rooms))
        val fix = assertNotNull(ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = Rooms.eye(4.6, 2.55), rooms = rooms))
        assertEquals(1, fix.room)
        expectClose(fix.transform, Rooms.truth)
    }

    @Test
    @DisplayName("the other way: in A, drifted towards B, A's corner")
    fun `the other way - in A, drifted towards B, A's corner`() {
        val drifted = Rooms.drifted(Vec3(0.12, 0.0, 0.0))
        val fix = assertNotNull(
            ReferenceAlignment.reanchor(drifted, mark = Rooms.mark(Rooms.cornerA), seenFrom = Rooms.eye(3.55, 2.55), rooms = rooms),
        )
        assertTrue(fix.room == 0 && fix.corner == Rooms.cornerA)
        expectClose(fix.transform, Rooms.truth)
    }

    @Test
    @DisplayName("close to the wall it still tells; right over it there's no telling")
    fun `close to the wall it still tells, right over it there's no telling`() {
        val drifted = Rooms.drifted(Vec3(-0.12, 0.0, 0.0))
        val mark = Rooms.mark(Rooms.cornerB)
        // 3 cm from B's wall face, half a metre back.
        val fix = assertNotNull(ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = Rooms.eye(4.18, 2.5), rooms = rooms))
        assertEquals(1, fix.room)
        // Half a centimetre: either side could be right.
        assertNull(ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = Rooms.eye(4.155, 2.5), rooms = rooms))
    }

    @Test
    @DisplayName("open plan: two rooms' corners on one spot are one corner, from either room")
    fun `open plan - two rooms' corners on one spot are one corner, from either room`() {
        val rooms = listOf(Rooms.a, Rooms.openPlan)
        val drifted = Rooms.drifted(Vec3(0.06, 0.0, -0.08))
        for (eye in listOf(Rooms.eye(3.5, 2.5), Rooms.eye(4.0, 2.5), Rooms.eye(4.5, 2.5))) {
            val fix = assertNotNull(ReferenceAlignment.reanchor(drifted, mark = Rooms.mark(Rooms.cornerA), seenFrom = eye, rooms = rooms))
            assertEquals(Rooms.cornerA, fix.corner)
            expectClose(fix.transform, Rooms.truth)
        }
    }

    @Test
    fun `a reflex corner, from either leg of the L`() {
        val drifted = Rooms.drifted(Vec3(0.1, 0.0, 0.1))
        for (eye in listOf(Rooms.eye(1.4, 1.4), Rooms.eye(2.6, 2.6))) {
            val fix = assertNotNull(
                ReferenceAlignment.reanchor(drifted, mark = Rooms.mark(Vec3(2.0, 0.0, 2.0)), seenFrom = eye, rooms = listOf(Rooms.ell)),
            )
            assertEquals(Vec3(2.0, 0.0, 2.0), fix.corner)
        }
    }

    @Test
    @DisplayName("a repeated outline point is one corner; a room on another storey doesn't count")
    fun `a repeated outline point is one corner, a room on another storey doesn't count`() {
        val repeated = Rooms.outline(listOf(listOf(0.0, 0.0), listOf(0.0, 3.0), listOf(0.0, 3.0), listOf(4.0, 3.0), listOf(4.0, 0.0)))
        val drifted = Rooms.drifted(Vec3(0.1, 0.0, 0.0))
        val mark = Rooms.mark(Vec3(0.0, 0.0, 3.0))
        assertNotNull(ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = Rooms.eye(0.5, 2.5), rooms = listOf(repeated)))
        assertNull(ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = Rooms.eye(0.5, 2.5), rooms = listOf(Rooms.aUpstairs)))
        val fix = assertNotNull(
            ReferenceAlignment.reanchor(drifted, mark = mark, seenFrom = Rooms.eye(0.5, 2.5), rooms = listOf(Rooms.aUpstairs, Rooms.a)),
        )
        assertEquals(1, fix.room)
    }

    @Test
    @DisplayName("an alignment turned 2° off still finds the corner; 60 cm of drift is within reach")
    fun `an alignment turned 2 deg off still finds the corner, 60 cm of drift is within reach`() {
        val turned = Rooms.drifted(Vec3(-0.12, 0.0, 0.0)).rotated(by = SyntheticPlates.degrees(2.0), about = Rooms.truth.apply(Vec3.zero))
        val fix = assertNotNull(
            ReferenceAlignment.reanchor(turned, mark = Rooms.mark(Rooms.cornerB), seenFrom = Rooms.eye(4.6, 2.55), rooms = rooms),
        )
        assertTrue(fix.room == 1 && fix.corner == Rooms.cornerB)
        assertTrue(ReferenceAlignment.horizontalDistance(fix.transform.apply(Rooms.cornerB), Rooms.mark(Rooms.cornerB)) < 1e-9)

        val far = Rooms.drifted(Vec3(-0.6, 0.0, 0.0))
        val farFix = assertNotNull(
            ReferenceAlignment.reanchor(far, mark = Rooms.mark(Vec3(8.0, 0.0, 3.0)), seenFrom = Rooms.eye(7.5, 2.5), rooms = rooms),
        )
        assertEquals(Vec3(8.0, 0.0, 3.0), farFix.corner)
    }
}

@DisplayName("Fix here: the turn from two corners")
class FixTurnTest {
    private val c1 = Vec3(0.0, 0.0, 3.0)
    private val c2 = Vec3(4.0, 0.0, 3.0)

    /** A fix of [c2] under an alignment turned [degrees] off about the room's middle. */
    private fun fix(degrees: Double): ReferenceAlignment.CornerFix {
        val off = Rooms.truth.rotated(by = SyntheticPlates.degrees(degrees), about = Rooms.truth.apply(Vec3(2.0, 0.0, 1.5)))
        val mark = Rooms.mark(c2)
        val delta = (mark - off.apply(c2)).copy(y = 0.0)
        return ReferenceAlignment.CornerFix(transform = off.translated(by = delta), corner = c2, room = 0, mark = mark)
    }

    @Test
    fun `two corners 4 m apart give the turn back, the second exactly on its mark`() {
        val turned = assertNotNull(ReferenceAlignment.turned(fix(1.5), previousCorner = c1, previousMark = Rooms.mark(c1)))
        expectClose(turned, Rooms.truth)
    }

    @Test
    @DisplayName("too close, marks that disagree with the model, or too big a turn: no turn")
    fun `too close, marks that disagree with the model, or too big a turn - no turn`() {
        val near = Vec3(2.0, 0.0, 3.0)
        assertNull(ReferenceAlignment.turned(fix(1.5), previousCorner = near, previousMark = Rooms.mark(near)))
        val stretched = Rooms.mark(c1) + YawTransform.rotate(Vec3(-0.06, 0.0, 0.0), by = Rooms.truth.yaw)
        assertNull(ReferenceAlignment.turned(fix(1.5), previousCorner = c1, previousMark = stretched))
        assertNull(ReferenceAlignment.turned(fix(5.0), previousCorner = c1, previousMark = Rooms.mark(c1)))
    }
}
