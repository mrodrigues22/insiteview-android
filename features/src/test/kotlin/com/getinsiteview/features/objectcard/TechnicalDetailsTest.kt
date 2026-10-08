package com.getinsiteview.features.objectcard

import com.getinsiteview.api.ElementDetail
import com.getinsiteview.core.JSONValue
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class TechnicalDetailsTest {
    private fun detail(
        ifcClass: String? = "IfcOutlet",
        globalId: String? = "2O2Fr_t4X7Zf8NOew3FLOH",
        props: Map<String, JSONValue> = emptyMap(),
    ) = ElementDetail(
        id = "e1", globalId = globalId, ifcClass = ifcClass, kind = "outlet", name = null, tag = null, typeName = null,
        system = "electrical", subsystem = null, keyProps = emptyMap(), props = props, documents = emptyList(),
    )

    @Test
    fun `guests never get technical details`() {
        assertNull(TechnicalDetails.of(isMember = false, detail = detail()))
        assertNull(TechnicalDetails.of(isMember = true, detail = null))
    }

    @Test
    fun `sets and properties are sorted by name, numbers by value, empty ones dropped`() {
        val props = mapOf(
            "Pset 10" to JSONValue.Object(mapOf("b" to JSONValue.String("2"), "A" to JSONValue.String("1"))),
            "Pset 2" to JSONValue.Object(mapOf("x" to JSONValue.Number(3.0))),
            "Empty" to JSONValue.Object(mapOf("y" to JSONValue.Null)),
            "NotASet" to JSONValue.String("value"),
        )
        val details = TechnicalDetails.of(isMember = true, detail = detail(props = props), locale = Locale.ENGLISH)!!
        assertEquals(listOf("Pset 2", "Pset 10"), details.propertySets.map { it.name })
        assertEquals(listOf("A", "b"), details.propertySets[1].properties.map { it.name })
        assertEquals("3", details.propertySets[0].properties.single().value)
        assertEquals("IfcOutlet", details.ifcClass)
    }

    @Test
    fun `nothing to show is no section`() {
        assertNull(TechnicalDetails.of(isMember = true, detail = detail(ifcClass = null, globalId = null)))
    }
}
