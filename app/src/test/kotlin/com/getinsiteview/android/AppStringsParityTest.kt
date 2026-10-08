package com.getinsiteview.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The app's own strings exist in English, Portuguese (Brazil) and Spanish (CLAUDE.md "Android specifics"). */
class AppStringsParityTest {
    private val res = File("src/main/res")
    private val locales = listOf("values", "values-b+pt+BR", "values-es")

    private fun strings(folder: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        File(res, folder).listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            for (tag in listOf("string", "plurals")) {
                val nodes = doc.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    result["${file.name}/$tag:" + node.attributes.getNamedItem("name").nodeValue] = node.textContent
                }
            }
        }
        return result
    }

    @Test
    fun `every locale has the same keys`() {
        val english = strings("values")
        assertTrue(english.isNotEmpty())
        for (locale in locales.drop(1)) assertEquals(english.keys, strings(locale).keys, "keys of $locale")
    }

    @Test
    fun `placeholders match across locales`() {
        val pattern = Regex("""%(\d+\$)?[sd]""")
        fun placeholders(text: String) = pattern.findAll(text).map { it.value }.sorted().toList()
        val english = strings("values")
        for (locale in locales.drop(1)) {
            val other = strings(locale)
            for ((name, text) in english) assertEquals(placeholders(text), other[name]?.let(::placeholders), "placeholders of $name in $locale")
        }
    }
}
