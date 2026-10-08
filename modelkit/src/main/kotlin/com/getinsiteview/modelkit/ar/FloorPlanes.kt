package com.getinsiteview.modelkit.ar

import kotlin.math.abs

/**
 * The horizontal planes that may be the floor, by the AR session's plane identity, and the floor
 * height chosen from them (iOS `ARAlignmentView.floorPlanes` / `worldFloorY`, AAV 184-188, 1273-1281).
 *
 * The floor is the lowest plane classified as floor or, where none is classified (always on ARCore,
 * which has no floor class: docs/PLAN.md §3 "AR"), the lowest plane of at least [MINIMUM_AREA].
 * Device tests in the glossy-tiled test room (2026-10-06) caught both traps: furniture classified
 * as floor 20 cm up (3 m², so not the biggest), and an unclassified 0.25 m² plane 9 cm below the
 * floor, likely a reflection (so not the lowest of every plane).
 *
 * A mutable class (the view's dictionary).
 */
class FloorPlanes {
    private val planes = LinkedHashMap<Any, FloorPlane>()

    /** A plane was added or moved. */
    fun update(id: Any, plane: FloorPlane) {
        planes[id] = plane
    }

    /** The session removed (or merged) a plane. */
    fun remove(id: Any) {
        planes.remove(id)
    }

    fun clear() = planes.clear()

    /** The planes [worldFloorY] chooses from, for the copied details. */
    val candidates: List<FloorPlane> get() = planes.values.toList()

    /** The floor's height in the AR world, or `null` before one is found. */
    val worldFloorY: Double?
        get() {
            val all = planes.values
            val floors = all.filter { it.isFloor }
            return (floors.ifEmpty { all }).minOfOrNull { it.height }
        }

    companion object {
        /** Unclassified horizontal planes smaller than this aren't the floor (a table, a box). */
        const val MINIMUM_AREA = 0.25

        /**
         * A horizontal plane as a floor candidate: classified floor, or big enough to be one
         * (iOS AnchorChange, AAV 1276-1280). `null` for a small unclassified one.
         */
        fun candidate(height: Double, area: Double, isFloor: Boolean = false): FloorPlane? =
            if (isFloor || area >= MINIMUM_AREA) FloorPlane(height = height, area = area, isFloor = isFloor) else null
    }
}

/**
 * Tells when the floor moved by [THRESHOLD] or more since it was last told (the session refining
 * the floor), so marks on it can follow (iOS `noteFloorPlanes`, AAV 1038).
 */
class FloorChangeNotifier {
    /** The floor height last reported. */
    var reported: Double? = null
        private set

    /** Whether [floorY] should be reported now; remembers it when so. */
    fun shouldReport(floorY: Double?): Boolean {
        if (floorY == null) return false
        val last = reported
        if (last != null && abs(last - floorY) < THRESHOLD) return false
        reported = floorY
        return true
    }

    companion object {
        /** 5 mm. */
        const val THRESHOLD = 0.005
    }
}
