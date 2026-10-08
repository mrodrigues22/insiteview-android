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
}
