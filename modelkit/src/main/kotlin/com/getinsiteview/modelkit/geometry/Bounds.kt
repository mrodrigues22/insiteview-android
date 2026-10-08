package com.getinsiteview.modelkit.geometry

import kotlin.math.max
import kotlin.math.sqrt

/** An axis-aligned box in model coordinates (metres, Y up). */
data class Bounds(val min: Vec3, val max: Vec3) {
    val center: Vec3 get() = (min + max) / 2.0

    /** Size along each axis. */
    val extents: Vec3 get() = max - min

    /** Radius of the sphere around the box: half its diagonal. */
    val radius: Double
        get() {
            val e = extents
            return sqrt(e.x * e.x + e.y * e.y + e.z * e.z) / 2
        }

    fun union(other: Bounds): Bounds = Bounds(
        min = Vec3(kotlin.math.min(min.x, other.min.x), kotlin.math.min(min.y, other.min.y), kotlin.math.min(min.z, other.min.z)),
        max = Vec3(max(max.x, other.max.x), max(max.y, other.max.y), max(max.z, other.max.z)),
    )

    companion object {
        /** The union of all boxes, or `null` for none. */
        fun union(boxes: Iterable<Bounds>): Bounds? = boxes.fold(null as Bounds?) { partial, box -> partial?.union(box) ?: box }
    }
}

/** How a tappable element gets its collision shape (docs/PLAN.md §3 "Loading a building", step 4). */
sealed interface PickingShape {
    /**
     * A box from the visual bounds, grown to [MINIMUM_SIZE] on thin axes so 16 mm conduits
     * can still be tapped.
     */
    data class Box(val center: Vec3, val size: Vec3) : PickingShape

    /**
     * The convex hull of the element's mesh: long diagonal runs, whose bounding box would cover
     * empty space and steal taps from everything behind it. A straight segment is convex, so
     * the hull fits it exactly.
     */
    data object ConvexHull : PickingShape

    companion object {
        /** Smallest box side for picking, in metres. */
        const val MINIMUM_SIZE = 0.06

        /** Boxes whose two larger sides are both at least this long are flat or diagonal. */
        internal const val DIAGONAL_SIDE = 0.6

        /**
         * Picks the shape for an element with these visual bounds (in the element's own frame).
         * A box fits axis-aligned runs (`3 × 0.05 × 0.05`); a run that is long along two axes
         * and thin along the third (`3 × 3 × 0.05`, a pipe at 45°) gets its convex hull instead.
         * Architecture and structure always use boxes (walls and slabs fill theirs).
         */
        fun forElement(bounds: Bounds, isSystemElement: Boolean): PickingShape {
            val size = bounds.extents
            val sides = listOf(size.x, size.y, size.z).sorted()
            if (isSystemElement && sides[1] >= DIAGONAL_SIDE && sides[0] < sides[1] / 4) {
                return ConvexHull
            }
            val grown = Vec3(max(size.x, MINIMUM_SIZE), max(size.y, MINIMUM_SIZE), max(size.z, MINIMUM_SIZE))
            return Box(center = bounds.center, size = grown)
        }
    }
}
