package com.getinsiteview.modelkit

import com.getinsiteview.core.Catalog
import com.getinsiteview.core.CatalogLanguage
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** One element, from its chunk's meta plus the chunk's system. */
data class ElementRecord(
    /** `e` + 32 hex digits: the node / prim name in the chunk files. */
    val id: String,
    val chunk: String,
    /** System key of the element's chunk (`electrical`, `architecture`, …). */
    val system: String,
    val meta: ElementMeta,
) {
    val kind: String get() = meta.kind
    val subsystem: String? get() = meta.subsystem
    val storeyID: String? get() = meta.storeyID
    val roomID: String? get() = meta.roomID
    val tag: String? get() = meta.tag

    /**
     * What people see instead of IFC terms (A-01): the kind and tag ("Outlet K-04"), else a
     * readable IFC name ("South wall"), else the kind ("Pipe").
     */
    fun displayName(catalog: Catalog?, language: CatalogLanguage): String {
        val kindName = catalog?.kindName(kind, language) ?: Catalog.humanizedKey(kind)
        meta.tag?.let { return "$kindName $it" }
        val name = meta.name
        if (name != null && isReadable(name)) return name
        return kindName
    }

    /** A key property with its catalog label. */
    data class KeyProperty(val label: String, val value: String)

    /** Key properties with catalog labels, in catalog order (unknown keys last, by key). */
    fun keyProperties(catalog: Catalog?, language: CatalogLanguage): List<KeyProperty> =
        meta.keyProperties.entries
            .sortedWith(compareBy<Map.Entry<String, String>> { catalog?.propertyOrder(it.key) ?: Int.MAX_VALUE }.thenBy { it.key })
            .map { KeyProperty(catalog?.propertyName(it.key, language) ?: Catalog.humanizedKey(it.key), it.value) }

    companion object {
        /**
         * Authoring-tool names like `Basic Wall:Generic - 200mm:348123` or `M_Duplex Receptacle`
         * are technical, so they're left out of guest-facing names.
         */
        internal fun isReadable(name: String): Boolean {
            if (name.codePointCount(0, name.length) > 40 || name.contains(":") || name.contains("_")) return false
            // Names that end in a long number are usually element ids (`Wall 348123`).
            val lastWord = name.split(" ").lastOrNull { it.isNotEmpty() } ?: ""
            return !(lastWord.length >= 5 && lastWord.all { it.isDigit() })
        }
    }
}

/**
 * A row of the Rooms list (IOS-M2-08): a room from the manifest's `spaces` with the elements in
 * scope that are in it.
 */
data class RoomEntry(
    val space: Manifest.Space,
    /** The space's storey, else the storey most of its elements are on. */
    val storeyID: String?,
    /** System elements in the room (architecture and structure aren't counted). */
    val elementCount: Int,
    /** Systems with elements in the room, architecture and structure excluded. */
    val systems: Set<String>,
) {
    val id: String get() = space.id
}

/**
 * Every loaded element by id, with lookups by storey, system, kind and room (IOS-M1-03). Built
 * chunk by chunk as meta files arrive; a chunk that failed to load is simply absent.
 *
 * iOS's is a value type. This one is mutated in place by [add] and [removeChunk]; take a [copy]
 * to hand a snapshot to another owner (e.g. a `StateFlow`).
 */
class ElementIndex {
    private val _elements = HashMap<String, ElementRecord>()
    private val byStorey = HashMap<String, MutableSet<String>>()
    private val bySystem = HashMap<String, MutableSet<String>>()
    private val byKind = HashMap<String, MutableSet<String>>()
    private val byRoom = HashMap<String, MutableSet<String>>()
    private val byChunk = HashMap<String, MutableSet<String>>()

    val elements: Map<String, ElementRecord> get() = _elements

    val count: Int get() = _elements.size

    val isEmpty: Boolean get() = _elements.isEmpty()

    /** Chunk keys whose meta is in the index. */
    val chunks: Set<String> get() = byChunk.keys.toSet()

    /** System keys that have at least one element. */
    val systems: Set<String> get() = bySystem.keys.toSet()

    /** An independent copy. */
    fun copy(): ElementIndex {
        val copy = ElementIndex()
        copy._elements.putAll(_elements)
        for ((source, target) in listOf(
            byStorey to copy.byStorey, bySystem to copy.bySystem, byKind to copy.byKind,
            byRoom to copy.byRoom, byChunk to copy.byChunk,
        )) {
            source.forEach { (key, ids) -> target[key] = HashSet(ids) }
        }
        return copy
    }

