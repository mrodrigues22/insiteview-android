package com.getinsiteview.core

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** ISO 8601 timestamps from the API. */
class ISO8601TimestampTest {
    /** 2026-10-01T12:15:00Z */
    private val reference = Instant.ofEpochSecond(1_790_856_900)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "2026-10-01T12:15:00Z",
            "2026-10-01T12:15:00+00:00",
            "2026-10-01T12:15:00.0000000+00:00",
            "2026-10-01T09:15:00-03:00",
            "2026-10-01T14:15:00+02:00",
            "2026-10-01t12:15:00z",
        ],
    )
    fun `Parses NET DateTimeOffset output and the Z form`(string: String) {
        assertEquals(reference, assertNotNull(ISO8601Timestamp.parse(string)))
    }

    @Test
    fun `Keeps up to 7 fractional digits`() {
        val date = assertNotNull(ISO8601Timestamp.parse("2026-10-01T12:15:00.1234567+00:00"))
        val seconds = Duration.between(reference, date).toNanos() / 1e9
        assertTrue(abs(seconds - 0.1234567) < 1e-6)
        assertEquals(Duration.ofNanos(123_456_700), Duration.between(reference, date))
        val millis = assertNotNull(ISO8601Timestamp.parse("2026-10-01T12:15:00.5Z"))
        assertEquals(Duration.ofMillis(500), Duration.between(reference, millis))
    }

    @Test
    fun `Handles leap days and the epoch`() {
        assertEquals(Instant.ofEpochSecond(0), ISO8601Timestamp.parse("1970-01-01T00:00:00Z"))
        assertEquals(Instant.ofEpochSecond(1_835_395_200), ISO8601Timestamp.parse("2028-02-29T00:00:00Z"))
        assertEquals(Instant.ofEpochSecond(-1), ISO8601Timestamp.parse("1969-12-31T23:59:59Z"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "2026-10-01", "2026-10-01T12:15:00", "2026-10-01T12:15Z", "2026-13-01T00:00:00Z",
            "2026-02-29T00:00:00Z", "2026-10-32T00:00:00Z", "2026-10-01T24:00:00Z", "2026-10-01T12:15:00.Z",
            "2026-10-01T12:15:00+0000", "2026-10-01T12:15:00Z junk", "26-10-01T12:15:00Z",
        ],
    )
    fun `Rejects malformed timestamps`(string: String) {
        assertNull(ISO8601Timestamp.parse(string))
    }

    @Test
    fun `Formats in UTC and round-trips`() {
        assertEquals("2026-10-01T12:15:00Z", ISO8601Timestamp.format(reference))
        assertEquals("2026-10-01T12:15:00.250Z", ISO8601Timestamp.format(reference.plusMillis(250)))
        assertEquals("1969-12-31T23:59:59Z", ISO8601Timestamp.format(Instant.ofEpochSecond(-1)))
        val date = Instant.ofEpochSecond(1_835_395_200, 125_000_000)
        assertEquals(date, ISO8601Timestamp.parse(ISO8601Timestamp.format(date)))
    }

    @Serializable
    private data class Payload(@Contextual val at: Instant)

    @Test
    fun `APIJSON decodes and encodes dates`() {
        val decoded = APIJSON.decode<Payload>("""{"at":"2026-10-01T12:15:00+00:00"}""")
        assertEquals(reference, decoded.at)
        assertEquals("""{"at":"2026-10-01T12:15:00Z"}""", APIJSON.encode(decoded))
        assertFailsWith<SerializationException> { APIJSON.decode<Payload>("""{"at":"yesterday"}""") }
    }
}
