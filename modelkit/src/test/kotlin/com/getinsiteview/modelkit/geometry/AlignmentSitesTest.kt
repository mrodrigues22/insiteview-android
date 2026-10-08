package com.getinsiteview.modelkit.geometry

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Alignment sites: local alignments tied to anchors")
class AlignmentSitesTest {
    private val truth = YawTransform(yaw = SyntheticPlates.degrees(30.0), translation = Vec3(0.5, 0.0, -1.25))

    private fun site(x: Double, z: Double = 0.0, y: Double = 0.0, time: Double = 0.0): AlignmentSite =
        AlignmentSite(method = PlateAnchoring.Method.Points, transform = truth, around = Vec3(x, y, z), camera = null, at = time)

    @Test
    fun `inverse and then`() {
        val a = YawTransform(yaw = 0.7, translation = Vec3(1.0, 2.0, 3.0))
        val b = YawTransform(yaw = -2.1, translation = Vec3(-0.5, 0.1, 4.0))
        val p = Vec3(0.3, -1.2, 2.5)
        assertTrue(Vector.distance(a.then(b).apply(p), b.apply(a.apply(p))) < 1e-12)
        assertTrue(Vector.distance(a.then(a.inverse).apply(p), p) < 1e-12)
        assertTrue(Vector.distance(a.inverse.apply(a.apply(p)), p) < 1e-12)
        assertTrue(Vector.distance(a.inverse.apply(p), a.inverseApply(p)) < 1e-12)
    }

    @Test
    fun `ARKit moving the anchor moves the building with it, turning about the anchor`() {
        var site = site(3.0, 2.0)
        assertEquals(truth, site.transform)
        val shift = Vec3(0.05, 0.01, -0.03)
        site = site.anchorMoved(to = YawTransform(yaw = SyntheticPlates.degrees(1.0), translation = Vec3(3.0, 0.0, 2.0) + shift))
        // The model point on the anchor goes where the anchor went.
        val onAnchor = truth.inverseApply(Vec3(3.0, 0.0, 2.0))
        assertTrue(Vector.distance(site.transform.apply(onAnchor), Vec3(3.0, 0.0, 2.0) + shift) < 1e-12)
        assertTrue(abs(site.transform.yaw - truth.yaw - SyntheticPlates.degrees(1.0)) < 1e-12)
        assertEquals(Vec3(3.0, 0.0, 2.0) + shift, site.point)
        // Retargeted, it places the building there whatever the anchor did.
        val other = truth.translated(by = Vec3(0.2, 0.0, 0.0))
        site = site.retarget(to = other)
        expectClose(site.transform, other, position = 1e-9, angle = 1e-12)
        site = site.raise(by = 0.02)
        expectClose(site.transform, other.translated(by = Vec3(0.0, 0.02, 0.0)), position = 1e-9, angle = 1e-12)
    }

    @Test
    fun `the nearest site takes over only once it's a metre nearer, walking back doesn't flip it`() {
        val sites = AlignmentSites()
        val a = site(0.0)
        val b = site(6.0)
        sites.startOver(with = a)
        sites.add(b)
        assertEquals(b.id, sites.currentID)
        assertFalse(sites.select(near = Vec3(3.4, 1.4, 0.0)))
        assertTrue(sites.select(near = Vec3(2.4, 1.4, 0.0)))
        assertEquals(a.id, sites.currentID)
        assertFalse(sites.select(near = Vec3(3.4, 1.4, 0.0)))
        assertTrue(sites.select(near = Vec3(3.6, 1.4, 0.0)))
        assertEquals(b.id, sites.currentID)
        assertFalse(sites.select(near = Vec3(2.6, 1.4, 0.0)))
    }

    @Test
    fun `a site upstairs doesn't take over downstairs, however near seen from above`() {
        val sites = AlignmentSites()
        val downstairs = site(4.0)
        val upstairs = site(0.0, y = 3.0)
        sites.startOver(with = downstairs)
        sites.add(upstairs)
        assertTrue(sites.select(near = Vec3(0.0, 1.4, 0.0)))
        assertEquals(downstairs.id, sites.currentID)
        assertFalse(sites.select(near = Vec3(0.2, 1.4, 0.0)))
    }

    @Test
    @DisplayName("a new site replaces those within 1.5 m; the oldest go beyond 16")
    fun `a new site replaces those within 1,5 m, the oldest go beyond 16`() {
        val sites = AlignmentSites()
        sites.startOver(with = site(0.0))
        sites.add(site(6.0))
        val replacing = site(6.5, 1.0)
        sites.add(replacing)
        assertTrue(sites.all.size == 2 && sites.currentID == replacing.id)
        for (index in 0 until 20) {
            sites.add(site(10 + 2 * index.toDouble()))
        }
        assertEquals(AlignmentSites.MAXIMUM_COUNT, sites.all.size)
        assertEquals(48.0, sites.current?.point?.x)
        sites.removeAll()
        assertNull(sites.current)
        assertTrue(sites.ids.isEmpty())
    }

    @Test
    @DisplayName("the floor glue raises every site; a straightened turn takes over the sites around it")
    fun `the floor glue raises every site, a straightened turn takes over the sites around it`() {
        val sites = AlignmentSites()
        sites.startOver(with = site(0.0))
        sites.add(site(4.0))
        sites.add(site(12.0))
        sites.raise(by = -0.03)
        assertTrue(sites.all.all { abs(it.transform.translation.y + 0.03) < 1e-12 })
        val straight = truth.rotated(by = 0.01, about = Vec3.zero)
        sites.retarget(around = Vec3(2.0, 0.0, 0.0), within = 2.5, to = straight)
        assertEquals(listOf(true, true, false), sites.all.map { it.transform == straight })
    }
}
