package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.Manifest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/**
 * A room's floor outline from the manifest (`Manifest.Space.outline`): its corners on the floor
 * and its walls, each with the normal pointing into the room. AR alignment by reference points
 * takes its corners from here and puts outlets and switches on these walls (docs/PLAN.md §3
 * "Alignment by reference points").
 */
data class RoomOutline(
    /**
     * Corners on the floor, model coordinates, in outline order: seen from above the room is on
     * the left of every edge.
     */
    val corners: List<Vec3>,
    /** The floor height, model metres. */
    val floorY: Double,
) {
    /** One edge of the outline: a wall's surface seen from inside the room. */
    data class Wall(
        val start: Vec3,
        val end: Vec3,
        /** Horizontal, unit length, into the room: up × (end − start). */
        val inwardNormal: Vec3,
    ) {
        val length: Double get() = Vector.distance(start, end)
    }

    /** Where a point is relative to its nearest wall. */
    data class WallPosition(
        val wall: Wall,
        /** The point moved onto the wall's surface (same height). */
        val foot: Vec3,
        /** Horizontal distance from the wall's surface, positive into the room. */
        val signedDistance: Double,
        /** Facing the wall from inside the room: horizontal distance to its left and right ends. */
        val fromLeftCorner: Double,
        val fromRightCorner: Double,
    )

    val walls: List<Wall>
        get() = corners.indices.mapNotNull { index -> wall(corners[index], corners[(index + 1) % corners.size]) }

    /** A corner of the room: where two of its walls meet, as seen from inside. */
    data class Corner(
        val position: Vec3,
        /** The wall arriving at the corner and the one leaving it, in outline order. */
        val incoming: Wall,
        val outgoing: Wall,
        /** The room is less than half a turn wide here (a reflex corner juts into the room). */
        val isConvex: Boolean,
    ) {
        /**
         * How far [point] is on the room's side of the corner, seen from above (negative behind):
         * in front of both walls at a convex corner, of either at a reflex one. A floor corner can
         * only be seen from its own room; from the next room, the corner on this side of the wall
         * is behind it.
         */
        fun facingMargin(point: Vec3): Double {
            val offset = Vec3(point.x - position.x, 0.0, point.z - position.z)
            val incomingSide = Vector.dot(offset, incoming.inwardNormal)
            val outgoingSide = Vector.dot(offset, outgoing.inwardNormal)
            return if (isConvex) min(incomingSide, outgoingSide) else max(incomingSide, outgoingSide)
        }

        /** [point] is on the room's side of the corner, or up to [tolerance] metres behind. */
        fun isFacing(point: Vec3, tolerance: Double): Boolean = facingMargin(point) >= -tolerance
    }

    /**
     * The corner at outline point [index]: `null` where the outline goes straight on (or turns
     * back on itself), which isn't a corner in the room. Repeated points are skipped.
     */
    fun corner(at: Int): Corner? {
        val index = at
        if (index !in corners.indices) return null
        val position = corners[index]
        fun neighbour(step: Int): Vec3? {
            var other = index
            for (i in 1 until corners.size) {
                other = (other + step + corners.size) % corners.size
                if (ReferenceAlignment.horizontalDistance(corners[other], position) > SAME_POINT) return corners[other]
            }
            return null
        }
        val previous = neighbour(-1) ?: return null
        val next = neighbour(1) ?: return null
        val incoming = wall(previous, position) ?: return null
        val outgoing = wall(position, next) ?: return null
        val arriving = Vector.normalized(Vec3(position.x - previous.x, 0.0, position.z - previous.z))
        val leaving = Vector.normalized(Vec3(next.x - position.x, 0.0, next.z - position.z))
        val turn = acos(min(max(Vector.dot(arriving, leaving), -1.0), 1.0))
        if (!(turn >= MINIMUM_CORNER_TURN && turn <= PI - MINIMUM_CORNER_TURN)) return null
        // The room is on the left: leaving towards the room's side of the incoming wall turns left.
        return Corner(position, incoming, outgoing, isConvex = Vector.dot(leaving, incoming.inwardNormal) > 0)
    }

    /** Floor area seen from above, square metres. */
    val area: Double
        get() {
            var twice = 0.0
            for ((index, a) in corners.withIndex()) {
                val b = corners[(index + 1) % corners.size]
                twice += a.x * b.z - b.x * a.z
            }
            // The room on the left of every edge makes this negative in x–z; the size is what counts.
            return abs(twice) / 2
        }

    /**
     * The floor's centre of area seen from above, at floor height: the pivot a room's correction
     * turns about (the API uses the same one, master PLAN §9 "Room corrections").
     */
    val centroid: Vec3
        get() {
            var twice = 0.0
            var x = 0.0
            var z = 0.0
            for ((index, a) in corners.withIndex()) {
                val b = corners[(index + 1) % corners.size]
                val cross = a.x * b.z - b.x * a.z
                twice += cross
                x += (a.x + b.x) * cross
                z += (a.z + b.z) * cross
            }
            if (!(abs(twice) > 1e-9)) {
                val mean = corners.fold(Vec3.zero) { acc, c -> acc + c } / max(corners.size, 1).toDouble()
                return Vec3(mean.x, floorY, mean.z)
            }
            return Vec3(x / (3 * twice), floorY, z / (3 * twice))
        }

    /** The same room moved by [transform] (a room correction): its corners, seen from above. */
    fun moved(by: YawTransform): RoomOutline =
        RoomOutline(corners = corners.map { by.apply(it) }, floorY = floorY + by.translation.y)

    /** Whether [point] is inside the outline, seen from above (its height isn't checked). */
    fun contains(point: Vec3): Boolean {
        var inside = false
        for ((index, a) in corners.withIndex()) {
            val b = corners[(index + 1) % corners.size]
            if ((a.z > point.z) != (b.z > point.z) && point.x < a.x + (point.z - a.z) / (b.z - a.z) * (b.x - a.x)) {
                inside = !inside
            }
        }
        return inside
    }

    /** Where [point] is relative to the wall nearest to it horizontally. */
    fun nearestWall(to: Vec3): WallPosition? = walls.map { position(of = to, on = it) }.minByOrNull { abs(it.signedDistance) }

    /**
     * [of] relative to one wall: its foot on the wall (clamped to the wall's ends) and the
     * distances to the wall's ends.
     */
    fun position(of: Vec3, on: Wall): WallPosition {
        val point = of
        val wall = on
        val along = Vec3(wall.end.x - wall.start.x, 0.0, wall.end.z - wall.start.z)
        val length = Vector.length(along)
        val direction = along / length
        val offset = Vec3(point.x - wall.start.x, 0.0, point.z - wall.start.z)
        val t = min(max(Vector.dot(offset, direction), 0.0), length)
        val foot = Vec3(wall.start.x + direction.x * t, point.y, wall.start.z + direction.z * t)
        val signed = Vector.dot(Vec3(point.x - foot.x, 0.0, point.z - foot.z), wall.inwardNormal)
        val outside = Vector.length(Vec3(point.x - foot.x, 0.0, point.z - foot.z))
        // Off the wall's ends, the horizontal distance is to the nearest end, not to the line.
        val distance = if (abs(signed) < outside - 1e-9) (if (signed < 0) -outside else outside) else signed
        // Walking start → end the room is on the left, so facing the wall from inside, the start
        // is on the right and the end on the left.
        return WallPosition(wall, foot, signedDistance = distance, fromLeftCorner = length - t, fromRightCorner = t)
    }

    companion object {
        /** Outline points where the wall turns less than this aren't corners anyone can aim at. */
        internal const val MINIMUM_CORNER_TURN = 10 * PI / 180

        /** Outline points closer than this are one point. */
        internal const val SAME_POINT = 0.001

        /**
         * A point this far below a room's floor, or this far above it, isn't in that room (another
         * storey's room in the same place seen from above).
         */
        internal val STOREY_BAND = -0.3..2.5

        /** The room's outline, or `null` when the manifest has no usable room geometry (iOS `init?(space:)`). */
        fun of(space: Manifest.Space): RoomOutline? {
            val outline = space.outline ?: return null
            val floorY = space.floorY ?: return null
            if (!floorY.isFinite() || outline.size < 3) return null
            val corners = ArrayList<Vec3>(outline.size)
            for (point in outline) {
                if (point.size != 2 || !point[0].isFinite() || !point[1].isFinite()) return null
                corners.add(Vec3(point[0], floorY, point[1]))
            }
            return RoomOutline(corners = corners, floorY = floorY)
        }

        private fun wall(start: Vec3, end: Vec3): Wall? {
            val along = Vec3(end.x - start.x, 0.0, end.z - start.z)
            if (!(Vector.length(along) > 1e-6)) return null
            // up × (dx, 0, dz) = (dz, 0, −dx)
            return Wall(start, end, inwardNormal = Vector.normalized(Vec3(along.z, 0.0, -along.x)))
        }

        /**
         * The room [point] is in: of the outlines around it seen from above, on its storey, the
         * smallest (rooms can overlap: a closet drawn inside a bedroom). `null` when it's in none.
         */
        fun index(containing: Vec3, outlines: List<RoomOutline>): Int? {
            val point = containing
            return outlines.indices
                .filter { (point.y - outlines[it].floorY) in STOREY_BAND && outlines[it].contains(point) }
                .minByOrNull { outlines[it].area }
        }
    }
}
