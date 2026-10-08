package com.getinsiteview.api

import com.getinsiteview.core.AnalyticsEvent
import com.getinsiteview.core.JSONValue
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Analytics uploader")
class AnalyticsUploaderTest {
    /** A clock the test moves by hand. */
    class Clock {
        @Volatile
        var now: Instant = fixtureNow
            private set

        fun advance(seconds: Double) {
            now = now.plusMillis((seconds * 1000).toLong())
        }
    }

    private val visit = AnalyticsAudience.Visit(token = "visit-1", expiresAt = fixtureNow.plusSeconds(3600))

    private fun uploader(
        storage: AnalyticsQueueStorage? = null,
        clock: Clock = Clock(),
        handler: (RoutingTransport.Sent) -> RoutingTransport.Reply,
    ): Pair<AnalyticsUploader, RoutingTransport> {
        val transport = RoutingTransport(handler)
        return AnalyticsUploader(transport.api(), storage, now = { clock.now }) to transport
    }

    private fun accepted(sent: RoutingTransport.Sent): RoutingTransport.Reply {
        val count = sent.jsonOrNull()?.get("events")?.jsonArray?.size ?: 0
        return RoutingTransport.Reply.json("""{"accepted":$count}""", status = 202)
    }

    private fun events(count: Int, clock: Clock): List<AnalyticsEvent> =
        (0 until count).map { AnalyticsEvent.systemToggled("electrical", on = it % 2 == 0, at = clock.now) }

    @Test
    fun `Batches of at most 100 events, with the visit token`() = test {
        val clock = Clock()
        val (uploader, transport) = uploader(clock = clock, handler = ::accepted)
        for (event in events(250, clock)) uploader.track(event, visit)
        uploader.flush()
        val sizes = transport.requests.map { it.json()["events"]!!.jsonArray.size }
        assertEquals(listOf(100, 100, 50), sizes)
        assertTrue(transport.requests.all { it.authorization == "Bearer visit-1" && it.operationId == "events_create" })
        assertEquals(0, uploader.pendingCount)
    }

    @Test
    fun `At most 10 calls a minute per visit, the rest waits for the next flush`() = test {
        val clock = Clock()
        val (uploader, transport) = uploader(clock = clock, handler = ::accepted)
        for (event in events(1000, clock)) uploader.track(event, visit)
        uploader.flush()
        assertEquals(10, transport.requests.size)
        uploader.track(AnalyticsEvent.arOpened(clock.now), visit)
        uploader.flush()
        assertEquals(10, transport.requests.size)
        assertEquals(1, uploader.pendingCount)
        clock.advance(61.0)
        uploader.flush()
        assertEquals(11, transport.requests.size)
        assertEquals(0, uploader.pendingCount)
    }

    @Test
    fun `Offline and 5xx keep the events, a rejected batch is dropped`() = test {
        val clock = Clock()
        val mode = AtomicReference("offline")
        val (uploader, transport) = uploader(clock = clock) { sent ->
            when (mode.get()) {
                "offline" -> throw UnknownHostException("offline")
                "down" -> RoutingTransport.Reply.problem(503, code = "internal")
                "invalid" -> RoutingTransport.Reply.problem(400, code = "validation")
                else -> accepted(sent)
            }
        }
        uploader.track(AnalyticsEvent.arOpened(clock.now), visit)
        uploader.flush()
        assertEquals(1, uploader.pendingCount)
        mode.set("down")
        uploader.flush()
        assertEquals(1, uploader.pendingCount)
        mode.set("invalid")
        uploader.flush()
        assertEquals(0, uploader.pendingCount)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun `rate_limited waits for Retry-After`() = test {
        val clock = Clock()
        val limited = AtomicBoolean(true)
        val (uploader, transport) = uploader(clock = clock) { sent ->
            if (limited.get()) {
                RoutingTransport.Reply(
                    status = 429,
                    headers = mapOf("Content-Type" to "application/problem+json", "Retry-After" to "30"),
                    body = """{"status":429,"code":"rate_limited"}""",
                )
            } else {
                accepted(sent)
            }
        }
        uploader.track(AnalyticsEvent.arOpened(clock.now), visit)
        uploader.flush()
        limited.set(false)
        uploader.flush()
        assertEquals(1, transport.requests.size)
        clock.advance(31.0)
        uploader.flush()
        assertEquals(2, transport.requests.size)
        assertEquals(0, uploader.pendingCount)
    }

    @Test
    fun `Events of an expired visit are dropped without a call`() = test {
        val clock = Clock()
        val (uploader, transport) = uploader(clock = clock, handler = ::accepted)
        uploader.track(AnalyticsEvent.arOpened(clock.now), visit)
        clock.advance(3601.0)
        uploader.flush()
        assertTrue(transport.requests.isEmpty())
        assertEquals(0, uploader.pendingCount)
    }

    @Test
    fun `The queue survives a relaunch, an empty queue leaves no file`() = test {
        val directory = Files.createTempDirectory("iv-analytics").toFile()
        try {
            val file = File(directory, "queue.json")
            val clock = Clock()
            val (first, _) = uploader(storage = FileAnalyticsQueueStorage(file), clock = clock) { throw UnknownHostException("offline") }
            first.track(AnalyticsEvent.arAligned(AnalyticsEvent.AlignmentMethod.MANUAL, 12.5.seconds, clock.now), visit)
            first.flush()
            assertTrue(file.exists())

            val (second, transport) = uploader(storage = FileAnalyticsQueueStorage(file), clock = clock, handler = ::accepted)
            assertEquals(
                listOf(AnalyticsEvent.arAligned(AnalyticsEvent.AlignmentMethod.MANUAL, 12.5.seconds, clock.now)),
                second.pending(visit),
            )
            second.flush()
            assertEquals(1, transport.requests.size)
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Props over the API's size limit are dropped, the queue keeps the newest events`() = test {
        val clock = Clock()
        val (uploader, _) = uploader(clock = clock, handler = ::accepted)
        val big = AnalyticsEvent(
            AnalyticsEvent.EventType.OBJECT_OPENED, mapOf("kind" to JSONValue.String("x".repeat(3000))), clock.now,
        )
        uploader.track(big, visit)
        assertEquals(true, uploader.pending(visit).firstOrNull()?.props?.isEmpty())

        for (event in events(AnalyticsUploader.MAX_QUEUED_EVENTS + 5, clock)) uploader.track(event, visit)
        val pending = uploader.pending(visit)
        assertEquals(AnalyticsUploader.MAX_QUEUED_EVENTS, pending.size)
        assertEquals(AnalyticsEvent.EventType.SYSTEM_TOGGLED, pending.firstOrNull()?.type)
    }
}
