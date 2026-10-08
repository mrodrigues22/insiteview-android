package com.getinsiteview.modelkit.geometry

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AR hides what isn't in front of you (docs/PLAN.md §3 "Behind a wall"). The rule is Meu Eletricista's
 * (`OclusaoPorParede` and `Desenho.opacidade` in its Obra screens):
 *
 * - **A wall decides, not a room.** An element is hidden when a wall of the model stands between the
 *   camera and it. "This room full, the rest hidden" was the first idea and it fails where it
 *   matters: an open kitchen and living room are one space, and many files have no rooms at all.
 * - **Distance fades too:** full up to 8 m, gone at 12 m, where the drawing is no longer reliable
 *   enough to show.
 *
 * What it changes from Meu Eletricista's:
 * - Behind a wall is hidden, not faded to 35 %. AR draws everything over the real walls, so a faint
 *   pipe in the next room still read as being in this one: the fade didn't tell rooms apart.
 * - Walls are the converter's footprints (chunk meta `f`): each wall seen from above, its parts
 *   higher than 1.2 m, not a centre line. The wall above a door or window closes it, as Meu
 *   Eletricista's closed segments do, so the next room doesn't show through a doorway; a passage
 *   open to the top is a gap. Cut at 1.2 m instead, doors and windows were gaps and every room's
 *   pipes showed through them.
 * - A wall you stand in hides nothing: a doorway is inside its wall's footprint, and walking
 *   through one would otherwise hide everything on both sides.
 * - An element is judged by its point nearest the camera, not its centre, so a pipe running in from
 *   the next room shows where it enters this one.
 * - What's set in the wall you face stays full: a crossing within [FACE_TOLERANCE] of
 *   either end doesn't count, so outlets, switches and pipes in that wall aren't behind it.
 */
object ProximityFade {
    /** Full opacity up to here (metres, on the plan). */
    const val FADE_START = 8.0

    /** Gone from here on: not drawn and not tappable. */
    const val CUT_OFF = 12.0

    /**
     * Behind a wall: hidden (not drawn, not tappable). Faded at 35 %, as Meu Eletricista does, it
     * still looked like part of the room you're in.
     */
    const val BEHIND_WALL = 0.0

    /**
     * Recompute only when the camera has moved this far; every frame would be wasted work while
     * someone stands still looking at a wall, which is most of the time on site.
     */
    const val RECOMPUTE_STEP = 0.30

    /**
     * Changes applied per frame: a first pass, or walking through a door, changes hundreds of
     * elements, and rewriting them all in one frame stalls the main thread.
     */
    const val CHANGES_PER_FRAME = 48

    /**
     * How far into a wall something can sit and still count as on that wall's near face: half a
     * 15 cm wall.
     */
    const val FACE_TOLERANCE = 0.075

    /** 1 near, [BEHIND_WALL] behind a wall, fading to 0 between [FADE_START] and [CUT_OFF]. */
    fun opacity(distance: Double, behindWall: Boolean): Double {
        val base = if (behindWall) BEHIND_WALL else 1.0
        if (distance <= FADE_START) return base
        if (distance >= CUT_OFF) return 0.0
        return base * (1 - (distance - FADE_START) / (CUT_OFF - FADE_START))
    }

    /** Opacities move in steps of 0.05, so a camera creeping along doesn't rewrite every element. */
    internal fun stepped(opacity: Double): Double = roundedHalfAwayFromZero(opacity * 20) / 20

    /** Swift's `rounded()` (to nearest, ties away from zero); Kotlin's `round` ties to even. */
    private fun roundedHalfAwayFromZero(x: Double): Double {
        if (!x.isFinite()) return x
        val magnitude = Math.round(kotlin.math.abs(x)).toDouble()
        return if (x < 0) -magnitude else magnitude
    }
}

