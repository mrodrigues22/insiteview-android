package com.getinsiteview.modelkit.ar

import com.getinsiteview.modelkit.geometry.PlateFrame
import com.getinsiteview.modelkit.geometry.Vec2
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.Vector
import com.getinsiteview.modelkit.geometry.YawTransform
import com.getinsiteview.modelkit.scene.Matrix4
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// iOS IVAR/AnchorMath.swift: AR session poses ↔ the alignment math (PlateFrame, YawTransform).
// Matrices are column-major, as ARCore's `Pose.toMatrix(float[], 0)` writes them. ARCore's
// augmented-image pose has the same axes as ARKit's image anchor (x right, y out of the image,
// z down the image), so the conversion is the same (docs/PLAN.md §3 "AR").

/**
 * The detected plate's frame from the image's pose matrix (columns: x = right, y = out of the
 * image, z = image down; see [PlateFrame.fromAnchor]). (iOS `PlateFrame(anchorTransform:)`.)
 */
fun PlateFrame.Companion.fromAnchorTransform(m: FloatArray): PlateFrame {
    val d = Matrix4.of(m)
    return fromAnchor(
        anchorX = Matrix4.column(d, 0), anchorY = Matrix4.column(d, 1), anchorZ = Matrix4.column(d, 2),
        position = Matrix4.column(d, 3),
    )
}

/**
 * An anchor's pose as yaw and position: its heading from its z axis seen from above (the x axis
 * when z points straight up or down); any tilt is left out, the world being gravity-aligned. The
 * inverse of [matrix] for an unturned or yaw-only anchor. (iOS `YawTransform(anchorTransform:)`.)
 */
fun YawTransform.Companion.fromAnchorTransform(m: FloatArray): YawTransform {
    val d = Matrix4.of(m)
    val z = Matrix4.column(d, 2)
    val x = Matrix4.column(d, 0)
    // rotate((0, 0, 1), θ) = (sin θ, 0, cos θ); rotate((1, 0, 0), θ) = (cos θ, 0, −sin θ).
    val yaw = if (abs(z.x) + abs(z.z) > 1e-4) atan2(z.x, z.z) else atan2(-x.z, x.x)
    return YawTransform(yaw = yaw, translation = Matrix4.column(d, 3))
}

/** `RotY(yaw)` then the translation, as a column-major world matrix (iOS `YawTransform.matrix`). */
val YawTransform.matrix: FloatArray
    get() {
        val c = cos(yaw)
        val s = sin(yaw)
        return Matrix4.toFloats(
            Matrix4.fromColumns(
                x = Vec3(c, 0.0, -s), y = Vec3(0.0, 1.0, 0.0), z = Vec3(s, 0.0, c), translation = translation,
            ),
        )
    }

/** The rotation as a quaternion `[x, y, z, w]` (ARCore `Pose`'s order): `yaw` about +Y. */
val YawTransform.quaternion: FloatArray
    get() = floatArrayOf(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat())

/** AR screen geometry: rays through view points and points on screen, from the camera's matrices. */
object ScreenGeometry {
    /**
     * The ray through the view point ([x], [y] in pixels, y down) of a [width] × [height] view,
     * from the camera's column-major [view] and [projection] matrices (iOS `arView.ray(through:)`).
     * It starts at the camera. `null` for an empty view or singular matrices.
     */
    fun ray(x: Double, y: Double, width: Double, height: Double, view: FloatArray, projection: FloatArray): Ray? {
        if (!(width > 0 && height > 0)) return null
        val viewMatrix = Matrix4.of(view)
        val cameraToWorld = Matrix4.invert(viewMatrix) ?: return null
        val inverse = Matrix4.invert(Matrix4.multiply(Matrix4.of(projection), viewMatrix)) ?: return null
        val ndcX = 2 * x / width - 1
        val ndcY = 1 - 2 * y / height
        val near = unproject(inverse, ndcX, ndcY, -1.0) ?: return null
        val far = unproject(inverse, ndcX, ndcY, 1.0) ?: return null
        val direction = far - near
        val length = Vector.length(direction)
        if (!(length > 1e-12)) return null
        return Ray(origin = Matrix4.column(cameraToWorld, 3), direction = direction / length)
    }

    /**
     * Where a world point shows in the view (pixels, y down), or `null` behind the camera
     * (iOS `arView.project`).
     */
    fun project(point: Vec3, width: Double, height: Double, view: FloatArray, projection: FloatArray): Vec2? {
        if (!(width > 0 && height > 0)) return null
        val clip = Matrix4.transform(Matrix4.multiply(Matrix4.of(projection), Matrix4.of(view)), point.x, point.y, point.z, 1.0)
        val w = clip[3]
        if (!(w > 1e-9)) return null
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        return Vec2((ndcX + 1) / 2 * width, (1 - ndcY) / 2 * height)
    }

    /** A point in camera space (the view matrix: x right, y up, looking down −z). */
    fun cameraSpacePoint(point: Vec3, view: FloatArray): Vec3 = Matrix4.transformPoint(Matrix4.of(view), point)

    /**
     * The rotation that turns +Y onto [normal], as a quaternion `[x, y, z, w]`: a flat (+Y up)
     * marker standing on a wall, facing out (iOS `simd_quatf(from: (0, 1, 0), to: normal)`).
     */
    fun rotationFromUp(normal: Vec3): FloatArray {
        val n = Vector.normalized(normal)
        val dot = n.y
        if (dot > 1 - 1e-9) return floatArrayOf(0f, 0f, 0f, 1f)
        if (dot < -1 + 1e-9) return floatArrayOf(1f, 0f, 0f, 0f) // half a turn about x
        // axis = Y × n, w = 1 + Y·n, then normalized (half-angle form).
        val axis = Vector.cross(Vec3(0.0, 1.0, 0.0), n)
        val w = 1 + dot
        val length = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z + w * w)
        return floatArrayOf((axis.x / length).toFloat(), (axis.y / length).toFloat(), (axis.z / length).toFloat(), (w / length).toFloat())
    }

    private fun unproject(inverse: DoubleArray, x: Double, y: Double, z: Double): Vec3? {
        val p = Matrix4.transform(inverse, x, y, z, 1.0)
        val w = p[3]
        if (abs(w) < 1e-12) return null
        return Vec3(p[0] / w, p[1] / w, p[2] / w)
    }
}
