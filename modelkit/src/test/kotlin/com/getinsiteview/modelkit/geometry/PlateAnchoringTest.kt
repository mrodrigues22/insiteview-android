package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.Manifest
import java.net.URI
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Plate anchoring: the AR alignment flow")
class PlateAnchoringTest {
    private val truth = YawTransform(yaw = SyntheticPlates.degrees(30.0), translation = Vec3(0.5, 0.0, -1.25))

    private val plates = listOf(
        plate(1, "Front door", SyntheticPlates.wall),
        plate(2, "Electrical panel", PlateFrame(position = Vec3(-3.0, 1.4, 2.0), normal = Vec3(1.0, 0.0, 0.0), up = Vec3(0.0, 1.0, 0.0))),
    )

    /** Feeds a full window of clean detections of [number], 1/30 s apart, from [start]. */
    private fun detect(
        number: Int,
        anchoring: PlateAnchoring,
        transform: YawTransform,
        start: Double,
        camera: Vec3? = null,
    ): PlateAnchoring.Event? {
        val model = plates.first { it.number == number }.frame
        var event: PlateAnchoring.Event? = null
        for (index in 0 until AlignmentSmoother.WINDOW_SIZE) {
            val world = SyntheticPlates.detection(model, transform)
            event = anchoring.observe(plate = number, world = world, camera = camera, at = start + index.toDouble() / 30) ?: event
        }
        return event
    }

    /** "Fix here" on model [corner], the building really at [real] there. */
    private fun fix(corner: Vec3, real: YawTransform): ReferenceAlignment.CornerFix =
        ReferenceAlignment.CornerFix(transform = real, corner = corner, room = 0, mark = real.apply(corner))

