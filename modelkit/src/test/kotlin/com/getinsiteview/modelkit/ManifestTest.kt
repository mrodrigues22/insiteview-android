package com.getinsiteview.modelkit

import com.getinsiteview.core.ISO8601Timestamp
import com.getinsiteview.modelkit.geometry.Vec3
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Manifest")
class ManifestTest {
    @Test
    fun `Decodes the API's manifest for test-room`() {
        val manifest = Fixtures.manifest()
        assertEquals(1, manifest.schema)
        assertEquals("TEST01", manifest.building.code)
        assertEquals("01926f3a-7c1e-7b2d-9a41-3c5e8f0a1b2c", manifest.versionId.toString().lowercase())
        assertEquals(listOf<String?>("Level 1"), manifest.storeys.map { it.name })
        assertEquals(listOf("architecture", "electrical", "plumbing"), manifest.systems.map { it.key })
        assertEquals(listOf("power", "lighting"), manifest.systems[1].subsystems)
        assertEquals(listOf("architecture", "electrical", "plumbing"), manifest.chunks.map { it.key })
        val electrical = assertNotNull(manifest.chunk("electrical"))
        assertNull(electrical.storey)
        assertEquals(2745L, electrical.file(ChunkFileKind.USDZ).bytes)
        assertEquals("2b639f4e6c1095afe222db9f95dcfa5b289eda93acf5c9add069cfb80f8d594b", electrical.file(ChunkFileKind.USDZ).sha256)
        assertEquals("gzip", electrical.file(ChunkFileKind.META).encoding)
        assertEquals(50, manifest.plates.firstOrNull()?.sizeMm)
        assertEquals(12, manifest.stats.elements)
        assertEquals(14.0, manifest.stats.floorAreaM2)
        assertNull(manifest.thumbnailUrl)
        assertEquals("2026-10-01T12:15:00.123Z", ISO8601Timestamp.format(manifest.urlsExpireAt))
    }

    @Test
    fun `Bounds and storeys`() {
        val manifest = Fixtures.manifest()
        assertEquals(Vec3(-2.15, -0.2, -1.9), manifest.modelBounds.min)
        assertEquals(Vec3(2.15, 2.6, 1.9), manifest.modelBounds.max)
        assertEquals(0.0, manifest.storeysByOrder.firstOrNull()?.elevation)
        assertEquals("Level 1", manifest.storey("e614d613568044166b4fe08af3b18d142")?.name)
    }

    @Test
    fun `AR starts on the lowest level with rooms, not a foundation of footings`() {
        var manifest = Fixtures.manifest().copy(
            storeys = listOf(
                Manifest.Storey(id = "roof", name = "Roof", elevation = 7.25, order = 3),
                Manifest.Storey(id = "fdn", name = "T/FDN", elevation = 0.0, order = 0),
                Manifest.Storey(id = "l1", name = "Level 1", elevation = 1.25, order = 1),
                Manifest.Storey(id = "l2", name = "Level 2", elevation = 4.35, order = 2),
            ),
            spaces = listOf(
                Manifest.Space(id = "kitchen", name = "Kitchen", longName = null, storeyId = "l2", areaM2 = null),
                Manifest.Space(id = "living", name = "Living Room", longName = null, storeyId = "l1", areaM2 = null),
            ),
        )
        assertEquals("l1", manifest.startingStorey?.id)
        // No room says its level: the lowest.
        manifest = manifest.copy(spaces = listOf(Manifest.Space(id = "kitchen", name = "Kitchen", longName = null, storeyId = null, areaM2 = null)))
        assertEquals("fdn", manifest.startingStorey?.id)
    }

    @Test
    @DisplayName("IFC ↔ model coordinates (master PLAN §9)")
    fun `IFC to model coordinates (master PLAN s9)`() {
        val frame = ModelFrame(offset = Vec3(512.3, 1830.0, 0.0))
        // IFC (x, y, z) → model (x − ox, z − oz, −(y − oy)).
        val model = frame.modelPoint(fromIFC = Vec3(513.3, 1829.0, 2.5))
        assertTrue(abs(model.x - 1.0) < 1e-9 && abs(model.y - 2.5) < 1e-9 && abs(model.z - 1.0) < 1e-9)
        val back = frame.ifcPoint(fromModel = model)
        assertTrue(abs(back.x - 513.3) < 1e-9 && abs(back.y - 1829.0) < 1e-9 && abs(back.z - 2.5) < 1e-9)
        // IFC north (+Y) is model −Z; IFC up (+Z) is model +Y.
        assertEquals(Vec3(0.0, 0.0, -1.0), ModelFrame.modelDirection(fromIFC = Vec3(0.0, 1.0, 0.0)))
        assertEquals(Vec3(0.0, 1.0, 0.0), ModelFrame.modelDirection(fromIFC = Vec3(0.0, 0.0, 1.0)))
    }

    @Test
    fun `Rejects a newer schema, other axes and short vectors`() {
        val data = Fixtures.data("test-room/manifest.json").decodeToString()
        fun mutated(change: MutableMap<String, JsonElement>.() -> Unit): String {
            val map = Json.parseToJsonElement(data).jsonObject.toMutableMap()
            map.change()
            return JsonObject(map).toString()
        }
        assertEquals(
            Manifest.DecodingProblem.UnsupportedSchema(2),
            assertFailsWith<Manifest.DecodingProblem> { Manifest.decode(mutated { this["schema"] = JsonPrimitive(2) }) },
        )
        assertEquals(
            Manifest.DecodingProblem.UnsupportedAxes(units = "mm", upAxis = "Y"),
            assertFailsWith<Manifest.DecodingProblem> { Manifest.decode(mutated { this["units"] = JsonPrimitive("mm") }) },
        )
        assertEquals(
            Manifest.DecodingProblem.InvalidVector("origin.offset"),
            assertFailsWith<Manifest.DecodingProblem> {
                Manifest.decode(mutated { this["origin"] = JsonObject(mapOf("offset" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))) })
            },
        )
    }

    @Test
    @DisplayName("Every schema in the supported range is read; a newer or older one asks for an update")
    fun `Every schema in the supported range is read, a newer or older one asks for an update`() {
        val manifest = Fixtures.manifest()
        for (schema in Manifest.supportedSchemas) {
            manifest.copy(schema = schema).validate()
        }
        for (schema in listOf(Manifest.supportedSchemas.first - 1, Manifest.supportedSchemas.last + 1, 99)) {
            val copy = manifest.copy(schema = schema)
            assertEquals(Manifest.DecodingProblem.UnsupportedSchema(schema), assertFailsWith<Manifest.DecodingProblem> { copy.validate() })
        }
        val problem = Manifest.DecodingProblem.UnsupportedSchema(99)
        assertTrue(problem.description.startsWith("Manifest schema 99 is not supported (this app reads schema"))
    }

    @Test
    fun `URLs need a refresh shortly before they expire`() {
        val manifest = Fixtures.manifest()
        val expiry = manifest.urlsExpireAt
        assertFalse(manifest.urlsNeedRefresh(at = expiry.minusSeconds(120)))
        assertTrue(manifest.urlsNeedRefresh(at = expiry.minusSeconds(30)))
        assertTrue(manifest.urlsNeedRefresh(at = expiry.plusSeconds(1)))
    }
}
