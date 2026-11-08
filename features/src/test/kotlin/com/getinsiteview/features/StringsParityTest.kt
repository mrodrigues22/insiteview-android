package com.getinsiteview.features

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Every string resource exists in English, Portuguese (Brazil) and Spanish (CLAUDE.md "Android specifics"). */
class StringsParityTest {
    private val res = File("src/main/res")
    private val locales = listOf("values", "values-b+pt+BR", "values-es")

    private fun keys(folder: String): Map<String, Set<String>> =
        File(res, folder).listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
            .associate { file ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val names = mutableSetOf<String>()
                for (tag in listOf("string", "plurals")) {
                    val nodes = doc.getElementsByTagName(tag)
                    for (i in 0 until nodes.length) names += "$tag:" + nodes.item(i).attributes.getNamedItem("name").nodeValue
                }
                file.name to names
            }

    @Test
    fun `every locale has the same string files and keys`() {
        val english = keys("values")
        assertTrue(english.isNotEmpty())
        for (locale in locales.drop(1)) {
            val other = keys(locale)
            assertEquals(english.keys, other.keys, "string files in $locale")
            for ((file, names) in english) {
                assertEquals(names, other.getValue(file), "keys of $locale/$file")
            }
        }
    }

    @Test
    fun `placeholders match across locales`() {
        val pattern = Regex("""%(\d+\$)?[sd]""")
        fun placeholders(folder: String): Map<String, List<String>> {
            val result = mutableMapOf<String, List<String>>()
            File(res, folder).listFiles { f -> f.name.startsWith("strings") }.orEmpty().forEach { file ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = doc.getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    result[node.attributes.getNamedItem("name").nodeValue] =
                        pattern.findAll(node.textContent).map { it.value }.sorted().toList()
                }
            }
            return result
        }
        val english = placeholders("values")
        for (locale in locales.drop(1)) {
            val other = placeholders(locale)
            for ((name, list) in english) assertEquals(list, other[name], "placeholders of $name in $locale")
        }
    }

    private fun plurals(folder: String): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, Map<String, String>>()
        File(res, folder).listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("plurals")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val items = mutableMapOf<String, String>()
                val children = node.childNodes
                for (j in 0 until children.length) {
                    val child = children.item(j)
                    if (child.nodeName == "item") items[child.attributes.getNamedItem("quantity").nodeValue] = child.textContent
                }
                result[node.attributes.getNamedItem("name").nodeValue] = items
            }
        }
        return result
    }

    /** P1-03: the converter once wrote "%d tentativa restantes"; every word of the phrase agrees. */
    @Test
    fun `plurals agree in number in every locale`() {
        val expected = mapOf(
            "values-b+pt+BR" to listOf("Teste · %d dias restantes", "PIN incorreto. %d tentativas restantes.", "%d documentos", "%d itens"),
            "values-es" to listOf("Prueba · %d días restantes", "PIN incorrecto. %d intentos restantes.", "%d documentos", "%d elementos"),
            "values" to listOf("Trial · %d days left", "Wrong PIN. %d tries left.", "%d documents", "%d items"),
        )
        for ((locale, others) in expected) {
            val all = plurals(locale)
            val otherForms = all.values.mapNotNull { it["other"] }
            for (form in others) assertTrue(form in otherForms, "$locale has \"$form\" (found $otherForms)")
            for ((name, items) in all) {
                val one = items.getValue("one")
                val other = items.getValue("other")
                assertTrue(one != other, "$locale $name: one and other differ")
                items["many"]?.let { assertEquals(other, it, "$locale $name: many is other") }
            }
        }
    }
}
