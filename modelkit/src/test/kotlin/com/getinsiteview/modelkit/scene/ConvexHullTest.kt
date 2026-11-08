package com.getinsiteview.modelkit.scene

import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private fun points(vararg p: Vec3): DoubleArray = p.flatMap { listOf(it.x, it.y, it.z) }.toDoubleArray()

@DisplayName("Convex hulls")
class ConvexHullTest {
    private val cubeCorners = (0 until 8).map { i -> Vec3(if (i and 1 == 0) -1.0 else 1.0, if (i and 2 == 0) -1.0 else 1.0, if (i and 4 == 0) -1.0 else 1.0) }

    @Test
    @DisplayName("A cube's hull is the cube, inner points and all")
    fun `A cube's hull is the cube, inner points and all`() {
        val random = Random(7)
        val inner = (0 until 200).map { Vec3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0)) }
        val hull = assertNotNull(ConvexHull.of(points(*(inner + cubeCorners).shuffled(random).toTypedArray())))
        assertEquals(8, hull.vertexCount, "only the corners")
        assertEquals(Bounds(Vec3(-1.0, -1.0, -1.0), Vec3(1.0, 1.0, 1.0)), hull.bounds)
        assertEquals(4.0, hull.intersect(Vec3(0.3, -0.2, 5.0), Vec3(0.0, 0.0, -1.0))!!, 1e-12)
        assertNull(hull.intersect(Vec3(1.2, 0.0, 5.0), Vec3(0.0, 0.0, -1.0)), "beside")
        assertNull(hull.intersect(Vec3(0.0, 0.0, 5.0), Vec3(0.0, 0.0, 1.0)), "pointing away")
        assertEquals(0.0, hull.intersect(Vec3(0.1, 0.1, 0.1), Vec3(1.0, 0.0, 0.0)), "from inside")
        assertEquals(2.0, hull.intersect(Vec3(0.0, 0.0, 5.0), Vec3(0.0, 0.0, -2.0))!!, 1e-12, "in the direction's units")
    }

    @Test
    @DisplayName("A tetrahedron cuts off the box's corners")
    fun `A tetrahedron cuts off the box's corners`() {
        val hull = assertNotNull(ConvexHull.of(points(Vec3.zero, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))))
        assertEquals(4, hull.vertexCount)
        assertEquals(1.4, hull.intersect(Vec3(0.2, 0.2, 2.0), Vec3(0.0, 0.0, -1.0))!!, 1e-12, "enters through the slanted face, at z = 0.6")
        assertNull(hull.intersect(Vec3(0.8, 0.8, 2.0), Vec3(0.0, 0.0, -1.0)), "inside its box, outside the tetrahedron")
        assertTrue(hull.contains(Vec3(0.1, 0.1, 0.1)))
        assertTrue(!hull.contains(Vec3(0.5, 0.5, 0.5)))
    }

    @Test
    @DisplayName("Flat, collinear and single points give their box")
    fun `Flat, collinear and single points give their box`() {
        val flat = assertNotNull(ConvexHull.of(points(Vec3.zero, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(0.5, 0.5, 0.0))))
        assertEquals(Bounds(Vec3.zero, Vec3(1.0, 1.0, 0.0)), flat.bounds)
        assertEquals(2.0, flat.intersect(Vec3(0.5, 0.5, 2.0), Vec3(0.0, 0.0, -1.0))!!, 1e-12, "a sheet still stops a ray across it")
        assertNull(flat.intersect(Vec3(0.5, 0.5, 2.0), Vec3(1.0, 0.0, 0.0)), "parallel above it")
        val line = assertNotNull(ConvexHull.of(points(Vec3.zero, Vec3(1.0, 1.0, 1.0), Vec3(2.0, 2.0, 2.0))))
        assertEquals(Bounds(Vec3.zero, Vec3(2.0, 2.0, 2.0)), line.bounds)
        val point = assertNotNull(ConvexHull.of(points(Vec3(1.0, 2.0, 3.0), Vec3(1.0, 2.0, 3.0))))
        assertEquals(4.0, point.intersect(Vec3(1.0, 2.0, 7.0), Vec3(0.0, 0.0, -1.0))!!, 1e-12)
        assertNull(ConvexHull.of(DoubleArray(0)))
    }

    @Test
    @DisplayName("Coplanar and repeated points on a cylinder's rings keep it convex and tight")
    fun `Coplanar and repeated points on a cylinder's rings keep it convex and tight`() {
        val ring = 32
        val pts = ArrayList<Vec3>()
        for (z in 0..10) for (k in 0 until ring) {
            val a = 2 * PI * k / ring
            pts += Vec3(0.05 * cos(a), 0.05 * sin(a), z * 0.3)
            pts += Vec3(0.05 * cos(a), 0.05 * sin(a), z * 0.3) // duplicates
        }
        pts += Vec3(0.0, 0.0, 0.0)
        pts += Vec3(0.0, 0.0, 3.0)
        val hull = assertNotNull(ConvexHull.of(points(*pts.toTypedArray())))
        for (p in pts) assertTrue(hull.contains(p, 1e-9), "$p")
        // A ray through the box's corner region misses the round hull; one through the middle hits.
        assertNull(hull.intersect(Vec3(0.048, 0.048, -1.0), Vec3(0.0, 0.0, 1.0)))
        assertEquals(1.0, hull.intersect(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 0.0, 1.0))!!, 1e-9)
        assertEquals(1.0 - 0.05, hull.intersect(Vec3(-1.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0))!!, 1e-9)
    }

    @Test
    @DisplayName("Random points on a sphere: every point inside, rays agree with the sphere")
    fun `Random points on a sphere - every point inside, rays agree with the sphere`() {
        val random = Random(42)
        val pts = (0 until 2000).map {
            val v = Vec3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
            val l = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
            Vec3(v.x / l, v.y / l, v.z / l)
        }
        val hull = assertNotNull(ConvexHull.of(points(*pts.toTypedArray())))
        // Points within the tolerance (3.5e-5 here) of the hull so far are left out.
        for (p in pts) assertTrue(hull.contains(p, 5e-4), "$p")
        val t = assertNotNull(hull.intersect(Vec3(0.0, 0.0, 3.0), Vec3(0.0, 0.0, -1.0)))
        assertTrue(t in 2.0..2.1, "$t")
        assertNull(hull.intersect(Vec3(1.01, 0.0, 3.0), Vec3(0.0, 0.0, -1.0)))
    }

    @Test
    @DisplayName("A box's hull is the box")
    fun `A box's hull is the box`() {
        val box = Bounds(Vec3(-1.0, 0.0, 2.0), Vec3(1.0, 0.5, 3.0))
        val hull = ConvexHull.of(box)
        for (o in listOf(Vec3(0.0, 0.2, 10.0), Vec3(3.0, 0.2, 2.5), Vec3(0.0, 0.2, 2.5))) {
            for (d in listOf(Vec3(0.0, 0.0, -1.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.3, 0.1, -1.0))) {
                val expected = ScenePicking.intersect(o, d, box)
                val actual = hull.intersect(o, d)
                assertEquals(expected == null, actual == null, "$o $d")
                if (expected != null) assertEquals(expected, actual!!, 1e-12, "$o $d")
            }
        }
    }
}
