package com.getinsiteview.modelkit.ar

import com.getinsiteview.modelkit.geometry.AlignmentSite
import java.util.UUID

/**
 * What the AR session's configuration depends on (iOS `ARAlignmentView.Settings`, AAV 380-401), to
 * reconfigure the session only when it changes. ARCore has no scene mesh (iOS `mesh`), so that
 * field has no counterpart here.
 */
data class ARSessionSettings(
    /** Placed plates to detect (by number): none while marking or once aligned. */
    val detection: Set<Int>,
    /** The image name of the plate being registered (`register-N`), watched even while marking. */
    val registration: String?,
    /**
     * Marking: no light estimation (the building is hidden), so the session keeps the phone's
     * resources (iOS: no environment texturing).
     */
    val lean: Boolean,
    val thermalLimited: Boolean,
    /** Aiming with the depth sensor: walls (vertical planes) are detected too. */
    val walls: Boolean,
) {
    /** ARCore `PlaneFindingMode`: horizontal, or horizontal and vertical while aiming at walls. */
    val findsVerticalPlanes: Boolean get() = walls

    /** ARCore light estimation (iOS environment texturing): off when hot or lean. */
    val estimatesLight: Boolean get() = !(thermalLimited || lean)

    /** The augmented images to detect, by name: placed plates (`plate-N`), then the registration image. */
    val imageNames: List<String>
        get() = detection.sorted().map { PlateImageName.plate(it) } + listOfNotNull(registration)

    companion object {
        /**
         * Whether earlier detections must be forgotten before running [wanted] (AAV 445-450): the
         * detection set grew, or a new registration image came. ARKit doesn't report an image it
         * already anchored again; on ARCore a rebuilt image database starts its images over.
         */
        fun forgetsImages(before: ARSessionSettings?, wanted: ARSessionSettings): Boolean =
            wanted.detection.size > (before?.detection?.size ?: 0) ||
                (wanted.registration != null && wanted.registration != before?.registration)

        /**
         * Walls and corners at any height can be aimed at: a depth sensor, not hot, and allowed
         * (the marking device test measures floor corners only). (AAV 237.)
         */
        fun aimsAtWalls(hasDepthSensor: Boolean, thermalLimited: Boolean, allowsWallAims: Boolean): Boolean =
            hasDepthSensor && !thermalLimited && allowsWallAims
    }
}

/** Camera formats when hot (iOS `coolVideoFormat`, AAV 431-435). */
object CoolCameraFormat {
    /** When hot: the smallest picture at 30 fps or less (the first such format can be a large one). */
    fun <T> choose(formats: List<T>, maxFps: (T) -> Int, pixels: (T) -> Long): T? =
        formats.filter { maxFps(it) <= 30 }.minByOrNull(pixels)
}

/** The augmented-image names (AAV 327, 1019) and where a detection goes (AAV 1116-1129). */
object PlateImageName {
    const val PLATE_PREFIX = "plate-"
    const val REGISTER_PREFIX = "register-"

    fun plate(number: Int): String = "$PLATE_PREFIX$number"

    fun register(number: Int): String = "$REGISTER_PREFIX$number"

    sealed interface Route {
        /** The plate being registered: it goes to the registration flow, never aligns the building. */
        data object Registration : Route

        /** A placed plate: it aligns the building (not while marking). */
        data class Plate(val number: Int) : Route

        data object Other : Route
    }

    fun route(name: String): Route {
        if (name.startsWith(REGISTER_PREFIX)) return Route.Registration
        if (name.startsWith(PLATE_PREFIX)) {
            val number = name.substring(PLATE_PREFIX.length).toIntOrNull() ?: return Route.Other
            return Route.Plate(number)
        }
        return Route.Other
    }
}

/**
 * One AR anchor per local alignment (iOS `reconcileSiteAnchors`, AAV 884-897), so the session's
 * corrections of its map move each site: added with the site, removed when it goes (a new
 * alignment, placing by hand).
 */
object SiteAnchors {
    /** Local alignments follow their anchors (AAV 247). */
    const val FOLLOWS_SITE_ANCHORS = true

    data class Plan(val remove: List<UUID>, val add: List<AlignmentSite>)

    /** The anchors to remove (sites gone) and the sites to anchor (new), from [existing] anchors. */
    fun plan(existing: Set<UUID>, sites: List<AlignmentSite>): Plan {
        val wanted = sites.map { it.id }.toSet()
        return Plan(remove = existing.filter { it !in wanted }, add = sites.filter { it.id !in existing })
    }
}
