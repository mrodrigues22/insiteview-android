package com.getinsiteview.modelkit.scene

import com.getinsiteview.modelkit.Fixtures
import java.util.Base64
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("meshopt decoders")
class MeshoptDecoderTest {
    private class Case(val name: String, val mode: MeshoptDecoder.Mode, val filter: MeshoptDecoder.Filter, val count: Int, val stride: Int, val version: Int, val encoded: ByteArray, val decoded: ByteArray)

    private val cases: List<Case> = Json.parseToJsonElement(Fixtures.data("meshopt/cases.json").decodeToString()).jsonObject["cases"]!!.jsonArray.map {
        val c = it as JsonObject
        Case(
            name = c["name"]!!.jsonPrimitive.content,
            mode = MeshoptDecoder.Mode.valueOf(c["mode"]!!.jsonPrimitive.content),
            filter = MeshoptDecoder.Filter.valueOf(c["filter"]!!.jsonPrimitive.content),
            count = c["count"]!!.jsonPrimitive.int,
            stride = c["stride"]!!.jsonPrimitive.int,
            version = c["version"]!!.jsonPrimitive.int,
            encoded = Base64.getDecoder().decode(c["encoded"]!!.jsonPrimitive.content),
            decoded = Base64.getDecoder().decode(c["decoded"]!!.jsonPrimitive.content),
        )
    }

    private fun decode(case: Case): ByteArray = MeshoptDecoder.decodeGltfBuffer(case.count, case.stride, case.encoded, case.mode, case.filter)

    @Test
    @DisplayName("Vertex buffers decode as meshoptimizer's own decoder does, both codec versions")
    fun `Vertex buffers decode as meshoptimizer's own decoder does, both codec versions`() {
        val vertex = cases.filter { it.mode == MeshoptDecoder.Mode.ATTRIBUTES && it.filter == MeshoptDecoder.Filter.NONE }
        assertTrue(vertex.any { it.version == 0 } && vertex.any { it.version == 1 }, "fixtures cover v0 and v1")
        for (case in vertex) assertContentEquals(case.decoded, decode(case), case.name)
    }

    @Test
    @DisplayName("Filters: exponential exactly, the others to the last bit")
    fun `Filters - exponential exactly, the others to the last bit`() {
        val filtered = cases.filter { it.filter != MeshoptDecoder.Filter.NONE }
        assertEquals(MeshoptDecoder.Filter.entries.toSet() - MeshoptDecoder.Filter.NONE, filtered.map { it.filter }.toSet())
        for (case in filtered) {
            val actual = decode(case)
            if (case.filter == MeshoptDecoder.Filter.EXPONENTIAL) {
                assertContentEquals(case.decoded, actual, case.name)
                continue
            }
            // The reference decoder runs its SIMD path, which rounds halves to even; the scalar
            // path (ported here) rounds them away from zero.
            val wide = case.stride == 8
            val n = case.count * 4
            for (i in 0 until n) {
                val e = component(case.decoded, i, wide, signed = case.filter != MeshoptDecoder.Filter.COLOR)
                val a = component(actual, i, wide, signed = case.filter != MeshoptDecoder.Filter.COLOR)
                assertTrue(abs(e - a) <= 1, "${case.name}: component $i is $a, expected $e")
            }
        }
    }

    private fun component(data: ByteArray, i: Int, wide: Boolean, signed: Boolean): Int = when {
        wide && signed -> ((data[i * 2].toInt() and 0xff) or (data[i * 2 + 1].toInt() shl 8))
        wide -> ((data[i * 2].toInt() and 0xff) or ((data[i * 2 + 1].toInt() and 0xff) shl 8))
        signed -> data[i].toInt()
        else -> data[i].toInt() and 0xff
    }

    @Test
    @DisplayName("Index buffers and sequences, 16 and 32 bits")
    fun `Index buffers and sequences, 16 and 32 bits`() {
        val indices = cases.filter { it.mode != MeshoptDecoder.Mode.ATTRIBUTES }
        assertEquals(setOf(MeshoptDecoder.Mode.TRIANGLES, MeshoptDecoder.Mode.INDICES), indices.map { it.mode }.toSet())
        assertEquals(setOf(2, 4), indices.map { it.stride }.toSet())
        for (case in indices) assertContentEquals(case.decoded, decode(case), case.name)
    }

    @Test
    @DisplayName("A buffer inside a larger array decodes from its offset")
    fun `A buffer inside a larger array decodes from its offset`() {
        val case = cases.first { it.mode == MeshoptDecoder.Mode.ATTRIBUTES && it.version == 0 }
        val padded = ByteArray(7) + case.encoded + ByteArray(5)
        val decoded = MeshoptDecoder.decodeGltfBuffer(case.count, case.stride, padded, case.mode, sourceOffset = 7, sourceLength = case.encoded.size)
        assertContentEquals(case.decoded, decoded)
    }

    @Test
    @DisplayName("Bad headers, versions and truncated data throw")
    fun `Bad headers, versions and truncated data throw`() {
        for (case in cases.filter { it.filter == MeshoptDecoder.Filter.NONE }) {
            val wrongHeader = case.encoded.copyOf().also { it[0] = 0x00 }
            assertFailsWith<MeshoptException>(case.name) { MeshoptDecoder.decodeGltfBuffer(case.count, case.stride, wrongHeader, case.mode) }
            val futureVersion = case.encoded.copyOf().also { it[0] = ((it[0].toInt() and 0xf0) or 0x0f).toByte() }
            assertFailsWith<MeshoptException>(case.name) { MeshoptDecoder.decodeGltfBuffer(case.count, case.stride, futureVersion, case.mode) }
            val truncated = case.encoded.copyOf(case.encoded.size / 2)
            assertFailsWith<MeshoptException>(case.name) { MeshoptDecoder.decodeGltfBuffer(case.count, case.stride, truncated, case.mode) }
        }
        val vertices = cases.first { it.mode == MeshoptDecoder.Mode.ATTRIBUTES }
        assertFailsWith<MeshoptException>("stride not a multiple of 4") { MeshoptDecoder.decodeGltfBuffer(vertices.count, 6, vertices.encoded, vertices.mode) }
        val triangles = cases.first { it.mode == MeshoptDecoder.Mode.TRIANGLES }
        assertFailsWith<MeshoptException>("not whole triangles") { MeshoptDecoder.decodeGltfBuffer(triangles.count - 1, triangles.stride, triangles.encoded, triangles.mode) }
    }
}
