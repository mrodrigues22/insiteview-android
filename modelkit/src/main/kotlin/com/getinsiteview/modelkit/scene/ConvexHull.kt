package com.getinsiteview.modelkit.scene

import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A convex polyhedron for picking (iOS: `ShapeResource.generateConvex(from:)`, SCN 358): the half-spaces
 * `normal · p ≤ offset` of its faces, in the element's own frame, exactly around the mesh (not grown).
 *
 * The planes always include the six of the points' box, so the shape stays bounded even where a
 * degenerate face was dropped, and fewer than four non-coplanar points give just their box (flat or
 * thinner, as RealityKit's hull of a flat mesh).
 */
class ConvexHull private constructor(
    /** The hull's corners, x y z for each. */
    val vertices: DoubleArray,
    /** Faces as `nx ny nz offset` (unit outward normals). */
    private val planes: DoubleArray,
    val bounds: Bounds,
) {
    /** Number of face planes (box planes included). */
    val planeCount: Int get() = planes.size / 4

    /** Number of corners. */
    val vertexCount: Int get() = vertices.size / 3

    /** Whether [p] is inside, or within [tolerance] of a face. */
    fun contains(p: Vec3, tolerance: Double = 1e-9): Boolean {
        for (i in 0 until planeCount) {
            val o = i * 4
            if (planes[o] * p.x + planes[o + 1] * p.y + planes[o + 2] * p.z - planes[o + 3] > tolerance) return false
        }
        return true
    }

    /**
     * Where a ray first meets the hull (the ray's parameter, ≥ 0; 0 from inside), or `null`, as
     * [ScenePicking.intersect] does for boxes. [direction] needn't be unit length.
     */
    fun intersect(origin: Vec3, direction: Vec3): Double? {
        var near = Double.NEGATIVE_INFINITY
        var far = Double.POSITIVE_INFINITY
        for (i in 0 until planeCount) {
            val o = i * 4
            val nx = planes[o]
            val ny = planes[o + 1]
            val nz = planes[o + 2]
            val distance = nx * origin.x + ny * origin.y + nz * origin.z - planes[o + 3] // > 0 outside
            val denominator = nx * direction.x + ny * direction.y + nz * direction.z
            if (abs(denominator) < 1e-15) {
                if (distance > 0) return null // parallel, outside this face
                continue
            }
            val t = -distance / denominator
            if (denominator < 0) near = max(near, t) else far = min(far, t)
            if (near > far) return null
        }
        if (far < 0) return null
        return max(near, 0.0)
    }

    companion object {
        /**
         * The convex hull of [points] (x y z for each; quickhull), or `null` for no points.
         * Points within [tolerance] of the hull so far are left out, so the result can miss a point
         * by a few tolerances; by default a hundred-thousandth of the points' size (0.03 mm on a 3 m
         * run, well under a chunk's 14-bit position step).
         */
        fun of(points: DoubleArray, tolerance: Double? = null): ConvexHull? {
            val n = points.size / 3
            if (n == 0) return null
            var lo = Vec3.repeating(Double.POSITIVE_INFINITY)
            var hi = Vec3.repeating(Double.NEGATIVE_INFINITY)
            for (i in 0 until n) {
                val p = Vec3(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])
                lo = Vec3.min(lo, p)
                hi = Vec3.max(hi, p)
            }
            val box = Bounds(min = lo, max = hi)
            val size = hi - lo
            val scale = max(max(abs(lo.x), abs(hi.x)), max(max(abs(lo.y), abs(hi.y)), max(abs(lo.z), abs(hi.z))))
            val eps = tolerance ?: max(1e-5 * sqrt(size.x * size.x + size.y * size.y + size.z * size.z), 1e-12 * max(scale, 1.0))
            val hull = Quickhull(points, n, eps).build()
            val boxPlanes = boxPlanes(box)
            if (hull == null) return ConvexHull(boxCorners(box), boxPlanes, box)
            val (corners, faces) = hull
            // Keep the faces every corner is behind: a sliver face whose normal came out wrong
            // would cut the hull, and dropping one only makes the shape a little larger.
            val kept = ArrayList<Double>(faces.size + boxPlanes.size)
            val slack = 4 * eps
            for (f in 0 until faces.size / 4) {
                val o = f * 4
                var ok = true
                for (v in 0 until corners.size / 3) {
                    val d = faces[o] * corners[v * 3] + faces[o + 1] * corners[v * 3 + 1] + faces[o + 2] * corners[v * 3 + 2] - faces[o + 3]
                    if (d > slack) {
                        ok = false
                        break
                    }
                }
                if (ok) for (k in 0 until 4) kept += faces[o + k]
            }
            for (value in boxPlanes) kept += value
            return ConvexHull(corners, kept.toDoubleArray(), box)
        }

        /** The hull of a box's corners (its six planes). */
        fun of(box: Bounds): ConvexHull = ConvexHull(boxCorners(box), boxPlanes(box), box)

        private fun boxPlanes(box: Bounds): DoubleArray = doubleArrayOf(
            1.0, 0.0, 0.0, box.max.x, -1.0, 0.0, 0.0, -box.min.x,
            0.0, 1.0, 0.0, box.max.y, 0.0, -1.0, 0.0, -box.min.y,
            0.0, 0.0, 1.0, box.max.z, 0.0, 0.0, -1.0, -box.min.z,
        )

        private fun boxCorners(box: Bounds): DoubleArray = DoubleArray(24) { i ->
            val corner = i / 3
            when (i % 3) {
                0 -> if (corner and 1 == 0) box.min.x else box.max.x
                1 -> if (corner and 2 == 0) box.min.y else box.max.y
                else -> if (corner and 4 == 0) box.min.z else box.max.z
            }
        }
    }
}

