package com.getinsiteview.api

import com.getinsiteview.core.APIJSON
import com.getinsiteview.core.AnalyticsEvent
import com.getinsiteview.core.JSONValue
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Where the analytics queue persists between launches (iOS: a JSON file in Application Support).
 * The app passes a [FileAnalyticsQueueStorage] in `filesDir`; `null` keeps the queue in memory.
 */
interface AnalyticsQueueStorage {
    fun read(): String?

    fun write(contents: String)

    fun delete()
}

/** [AnalyticsQueueStorage] in a file, written atomically (a temporary file renamed over it). */
class FileAnalyticsQueueStorage(val file: File) : AnalyticsQueueStorage {
    override fun read(): String? = try {
        if (file.exists()) file.readText() else null
    } catch (_: IOException) {
        null
    }

    override fun write(contents: String) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(contents)
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        } catch (_: IOException) {
        }
    }

    override fun delete() {
        file.delete()
    }
}

/**
 * The analytics batch uploader (IOS-M2-09, docs/PLAN.md §3 "Analytics"): events are queued in
 * memory and a small file, and sent to `POST /v1/events` every 30 s and when the app goes to the
 * background (the caller calls [flush] then).
 *
 * - At most [AnalyticsEvent.MAX_PER_CALL] events per call, and [MAX_CALLS_PER_MINUTE] calls per
 *   audience (the API allows 10 per minute per visit).
 * - Kept for later: no connection, a 5xx, `rate_limited` (until its `Retry-After`).
 * - Dropped: anything else the API rejects (an expired visit, a validation error), events of a
 *   visit whose token has expired, and the oldest events past [MAX_QUEUED_EVENTS].
 *
 * @param api the shared client (device id and bearer interceptors).
 * @param storage where the queue persists between launches; `null` keeps it in memory.
 */
