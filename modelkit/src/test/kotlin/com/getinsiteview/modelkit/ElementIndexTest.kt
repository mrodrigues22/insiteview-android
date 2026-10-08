package com.getinsiteview.modelkit

import com.getinsiteview.core.CatalogLanguage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Chunk meta and ElementIndex")
class ElementIndexTest {
    companion object {
        const val storey = "e614d613568044166b4fe08af3b18d142"
        const val room = "ed3083b6225a94e56856253148965de2f"

        /** The test-room index: architecture, electrical and plumbing meta from the converter. */
        fun testRoomIndex(): ElementIndex {
            val index = ElementIndex()
            for (chunk in listOf("architecture", "electrical", "plumbing")) {
                index.add(Fixtures.meta(chunk), chunk = chunk, system = chunk)
            }
            return index
        }
    }

    @Test
    fun `Decodes the converter's meta`() {
        val meta = Fixtures.meta("electrical")
        assertEquals(4, meta.elements.size)
        val outlet = assertNotNull(meta.elements["e22dff25b5a264301813afd2f07c0ff68"])
        assertEquals("outlet", outlet.kind)
        assertEquals("IfcOutlet", outlet.ifcClass)
        assertEquals("Outlet K-01", outlet.name)
        assertEquals("K-01", outlet.tag)
        assertNull(outlet.typeName)
        assertEquals(storey, outlet.storeyID)
        assertEquals(room, outlet.roomID)
        assertEquals("power", outlet.subsystem)
        assertTrue(outlet.keyProperties.isEmpty())
        assertEquals(0, outlet.documentCount)
    }

    @Test
    fun `Meta decoding is lenient about missing fields, blanks and non-string properties`() {
        val json = """
            {
              "e0f3c2": { "k": "outlet", "c": "IfcOutlet", "n": "M_Duplex Receptacle", "g": "K-04", "t": "Standard",
                          "s": "s1", "r": "r1", "ss": "power",
                          "p": { "circuit": "K-04", "panel": "Main panel", "voltage": "120 V", "poles": 2, "gfci": true }, "d": 1 },
              "e0a": { "k": "pipe", "g": "  ", "p": null },
              "e0b": { }
            }
        """.trimIndent()
        val meta = ChunkMeta.decode(json.encodeToByteArray())
        val outlet = assertNotNull(meta.elements["e0f3c2"])
        assertEquals(
            mapOf("circuit" to "K-04", "panel" to "Main panel", "voltage" to "120 V", "poles" to "2", "gfci" to "✓"),
            outlet.keyProperties,
        )
        assertEquals(1, outlet.documentCount)
        assertNull(meta.elements["e0a"]?.tag)
        assertEquals(emptyMap(), meta.elements["e0a"]?.keyProperties)
        assertEquals("element", meta.elements["e0b"]?.kind)
    }

    @Test
    fun `Lookups by storey, system, kind and room`() {
        val index = testRoomIndex()
        assertEquals(12, index.count)
        assertEquals(setOf("architecture", "electrical", "plumbing"), index.systems)
        assertEquals(12, index.elementsOnStorey(storey).size)
        assertEquals(4, index.elementsInSystem("electrical").size)
        assertEquals(listOf<String?>("K-01", "K-03", "K-02"), index.elementsOfKind("outlet").map { it.tag }) // sorted by id
        assertEquals(4, index.elementsOfKind("wall").size)
        assertEquals(5, index.elementsInRoom(room).size) // floor slab, 3 outlets, switch
        assertEquals("cold_water", index["e3d036843b0d84631aba731b1ecebf2d7"]?.subsystem)
        assertEquals("plumbing", index["e3d036843b0d84631aba731b1ecebf2d7"]?.system)
        assertNull(index.element("missing"))
    }

    @Test
    fun `Removing and replacing a chunk`() {
        val index = testRoomIndex()
        index.removeChunk("electrical")
        assertEquals(8, index.count)
        assertTrue(index.elementsInSystem("electrical").isEmpty())
        assertTrue(index.elementsOfKind("outlet").isEmpty())
        assertEquals(1, index.elementsInRoom(room).size)
        assertFalse("electrical" in index.chunks)
        index.add(Fixtures.meta("electrical"), chunk = "electrical", system = "electrical")
        index.add(Fixtures.meta("electrical"), chunk = "electrical", system = "electrical")
        assertEquals(12, index.count)
        assertEquals(4, index.elementIDsInChunk("electrical").size)
    }

