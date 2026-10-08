package com.getinsiteview.modelkit

import com.getinsiteview.core.JSONValue
import com.getinsiteview.modelkit.geometry.Vec2
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * One chunk's `{key}.meta.json` (master PLAN §9 "Chunk meta"):
 * `{ "e{hex}": { k, c, n, g, t, s, r, ss, p, d, f } }`. It ships with the chunk so the object card
 * works offline.
 */
data class ChunkMeta(val elements: Map<String, ElementMeta>) {
    companion object {
        fun decode(data: ByteArray): ChunkMeta = decode(data.decodeToString())

        fun decode(string: String): ChunkMeta {
            val root = Json.parseToJsonElement(string) as? JsonObject
                ?: throw SerializationException("Chunk meta is not a JSON object.")
            return ChunkMeta(root.mapValues { ElementMeta.fromJson(it.value) })
        }
    }
}

/** An element's entry in chunk meta. Every field but the kind may be null or missing. */
@Serializable(with = ElementMetaSerializer::class)
data class ElementMeta(
    /** `k`: catalog kind key (`outlet`, `pipe`, …). */
    val kind: String,
    /** `c`: IFC class. Only for "Technical details" (members); never shown to guests. */
    val ifcClass: String? = null,
    /** `n`: IFC name. */
    val name: String? = null,
    /** `g`: tag (Revit "Mark"), e.g. `K-04`. */
    val tag: String? = null,
    /** `t`: type name. */
    val typeName: String? = null,
    /** `s`: storey id. */
    val storeyID: String? = null,
    /** `r`: room (space) id. */
    val roomID: String? = null,
    /** `ss`: subsystem key. */
    val subsystem: String? = null,
    /** `p`: key properties, catalog property key → display value (`"voltage": "120 V"`). */
    val keyProperties: Map<String, String> = emptyMap(),
    /** `d`: number of linked documents. */
    val documentCount: Int = 0,
    /**
     * `f`: a wall's footprint, its parts higher than 1.2 m above its floor seen from above: plan
     * `[x, z]` polygons, one per piece a passage open to the top leaves (the wall above a door or
     * window closes it). Only walls have it; AR hides what's behind them (`ProximityFade`).
     */
    val footprint: List<List<Vec2>>? = null,
) {
    companion object {
        /**
         * Lenient decoding: blank strings are `null`, a missing kind is `element`, property values
         * that aren't strings become display text, malformed fields are dropped rather than failing
         * the whole meta. Throws only when `element` isn't an object.
         */
        fun fromJson(element: JsonElement): ElementMeta {
            val obj = element as? JsonObject ?: throw SerializationException("Element meta is not a JSON object.")
            fun text(key: String): String? {
                val value = obj[key] as? JsonPrimitive ?: return null
                if (!value.isString) return null
                return value.content.trim().ifEmpty { null }
            }
            // Values are display strings; tolerate numbers and booleans from a future converter.
            val properties = (obj["p"] as? JsonObject)?.let { p ->
                p.mapNotNull { (key, value) ->
                    val display = runCatching { JSONValue.from(value).displayText }.getOrNull() ?: return@mapNotNull null
                    key to display
                }.toMap()
            } ?: emptyMap()
            val documentCount = (obj["d"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?.takeIf { it == Math.rint(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt() ?: 0
            // Polygons of at least three finite points; anything else is left out rather than failing the meta.
            val polygons = (obj["f"] as? JsonArray)?.mapNotNull { polygon ->
                val points = (polygon as? JsonArray) ?: return@mapNotNull null
                val parsed = points.mapNotNull { point ->
                    val pair = point as? JsonArray ?: return@mapNotNull null
                    if (pair.size != 2) return@mapNotNull null
                    val x = (pair[0] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                    val z = (pair[1] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                    if (x == null || z == null || !x.isFinite() || !z.isFinite()) null else Vec2(x, z)
                }
                if (parsed.size >= 3 && parsed.size == points.size) parsed else null
            } ?: emptyList()
            return ElementMeta(
                kind = text("k") ?: "element",
                ifcClass = text("c"),
                name = text("n"),
                tag = text("g"),
                typeName = text("t"),
                storeyID = text("s"),
                roomID = text("r"),
                subsystem = text("ss"),
                keyProperties = properties,
                documentCount = documentCount,
                footprint = polygons.ifEmpty { null },
            )
        }
    }

    /** The meta entry as JSON (short keys; `null`s left out). */
    fun toJson(): JsonObject = buildJsonObject {
        put("k", kind)
        ifcClass?.let { put("c", it) }
        name?.let { put("n", it) }
        tag?.let { put("g", it) }
        typeName?.let { put("t", it) }
        storeyID?.let { put("s", it) }
        roomID?.let { put("r", it) }
        subsystem?.let { put("ss", it) }
        put("p", JsonObject(keyProperties.mapValues { JsonPrimitive(it.value) }))
        put("d", documentCount)
        footprint?.let { polygons ->
            put("f", JsonArray(polygons.map { polygon -> JsonArray(polygon.map { JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y))) }) }))
        }
    }
}

/** JSON-only serializer for [ElementMeta] (the lenient decoding of [ElementMeta.fromJson]). */
object ElementMetaSerializer : KSerializer<ElementMeta> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): ElementMeta {
        val json = decoder as? JsonDecoder ?: throw SerializationException("ElementMeta can only be decoded from JSON.")
        return ElementMeta.fromJson(json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: ElementMeta) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("ElementMeta can only be encoded to JSON.")
        json.encodeJsonElement(value.toJson())
    }
}