/** The walls of one storey seen from above, to ask whether one stands between two points. */
class WallOcclusion(
    /** From wall footprints: polygons of plan `[x, z]` points (chunk meta `f`). */
    footprints: List<List<Vec2>>,
) {
    private class Edge(
        val a: Vec2,
        val b: Vec2,
        /** The footprint polygon it's an edge of. */
        val polygon: Int,
    )

    private data class Cell(val x: Int, val z: Int)

    private val polygons: List<List<Vec2>>
    private val edges: List<Edge>
    private val grid: Map<Cell, List<Int>>

    /** Polygons by the cells under their box, to find the walls the camera stands in. */
    private val polygonGrid: Map<Cell, List<Int>>

    init {
        val polygons = ArrayList<List<Vec2>>()
        val edges = ArrayList<Edge>()
        for (polygon in footprints) {
            if (polygon.size < 3) continue
            for (index in polygon.indices) {
                val a = polygon[index]
                val b = polygon[(index + 1) % polygon.size]
                if (a != b) edges.add(Edge(a, b, polygon = polygons.size))
            }
            polygons.add(polygon)
        }
        val grid = HashMap<Cell, MutableList<Int>>()
        for ((index, edge) in edges.withIndex()) {
            for (cell in cells(edge.a, edge.b)) {
                grid.getOrPut(cell) { ArrayList() }.add(index)
            }
        }
        val polygonGrid = HashMap<Cell, MutableList<Int>>()
        for ((index, polygon) in polygons.withIndex()) {
            val low = polygon.fold(polygon[0]) { acc, p -> Vec2.min(acc, p) }
            val high = polygon.fold(polygon[0]) { acc, p -> Vec2.max(acc, p) }
            for (cell in cells(low, high)) {
                polygonGrid.getOrPut(cell) { ArrayList() }.add(index)
            }
        }
        this.polygons = polygons
        this.edges = edges
        this.grid = grid
        this.polygonGrid = polygonGrid
    }

    val isEmpty: Boolean get() = edges.isEmpty()

    /**
     * Whether a wall stands between [from] (the camera) and [to] (the target), plan x/z.
     *
     * Only a proper crossing counts: touching a wall's end or running along it isn't one, so an
     * outlet in a corner doesn't fade because the line to it grazes the next wall. That errs on
     * showing, which is the right side: something shown that might have been hidden costs a line
     * on screen; hiding what's in front of you costs trust in the feature. For the same reason a
     * wall the camera stands in (a doorway) doesn't count.
     */
    fun blocks(from: Vec2, to: Vec2, faceTolerance: Double = ProximityFade.FACE_TOLERANCE): Boolean {
        val camera = from
        val target = to
        val standingIn = (cells(camera, camera).firstOrNull()?.let { polygonGrid[it] } ?: emptyList())
            .filter { contains(polygons[it], camera) }
            .toSet()
        val tested = HashSet<Int>()
        for (cell in cells(camera, target)) {
            for (index in grid[cell] ?: emptyList()) {
                if (!tested.add(index)) continue
                if (standingIn.contains(edges[index].polygon)) continue
                val point = crossing(camera, target, edges[index].a, edges[index].b) ?: continue
                if (distance(point, target) > faceTolerance && distance(point, camera) > faceTolerance) {
                    return true
                }
            }
        }
        return false
    }

    companion object {
        /** Side of a grid cell, metres: a line of sight is only tested against the walls near it. */
        internal const val CELL_SIZE = 2.0

        /** Where two segments properly cross, else `null`. */
        internal fun crossing(p0: Vec2, p1: Vec2, q0: Vec2, q1: Vec2): Vec2? {
            val d1 = side(p0, p1, q0)
            val d2 = side(p0, p1, q1)
            val d3 = side(q0, q1, p0)
            val d4 = side(q0, q1, p1)
            if (d1 == 0 || d2 == 0 || d3 == 0 || d4 == 0 || d1 == d2 || d3 == d4) return null
            val r = p1 - p0
            val s = q1 - q0
            val t = cross(q0 - p0, s) / cross(r, s)
            return p0 + r * t
        }

        /** Whether [point] is inside [polygon] (even-odd rule). */
        internal fun contains(polygon: List<Vec2>, point: Vec2): Boolean {
            var inside = false
            var previous = polygon[polygon.size - 1]
            for (current in polygon) {
                if ((current.y > point.y) != (previous.y > point.y) &&
                    point.x < (previous.x - current.x) * (point.y - current.y) / (previous.y - current.y) + current.x
                ) {
                    inside = !inside
                }
                previous = current
            }
            return inside
        }

        private fun side(a: Vec2, b: Vec2, c: Vec2): Int {
            val value = cross(b - a, c - a)
            return if (value > 1e-9) 1 else if (value < -1e-9) -1 else 0
        }

        private fun cross(u: Vec2, v: Vec2): Double = u.x * v.y - u.y * v.x

        internal fun distance(a: Vec2, b: Vec2): Double {
            val d = a - b
            return sqrt(d.x * d.x + d.y * d.y)
        }

        /**
         * The grid cells under a segment's box: a few more than the line crosses, much harder to get
         * wrong. Absurd coordinates (a model at the wrong scale) give no cells: better to fade nothing
         * than to freeze.
         */
        private fun cells(a: Vec2, b: Vec2): List<Cell> {
            if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) return emptyList()
            val x0 = floor(min(a.x, b.x) / CELL_SIZE).toLong()
            val x1 = floor(max(a.x, b.x) / CELL_SIZE).toLong()
            val z0 = floor(min(a.y, b.y) / CELL_SIZE).toLong()
            val z1 = floor(max(a.y, b.y) / CELL_SIZE).toLong()
            if (x1 - x0 > 4096 || z1 - z0 > 4096 || x0 < Int.MIN_VALUE || x1 > Int.MAX_VALUE || z0 < Int.MIN_VALUE || z1 > Int.MAX_VALUE) {
                return emptyList()
            }
            val cells = ArrayList<Cell>()
            for (x in x0..x1) {
                for (z in z0..z1) {
                    cells.add(Cell(x.toInt(), z.toInt()))
                }
            }
            return cells
        }
    }
}