    @Test
    fun `An id in two chunks stays with the first`() {
        val index = ElementIndex()
        index.add(ChunkMeta(mapOf("e1" to ElementMeta(kind = "wall"))), chunk = "architecture", system = "architecture")
        index.add(
            ChunkMeta(mapOf("e1" to ElementMeta(kind = "pipe"), "e2" to ElementMeta(kind = "pipe"))),
            chunk = "plumbing", system = "plumbing",
        )
        assertEquals("architecture", index["e1"]?.chunk)
        assertEquals(listOf("e2"), index.elementsInSystem("plumbing").map { it.id })
    }

    @Test
    @DisplayName("Friendly names: kind and tag, readable names, never IFC terms")
    fun `Friendly names - kind and tag, readable names, never IFC terms`() {
        val catalog = Fixtures.catalog()
        val index = testRoomIndex()
        assertEquals("Outlet K-01", index["e22dff25b5a264301813afd2f07c0ff68"]?.displayName(catalog, CatalogLanguage.EN))
        assertEquals("Tomada K-01", index["e22dff25b5a264301813afd2f07c0ff68"]?.displayName(catalog, CatalogLanguage.PT_BR))
        assertEquals("South wall", index["e00b72488d323437b84ba0873c48f9010"]?.displayName(catalog, CatalogLanguage.EN))
        assertEquals("Cold water run", index["e3d036843b0d84631aba731b1ecebf2d7"]?.displayName(catalog, CatalogLanguage.EN))

        fun name(meta: ElementMeta): String =
            ElementRecord(id = "e1", chunk = "x", system = "x", meta = meta).displayName(catalog, CatalogLanguage.EN)
        assertEquals("Outlet", name(ElementMeta(kind = "outlet", name = "M_Duplex Receptacle")))
        assertEquals("Wall", name(ElementMeta(kind = "wall", name = "Basic Wall:Generic - 200mm:348123")))
        assertEquals("Wall", name(ElementMeta(kind = "wall", name = "Wall 348123")))
        assertEquals("Water heater WH-1", name(ElementMeta(kind = "water_heater", name = "Water heater", tag = "WH-1")))
        assertEquals(
            "Heat pump",
            ElementRecord(id = "e1", chunk = "x", system = "x", meta = ElementMeta(kind = "heat_pump")).displayName(null, CatalogLanguage.EN),
        )
    }

    @Test
    fun `Key properties in catalog order with catalog labels`() {
        val catalog = Fixtures.catalog()
        val record = ElementRecord(
            id = "e1", chunk = "electrical", system = "electrical",
            meta = ElementMeta(kind = "outlet", keyProperties = mapOf("voltage" to "120 V", "zeta" to "z", "circuit" to "K-04", "panel" to "Main panel")),
        )
        val properties = record.keyProperties(catalog, CatalogLanguage.EN)
        assertEquals(listOf("Circuit", "Panel", "Voltage", "Zeta"), properties.map { it.label })
        assertEquals(listOf("K-04", "Main panel", "120 V", "z"), properties.map { it.value })
        assertEquals("Circuito", record.keyProperties(catalog, CatalogLanguage.PT_BR).map { it.label }.firstOrNull())
    }

    @Test
    @DisplayName("Equipment list: equipment kinds only, by storey, system and name")
    fun `Equipment list - equipment kinds only, by storey, system and name`() {
        val catalog = Fixtures.catalog()
        val storeys = listOf(
            Manifest.Storey(id = "s0", name = "Ground", elevation = 0.0, order = 0),
            Manifest.Storey(id = "s1", name = "Upper", elevation = 3.0, order = 1),
        )
        val index = ElementIndex()
        index.add(
            ChunkMeta(
                mapOf(
                    "e01" to ElementMeta(kind = "panel", tag = "QDC-2", storeyID = "s1"),
                    "e02" to ElementMeta(kind = "panel", tag = "QDC-1", storeyID = "s0"),
                    "e03" to ElementMeta(kind = "outlet", tag = "K-01", storeyID = "s0"),
                    "e04" to ElementMeta(kind = "meter", storeyID = null),
                ),
            ),
            chunk = "electrical", system = "electrical",
        )
        index.add(
            ChunkMeta(
                mapOf(
                    "e05" to ElementMeta(kind = "water_heater", tag = "WH", storeyID = "s0"),
                    "e06" to ElementMeta(kind = "pipe", storeyID = "s0"),
                ),
            ),
            chunk = "plumbing", system = "plumbing",
        )

        val all = index.equipment(catalog, storeys)
        assertEquals(listOf("e02", "e05", "e01", "e04"), all.map { it.id })
        val plumbingOnly = index.equipment(catalog, storeys, systems = setOf("plumbing"))
        assertEquals(listOf("e05"), plumbingOnly.map { it.id })
    }

