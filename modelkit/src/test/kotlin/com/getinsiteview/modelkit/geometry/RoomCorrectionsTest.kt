package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.Manifest
import java.net.URI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Room corrections (docs/PLAN.md §3 "Room corrections"): rooms A and B of [Rooms], where B is
 * really built 4 cm east, 2 cm north and turned 0.5° from the model.
 */
@DisplayName("Room corrections: the as-built frame, the camera's room and what a fix says")
class RoomCorrectionsTest {
    @Test
    @DisplayName("A correction turns about its pivot, then moves; it round-trips through a transform")
    fun `A correction turns about its pivot, then moves, it round-trips through a transform`() {
        val correction = RoomCorrection(pivot = Vec3(2.0, 0.0, 1.5), offset = Vec3(0.03, 0.2, -0.01), yaw = 0.02)
        assertEquals(0.0, correction.offset.y)
        val p = Vec3(5.0, 1.2, -1.0)
        val expected = YawTransform.rotate(p - Vec3(2.0, 0.0, 1.5), by = 0.02) + Vec3(2.0, 0.0, 1.5) + Vec3(0.03, 0.0, -0.01)
        assertTrue(Vector.distance(correction.transform.apply(p), expected) < 1e-12)
        val again = RoomCorrection(correction.transform, pivot = Vec3(2.0, 0.0, 1.5))
        assertTrue(Vector.distance(again.offset, correction.offset) < 1e-12 && abs(again.yaw - correction.yaw) < 1e-12)
        assertNull(RoomCorrection.of(Manifest.Space.Correction(pivot = listOf(1.0, 2.0), offset = listOf(0.0, 0.0, 0.0), yaw = 0.0)))
        assertEquals(
            RoomCorrection(pivot = correction.pivot, offset = correction.offset, yaw = correction.yaw),
            RoomCorrection.of(correction.manifest),
        )
    }

    @Test
    fun `A corrected outline is the room where it really is, inward normals turned with it`() {
        val room = assertNotNull(corrections(b = realB).room("b"))
        for ((model, real) in room.outline.corners.zip(room.corrected.corners)) {
            assertTrue(Vector.distance(realB.transform.apply(model), real) < 1e-12)
        }
        for ((model, real) in room.outline.walls.zip(room.corrected.walls)) {
            assertTrue(Vector.distance(YawTransform.rotate(model.inwardNormal, by = realB.yaw), real.inwardNormal) < 1e-12)
        }
        assertTrue(abs(Rooms.a.centroid.x - 2) < 1e-12 && abs(Rooms.a.centroid.z - 1.5) < 1e-12)
    }

    @Test
    @DisplayName("The camera takes the next room's correction once 30 cm inside it; outside every room it keeps the last")
    fun `The camera takes the next room's correction once 30 cm inside it, outside every room it keeps the last`() {
        val rooms = corrections()
        assertEquals("a", rooms.room(containing = Vec3(2.0, 1.4, 1.5), previous = null))
        assertEquals("a", rooms.room(containing = Vec3(4.3, 1.4, 1.5), previous = "a"))
        assertEquals("b", rooms.room(containing = Vec3(4.6, 1.4, 1.5), previous = "a"))
        assertEquals("b", rooms.room(containing = Vec3(4.07, 1.4, 1.5), previous = "b"))
        assertEquals("a", rooms.room(containing = Vec3(20.0, 1.4, 1.5), previous = "a"))
    }

    @Test
    @DisplayName("A plate's room is the one in front of it, ceiling plates included; the plate moves with it")
    fun `A plate's room is the one in front of it, ceiling plates included, the plate moves with it`() {
        val rooms = corrections(b = realB)
        val wall = PlateFrame(position = Vec3(6.0, 1.5, 0.0), normal = Vec3(0.0, 0.0, 1.0), up = Vec3(0.0, 1.0, 0.0))
        val ceiling = PlateFrame(position = Vec3(2.0, 2.7, 1.0), normal = Vec3(0.0, -1.0, 0.0), up = Vec3(0.0, 0.0, -1.0))
        assertEquals("b", rooms.room(ofPlate = wall))
        assertEquals("a", rooms.room(ofPlate = ceiling))
        val plate = Manifest.Plate(
            number = 2, label = "Panel", position = listOf(6.0, 1.5, 0.0), normal = listOf(0.0, 0.0, 1.0), up = listOf(0.0, 1.0, 0.0),
            sizeMm = 50, imageUrl = URI("https://api.test/v1/plates/2/image.png"),
        )
        val moved = rooms.corrected(plate).frame
        assertTrue(Vector.distance(moved.position, realB.transform.apply(wall.position)) < 1e-12)
        assertTrue(Vector.distance(moved.normal, YawTransform.rotate(wall.normal, by = realB.yaw)) < 1e-12)
    }