class AnalyticsUploader(
    private val api: ApiClient,
    private val storage: AnalyticsQueueStorage?,
    private val now: () -> Instant = Instant::now,
) {
    private class Queue(val audience: AnalyticsAudience, val events: MutableList<AnalyticsEvent>)

    @Serializable
    private data class StoredQueue(val audience: AnalyticsAudience, val events: List<AnalyticsEvent>)

    private val lock = Any()
    private val queues: MutableList<Queue> = read(storage)

    /** Call times per audience, for the per-minute limit. */
    private val calls = HashMap<AnalyticsAudience, MutableList<Instant>>()

    /** No calls for this audience before this time (`rate_limited`). */
    private val blockedUntil = HashMap<AnalyticsAudience, Instant>()
    private var flushing: CompletableDeferred<Unit>? = null
    private var timer: Job? = null

    /** Events waiting to be sent, all audiences. */
    val pendingCount: Int get() = synchronized(lock) { queues.sumOf { it.events.size } }

    fun pending(audience: AnalyticsAudience): List<AnalyticsEvent> =
        synchronized(lock) { queues.firstOrNull { it.audience == audience }?.events?.toList() ?: emptyList() }

    /** Queues an event. Props bigger than the API accepts are dropped from the event. */
    fun track(event: AnalyticsEvent, audience: AnalyticsAudience) {
        var tracked = event
        val size = try {
            APIJSON.encode(MapSerializer(String.serializer(), JSONValue.serializer()), event.props).encodeToByteArray().size
        } catch (_: IllegalArgumentException) {
            0
        }
        if (size > AnalyticsEvent.MAX_PROPS_BYTES) tracked = event.copy(props = emptyMap())
        synchronized(lock) {
            val queue = queues.firstOrNull { it.audience == audience }
            if (queue != null) {
                queue.events.add(tracked)
                val overflow = queue.events.size - MAX_QUEUED_EVENTS
                if (overflow > 0) queue.events.subList(0, overflow).clear()
            } else {
                queues.add(Queue(audience, mutableListOf(tracked)))
            }
            write()
        }
    }

    /** Sends what's queued, as far as the limits allow. Concurrent calls share one flush. */
    suspend fun flush() {
        var owner = false
        val deferred = synchronized(lock) {
            flushing ?: CompletableDeferred<Unit>().also {
                flushing = it
                owner = true
            }
        }
        if (!owner) {
            deferred.await()
            return
        }
        try {
            send()
        } finally {
            synchronized(lock) { flushing = null }
            deferred.complete(Unit)
        }
    }

    /** Flushes every [interval] in [scope] until [stop]. */
    fun start(scope: CoroutineScope, interval: Duration = FLUSH_INTERVAL) {
        synchronized(lock) {
            if (timer != null) return
            timer = scope.launch {
                while (isActive) {
                    delay(interval)
                    flush()
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            timer?.cancel()
            timer = null
        }
    }

    // Sending

    private suspend fun send() {
        val date = now()
        val audiences = synchronized(lock) {
            // Visit tokens that expired can't send anything any more.
            queues.removeAll { queue -> (queue.audience as? AnalyticsAudience.Visit)?.let { !it.expiresAt.isAfter(date) } ?: false }
            queues.map { it.audience }
        }
        try {
            for (audience in audiences) send(audience)
        } finally {
            synchronized(lock) {
                queues.removeAll { it.events.isEmpty() }
                write()
            }
        }
    }

    private suspend fun send(audience: AnalyticsAudience) {
        while (true) {
            val batch = synchronized(lock) {
                val batch = nextBatch(audience)
                if (batch == null || !allowsCall(audience)) return
                calls.getOrPut(audience) { mutableListOf() }.add(now())
                batch
            }
            try {
                post(batch, audience)
                remove(batch.size, audience)
            } catch (error: ApiError) {
                if (error.code == ApiError.Code.RATE_LIMITED || error.status == 429) {
                    synchronized(lock) { blockedUntil[audience] = now().plusSeconds((error.retryAfter ?: 60).toLong()) }
                    return
                }
                if (error.status >= 500 || error.status == 0) return // try again next flush
                // Rejected for good (expired visit, validation, not found): drop the batch.
                remove(batch.size, audience)
            } catch (error: CancellationException) {
                throw error
            } catch (_: UnexpectedResponse) {
                // A request that can't be built (bad props) or a 2xx we can't read: sending it
                // again would fail again or count twice.
                remove(batch.size, audience)
            } catch (_: Exception) {
                return // offline or a transport error: keep the events
            }
        }
    }

    private suspend fun post(events: List<AnalyticsEvent>, audience: AnalyticsAudience) {
        when (audience) {
            is AnalyticsAudience.Visit -> {
                val store = VisitTokenStore()
                store.set(audience.token, audience.expiresAt)
                api.withVisitToken(store).sendEvents(events)
            }
            is AnalyticsAudience.Member -> api.sendEvents(events, buildingId = audience.buildingId)
        }
    }

    private fun nextBatch(audience: AnalyticsAudience): List<AnalyticsEvent>? {
        val events = queues.firstOrNull { it.audience == audience }?.events
        if (events.isNullOrEmpty()) return null
        return events.take(AnalyticsEvent.MAX_PER_CALL)
    }

    private fun remove(count: Int, audience: AnalyticsAudience) {
        synchronized(lock) {
            val queue = queues.firstOrNull { it.audience == audience } ?: return
            queue.events.subList(0, minOf(count, queue.events.size)).clear()
        }
    }

    private fun allowsCall(audience: AnalyticsAudience): Boolean {
        val date = now()
        val blocked = blockedUntil[audience]
        if (blocked != null && blocked.isAfter(date)) return false
        blockedUntil.remove(audience)
        val recent = (calls[audience] ?: mutableListOf()).filter { java.time.Duration.between(it, date).toNanos() < 60_000_000_000 }
        calls[audience] = recent.toMutableList()
        return recent.size < MAX_CALLS_PER_MINUTE
    }

    // Storage

    private fun write() {
        val storage = storage ?: return
        if (queues.all { it.events.isEmpty() }) {
            storage.delete()
            return
        }
        val stored = queues.map { StoredQueue(it.audience, it.events.toList()) }
        storage.write(APIJSON.encode(ListSerializer(StoredQueue.serializer()), stored))
    }

    companion object {
        val FLUSH_INTERVAL: Duration = 30.seconds
        const val MAX_CALLS_PER_MINUTE = 10

        /** Per audience, so a long offline session can't grow the file without bound. */
        const val MAX_QUEUED_EVENTS = 1000

        private fun read(storage: AnalyticsQueueStorage?): MutableList<Queue> {
            val contents = storage?.read() ?: return mutableListOf()
            return try {
                APIJSON.json.decodeFromString(ListSerializer(StoredQueue.serializer()), contents)
                    .map { Queue(it.audience, it.events.toMutableList()) }
                    .toMutableList()
            } catch (_: IllegalArgumentException) {
                mutableListOf()
            }
        }
    }
}