/**
 * Quickhull over triangles: a tetrahedron from extreme points, then the furthest outside point of a
 * face at a time, replacing the faces it sees (found from that face across shared edges) by a cone
 * from the horizon. Faces are triangles; coplanar ones aren't merged (their planes coincide, which
 * picking doesn't mind).
 */
private class Quickhull(private val p: DoubleArray, private val n: Int, private val eps: Double) {
    private val fa = IntList()
    private val fb = IntList()
    private val fc = IntList()
    private val normals = DoubleList() // nx ny nz offset
    private val alive = ArrayList<Boolean>()
    private val outside = ArrayList<IntList?>()

    /** Directed edge (from, to) → the face it belongs to (counter-clockwise from outside). */
    private val edges = HashMap<Long, Int>()

    private fun key(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xffffffffL)

    private fun x(i: Int) = p[i * 3]
    private fun y(i: Int) = p[i * 3 + 1]
    private fun z(i: Int) = p[i * 3 + 2]

    private fun distance(face: Int, i: Int): Double {
        val o = face * 4
        return normals[o] * x(i) + normals[o + 1] * y(i) + normals[o + 2] * z(i) - normals[o + 3]
    }

    /** Corners and planes, or `null` when the points don't span a volume. */
    fun build(): Pair<DoubleArray, DoubleArray>? {
        val simplex = simplex() ?: return null
        val (a, b, c, d) = simplex
        // Orient the base so d is behind it, then add the three sides.
        if (planeDistance(a, b, c, d) > 0) {
            addFace(a, c, b)
            addFace(a, b, d)
            addFace(b, c, d)
            addFace(c, a, d)
        } else {
            addFace(a, b, c)
            addFace(a, d, b)
            addFace(b, d, c)
            addFace(c, d, a)
        }
        val all = IntList()
        for (i in 0 until n) if (i != a && i != b && i != c && i != d) all.add(i)
        assign(all, 0 until fa.size)

        val stack = ArrayDeque<Int>()
        for (f in 0 until fa.size) stack.addLast(f)
        var guard = 0
        while (stack.isNotEmpty()) {
            val face = stack.removeLast()
            if (!alive[face]) continue
            val points = outside[face] ?: continue
            if (points.size == 0) continue
            if (++guard > 4 * n + 16) break
            var apex = points[0]
            var best = distance(face, apex)
            for (k in 1 until points.size) {
                val dk = distance(face, points[k])
                if (dk > best) {
                    best = dk
                    apex = points[k]
                }
            }
            for (f in add(face, apex)) stack.addLast(f)
        }

        val used = HashMap<Int, Int>()
        val corners = DoubleList()
        val planes = DoubleList()
        for (f in 0 until fa.size) {
            if (!alive[f]) continue
            for (v in intArrayOf(fa[f], fb[f], fc[f])) {
                if (used.containsKey(v)) continue
                used[v] = used.size
                corners.add(x(v))
                corners.add(y(v))
                corners.add(z(v))
            }
            if (normals[f * 4] == 0.0 && normals[f * 4 + 1] == 0.0 && normals[f * 4 + 2] == 0.0) continue
            for (k in 0 until 4) planes.add(normals[f * 4 + k])
        }
        return corners.toArray() to planes.toArray()
    }

