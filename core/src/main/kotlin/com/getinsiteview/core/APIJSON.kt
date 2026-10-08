package com.getinsiteview.core

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * JSON coding shared by the API client and the model files (master PLAN §7: camelCase JSON,
 * `DateTimeOffset` in UTC).
 *
 * .NET writes timestamps like `2026-10-01T12:15:00.1234567+00:00`: up to 7 fractional digits and a
 * numeric offset. [ISO8601Timestamp] parses both that and the `Z` form; [ISO8601InstantSerializer]
 * uses it for `Instant` fields (annotate them with `@Contextual` or
 * `@Serializable(with = ISO8601InstantSerializer::class)`).
 */
object APIJSON {
    /**
     * Unknown keys are ignored (a newer API adds fields), missing nullable fields decode as `null`
     * and `null`s aren't written (Swift's `Codable` behaviour), defaults are written.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        serializersModule = SerializersModule { contextual(Instant::class, ISO8601InstantSerializer) }
    }

    inline fun <reified T> decode(string: String): T = json.decodeFromString(serializer<T>(), string)

    /** Encodes with object keys sorted, like iOS's `.sortedKeys`, so output is deterministic. */
    inline fun <reified T> encode(value: T): String = encode(serializer<T>(), value)

    fun <T> encode(serializer: KSerializer<T>, value: T): String =
        sortedKeys(json.encodeToJsonElement(serializer, value)).toString()

    private fun sortedKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sortedKeys(it.value) })
        is JsonArray -> JsonArray(element.map(::sortedKeys))
        else -> element
    }
}

/** `Instant` as an ISO 8601 string: parsed with [ISO8601Timestamp.parse], written with [ISO8601Timestamp.format]. */
object ISO8601InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.getinsiteview.core.ISO8601Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val string = decoder.decodeString()
        return ISO8601Timestamp.parse(string)
            ?: throw SerializationException("Expected an ISO 8601 timestamp, found \"$string\".")
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(ISO8601Timestamp.format(value))
    }
}

/** ISO 8601 / RFC 3339 timestamps: `YYYY-MM-DDTHH:MM:SS[.fraction](Z|±HH:MM)`. */
object ISO8601Timestamp {
    /**
     * Parses a timestamp with any number of fractional digits (kept to the nanosecond) and a `Z` or
     * `±HH:MM` offset. Returns `null` for anything else, including a missing offset.
     */
    fun parse(string: String): Instant? {
        val scanner = Scanner(string)
        val year = scanner.digits(4) ?: return null
        if (!scanner.skip('-')) return null
        val month = scanner.digits(2) ?: return null
        if (!scanner.skip('-')) return null
        val day = scanner.digits(2) ?: return null
        if (!(scanner.skip('T') || scanner.skip('t') || scanner.skip(' '))) return null
        val hour = scanner.digits(2) ?: return null
        if (!scanner.skip(':')) return null
        val minute = scanner.digits(2) ?: return null
        if (!scanner.skip(':')) return null
        val second = scanner.digits(2) ?: return null

        var nanos = 0L
        if (scanner.skip('.')) {
            var scale = 100_000_000L
            var count = 0
            while (true) {
                val digit = scanner.digit() ?: break
                nanos += digit * scale
                scale /= 10
                count += 1
            }
            if (count == 0) return null
        }

        val offsetSeconds: Int
        if (scanner.skip('Z') || scanner.skip('z')) {
            offsetSeconds = 0
        } else {
            val sign = scanner.sign() ?: return null
            val offsetHours = scanner.digits(2) ?: return null
            if (!scanner.skip(':')) return null
            val offsetMinutes = scanner.digits(2) ?: return null
            if (offsetHours >= 24 || offsetMinutes >= 60) return null
            offsetSeconds = sign * (offsetHours * 3600 + offsetMinutes * 60)
        }
        if (!scanner.isAtEnd || month !in 1..12 || day !in 1..daysInMonth(year, month) ||
            hour >= 24 || minute >= 60 || second >= 61
        ) return null

        val days = daysFromCivil(year.toLong(), month.toLong(), day.toLong())
        val seconds = days * 86_400 + hour * 3600 + minute * 60 + second - offsetSeconds
        return Instant.ofEpochSecond(seconds, nanos)
    }

    /** `2026-10-01T12:15:00Z`, or with milliseconds when the instant has a fractional part. */
    fun format(instant: Instant): String {
        var wholeSeconds = instant.epochSecond
        var milliseconds = (instant.nano + 500_000) / 1_000_000
        if (milliseconds == 1000) {
            wholeSeconds += 1
            milliseconds = 0
        }
        val days = Math.floorDiv(wholeSeconds, 86_400L)
        val secondsOfDay = wholeSeconds - days * 86_400
        val (year, month, day) = civilFromDays(days)
        fun pad(value: Long, width: Int) = value.toString().padStart(width, '0')
        val result = StringBuilder()
        result.append("${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T")
        result.append("${pad(secondsOfDay / 3600, 2)}:${pad(secondsOfDay % 3600 / 60, 2)}:${pad(secondsOfDay % 60, 2)}")
        if (milliseconds > 0) result.append(".${pad(milliseconds.toLong(), 3)}")
        return result.append('Z').toString()
    }

    // Howard Hinnant's civil-date algorithms (proleptic Gregorian calendar, UTC).

    private fun daysFromCivil(year: Long, month: Long, day: Long): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400L)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    private fun civilFromDays(days: Long): Triple<Long, Long, Long> {
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        return Triple(yearOfEra + era * 400 + (if (month <= 2) 1 else 0), month, day)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private class Scanner(private val chars: String) {
        private var index = 0

        val isAtEnd: Boolean get() = index == chars.length

        fun skip(character: Char): Boolean {
            if (index >= chars.length || chars[index] != character) return false
            index += 1
            return true
        }

        fun digit(): Int? {
            if (index >= chars.length || chars[index] !in '0'..'9') return null
            return chars[index++] - '0'
        }

        fun digits(count: Int): Int? {
            var value = 0
            repeat(count) { value = value * 10 + (digit() ?: return null) }
            return value
        }

        fun sign(): Int? = when {
            skip('+') -> 1
            skip('-') -> -1
            else -> null
        }
    }
}
