package com.getinsiteview.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CatalogTest {
    companion object {
        /** `insiteview-api/catalog/catalog.json`, the file `GET /v1/catalog` serves. */
        fun fixture(): Catalog {
            val text = assertNotNull(CatalogTest::class.java.getResource("/catalog.json")).readText()
            return APIJSON.decode<Catalog>(text)
        }
    }

    @Test
    fun `Decodes the API's catalog`() {
        val catalog = fixture()
        assertEquals(1, catalog.version)
        assertEquals(
            listOf("architecture", "structure", "electrical", "plumbing", "gas", "hvac", "fire", "data", "other"),
            catalog.systems.map { it.key },
        )
        assertTrue(catalog.kinds.size >= 40)
        assertEquals("circuit", catalog.properties.firstOrNull()?.key)
    }

    @Test
    fun `Colours come from the catalog, subsystem first`() {
        val catalog = fixture()
        assertEquals("#F2B72E", catalog.color(system = "electrical").hex)
        assertEquals("#F4A261", catalog.color(system = "plumbing", subsystem = "hot_water").hex)
        assertEquals(catalog.color(system = "plumbing"), catalog.color(system = "plumbing", subsystem = "unknown"))
        assertEquals(CatalogColor.FALLBACK, catalog.color(system = "teleport"))
        // Hot water must stay distinguishable from gas (master PLAN §9).
        assertNotEquals(catalog.color(system = "gas"), catalog.color(system = "plumbing", subsystem = "hot_water"))
    }

    @Test
    fun `Names in three languages, with a readable fallback for unknown keys`() {
        val catalog = fixture()
        assertEquals("Hidráulica", catalog.systemName("plumbing", CatalogLanguage.PT_BR))
        assertEquals("Fontanería", catalog.systemName("plumbing", CatalogLanguage.ES))
        assertEquals("Outlet", catalog.kindName("outlet", CatalogLanguage.EN))
        assertEquals("Hot water", catalog.subsystemName("hot_water", "plumbing", CatalogLanguage.EN))
        assertEquals("Serial number", catalog.propertyName("serial", CatalogLanguage.EN))
        assertEquals("Heat pump", catalog.kindName("heat_pump", CatalogLanguage.EN))
    }

    @Test
    fun `Equipment kinds and ordering`() {
        val catalog = fixture()
        assertTrue(catalog.isEquipment(kind = "panel"))
        assertTrue(catalog.isEquipment(kind = "water_heater"))
        assertFalse(catalog.isEquipment(kind = "outlet"))
        assertFalse(catalog.isEquipment(kind = "not_a_kind"))
        assertTrue(catalog.systemOrder("architecture") < catalog.systemOrder("electrical"))
        assertEquals(Int.MAX_VALUE, catalog.systemOrder("teleport"))
        assertTrue(catalog.propertyOrder("circuit") < catalog.propertyOrder("voltage"))
    }

    @Test
    fun `Round-trips through JSON (the on-disk cache)`() {
        val catalog = fixture()
        val decoded = Json.decodeFromString<Catalog>(Json.encodeToString(catalog))
        assertEquals(catalog, decoded)
        assertEquals(true, decoded.kind("panel")?.equipment)
    }

    @ParameterizedTest
    @CsvSource("'#F2B72E', true", "f2b72e, true", "'#F2B72', false", "'#GGGGGG', false", "'', false")
    fun `Colour parsing`(hex: String, valid: Boolean) {
        assertEquals(valid, CatalogColor.fromHex(hex) != null)
    }

    @Test
    fun `Language from preferred languages`() {
        assertEquals(CatalogLanguage.PT_BR, CatalogLanguage.fromIdentifier("pt-BR"))
        assertEquals(CatalogLanguage.PT_BR, CatalogLanguage.fromIdentifier("pt_PT"))
        assertEquals(CatalogLanguage.ES, CatalogLanguage.fromIdentifier("es-MX"))
        assertEquals(CatalogLanguage.EN, CatalogLanguage.fromIdentifier("EN-us"))
        assertNull(CatalogLanguage.fromIdentifier("fr-FR"))
        assertEquals(CatalogLanguage.ES, CatalogLanguage.preferred(listOf("fr-FR", "es-419", "en")))
        assertEquals(CatalogLanguage.EN, CatalogLanguage.preferred(listOf("de")))
        assertEquals(CatalogLanguage.EN, CatalogLanguage.preferred(emptyList()))
    }
}