    /** Four points spanning a volume, from the extremes, or `null`. */
    private fun simplex(): IntArray? {
        val extremes = IntArray(6)
        for (i in 1 until n) {
            if (x(i) < x(extremes[0])) extremes[0] = i
            if (x(i) > x(extremes[1])) extremes[1] = i
            if (y(i) < y(extremes[2])) extremes[2] = i
            if (y(i) > y(extremes[3])) extremes[3] = i
            if (z(i) < z(extremes[4])) extremes[4] = i
            if (z(i) > z(extremes[5])) extremes[5] = i
        }
        var a = -1
        var b = -1
        var bestSpan = -1.0
        for (i in 0 until 6) for (j in i + 1 until 6) {
            val d = dist2(extremes[i], extremes[j])
            if (d > bestSpan) {
                bestSpan = d
                a = extremes[i]
                b = extremes[j]
            }
        }
        if (sqrt(bestSpan) <= eps) return null
        // The point furthest from the line ab.
        var c = -1
        var bestLine = eps * eps
        val ux = x(b) - x(a)
        val uy = y(b) - y(a)
        val uz = z(b) - z(a)
        val ul = ux * ux + uy * uy + uz * uz
        for (i in 0 until n) {
            val vx = x(i) - x(a)
            val vy = y(i) - y(a)
            val vz = z(i) - z(a)
            val cx = uy * vz - uz * vy
            val cy = uz * vx - ux * vz
            val cz = ux * vy - uy * vx
            val d = (cx * cx + cy * cy + cz * cz) / ul
            if (d > bestLine) {
                bestLine = d
                c = i
            }
        }
        if (c < 0) return null
        // The point furthest from the plane abc.
        var d = -1
        var bestPlane = eps
        for (i in 0 until n) {
            val dist = abs(planeDistance(a, b, c, i))
            if (dist > bestPlane) {
                bestPlane = dist
                d = i
            }
        }
        if (d < 0) return null
        return intArrayOf(a, b, c, d)
    }

    private fun dist2(i: Int, j: Int): Double {
        val dx = x(i) - x(j)
        val dy = y(i) - y(j)
        val dz = z(i) - z(j)
        return dx * dx + dy * dy + dz * dz
    }

    /** Signed distance of [i] from the plane through a, b, c (counter-clockwise from the front). */
    private fun planeDistance(a: Int, b: Int, c: Int, i: Int): Double {
        val n = normal(a, b, c)
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        if (l == 0.0) return 0.0
        return (n[0] * (x(i) - x(a)) + n[1] * (y(i) - y(a)) + n[2] * (z(i) - z(a))) / l
    }