    /** Adds (or replaces) a chunk's elements. */
    fun add(meta: ChunkMeta, chunk: String, system: String) {
        removeChunk(chunk)
        val ids = HashSet<String>()
        for ((id, element) in meta.elements) {
            val existing = _elements[id]
            // The converter keeps one copy of each GlobalId; if two chunks still share one, the
            // first chunk wins.
            if (existing != null && existing.chunk != chunk) continue
            _elements[id] = ElementRecord(id, chunk, system, element)
            ids += id
            bySystem.getOrPut(system) { HashSet() } += id
            byKind.getOrPut(element.kind) { HashSet() } += id
            element.storeyID?.let { byStorey.getOrPut(it) { HashSet() } += id }
            element.roomID?.let { byRoom.getOrPut(it) { HashSet() } += id }
        }
        byChunk[chunk] = ids
    }

    fun removeChunk(chunk: String) {
        val ids = byChunk.remove(chunk) ?: return
        for (id in ids) {
            val record = _elements.remove(id) ?: continue
            remove(id, record.system, bySystem)
            remove(id, record.kind, byKind)
            record.storeyID?.let { remove(id, it, byStorey) }
            record.roomID?.let { remove(id, it, byRoom) }
        }
    }

    private fun remove(id: String, key: String, index: HashMap<String, MutableSet<String>>) {
        val ids = index[key] ?: return
        ids.remove(id)
        if (ids.isEmpty()) index.remove(key)
    }

    operator fun get(id: String): ElementRecord? = _elements[id]

    fun element(id: String): ElementRecord? = _elements[id]

    fun elementsOnStorey(storeyID: String): List<ElementRecord> = records(byStorey[storeyID])

    fun elementsInSystem(system: String): List<ElementRecord> = records(bySystem[system])

    fun elementsOfKind(kind: String): List<ElementRecord> = records(byKind[kind])

    fun elementsInRoom(roomID: String): List<ElementRecord> = records(byRoom[roomID])

    fun elementIDsInChunk(chunk: String): Set<String> = byChunk[chunk]?.toSet() ?: emptySet()

    /** Sorted by id so results are stable. */
    private fun records(ids: Set<String>?): List<ElementRecord> =
        (ids ?: emptySet()).sorted().mapNotNull { _elements[it] }

    /**
     * Elements whose kind the catalog marks as equipment (panels, water heaters, AC units, …),
     * only from `systems` when given: ordered by storey (lowest first), then system in catalog
     * order, then name.
     */
    fun equipment(
        catalog: Catalog,
        storeys: List<Manifest.Storey>,
        systems: Set<String>? = null,
        language: CatalogLanguage = CatalogLanguage.EN,
    ): List<ElementRecord> {
        val storeyOrder = storeyOrder(storeys)
        val kinds = byKind.keys.filter { catalog.isEquipment(it) }
        val matches = kinds.flatMap { records(byKind[it]) }.filter { systems?.contains(it.system) ?: true }
        val collator = NaturalOrder()
        return matches
            .map { it to it.displayName(catalog, language) }
            .sortedWith { lhs, rhs ->
                val leftStorey = lhs.first.storeyID?.let { storeyOrder[it] } ?: Int.MAX_VALUE
                val rightStorey = rhs.first.storeyID?.let { storeyOrder[it] } ?: Int.MAX_VALUE
                if (leftStorey != rightStorey) return@sortedWith leftStorey.compareTo(rightStorey)
                val leftSystem = catalog.systemOrder(lhs.first.system)
                val rightSystem = catalog.systemOrder(rhs.first.system)
                if (leftSystem != rightSystem) return@sortedWith leftSystem.compareTo(rightSystem)
                val byName = collator.compare(lhs.second, rhs.second)
                if (byName == 0) lhs.first.id.compareTo(rhs.first.id) else byName
            }
            .map { it.first }
    }

    /**
     * The Rooms list: every space in the manifest, by storey (lowest first) and then name. Counts
     * only elements of `systems` (all loaded systems when `null`), architecture and structure
     * excluded. With `onlyWithElements`, rooms with none of those are left out.
     */
    fun roomList(
        spaces: List<Manifest.Space>,
        storeys: List<Manifest.Storey>,
        systems: Set<String>? = null,
        onlyWithElements: Boolean = false,
    ): List<RoomEntry> {
        val storeyOrder = storeyOrder(storeys)
        val entries = spaces.mapNotNull { space ->
            val records = records(byRoom[space.id]).filter {
                !ModelFilters.isContext(it.system) && (systems?.contains(it.system) ?: true)
            }
            if (onlyWithElements && records.isEmpty()) return@mapNotNull null
            val storey = space.storeyId ?: mostCommon(records.mapNotNull { it.storeyID })
            RoomEntry(space, storey, records.size, records.map { it.system }.toSet())
        }
        val collator = NaturalOrder()
        return entries.sortedWith { lhs, rhs ->
            val left = lhs.storeyID?.let { storeyOrder[it] } ?: Int.MAX_VALUE
            val right = rhs.storeyID?.let { storeyOrder[it] } ?: Int.MAX_VALUE
            if (left != right) return@sortedWith left.compareTo(right)
            val byName = collator.compare(lhs.space.displayName ?: "", rhs.space.displayName ?: "")
            if (byName == 0) lhs.id.compareTo(rhs.id) else byName
        }
    }