    @Test
    @DisplayName("No placed plates: straight to manual placement, no image detection")
    fun `No placed plates - straight to manual placement, no image detection`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = 2, at = 0.0)
        assertEquals(PlateAnchoring.Phase.Manual, anchoring.phase)
        assertTrue(anchoring.isManual)
        assertFalse(anchoring.wantsImageDetection)
        assertEquals(PlateAnchoring.Coaching.PlaceManually, anchoring.coaching)
        assertEquals(PlateAnchoring.Event.AlignedManually(firstIn = 7.5), anchoring.placedManually(truth, at = 7.5))
        assertEquals(PlateAnchoring.Coaching.None, anchoring.coaching)
        assertEquals(truth, anchoring.transform(at = 8.0))
    }

    @Test
    @DisplayName("Aligned by points: reported as points, kept by hand through tracking loss, a plate still re-anchors")
    fun `Aligned by points - reported as points, kept by hand through tracking loss, a plate still re-anchors`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        anchoring.switchToManual(at = 2.0)
        assertEquals(PlateAnchoring.Event.AlignedByPoints(firstIn = 30.0), anchoring.alignedByPoints(truth, at = 30.0))
        assertEquals(PlateAnchoring.Method.Points, anchoring.alignment?.method)
        assertTrue(anchoring.isManual)
        assertTrue(anchoring.wantsImageDetection)
        assertEquals(PlateAnchoring.Coaching.None, anchoring.coaching)
        anchoring.trackingLost(at = 31.0)
        assertTrue(anchoring.isManual)
        anchoring.trackingRestored(at = 32.0)
        assertEquals(PlateAnchoring.Phase.Manual, anchoring.phase)
        assertEquals(truth, anchoring.transform(at = 32.0))
        // A plate seen later takes over.
        val event = detect(1, anchoring, truth, start = 40.0)
        assertEquals(PlateAnchoring.Event.AlignedOnPlate(1, firstIn = null), event)
        assertFalse(anchoring.isManual)
    }

    @Test
    @DisplayName("Fix here and the floor glue glide an alignment by points there; not one placed by hand")
    fun `Fix here and the floor glue glide an alignment by points there, not one placed by hand`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0)
        val fixed = fix(Vec3(1.0, 0.0, 1.0), real = truth.translated(by = Vec3(0.1, 0.0, -0.05)))
        assertFalse(anchoring.canFix)
        val beforeAligning = anchoring.fixedAlignment(fixed, at = 1.0)
        assertFalse(beforeAligning)
        anchoring.placedManually(truth, at = 2.0)
        val placedByHand = anchoring.fixedAlignment(fixed, at = 3.0) || anchoring.raisedPointsAlignment(by = 0.02, at = 3.0)
        assertFalse(placedByHand)
        anchoring.alignedByPoints(truth, at = 4.0)
        assertTrue(anchoring.canFix)
        val byPoints = anchoring.fixedAlignment(fixed, at = 5.0)
        assertTrue(byPoints)
        assertTrue(anchoring.isBlending(at = 5.2))
        assertEquals(fixed.transform, anchoring.transform(at = 6.0))
        val glued = anchoring.raisedPointsAlignment(by = 0.02, at = 7.0)
        assertTrue(glued)
        expectClose(anchoring.transform(at = 8.0), fixed.transform.translated(by = Vec3(0.0, 0.02, 0.0)))
        assertTrue(anchoring.sites.all.all { abs(it.transform.translation.y - 0.02) < 1e-12 })
        assertTrue(anchoring.alignment?.method == PlateAnchoring.Method.Points && anchoring.phase == PlateAnchoring.Phase.Manual)
    }

    @Test
    fun `A fix in another room holds there and leaves the first room as it was (drift across the building)`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0)
        val inA = truth.apply(Vec3(0.5, 1.4, 0.5))
        anchoring.alignedByPoints(truth, around = truth.apply(Vec3.zero), camera = inA, at = 1.0)
        // 8 m away tracking drifted 15 cm: B's corner is fixed there.
        val real = truth.translated(by = Vec3(0.15, 0.0, 0.0))
        val inB = real.apply(Vec3(7.5, 1.4, 0.5))
        anchoring.cameraMoved(to = inB, at = 10.0)
        val fixedInB = fix(Vec3(8.0, 0.0, 0.0), real = real)
        val fixedThere = anchoring.fixedAlignment(fixedInB, camera = inB, at = 11.0)
        assertTrue(fixedThere)
        expectClose(anchoring.transform(at = 12.0), real)
        assertEquals(2, anchoring.sites.all.size)
        // Back in A: A's own alignment, gliding there.
        anchoring.cameraMoved(to = inA, at = 20.0)
        assertTrue(anchoring.isBlending(at = 20.2))
        expectClose(anchoring.transform(at = 21.0), truth)
        // And in B again, B's fix.
        anchoring.cameraMoved(to = inB, at = 30.0)
        expectClose(anchoring.transform(at = 31.0), real)
        assertEquals(PlateAnchoring.Method.Points, anchoring.alignment?.method)
    }

    @Test
    fun `Fixing the same spot again replaces the fix there`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0)
        anchoring.alignedByPoints(truth, around = truth.apply(Vec3.zero), at = 1.0)
        anchoring.fixedAlignment(fix(Vec3(8.0, 0.0, 0.0), real = truth.translated(by = Vec3(0.15, 0.0, 0.0))), at = 2.0)
        val again = fix(Vec3(8.0, 0.0, 1.0), real = truth.translated(by = Vec3(0.12, 0.0, 0.02)))
        anchoring.fixedAlignment(again, at = 3.0)
        assertEquals(2, anchoring.sites.all.size)
        assertEquals(again.transform, anchoring.alignment?.transform)
    }

    @Test
    @DisplayName("ARKit moving a site's anchor: 2 cm or more glides there, less waits until it adds up")
    fun `ARKit moving a site's anchor - 2 cm or more glides there, less waits until it adds up`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0)
        val site = truth.apply(Vec3.zero)
        anchoring.alignedByPoints(truth, around = site, at = 1.0)
        val id = assertNotNull(anchoring.sites.currentID)
        anchoring.siteAnchorsMoved(mapOf(id to YawTransform(yaw = 0.0, translation = site + Vec3(0.01, 0.0, 0.0))), at = 2.0)
        assertEquals(truth, anchoring.alignment?.transform)
        assertFalse(anchoring.isBlending(at = 2.1))
        anchoring.siteAnchorsMoved(mapOf(id to YawTransform(yaw = 0.0, translation = site + Vec3(0.025, 0.0, 0.0))), at = 3.0)
        assertTrue(anchoring.isBlending(at = 3.2))
        expectClose(anchoring.transform(at = 4.0), truth.translated(by = Vec3(0.025, 0.0, 0.0)))
        // An anchor that isn't there (removed) changes nothing.
        anchoring.siteAnchorsMoved(mapOf(UUID.randomUUID() to YawTransform.identity), at = 5.0)
        expectClose(anchoring.transform(at = 5.0), truth.translated(by = Vec3(0.025, 0.0, 0.0)))
    }

    @Test
    @DisplayName("While tracking is lost no site takes over; once it's back, where the camera is and the anchors' moves count")
    fun `While tracking is lost no site takes over, once it's back, where the camera is and the anchors' moves count`() {
        val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0)
        val inA = truth.apply(Vec3(0.5, 1.4, 0.5))
        anchoring.alignedByPoints(truth, around = truth.apply(Vec3.zero), camera = inA, at = 1.0)
        val real = truth.translated(by = Vec3(0.15, 0.0, 0.0))
        anchoring.fixedAlignment(fix(Vec3(8.0, 0.0, 0.0), real = real), at = 2.0)
        anchoring.trackingLost(at = 3.0)
        anchoring.cameraMoved(to = inA, at = 4.0)
        expectClose(anchoring.transform(at = 5.0), real)
        anchoring.trackingRestored(at = 6.0)
        expectClose(anchoring.transform(at = 7.0), truth)
    }

    @Test
    @DisplayName("On a plate: a fix holds where it's made; re-anchoring after a big move adds a site, Re-align starts over")
    fun `On a plate - a fix holds where it's made, re-anchoring after a big move adds a site, Re-align starts over`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        detect(1, anchoring, truth, start = 1.0, camera = Vec3(0.0, 1.4, 0.0))
        assertTrue(anchoring.canFix && anchoring.sites.all.size == 1)
        val real = truth.translated(by = Vec3(0.05, 0.0, 0.0))
        val fixedOnPlate = anchoring.fixedAlignment(fix(Vec3(-3.0, 0.0, 4.0), real = real), camera = real.apply(Vec3(-2.5, 1.4, 3.5)), at = 3.0)
        assertTrue(fixedOnPlate)
        assertTrue(anchoring.alignment?.method == PlateAnchoring.Method.Plate(1) && anchoring.phase == PlateAnchoring.Phase.Aligned)
        assertEquals(2, anchoring.sites.all.size)
        anchoring.cameraMoved(to = Vec3(6.0, 1.4, 6.0), at = 4.0)
        assertEquals(PlateAnchoring.Phase.Realigning(requested = false), anchoring.phase)
        detect(2, anchoring, truth, start = 5.0)
        assertTrue(anchoring.sites.all.size == 3 && anchoring.alignment?.method == PlateAnchoring.Method.Plate(2))
        anchoring.requestRealign(at = 10.0)
        detect(1, anchoring, truth, start = 11.0)
        assertEquals(1, anchoring.sites.all.size)
        anchoring.switchToManual(at = 12.0)
        assertTrue(anchoring.sites.all.isEmpty() && !anchoring.canFix)
    }

    @Test
    @DisplayName("Two fixes in a row 4 m apart straighten the turn for the room; one much later doesn't")
    fun `Two fixes in a row 4 m apart straighten the turn for the room, one much later doesn't`() {
        val c1 = Vec3(0.0, 0.0, 3.0)
        val c2 = Vec3(4.0, 0.0, 3.0)
        val middle = truth.apply(Vec3(2.0, 0.0, 1.5))
        val off = truth.rotated(by = SyntheticPlates.degrees(1.5), about = middle)
        for ((secondAfter, straightens) in listOf(60.0 to true, 200.0 to false)) {
            val anchoring = PlateAnchoring(plates = emptyList(), scannedPlate = null, at = 0.0)
            anchoring.alignedByPoints(off, around = middle, at = 1.0)
            val first = assertNotNull(
                ReferenceAlignment.reanchor(off, mark = truth.apply(c1), seenFrom = truth.apply(Vec3(0.5, 1.4, 2.5)), rooms = listOf(Rooms.a)),
            )
            anchoring.fixedAlignment(first, at = 10.0)
            val current = assertNotNull(anchoring.alignment?.transform)
            val second = assertNotNull(
                ReferenceAlignment.reanchor(current, mark = truth.apply(c2), seenFrom = truth.apply(Vec3(3.5, 1.4, 2.5)), rooms = listOf(Rooms.a)),
            )
            anchoring.fixedAlignment(second, at = 10 + secondAfter)
            assertEquals(3, anchoring.sites.all.size)
            val placed = assertNotNull(anchoring.alignment?.transform)
            if (straightens) {
                expectClose(placed, truth)
                // Every site in the room has it: the first fix's, and the first alignment's.
                for (site in anchoring.sites.all) {
                    expectClose(site.transform, truth)
                }
            } else {
                assertTrue(placed.yawDifference(to = truth) > SyntheticPlates.degrees(1.0))
            }
        }
    }

    @Test
    @DisplayName("Plate invocation: point at the scanned plate, align on its first detections (A-04)")
    fun `Plate invocation - point at the scanned plate, align on its first detections (A-04)`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = 2, at = 100.0)
        assertEquals(PlateAnchoring.Phase.Searching, anchoring.phase)
        assertTrue(anchoring.wantsImageDetection)
        assertEquals(PlateAnchoring.Coaching.PointAtScannedPlate(number = 2, label = "Electrical panel"), anchoring.coaching)

        anchoring.observe(plate = 2, world = SyntheticPlates.detection(plates[1].frame, truth), at = 101.0)
        assertEquals(PlateAnchoring.Coaching.HoldStill(number = 2), anchoring.coaching)

        val event = detect(2, anchoring, truth, start = 101.1)
        val firstIn = assertNotNull((event as? PlateAnchoring.Event.AlignedOnPlate)?.takeIf { it.plate == 2 }?.firstIn)
        // The detection at 101 plus nine more fill the window: 101.1 + 8/30 s.
        assertTrue(abs(firstIn - (1.1 + 8.0 / 30)) < 0.001)
        assertEquals(PlateAnchoring.Phase.Aligned, anchoring.phase)
        assertFalse(anchoring.wantsImageDetection)
        assertEquals("Electrical panel", anchoring.alignedPlate?.label)
        assertEquals(PlateAnchoring.Coaching.None, anchoring.coaching)
        expectClose(anchoring.transform(at = 102.0), truth)
    }

    @Test
    @DisplayName("A scanned plate that isn't placed: point at any plate")
    fun `A scanned plate that isn't placed - point at any plate`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = 9, at = 0.0)
        assertEquals(PlateAnchoring.Coaching.PointAtAnyPlate, anchoring.coaching)
        assertEquals(PlateAnchoring.Coaching.PointAtAnyPlate, PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0).coaching)
    }

    @Test
    fun `Detections of plates the manifest doesn't list are ignored`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        assertNull(anchoring.observe(plate = 7, world = SyntheticPlates.wall, at = 1.0))
        assertEquals(PlateAnchoring.Coaching.PointAtAnyPlate, anchoring.coaching)
    }

    @Test
    @DisplayName("10 s without a detection: manual placement; a plate found later still aligns, with a blend")
    fun `10 s without a detection - manual placement, a plate found later still aligns, with a blend`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        assertNull(anchoring.tick(at = 9.9))
        assertEquals(PlateAnchoring.Event.FellBackToManual, anchoring.tick(at = 10.0))
        assertTrue(anchoring.isManual)
        assertTrue(anchoring.wantsImageDetection)
        assertEquals(PlateAnchoring.Coaching.PlaceManually, anchoring.coaching)

        val manual = YawTransform(yaw = SyntheticPlates.degrees(28.0), translation = Vec3(0.45, 0.0, -1.2))
        assertEquals(PlateAnchoring.Event.AlignedManually(firstIn = 20.0), anchoring.placedManually(manual, at = 20.0))

        val event = detect(1, anchoring, truth, start = 30.0)
        assertEquals(PlateAnchoring.Event.AlignedOnPlate(1, firstIn = null), event)
        assertFalse(anchoring.isManual)
        val end = 30 + (AlignmentSmoother.WINDOW_SIZE - 1).toDouble() / 30
        assertTrue(anchoring.isBlending(at = end + 0.25))
        val midway = assertNotNull(anchoring.transform(at = end + 0.25))
        assertTrue(midway.yawDifference(to = manual) > 0 && midway.yawDifference(to = truth) > 0)
        expectClose(anchoring.transform(at = end + 0.5), truth)
        assertFalse(anchoring.isBlending(at = end + 0.5))
    }

    @Test
    fun `A plate in view holds off the fallback`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        anchoring.observe(plate = 1, world = SyntheticPlates.detection(plates[0].frame, truth), at = 9.5)
        assertNull(anchoring.tick(at = 10.0))
        assertEquals(PlateAnchoring.Phase.Searching, anchoring.phase)
        assertEquals(PlateAnchoring.Event.FellBackToManual, anchoring.tick(at = 10.6))
    }

    @Test
    @DisplayName("Re-align: look again, blend to the new plate; give up quietly after the timeout")
    fun `Re-align - look again, blend to the new plate, give up quietly after the timeout`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        detect(1, anchoring, truth, start = 1.0)
        anchoring.requestRealign(at = 5.0)
        assertEquals(PlateAnchoring.Phase.Realigning(requested = true), anchoring.phase)
        assertTrue(anchoring.wantsImageDetection)
        assertEquals(PlateAnchoring.Coaching.PointAtAnyPlate, anchoring.coaching)
        expectClose(anchoring.transform(at = 5.0), truth)

        // Drift: plate 2 says the building is 4 cm and 0.5° off.
        val drifted = YawTransform(yaw = truth.yaw + SyntheticPlates.degrees(0.5), translation = truth.translation + Vec3(0.04, 0.0, 0.0))
        assertEquals(PlateAnchoring.Event.AlignedOnPlate(2, firstIn = null), detect(2, anchoring, drifted, start = 6.0))
        assertEquals(2, anchoring.alignedPlate?.number)
        expectClose(anchoring.transform(at = 7.0), drifted)

        anchoring.requestRealign(at = 10.0)
        assertNull(anchoring.tick(at = 10 + PlateAnchoring.REALIGN_TIMEOUT))
        assertEquals(PlateAnchoring.Phase.Aligned, anchoring.phase)
        assertEquals(2, anchoring.alignedPlate?.number)
    }

    @Test
    fun `Walking more than 4 m from where it aligned turns detection back on, quietly`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        detect(1, anchoring, truth, start = 1.0, camera = Vec3(0.0, 1.4, 0.0))
        anchoring.cameraMoved(to = Vec3(3.0, 1.4, 0.0), at = 3.0)
        assertEquals(PlateAnchoring.Phase.Aligned, anchoring.phase)
        anchoring.cameraMoved(to = Vec3(3.0, 1.4, 3.0), at = 4.0)
        assertEquals(PlateAnchoring.Phase.Realigning(requested = false), anchoring.phase)
        assertTrue(anchoring.wantsImageDetection)
        assertEquals(PlateAnchoring.Coaching.None, anchoring.coaching)
        assertEquals(PlateAnchoring.Event.AlignedOnPlate(2, firstIn = null), detect(2, anchoring, truth, start = 5.0))
    }

    @Test
    @DisplayName("Tracking lost: coaching comes back; relocalized: the alignment holds")
    fun `Tracking lost - coaching comes back, relocalized - the alignment holds`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        anchoring.trackingLost(at = 0.5)
        assertEquals(PlateAnchoring.Phase.Searching, anchoring.phase) // nothing to lose yet
        detect(1, anchoring, truth, start = 1.0)
        anchoring.trackingLost(at = 3.0)
        assertEquals(PlateAnchoring.Phase.Lost, anchoring.phase)
        assertEquals(PlateAnchoring.Coaching.AlignmentLost, anchoring.coaching)
        assertTrue(anchoring.wantsImageDetection)
        anchoring.trackingRestored(at = 4.0)
        assertEquals(PlateAnchoring.Phase.Aligned, anchoring.phase)
        assertEquals(PlateAnchoring.Coaching.None, anchoring.coaching)

        anchoring.trackingLost(at = 5.0)
        assertEquals(PlateAnchoring.Event.AlignedOnPlate(2, firstIn = null), detect(2, anchoring, truth, start = 6.0))
        assertEquals(PlateAnchoring.Phase.Aligned, anchoring.phase)
    }

    @Test
    @DisplayName("Place manually after a plate alignment starts from it; lost manual placement returns to manual")
    fun `Place manually after a plate alignment starts from it, lost manual placement returns to manual`() {
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        detect(1, anchoring, truth, start = 1.0)
        anchoring.switchToManual(at = 3.0)
        assertTrue(anchoring.isManual)
        assertEquals(PlateAnchoring.Method.Manual, anchoring.alignment?.method)
        expectClose(anchoring.transform(at = 3.0), truth)
        anchoring.trackingLost(at = 4.0)
        anchoring.trackingRestored(at = 5.0)
        assertEquals(PlateAnchoring.Phase.Manual, anchoring.phase)
    }

    @Test
    fun `Tracking lost after a manual placement stays manual, with or without plates`() {
        for (placed in listOf(emptyList(), plates)) {
            val anchoring = PlateAnchoring(plates = placed, scannedPlate = null, at = 0.0)
            anchoring.tick(at = PlateAnchoring.FALLBACK_DELAY)
            anchoring.placedManually(truth, at = 11.0)
            anchoring.trackingLost(at = 12.0)
            assertEquals(PlateAnchoring.Phase.Lost, anchoring.phase)
            assertTrue(anchoring.isManual)
            anchoring.trackingRestored(at = 13.0)
            assertTrue(anchoring.isManual)
        }
        // A lost plate alignment still asks for a plate.
        val anchoring = PlateAnchoring(plates = plates, scannedPlate = null, at = 0.0)
        detect(1, anchoring, truth, start = 1.0)
        anchoring.trackingLost(at = 3.0)
        assertFalse(anchoring.isManual)
    }

    companion object {
        fun plate(number: Int, label: String, frame: PlateFrame): Manifest.Plate = Manifest.Plate(
            number = number,
            label = label,
            position = listOf(frame.position.x, frame.position.y, frame.position.z),
            normal = listOf(frame.normal.x, frame.normal.y, frame.normal.z),
            up = listOf(frame.up.x, frame.up.y, frame.up.z),
            sizeMm = 50,
            imageUrl = URI("https://api.test/v1/plates/$number/image.png"),
        )
    }
}