    private fun normal(a: Int, b: Int, c: Int): DoubleArray {
        val ux = x(b) - x(a)
        val uy = y(b) - y(a)
        val uz = z(b) - z(a)
        val vx = x(c) - x(a)
        val vy = y(c) - y(a)
        val vz = z(c) - z(a)
        return doubleArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    }

    private fun addFace(a: Int, b: Int, c: Int): Int {
        val f = fa.size
        fa.add(a)
        fb.add(b)
        fc.add(c)
        val nv = normal(a, b, c)
        val l = sqrt(nv[0] * nv[0] + nv[1] * nv[1] + nv[2] * nv[2])
        if (l > 0) {
            val nx = nv[0] / l
            val ny = nv[1] / l
            val nz = nv[2] / l
            normals.add(nx)
            normals.add(ny)
            normals.add(nz)
            normals.add(nx * x(a) + ny * y(a) + nz * z(a))
        } else {
            // Degenerate: no plane (it's dropped from the output, and nothing is outside it).
            repeat(3) { normals.add(0.0) }
            normals.add(Double.POSITIVE_INFINITY)
        }
        alive.add(true)
        outside.add(null)
        edges[key(a, b)] = f
        edges[key(b, c)] = f
        edges[key(c, a)] = f
        return f
    }

    /** Gives each point to the face it's furthest outside of (beyond [eps]); the others are inside. */
    private fun assign(points: IntList, faces: Iterable<Int>) {
        val list = faces.toList()
        for (k in 0 until points.size) {
            val i = points[k]
            var best = eps
            var face = -1
            for (f in list) {
                val d = distance(f, i)
                if (d > best) {
                    best = d
                    face = f
                }
            }
            if (face >= 0) (outside[face] ?: IntList().also { outside[face] = it }).add(i)
        }
    }

    /** Adds [apex] (outside [start]); returns the new faces. */
    private fun add(start: Int, apex: Int): List<Int> {
        // Faces the apex sees, connected to the start face.
        val visible = LinkedHashSet<Int>()
        val queue = ArrayDeque<Int>()
        visible += start
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            for ((u, v) in faceEdges(f)) {
                val g = edges[key(v, u)] ?: continue
                if (g in visible || !alive[g]) continue
                if (distance(g, apex) > eps) {
                    visible += g
                    queue.addLast(g)
                }
            }
        }
        // Horizon: edges of visible faces whose neighbour isn't visible.
        val horizon = ArrayList<IntArray>()
        for (f in visible) {
            for ((u, v) in faceEdges(f)) {
                val g = edges[key(v, u)]
                if (g == null || g !in visible) horizon += intArrayOf(u, v)
            }
        }
        val orphans = IntList()
        for (f in visible) {
            alive[f] = false
            for ((u, v) in faceEdges(f)) if (edges[key(u, v)] == f) edges.remove(key(u, v))
            outside[f]?.let { points -> for (k in 0 until points.size) if (points[k] != apex) orphans.add(points[k]) }
            outside[f] = null
        }
        val created = ArrayList<Int>(horizon.size)
        for ((u, v) in horizon.map { it[0] to it[1] }) created += addFace(u, v, apex)
        assign(orphans, created)
        return created
    }

    private fun faceEdges(f: Int): List<Pair<Int, Int>> = listOf(fa[f] to fb[f], fb[f] to fc[f], fc[f] to fa[f])
}

/** A growable `IntArray`. */
internal class IntList(capacity: Int = 16) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(max(16, size * 2))
        data[size++] = value
    }

    operator fun get(i: Int): Int = data[i]

    fun toArray(): IntArray = data.copyOf(size)
}

/** A growable `DoubleArray`. */
internal class DoubleList(capacity: Int = 16) {
    private var data = DoubleArray(capacity)
    var size = 0
        private set

    fun add(value: Double) {
        if (size == data.size) data = data.copyOf(max(16, size * 2))
        data[size++] = value
    }

    operator fun get(i: Int): Double = data[i]

    fun toArray(): DoubleArray = data.copyOf(size)
}
