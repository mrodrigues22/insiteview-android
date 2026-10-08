package com.getinsiteview.core

import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Any JSON value, for payloads whose shape the client doesn't fix, such as an element's
 * property sets (`props: { psetName: { propName: value } }`).
 *
 * The cases mirror iOS: `JSONValue.Null`, `Bool`, `Number`, `String`, `Array`, `Object`.
 */
@Serializable(with = JSONValueSerializer::class)
sealed interface JSONValue {
    data object Null : JSONValue
    data class Bool(val value: Boolean) : JSONValue
    data class Number(val value: Double) : JSONValue
    data class String(val value: kotlin.String) : JSONValue
    data class Array(val values: List<JSONValue>) : JSONValue
    data class Object(val fields: Map<kotlin.String, JSONValue>) : JSONValue

    /**
     * A short text for showing the value to people: strings as they are, whole numbers without
     * a decimal point, lists joined with commas. `null` for null, objects and empty values.
     */
    val displayText: kotlin.String?
        get() = when (this) {
            Null, is Object -> null
            is Bool -> if (value) "✓" else "✗"
            is Number -> if (floor(value) == value && abs(value) < 1e15) value.toLong().toString() else value.toString()
            is String -> value.ifEmpty { null }
            is Array -> if (values.isEmpty()) null else values.mapNotNull { it.displayText }.joinToString(", ")
        }

    companion object {
        fun from(element: JsonElement): JSONValue = when (element) {
            JsonNull -> Null
            is JsonPrimitive -> when {
                element.isString -> String(element.content)
                element.booleanOrNull != null -> Bool(element.booleanOrNull!!)
                else -> Number(element.doubleOrNull ?: throw SerializationException("Not a JSON value: ${element.content}"))
            }
            is JsonArray -> Array(element.map(::from))
            is JsonObject -> Object(element.mapValues { from(it.value) })
        }
    }

    /** The value as a kotlinx `JsonElement`. Whole numbers below 10¹⁵ are written without `.0`. */
    fun toJsonElement(): JsonElement = when (this) {
        Null -> JsonNull
        is Bool -> JsonPrimitive(value)
        is Number -> if (floor(value) == value && abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Array -> JsonArray(values.map { it.toJsonElement() })
        is Object -> JsonObject(fields.mapValues { it.value.toJsonElement() })
    }
}

/** JSON-only serializer for [JSONValue] (through `JsonElement`). */
object JSONValueSerializer : KSerializer<JSONValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): JSONValue {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSONValue can only be decoded from JSON.")
        return JSONValue.from(json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: JSONValue) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSONValue can only be encoded to JSON.")
        json.encodeJsonElement(value.toJsonElement())
    }
}
