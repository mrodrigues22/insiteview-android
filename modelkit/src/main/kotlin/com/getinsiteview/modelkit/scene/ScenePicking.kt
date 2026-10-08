package com.getinsiteview.modelkit.scene

import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.OrbitCamera
import com.getinsiteview.modelkit.geometry.PickingShape
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.Vector
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Tapping an element (iOS: RealityKit collision shapes and `entity(at:)`, SCN 341-368): a ray
 * against each element's picking box, the nearest hit wins. Boxes are in the element's own frame,
 * grown so thin conduits stay tappable ([PickingShape]).
 *
 * Long diagonal runs get their convex hull on iOS; Filament keeps no CPU copy of the meshes
 * (gltfio uploads them and, with meshopt compression, decodes them only into GPU buffers), so here
 * they fall back to the grown box, as iOS does for an element without a mesh of its own (SCN 358-363).
 */
object ScenePicking {
    /**
     * One tappable element.
     *
     * @property box the picking box in the element's own frame.
     * @property elementFromModel model (the building root's frame) → the element's frame, column-major.
     */
    class Target(val id: String, val box: Bounds, val elementFromModel: DoubleArray)

    /** The picking box for an element whose visual bounds (its own frame) are [bounds]. */
    fun pickingBox(bounds: Bounds, isSystemElement: Boolean): Bounds = when (val shape = PickingShape.forElement(bounds, isSystemElement)) {
        is PickingShape.Box -> Bounds(min = shape.center - shape.size / 2.0, max = shape.center + shape.size / 2.0)
        PickingShape.ConvexHull -> {
            val grown = Vec3(
                max(bounds.extents.x, PickingShape.MINIMUM_SIZE),
                max(bounds.extents.y, PickingShape.MINIMUM_SIZE),
                max(bounds.extents.z, PickingShape.MINIMUM_SIZE),
            )
            Bounds(min = bounds.center - grown / 2.0, max = bounds.center + grown / 2.0)
        }
    }

    /**
     * Where a ray first meets a box (the ray's parameter, ≥ 0; 0 from inside), or `null` (slab test).
     * [direction] needn't be unit length: the parameter is in its units.
     */
    fun intersect(origin: Vec3, direction: Vec3, box: Bounds): Double? {
        var near = Double.NEGATIVE_INFINITY
        var far = Double.POSITIVE_INFINITY
        for (axis in 0 until 3) {
            val o = origin[axis]
            val d = direction[axis]
            val lo = box.min[axis]
            val hi = box.max[axis]
            if (kotlin.math.abs(d) < 1e-12) {
                if (o < lo || o > hi) return null
                continue
            }
            var t0 = (lo - o) / d
            var t1 = (hi - o) / d
            if (t0 > t1) {
                val swap = t0
                t0 = t1
                t1 = swap
            }
            near = max(near, t0)
            far = min(far, t1)
            if (near > far) return null
        }
        if (far < 0) return null
        return max(near, 0.0)
    }

    /**
     * The element the ray (model coordinates) meets first, or `null`. Each ray goes into the
     * element's frame by the same affine map, so the parameters compare.
     */
    fun pick(origin: Vec3, direction: Vec3, targets: Iterable<Target>): String? {
        var best: String? = null
        var bestT = Double.POSITIVE_INFINITY
        for (target in targets) {
            val o = Matrix4.transformPoint(target.elementFromModel, origin)
            val d = Matrix4.transformDirection(target.elementFromModel, direction)
            val t = intersect(o, d, target.box) ?: continue
            if (t < bestT) {
                bestT = t
                best = target.id
            }
        }
        return best
    }

    /** The box around [corners]' images through [m] (a box carried into another frame). */
    fun transformedBounds(box: Bounds, m: DoubleArray): Bounds {
        var low = Vec3.repeating(Double.POSITIVE_INFINITY)
        var high = Vec3.repeating(Double.NEGATIVE_INFINITY)
        for (i in 0 until 8) {
            val corner = Vec3(
                if (i and 1 == 0) box.min.x else box.max.x,
                if (i and 2 == 0) box.min.y else box.max.y,
                if (i and 4 == 0) box.min.z else box.max.z,
            )
            val p = Matrix4.transformPoint(m, corner)
            low = Vec3.min(low, p)
            high = Vec3.max(high, p)
        }
        return Bounds(min = low, max = high)
    }

    /**
     * The 3D viewer's ray through a view point ([x], [y] pixels, y down, of a [width] × [height]
     * view) for an orbit camera with vertical field of view [fieldOfView] (radians), world
     * coordinates (iOS: the `ARView`'s `entity(at:)` under the virtual camera).
     */
    fun orbitRay(camera: OrbitCamera, fieldOfView: Double, x: Double, y: Double, width: Double, height: Double): Pair<Vec3, Vec3>? {
        if (!(width > 0 && height > 0)) return null
        val ndcX = 2 * x / width - 1
        val ndcY = 1 - 2 * y / height
        val halfHeight = tan(fieldOfView / 2)
        val halfWidth = halfHeight * width / height
        val direction = camera.forward + camera.right * (ndcX * halfWidth) + Vector.normalized(camera.up) * (ndcY * halfHeight)
        return camera.position to Vector.normalized(direction)
    }
}