/**
 * Which system elements to fade, and when, as the AR camera moves.
 *
 * The scene gives it each element's box and the walls (by storey); every frame it's told where the
 * camera is and answers with the opacities to change now. Opacity 1 means "as the filters say".
 *
 * A mutable class (a Swift struct with mutating methods).
 */
class ProximityTracker {
    /** An element seen from above: its box on the plan and its storey (walls of that storey hide it). */
    data class Target(val min: Vec2, val max: Vec2, val storeyID: String?) {
        constructor(bounds: Bounds, storeyID: String?) : this(
            min = Vec2(bounds.min.x, bounds.min.z),
            max = Vec2(bounds.max.x, bounds.max.z),
            storeyID = storeyID,
        )

        /** The box's point nearest [point]. */
        internal fun nearest(point: Vec2): Vec2 =
            Vec2(min(max(point.x, min.x), max.x), min(max(point.y, min.y), max.y))
    }

    /** One opacity change to apply (Swift's `(id:, opacity:)` tuple). */
    data class Change(val id: String, val opacity: Double)

    private val targetMap = LinkedHashMap<String, Target>()

    val targets: Map<String, Target> get() = targetMap.toMap()

    /**
     * Only distance fades, walls don't: when someone picked a room to look at, it isn't greyed out
     * for being behind a wall.
     */
    var ignoresWalls: Boolean = false
        set(value) {
            if (value != field) lastCamera = null
            field = value
        }

    private var walls: Map<String, WallOcclusion> = emptyMap()
    private var lastCamera: Vec2? = null

    /** What the scene shows now, for elements not at 1. */
    private val applied = HashMap<String, Double>()

    /** Changes waiting for a frame, in the order they were found. */
    private val pending = HashMap<String, Double>()
    private var queue = ArrayList<String>()
    private var head = 0

    /** The opacity the scene shows for an element now. */
    fun opacity(of: String): Double = applied[of] ?: 1.0

    fun setTarget(id: String, target: Target) {
        targetMap[id] = target
        lastCamera = null
    }

    fun removeTargets(ids: Iterable<String>) {
        for (id in ids) {
            targetMap.remove(id)
            applied.remove(id)
            pending.remove(id)
        }
    }

    /** The walls, by storey id (`null` for walls on no storey), as footprint polygons. */
    fun setWalls(footprints: Map<String?, List<List<Vec2>>>) {
        walls = footprints.entries.associate { key(it.key) to WallOcclusion(footprints = it.value) }
        lastCamera = null
    }

    /**
     * The opacity changes to apply now, the camera at [camera] (model coordinates). Recomputed once
     * the camera has moved [ProximityFade.RECOMPUTE_STEP]; at most [ProximityFade.CHANGES_PER_FRAME]
     * changes per call, the rest on the next calls.
     */
    fun update(camera: Vec3): List<Change> {
        val plan = Vec2(camera.x, camera.z)
        val last = lastCamera
        if (last == null || WallOcclusion.distance(last, plan) >= ProximityFade.RECOMPUTE_STEP) {
            lastCamera = plan
            for ((id, target) in targetMap) {
                val point = target.nearest(plan)
                val behind = !ignoresWalls && (walls[key(target.storeyID)]?.blocks(from = plan, to = point) ?: false)
                val wanted = ProximityFade.stepped(
                    ProximityFade.opacity(distance = WallOcclusion.distance(plan, point), behindWall = behind),
                )
                if (wanted == opacity(of = id)) {
                    pending.remove(id)
                } else {
                    if (!pending.containsKey(id)) queue.add(id)
                    pending[id] = wanted
                }
            }
        }
        return drain()
    }

    /**
     * Back to full everywhere (leaving AR): the elements that were faded. Starts over on the next
     * [update].
     */
    fun reset(): List<String> {
        val faded = applied.keys.toList()
        applied.clear()
        pending.clear()
        queue = ArrayList()
        head = 0
        lastCamera = null
        return faded
    }

    private fun drain(): List<Change> {
        val changes = ArrayList<Change>()
        while (changes.size < ProximityFade.CHANGES_PER_FRAME && head < queue.size) {
            val id = queue[head]
            head += 1
            val opacity = pending.remove(id) ?: continue
            if (opacity < 1) applied[id] = opacity else applied.remove(id)
            changes.add(Change(id, opacity))
        }
        if (head == queue.size) {
            queue = ArrayList()
            head = 0
        }
        return changes
    }

    private companion object {
        fun key(storeyID: String?): String = storeyID ?: ""
    }
}