    /** Ids of the elements in a room, for focusing the camera on it. */
    fun elementIDsInRoom(roomID: String): Set<String> = byRoom[roomID]?.toSet() ?: emptySet()

    // Search

    /**
     * Offline search over meta (IOS-M2-03; the server's `/v1/visit/search` when online): every
     * word of the query must match the element's display name, kind name, tag, type name, key
     * property values, system name or room name. Only `systems` (the scope) are searched and
     * architecture never is. Tag matches first, then names that start with the query.
     */
    fun search(
        query: String,
        catalog: Catalog?,
        language: CatalogLanguage,
        systems: Set<String>? = null,
        roomNames: Map<String, String> = emptyMap(),
        limit: Int = 50,
    ): List<ElementRecord> {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val folded = fold(query.trim())
        data class Match(val record: ElementRecord, val score: Int, val name: String)
        val matches = ArrayList<Match>()
        for (record in _elements.values) {
            if (record.system == ChunkPlan.architecture || !(systems?.contains(record.system) ?: true)) continue
            val name = record.displayName(catalog, language)
            val fields = mutableListOf(name, catalog?.kindName(record.kind, language) ?: Catalog.humanizedKey(record.kind))
            fields += listOfNotNull(record.tag, record.meta.typeName)
            fields += record.meta.keyProperties.values
            fields += catalog?.systemName(record.system, language) ?: Catalog.humanizedKey(record.system)
            record.subsystem?.let { subsystem ->
                fields += catalog?.subsystemName(subsystem, record.system, language) ?: Catalog.humanizedKey(subsystem)
            }
            record.roomID?.let { roomNames[it] }?.let { fields += it }
            val haystack = fields.joinToString(" ") { fold(it) }
            if (!words.all { haystack.contains(it) }) continue
            var score = 0
            val tag = record.tag
            if (tag != null && fold(tag) == folded) score += 100
            if (fold(name).startsWith(folded)) score += 10
            matches += Match(record, score, name)
        }
        val collator = NaturalOrder()
        return matches
            .sortedWith { lhs, rhs ->
                if (lhs.score != rhs.score) return@sortedWith rhs.score.compareTo(lhs.score)
                val byName = collator.compare(lhs.name, rhs.name)
                if (byName == 0) lhs.record.id.compareTo(rhs.record.id) else byName
            }
            .take(limit)
            .map { it.record }
    }

    companion object {
        private fun storeyOrder(storeys: List<Manifest.Storey>): Map<String, Int> {
            val order = HashMap<String, Int>()
            storeys.forEach { order.putIfAbsent(it.id, it.order) }
            return order
        }

        private fun mostCommon(values: List<String>): String? =
            values.groupingBy { it }.eachCount().entries
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key

        private val marks = Regex("\\p{Mn}+")

        /** Case- and accent-insensitive form for matching ("Elétrica" finds "eletrica"). */
        internal fun fold(text: String): String =
            marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}

/**
 * Foundation's `localizedStandardCompare`, the Finder's order: case-insensitive, locale-aware, and
 * numbers by value ("Bedroom 2" before "Bedroom 10").
 */
internal class NaturalOrder(locale: Locale = Locale.getDefault()) : Comparator<String> {
    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        val left = runs(a)
        val right = runs(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val l = left[i]
            val r = right[i]
            val lDigits = l[0].isDigit()
            val rDigits = r[0].isDigit()
            val result = if (lDigits && rDigits) {
                val lt = l.trimStart('0')
                val rt = r.trimStart('0')
                if (lt.length != rt.length) lt.length.compareTo(rt.length) else lt.compareTo(rt)
            } else {
                collator.compare(l, r)
            }
            if (result != 0) return result
        }
        return left.size.compareTo(right.size)
    }

    /** Alternating runs of digits and non-digits. */
    private fun runs(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val runs = ArrayList<String>()
        var start = 0
        for (i in 1..text.length) {
            if (i == text.length || text[i].isDigit() != text[start].isDigit()) {
                runs += text.substring(start, i)
                start = i
            }
        }
        return runs
    }
}
