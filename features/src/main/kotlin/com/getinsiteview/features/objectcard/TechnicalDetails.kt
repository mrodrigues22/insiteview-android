package com.getinsiteview.features.objectcard

import com.getinsiteview.api.ElementDetail
import java.text.Collator
import java.util.Locale

/**
 * "Technical details" for members (A-01): IFC class, GlobalId and the property sets, each sorted
 * by name (iOS `localizedStandardCompare`). Pure, so it's tested on the JVM.
 */
data class TechnicalDetails(
    val ifcClass: String?,
    val globalID: String?,
    val propertySets: List<PropertySet>,
) {
    data class Property(val name: String, val value: String)

    data class PropertySet(val name: String, val properties: List<Property>) {
        val id: String get() = name
    }

    val isEmpty: Boolean get() = ifcClass == null && globalID == null && propertySets.isEmpty()

    companion object {
        /**
         * Members only, from the API's detail: properties without a displayable value and sets left
         * empty are dropped; `null` when nothing is left.
         */
        fun of(isMember: Boolean, detail: ElementDetail?, locale: Locale = Locale.getDefault()): TechnicalDetails? {
            if (!isMember || detail == null) return null
            val order = StandardOrder(locale)
            val sets = detail.propertySets
                .map { (name, properties) ->
                    PropertySet(
                        name = name,
                        properties = properties
                            .mapNotNull { (key, value) -> value.displayText?.let { Property(key, it) } }
                            .sortedWith { a, b -> order.compare(a.name, b.name) },
                    )
                }
                .filter { it.properties.isNotEmpty() }
                .sortedWith { a, b -> order.compare(a.name, b.name) }
            val details = TechnicalDetails(detail.ifcClass, detail.globalId, sets)
            return if (details.isEmpty) null else details
        }
    }
}

/**
 * Finder-style name order (iOS `localizedStandardCompare`): case- and accent-insensitive, runs of
 * digits by value ("Pset 2" before "Pset 10").
 */
class StandardOrder(locale: Locale = Locale.getDefault()) : Comparator<String> {
    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        val left = chunks(a)
        val right = chunks(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val l = left[i]
            val r = right[i]
            val order = if (l.first().isDigit() && r.first().isDigit()) {
                val lv = l.trimStart('0')
                val rv = r.trimStart('0')
                if (lv.length != rv.length) lv.length.compareTo(rv.length) else lv.compareTo(rv)
            } else {
                collator.compare(l, r)
            }
            if (order != 0) return order
        }
        val bySize = left.size.compareTo(right.size)
        return if (bySize != 0) bySize else a.compareTo(b)
    }

    private fun chunks(string: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (character in string) {
            if (current.isNotEmpty() && current.last().isDigit() != character.isDigit()) {
                result.add(current.toString())
                current.clear()
            }
            current.append(character)
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }
}
