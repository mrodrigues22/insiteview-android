package com.getinsiteview.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class JSONValueTest {
    @Test
    fun `Decodes property sets of any shape`() {
        val json = """{"Pset_Common":{"Reference":"K-04","IsExternal":false,"Width":0.9,"Count":3,"Tags":["a","b"],"Empty":null}}"""
        val props = Json.decodeFromString<Map<String, Map<String, JSONValue>>>(json)
        val common = assertNotNull(props["Pset_Common"])
        assertEquals(JSONValue.String("K-04"), common["Reference"])
        assertEquals(JSONValue.Bool(false), common["IsExternal"])
        assertEquals(JSONValue.Number(0.9), common["Width"])
        assertEquals(JSONValue.Number(3.0), common["Count"])
        assertEquals(JSONValue.Array(listOf(JSONValue.String("a"), JSONValue.String("b"))), common["Tags"])
        assertEquals(JSONValue.Null, common["Empty"])
    }

    @Test
    fun `Display text`() {
        assertEquals("3", JSONValue.Number(3.0).displayText)
        assertEquals("0.9", JSONValue.Number(0.9).displayText)
        assertNull(JSONValue.String("").displayText)
        assertEquals("1, x", JSONValue.Array(listOf(JSONValue.Number(1.0), JSONValue.String("x"))).displayText)
        assertNull(JSONValue.Null.displayText)
        assertNull(JSONValue.Object(mapOf("a" to JSONValue.Number(1.0))).displayText)
    }

    @Test
    fun `Round-trips`() {
        val value: JSONValue = JSONValue.Object(
            mapOf("a" to JSONValue.Array(listOf(JSONValue.Number(1.5), JSONValue.Bool(true), JSONValue.Null, JSONValue.String("x")))),
        )
        assertEquals(value, Json.decodeFromString<JSONValue>(Json.encodeToString(value)))
    }
}
