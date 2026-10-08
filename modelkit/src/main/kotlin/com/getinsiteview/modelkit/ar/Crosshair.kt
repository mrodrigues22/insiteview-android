package com.getinsiteview.modelkit.ar

import com.getinsiteview.modelkit.geometry.LidarAim
import com.getinsiteview.modelkit.geometry.ManualAlignment
import com.getinsiteview.modelkit.geometry.ReferenceMark
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.Vector
import com.getinsiteview.modelkit.intersectFloor
import kotlin.math.PI
import kotlin.math.cos

/**
 * What the crosshair is on (iOS `ARAlignmentView.crosshairTarget()`, AAV 958-984): the point where
 * the ray through the middle of the screen meets the floor plane, when the floor is known, tracking
 * is normal and the phone points nearly straight down. Otherwise, with wall aims (depth sensor),
 * the wall under the middle of the screen, or the corner when the points either side of it along
 * the wall are on two walls meeting there ([LidarAim]).
 */
object CrosshairTargeting {
    /**
     * The crosshair takes the floor only when the phone points within this of straight down: a
     * floor height off by `e` moves the mark `e · tan(angle)` sideways (2 cm → 1.4 cm here), where
     * the first crosshair's shallow aims made it several times the error.
     */
    val MAXIMUM_AIM_ANGLE: Double = 35 * PI / 180

    data class Target(val state: CrosshairState, val aim: FloorAim?)

    /**
     * @param worldFloorY the floor's height, `null` before one is found.
     * @param canTrack tracking is normal and the view has a size.
     * @param ray the ray through the middle of the screen (`null` when there is none).
     * @param aimsAtWalls walls and corners can be aimed at (depth sensor, not hot, allowed).
     * @param wallHitAtCentre the wall a raycast through the middle of the screen meets.
     * @param wallHitAt the wall a raycast through the screen point of a world point meets (`null`
     *   when it's off screen or meets none).
     */
    fun target(
        worldFloorY: Double?,
        canTrack: Boolean,
        ray: Ray?,
        aimsAtWalls: Boolean,
        wallHitAtCentre: () -> LidarAim.Plane?,
        wallHitAt: (Vec3) -> LidarAim.Plane?,
    ): Target {
        val floorY = worldFloorY ?: return Target(CrosshairState.NO_FLOOR, null)
        if (!canTrack || ray == null) return Target(CrosshairState.NOT_TRACKING, null)
        val eye = ray.origin
        val direction = Vector.normalized(ray.direction)
        if (-direction.y >= cos(MAXIMUM_AIM_ANGLE)) {
            val point = ManualAlignment.intersectFloor(origin = eye, direction = direction, height = floorY)
            if (point != null) return Target(CrosshairState.READY, FloorAim(point = point, eye = eye))
        }
        if (!aimsAtWalls) return Target(CrosshairState.TOO_SHALLOW, null)
        val middle = wallHitAtCentre() ?: return Target(CrosshairState.NO_SURFACE, null)
        // Far enough along the wall either side that each lands clearly on one wall of a corner.
        val sides = listOf(-LidarAim.SIDE_REACH, LidarAim.SIDE_REACH).map { reach -> wallHitAt(middle.point + middle.tangent * reach) }
        return when (val target = LidarAim.target(centre = middle, left = sides[0], right = sides[1])) {
            is LidarAim.Target.Corner -> Target(
                CrosshairState.ON_CORNER,
                FloorAim(point = Vec3(target.point.x, floorY, target.point.z), eye = eye, surface = ReferenceMark.Surface.EDGE),
            )
            is LidarAim.Target.Wall -> Target(
                CrosshairState.ON_WALL,
                FloorAim(point = target.plane.point, eye = eye, surface = ReferenceMark.Surface.WALL_PLANE, normal = target.plane.normal),
            )
            null -> Target(CrosshairState.NO_SURFACE, null)
        }
    }

    /**
     * The first raycast hit that is a wall (iOS `wallHit(at:eye:)`): hits are (point, normal) in
     * the order the session gives them, wall planes first, then depth. One tilted more than
     * [LidarAim]'s limit is skipped.
     */
    fun firstWall(hits: List<Pair<Vec3, Vec3>>, eye: Vec3): LidarAim.Plane? {
        for ((point, normal) in hits) {
            val wall = LidarAim.Plane.of(point = point, normal = normal, eye = eye)
            if (wall != null) return wall
        }
        return null
    }

    /** Every frame while capturing a mark, [UPDATE_RATE] times a second otherwise (AAV 938-941). */
    const val UPDATE_RATE = 15.0

    /** Whether the crosshair updates at [time], last updated at [lastUpdate]. */
    fun isDue(time: Double, lastUpdate: Double, capturing: Boolean): Boolean = capturing || time - lastUpdate >= 1.0 / UPDATE_RATE
}