    @Test
    fun `Rooms list from the manifest's spaces, counting elements in scope`() {
        val manifest = Fixtures.manifest()
        val index = testRoomIndex()
        val rooms = index.roomList(spaces = manifest.spaces, storeys = manifest.storeys)
        val room = assertNotNull(rooms.firstOrNull())
        assertEquals(1, rooms.size)
        assertEquals(ElementIndexTest.room, room.id)
        assertEquals("Test room", room.space.displayName)
        assertEquals(storey, room.storeyID)
        // 3 outlets and a switch; walls in the room are context, not counted.
        assertEquals(4, room.elementCount)
        assertEquals(setOf("electrical"), room.systems)
        assertEquals(5, index.elementIDsInRoom(ElementIndexTest.room).size)

        // A plumbing-only scope: the room has nothing to show.
        assertEquals(0, index.roomList(spaces = manifest.spaces, storeys = manifest.storeys, systems = setOf("plumbing")).firstOrNull()?.elementCount)
        assertTrue(index.roomList(spaces = manifest.spaces, storeys = manifest.storeys, systems = setOf("plumbing"), onlyWithElements = true).isEmpty())
    }

    @Test
    @DisplayName("Rooms sort by storey, then name; a space without a storey takes its elements'")
    fun `Rooms sort by storey, then name, a space without a storey takes its elements'`() {
        val storeys = listOf(
            Manifest.Storey(id = "s0", name = "Ground", elevation = 0.0, order = 0),
            Manifest.Storey(id = "s1", name = "Upper", elevation = 3.0, order = 1),
        )
        val spaces = listOf(
            Manifest.Space(id = "r3", name = "3", longName = "Bedroom 10", storeyId = "s1", areaM2 = 12.0),
            Manifest.Space(id = "r1", name = "1", longName = "Kitchen", storeyId = "s0", areaM2 = 9.0),
            Manifest.Space(id = "r2", name = "2", longName = "Bedroom 2", storeyId = "s1", areaM2 = 11.0),
            Manifest.Space(id = "r4", name = "4", longName = null, storeyId = null, areaM2 = null),
        )
        val index = ElementIndex()
        index.add(ChunkMeta(mapOf("e01" to ElementMeta(kind = "outlet", storeyID = "s0", roomID = "r4"))), chunk = "electrical", system = "electrical")
        val rooms = index.roomList(spaces = spaces, storeys = storeys)
        // Names sort naturally ("Bedroom 2" before "Bedroom 10"); "4" has no long name.
        assertEquals(listOf("r4", "r1", "r2", "r3"), rooms.map { it.id })
        assertEquals("s0", rooms[0].storeyID)
    }

    @Test
    @DisplayName("Offline search: every word matches, scoped to the systems in scope, tags first")
    fun `Offline search - every word matches, scoped to the systems in scope, tags first`() {
        val catalog = Fixtures.catalog()
        val index = testRoomIndex()
        val outlets = index.search("outlet", catalog, CatalogLanguage.EN)
        assertEquals(3, outlets.size)
        assertTrue(outlets.all { it.kind == "outlet" })

        val tag = index.search("k-02", catalog, CatalogLanguage.EN)
        assertEquals("K-02", tag.firstOrNull()?.tag)

        // Room names and system names match too, accent-insensitively.
        val inRoom = index.search("test room switch", catalog, CatalogLanguage.EN, roomNames = mapOf(room to "Test room"))
        assertEquals(listOf("switch"), inRoom.map { it.kind })
        assertFalse(index.search("eletrica", catalog, CatalogLanguage.PT_BR).isEmpty())

        // Out of scope: nothing from electrical; architecture is never a result.
        assertTrue(index.search("outlet", catalog, CatalogLanguage.EN, systems = setOf("plumbing")).isEmpty())
        assertTrue(index.search("wall", catalog, CatalogLanguage.EN).isEmpty())
        assertTrue(index.search("   ", catalog, CatalogLanguage.EN).isEmpty())
    }
}
