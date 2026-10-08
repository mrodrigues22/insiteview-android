package com.getinsiteview.modelkit.geometry

import kotlin.math.sqrt

/**
 * A 3D vector of doubles: the port of the stdlib's `SIMD3<Double>` used throughout IVModelKit
 * (model coordinates: metres, Y up). Element-wise operators match SIMD's (`*` and `/` between two
 * vectors are element-wise).
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    /** IEEE equality, as SIMD's `==`: `-0.0 == 0.0` and NaN is never equal (a data class compares bit patterns). */
    override fun equals(other: Any?): Boolean = other is Vec3 && x == other.x && y == other.y && z == other.z

    override fun hashCode(): Int = ((x + 0.0).hashCode() * 31 + (y + 0.0).hashCode()) * 31 + (z + 0.0).hashCode()

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(o: Vec3) = Vec3(x * o.x, y * o.y, z * o.z)
    operator fun div(o: Vec3) = Vec3(x / o.x, y / o.y, z / o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun get(index: Int): Double = when (index) {
        0 -> x
        1 -> y
        2 -> z
        else -> throw IndexOutOfBoundsException("Vec3 index $index")
    }

    /** SIMD's `sum()`. */
    fun sum(): Double = x + y + z

    /** SIMD's `.xz` swizzle (the floor plane). */
    val xz: Vec2 get() = Vec2(x, z)

    companion object {
        val zero = Vec3(0.0, 0.0, 0.0)
        val one = Vec3(1.0, 1.0, 1.0)

        /** `[x, y, z]` → vector; missing components are 0 (IVModelKit's `SIMD3(components:)`). */
        fun of(components: List<Double>) = Vec3(
            components.getOrElse(0) { 0.0 },
            components.getOrElse(1) { 0.0 },
            components.getOrElse(2) { 0.0 },
        )

        /** SIMD's `SIMD3(repeating:)`. */
        fun repeating(v: Double) = Vec3(v, v, v)

        /** SIMD's `pointwiseMin`/`pointwiseMax`. */
        fun min(a: Vec3, b: Vec3) = Vec3(kotlin.math.min(a.x, b.x), kotlin.math.min(a.y, b.y), kotlin.math.min(a.z, b.z))
        fun max(a: Vec3, b: Vec3) = Vec3(kotlin.math.max(a.x, b.x), kotlin.math.max(a.y, b.y), kotlin.math.max(a.z, b.z))
    }
}

operator fun Double.times(v: Vec3) = v * this

/** A 2D vector of doubles (`SIMD2<Double>`): floor-plane points (x, z) and outlines. */
data class Vec2(val x: Double, val y: Double) {
    /** IEEE equality, as SIMD's `==` (see [Vec3.equals]). */
    override fun equals(other: Any?): Boolean = other is Vec2 && x == other.x && y == other.y

    override fun hashCode(): Int = (x + 0.0).hashCode() * 31 + (y + 0.0).hashCode()

    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(o: Vec2) = Vec2(x * o.x, y * o.y)
    operator fun div(o: Vec2) = Vec2(x / o.x, y / o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun div(s: Double) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)
    operator fun get(index: Int): Double = when (index) {
        0 -> x
        1 -> y
        else -> throw IndexOutOfBoundsException("Vec2 index $index")
    }

    fun sum(): Double = x + y

    companion object {
        val zero = Vec2(0.0, 0.0)
        fun repeating(v: Double) = Vec2(v, v)

        /** SIMD's `pointwiseMin`/`pointwiseMax`. */
        fun min(a: Vec2, b: Vec2) = Vec2(kotlin.math.min(a.x, b.x), kotlin.math.min(a.y, b.y))
        fun max(a: Vec2, b: Vec2) = Vec2(kotlin.math.max(a.x, b.x), kotlin.math.max(a.y, b.y))
    }
}

operator fun Double.times(v: Vec2) = v * this

/** Small vector helpers (iOS `Vector`, in PlateAlignment.swift). */
object Vector {
    fun dot(a: Vec3, b: Vec3): Double = (a * b).sum()

    fun cross(a: Vec3, b: Vec3): Vec3 = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    fun length(v: Vec3): Double = sqrt(dot(v, v))

    fun normalized(v: Vec3): Vec3 {
        val l = length(v)
        return if (l > 1e-12) v / l else v
    }

    fun distance(a: Vec3, b: Vec3): Double = length(a - b)
}