    @Test
    @DisplayName("Aligned in A, two corners of B fixed in a row measure B: its position and its turn")
    fun `Aligned in A, two corners of B fixed in a row measure B - its position and its turn`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0, corrections = corrections())
        anchoring.alignedByPoints(Rooms.truth, around = Rooms.truth.apply(Vec3(2.0, 0.0, 1.5)), camera = Rooms.eye(2.0, 1.5), room = "a", at = 1.0)
        val eye = realInB(Vec3(6.0, 1.35, 1.5))
        anchoring.cameraMoved(to = eye, at = 5.0)

        val aligned = assertNotNull(anchoring.alignment?.transform)
        val first = assertNotNull(anchoring.corrections.reanchor(aligned, mark = realInB(Vec3(8.0, 0.0, 0.0)), seenFrom = eye))
        assertEquals("b", first.spaceID)
        val fixed1 = anchoring.fixedAlignment(first, camera = eye, at = 6.0)

        assertTrue(fixed1)
        val one = assertNotNull(anchoring.lastObservation)
        assertTrue(one.spaceID == "b" && one.baseSpaceID == "a" && one.turn == null && one.hasTranslation)
        assertTrue(Vector.distance(one.anchor, Vec3(8.0, 0.0, 0.0)) < 1e-12)
        assertTrue(Vector.distance(one.markInBase, realB.transform.apply(Vec3(8.0, 0.0, 0.0))) < 1e-9)

        val afterFirst = assertNotNull(anchoring.alignment?.transform)
        val second = assertNotNull(anchoring.corrections.reanchor(afterFirst, mark = realInB(Vec3(8.0, 0.0, 3.0)), seenFrom = eye))
        val fixed2 = anchoring.fixedAlignment(second, camera = eye, at = 20.0)

        assertTrue(fixed2)
        val two = assertNotNull(anchoring.lastObservation)
        assertEquals(2, anchoring.observationCount)
        // Measured from A's alignment, not from the first fix in B.
        assertEquals("a", two.baseSpaceID)
        val turn = assertNotNull(two.turn)
        assertTrue(abs(turn - realB.yaw) < 1e-9)
        assertTrue(Vector.distance(two.correction.offset, realB.offset) < 1e-9)
        assertTrue(abs(two.correction.yaw - realB.yaw) < 1e-9)
        assertTrue(Vector.distance(two.correction.pivot, Rooms.b.centroid) < 1e-12)
    }

    @Test
    fun `A fix in the room the building was aligned in says nothing about it`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0, corrections = corrections())
        anchoring.alignedByPoints(Rooms.truth, camera = Rooms.eye(2.0, 1.5), room = "a", at = 1.0)
        val real = Rooms.truth.translated(by = Vec3(0.03, 0.0, 0.0))
        val fix = assertNotNull(
            anchoring.corrections.reanchor(Rooms.truth, mark = real.apply(Vec3(0.0, 0.0, 0.0)), seenFrom = real.apply(Vec3(1.0, 1.35, 1.0))),
        )
        assertEquals("a", fix.spaceID)
        val fixed3 = anchoring.fixedAlignment(fix, camera = real.apply(Vec3(1.0, 1.35, 1.0)), at = 2.0)

        assertTrue(fixed3)
        assertTrue(anchoring.lastObservation == null && anchoring.observationCount == 0)
    }

    @Test
    fun `Two fixes in a row in different rooms straighten the building, but give B's position only`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0, corrections = corrections())
        anchoring.alignedByPoints(Rooms.truth, camera = Rooms.eye(2.0, 1.5), room = "a", at = 1.0)
        val inA = Rooms.eye(3.5, 2.5)
        val fixA = assertNotNull(anchoring.corrections.reanchor(Rooms.truth, mark = Rooms.truth.apply(Vec3(4.0, 0.0, 3.0)), seenFrom = inA))
        val fixed4 = anchoring.fixedAlignment(fixA, camera = inA, at = 2.0)

        assertTrue(fixed4)
        val inB = realInB(Vec3(7.0, 1.35, 1.0))
        anchoring.cameraMoved(to = inB, at = 8.0)
        val afterA = assertNotNull(anchoring.alignment?.transform)
        val fixB = assertNotNull(anchoring.corrections.reanchor(afterA, mark = realInB(Vec3(8.0, 0.0, 0.0)), seenFrom = inB))
        val fixed5 = anchoring.fixedAlignment(fixB, camera = inB, at = 9.0)

        assertTrue(fixed5)
        val observation = assertNotNull(anchoring.lastObservation)
        assertTrue(observation.spaceID == "b" && observation.baseSpaceID == "a" && observation.turn == null)
    }

    @Test
    fun `The building is drawn with the camera's room's correction, gliding at the doorway`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0, corrections = corrections(b = realB))
        anchoring.alignedByPoints(Rooms.truth, camera = Rooms.eye(2.0, 1.5), room = "a", at = 1.0)
        expectClose(anchoring.transform(at = 2.0), Rooms.truth)
        anchoring.cameraMoved(to = Rooms.eye(6.0, 1.5), at = 10.0)
        assertEquals("b", anchoring.cameraRoom)
        assertTrue(anchoring.isBlending(at = 10.2))
        expectClose(anchoring.transform(at = 11.0), realB.transform.then(Rooms.truth))
        // B's corners are drawn on the real ones.
        val drawn = assertNotNull(anchoring.transform(at = 11.0))
        assertTrue(Vector.distance(drawn.apply(Vec3(8.0, 0.0, 3.0)), realInB(Vec3(8.0, 0.0, 3.0))) < 1e-9)
        anchoring.cameraMoved(to = Rooms.eye(1.0, 1.5), at = 20.0)
        expectClose(anchoring.transform(at = 21.0), Rooms.truth)
    }

    @Test
    fun `Marked in a corrected room, the alignment is its as-built equivalent and draws the room where it was marked`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0, corrections = corrections(b = realB))
        // Corners of B as in the model fitted to the real ones.
        val fitted = realB.transform.then(Rooms.truth)
        anchoring.alignedByPoints(fitted, camera = realInB(Vec3(6.0, 1.35, 1.5)), room = "b", at = 1.0)
        expectClose(anchoring.alignment?.transform, Rooms.truth)
        expectClose(anchoring.transform(at = 2.0), fitted)
    }

    @Test
    fun `A new correction moves the sites measured in that room so it stays where it is`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0, corrections = corrections())
        anchoring.alignedByPoints(Rooms.truth, camera = Rooms.eye(2.0, 1.5), room = "a", at = 1.0)
        val eye = realInB(Vec3(6.0, 1.35, 1.5))
        anchoring.cameraMoved(to = eye, at = 5.0)
        val fix = assertNotNull(anchoring.corrections.reanchor(Rooms.truth, mark = realInB(Vec3(8.0, 0.0, 0.0)), seenFrom = eye))
        val fixed6 = anchoring.fixedAlignment(fix, camera = eye, at = 6.0)

        assertTrue(fixed6)
        val before = assertNotNull(anchoring.transform(at = 7.0))

        val observation = assertNotNull(anchoring.lastObservation)
        anchoring.updateCorrections(anchoring.corrections.replacing("b", with = observation.correction), at = 8.0)
        expectClose(anchoring.transform(at = 9.0), before)
        // A's site still draws A as before.
        anchoring.cameraMoved(to = Rooms.eye(1.0, 1.5), at = 20.0)
        expectClose(anchoring.transform(at = 21.0), Rooms.truth)
    }

    companion object {
        private val realB = RoomCorrection(pivot = Rooms.b.centroid, offset = Vec3(0.04, 0.0, -0.02), yaw = SyntheticPlates.degrees(0.5))

        private fun corrections(a: RoomCorrection? = null, b: RoomCorrection? = null): RoomCorrections = RoomCorrections(
            rooms = listOf(
                RoomCorrections.Room(id = "a", outline = Rooms.a, correction = a),
                RoomCorrections.Room(id = "b", outline = Rooms.b, correction = b),
            ),
        )

        /** Where model point [p] of room B really is in the AR world (A is as in the model). */
        private fun realInB(p: Vec3): Vec3 = Rooms.truth.apply(realB.transform.apply(p))
    }
}
