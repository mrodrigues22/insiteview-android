package com.getinsiteview.modelkit.scene

import com.getinsiteview.modelkit.Fixtures
import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.Vector
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Element hulls from a chunk GLB")
class ElementHullsTest {
    private val pipe = "e0123456789abcdef0123456789abcdef"
    private val box = "efedcba9876543210fedcba9876543210"
    private val nested = "e00112233445566778899aabbccddeeff"

    private val expected: Map<String, List<Vec3>> = Json.parseToJsonElement(Fixtures.data("glb/expected.json").decodeToString()).jsonObject.mapValues { (_, points) ->
        points.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double }.let { Vec3(it[0], it[1], it[2]) } }
    }

    private fun vectors(values: DoubleArray): List<Vec3> = (0 until values.size / 3).map { Vec3(values[it * 3], values[it * 3 + 1], values[it * 3 + 2]) }

    private fun bounds(points: List<Vec3>): Bounds = Bounds(points.reduce(Vec3::min), points.reduce(Vec3::max))

    /** Each point of one set is within [tolerance] of a point of the other. */
    private fun assertSamePoints(expected: List<Vec3>, actual: List<Vec3>, tolerance: Double, message: String) {
        assertEquals(expected.size, actual.size, "$message: vertex count")
        for (e in expected) assertTrue(actual.any { Vector.distance(it, e) <= tolerance }, "$message: nothing near $e")
        for (a in actual) assertTrue(expected.any { Vector.distance(it, a) <= tolerance }, "$message: $a is nowhere expected")
    }

    @Test
    @DisplayName("gltfpack's chunk: each element's positions in its own frame, EXT and KHR meshopt")
    fun `gltfpack's chunk - each element's positions in its own frame, EXT and KHR meshopt`() {
        for (file in listOf("glb/chunk.glb", "glb/chunk-khr.glb")) {
            val positions = GlbGeometry.read(Fixtures.data(file)).elementPositions(setOf(pipe, box, nested, "e" + "f".repeat(32)))
            assertEquals(setOf(pipe, box, nested), positions.keys, file)
            // 14-bit positions over the chunk's ~3.3 m: a step of 0.2 mm.
            for ((id, values) in positions) assertSamePoints(expected.getValue(id), vectors(values), 3e-4, "$file $id")
        }
    }

    @Test
    @DisplayName("Only the requested elements are read")
    fun `Only the requested elements are read`() {
        val glb = Fixtures.data("glb/chunk.glb")
        assertEquals(setOf(box), GlbGeometry.read(glb).elementPositions(setOf(box)).keys)
        assertTrue(ElementHulls.read(glb, emptySet()).isEmpty())
    }

    @Test
    @DisplayName("A ray beside the diagonal pipe but inside its grown box misses its hull, one through it hits")
    fun `A ray beside the diagonal pipe but inside its grown box misses its hull, one through it hits`() {
        val hulls = ElementHulls.read(Fixtures.data("glb/chunk.glb"), setOf(pipe, box))
        val hull = assertNotNull(hulls[pipe])
        val visual = bounds(expected.getValue(pipe))
        assertTrue(ScenePicking.usesHull(visual, isSystemElement = true))
        assertTrue(!ScenePicking.usesHull(bounds(expected.getValue(box)), isSystemElement = true))
        assertTrue(!ScenePicking.usesHull(visual, isSystemElement = false), "architecture keeps boxes")
        val grown = ScenePicking.pickingBox(visual, isSystemElement = true)

        val down = Vec3(0.0, 0.0, -1.0)
        val beside = Vec3(2.0, 1.0, 5.0) // 0.7 m from the pipe's axis
        assertNotNull(ScenePicking.intersect(beside, down, grown), "inside the grown box")
        assertNull(hull.intersect(beside, down))
        val through = Vec3(1.5, 1.5, 5.0)
        assertEquals(5.0 - 0.025, hull.intersect(through, down)!!, 5e-4, "the pipe's top")
        assertEquals(0.0, hull.intersect(Vec3(1.5, 1.5, 0.0), down), "from inside")

        // Picking: a wall behind the pipe gets the tap beside it, the pipe the one on it, in a
        // turned and moved frame.
        val modelFromPipe = Matrix4.fromColumns(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(4.0, -1.0, 2.0))
        val pipeTarget = ScenePicking.Target(pipe, grown, Matrix4.invert(modelFromPipe)!!, hull)
        val wallBox = Bounds(Vec3(-1.0, -1.0, -2.0), Vec3(4.0, 4.0, -1.0))
        val wall = ScenePicking.Target("wall", wallBox, Matrix4.invert(modelFromPipe)!!)
        fun model(p: Vec3) = Matrix4.transformPoint(modelFromPipe, p)
        val d = Matrix4.transformDirection(modelFromPipe, down)
        assertEquals("wall", ScenePicking.pick(model(beside), d, listOf(pipeTarget, wall)))
        assertEquals(pipe, ScenePicking.pick(model(through), d, listOf(pipeTarget, wall)))
        // Without its hull the grown box steals the tap (the behaviour before P2-01).
        val boxOnly = ScenePicking.Target(pipe, grown, Matrix4.invert(modelFromPipe)!!)
        assertEquals(pipe, ScenePicking.pick(model(beside), d, listOf(boxOnly, wall)))
    }

    @Test
    @DisplayName("The box element's hull is its box")
    fun `The box element's hull is its box`() {
        val hull = assertNotNull(ElementHulls.read(Fixtures.data("glb/chunk.glb"), setOf(box))[box])
        val b = bounds(expected.getValue(box))
        assertEquals(8, hull.vertexCount)
        assertTrue(Vector.distance(hull.bounds.min, b.min) < 3e-4 && Vector.distance(hull.bounds.max, b.max) < 3e-4, "${hull.bounds}")
    }

    // region A hand-made GLB: plain floats, strides, matrices, nested elements

    private fun glb(json: String, bin: ByteArray): ByteArray {
        val text = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val data = bin + ByteArray((4 - bin.size % 4) % 4)
        val out = ByteArrayOutputStream()
        val b = ByteBuffer.allocate(12 + 8).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46546C67).putInt(2).putInt(12 + 8 + text.size + 8 + data.size).putInt(text.size).putInt(0x4E4F534A)
        out.write(b.array())
        out.write(text)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).putInt(0x004E4942).array())
        out.write(data)
        return out.toByteArray()
    }

    @Test
    @DisplayName("Plain buffers: interleaved floats, a matrix child, indices, and nested elements left out")
    fun `Plain buffers - interleaved floats, a matrix child, indices, and nested elements left out`() {
        val outer = "e" + "1".repeat(32)
        val inner = "e" + "2".repeat(32)
        // Four vertices, 16 bytes apart (x y z + 4 bytes of something else); the indices use three.
        val bin = ByteBuffer.allocate(64 + 6 + 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 2.0, 0.0), Vec3(9.0, 9.0, 9.0))) {
            bin.putFloat(v.x.toFloat()).putFloat(v.y.toFloat()).putFloat(v.z.toFloat()).putInt(-1)
        }
        bin.putShort(0).putShort(1).putShort(2)
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
            "nodes":[
              {"name":"$outer","translation":[100,0,0],"children":[1,2]},
              {"matrix":[2,0,0,0, 0,2,0,0, 0,0,2,0, 0,0,5,1],"mesh":0},
              {"name":"$inner","mesh":0}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3"},
                         {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":64,"byteStride":16},{"buffer":0,"byteOffset":64,"byteLength":6}],
            "buffers":[{"byteLength":72}]}"""
        val geometry = GlbGeometry.read(glb(json, bin.array()))
        val positions = geometry.elementPositions(setOf(outer, inner))
        assertEquals(setOf(outer), positions.keys, "the nested element belongs to no element")
        assertEquals(listOf(Vec3(0.0, 0.0, 5.0), Vec3(2.0, 0.0, 5.0), Vec3(0.0, 4.0, 5.0)), vectors(positions.getValue(outer)))
        // Flat: the hull is its box.
        val hull = assertNotNull(ElementHulls.read(glb(json, bin.array()), setOf(outer))[outer])
        assertEquals(Bounds(Vec3(0.0, 0.0, 5.0), Vec3(2.0, 4.0, 5.0)), hull.bounds)
    }

    @Test
    @DisplayName("Not a GLB, or a broken one, throws")
    fun `Not a GLB, or a broken one, throws`() {
        assertFailsWith<GlbException> { GlbGeometry.read(ByteArray(8)) }
        assertFailsWith<GlbException> { GlbGeometry.read(glb("not json", ByteArray(0))) }
        val chunk = Fixtures.data("glb/chunk.glb")
        val broken = chunk.copyOf(chunk.size - 200) // the BIN chunk cut short
        assertFailsWith<GlbException> { ElementHulls.read(broken, setOf(pipe)) }
    }

    // endregion
}
