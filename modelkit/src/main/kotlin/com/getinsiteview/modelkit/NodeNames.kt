package com.getinsiteview.modelkit

import com.getinsiteview.modelkit.geometry.ManualAlignment
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.YawTransform
import kotlin.math.abs

/**
 * Names in the chunk files (master PLAN §9 "Node names" and "Hierarchy"): chunk root → storey
 * group → element node.
 */
object NodeNames {
    /** `e` + the 32-character hex GlobalId: an element node (glTF node / USD prim). */
    fun isElement(name: String): Boolean {
        if (name.length != 33 || name[0] != 'e') return false
        for (i in 1 until name.length) {
            val c = name[i]
            if (c !in '0'..'9' && c !in 'a'..'f') return false
        }
        return true
    }

    /** `s{order}`: a storey group. Returns the storey's `order`. */
    fun storeyOrder(name: String): Int? {
        if (!name.startsWith("s") || name.length <= 1 || name.length > 4) return null
        val digits = name.substring(1)
        if (!digits.all { it in '0'..'9' }) return null
        return digits.toIntOrNull()
    }

    /** `chunk_{key}`: a chunk's root. */
    fun chunkRoot(key: String): String = "chunk_$key"
}

// iOS keeps these `ManualAlignment` helpers in NodeNames.swift; they're extensions here too.

/**
 * Where a ray from the camera meets the horizontal plane at `height` (world metres), or `null` when
 * it points away from or along the plane. Dragging uses this rather than raycasting ARCore planes,
 * so the model slides smoothly on its own floor.
 */
fun ManualAlignment.intersectFloor(origin: Vec3, direction: Vec3, height: Double): Vec3? {
    if (abs(direction.y) <= 1e-6) return null
    val distance = (height - origin.y) / direction.y
    if (!(distance > 0 && distance < 100)) return null
    return origin + direction * distance
}

/** World height of a storey floor under the transform. */
fun ManualAlignment.floorHeight(transform: YawTransform, elevation: Double): Double =
    transform.translation.y + elevation
