package com.getinsiteview.modelkit

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One file to fetch: a chunk's meta, GLB or USDZ. */
data class ChunkRequest(
    val chunk: String,
    val system: String,
    val kind: ChunkFileKind,
    val file: Manifest.File,
) {
    constructor(chunk: Manifest.Chunk, kind: ChunkFileKind) :
        this(chunk = chunk.key, system = chunk.system, kind = kind, file = chunk.file(kind))

    /** `electrical.glb`: stable across manifest refreshes (URLs change, keys don't). */
    val id: String get() = "$chunk.${kind.raw}"
}

/**
 * The order files are fetched in (docs/PLAN.md §3 "Loading a building"): the architecture chunk
 * first, then every other chunk's meta (small; the object card and equipment list need it), then
 * the other files, systems in `priority` order first, then the rest in manifest order.
 */
object ChunkPlan {
    const val architecture = "architecture"

    /** What Android loads: meta and GLB (iOS: meta and USDZ). */
    val defaultKinds: List<ChunkFileKind> = listOf(ChunkFileKind.META, ChunkFileKind.GLB)

    fun requests(
        manifest: Manifest,
        kinds: List<ChunkFileKind> = defaultKinds,
        priority: List<String> = emptyList(),
        systems: Set<String>? = null,
    ): List<ChunkRequest> {
        val chunks = manifest.chunks.filter { systems?.contains(it.system) ?: true }
        val first = chunks.filter { it.system == architecture }
        val rest = chunks.filter { it.system != architecture }
        val rank = HashMap<String, Int>()
        priority.forEachIndexed { offset, system -> rank.putIfAbsent(system, offset) }
        // `sortedBy` is stable: equal ranks keep manifest order.
        val ordered = rest.sortedBy { rank[it.system] ?: Int.MAX_VALUE }

        val requests = first.flatMap { chunk -> kinds.map { ChunkRequest(chunk, it) } }.toMutableList()
        if (ChunkFileKind.META in kinds) {
            requests += ordered.map { ChunkRequest(it, ChunkFileKind.META) }
        }
        for (chunk in ordered) {
            requests += kinds.filter { it != ChunkFileKind.META }.map { ChunkRequest(chunk, it) }
        }
        return requests
    }
}

/**
 * Byte-based progress over every requested file (the manifest's `bytes`, not the smaller gzip
 * transfer size).
 */
data class LoadProgress(
    val completedBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int,
    val totalFiles: Int,
) {
    /** 0…1; 1 when there is nothing to load. */
    val fraction: Double
        get() = if (totalBytes > 0) {
            minOf(1.0, completedBytes.toDouble() / totalBytes.toDouble())
        } else if (completedFiles >= totalFiles) 1.0 else 0.0

    val isComplete: Boolean get() = completedFiles >= totalFiles
}

sealed interface ChunkLoadError {
    /** The file's SHA-256 doesn't match the manifest. */
    data class ChecksumMismatch(val expected: String, val actual: String) : ChunkLoadError

    /**
     * The bytes are still gzip: storage served the file without `Content-Encoding: gzip` (or the
     * request asked for gzip itself), so nothing decoded it.
     */
    data object NotDecoded : ChunkLoadError

    /** No connection. */
    data object Offline : ChunkLoadError

    /** The presigned URL expired and a fresh manifest didn't help. */
    data object Expired : ChunkLoadError

    data class Transport(val message: String) : ChunkLoadError

    /** Writing to the cache failed (e.g. a full disk). */
    data class Storage(val message: String) : ChunkLoadError
}

data class LoadedChunkFile(
    val request: ChunkRequest,
    /** The verified file in the [ChunkCache]. */
    val url: File,
    val fromCache: Boolean,
)

sealed interface ChunkEvent {
    data class Progress(val progress: LoadProgress) : ChunkEvent

    data class Loaded(val file: LoadedChunkFile) : ChunkEvent

    /**
     * The file couldn't be loaded after retrying; other files carry on (a missing chunk never
     * takes a screen down).
     */
    data class Failed(val request: ChunkRequest, val error: ChunkLoadError) : ChunkEvent
}

/**
 * Fetches chunk files in plan order, a few at a time, verifying each file's SHA-256 and serving
 * repeats from the [ChunkCache] (IOS-M1-03).
 *
 * @param attempts tries per file (a checksum mismatch or a network error is retried once by default).
 * @param dispatcher where downloads, hashing and cache writes run.
 */
class ChunkDownloader(
    val transport: ChunkTransport,
    val cache: ChunkCache,
    maxConcurrentDownloads: Int = 3,
    attempts: Int = 2,
    private val now: () -> Instant = { Instant.now() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val maxConcurrentDownloads: Int = maxOf(1, maxConcurrentDownloads)
    val attempts: Int = maxOf(1, attempts)

    /**
     * Loads `requests` (e.g. from [ChunkPlan]). Cache hits are reported first, then downloads
     * finish in roughly plan order. The flow completes when every file is loaded or failed;
     * cancelling the collector cancels the downloads.
     *
     * @param urlsExpireAt the manifest's expiry; with `refreshManifest`, URLs are re-signed before
     *   a download that would start after it, and after a 403.
     * @param pinned SHA-256s the cache must keep while this building is open.
     */
    fun load(
        requests: List<ChunkRequest>,
        urlsExpireAt: Instant? = null,
        refreshManifest: (suspend () -> Manifest)? = null,
        pinned: Set<String> = emptySet(),
    ): Flow<ChunkEvent> = channelFlow {
        val urls = URLBook(urlsExpireAt, refreshManifest, now)
        run(requests, urls, pinned + requests.map { it.file.sha256 })
    }.buffer(Channel.UNLIMITED).flowOn(dispatcher)

    private suspend fun ProducerScope<ChunkEvent>.run(requests: List<ChunkRequest>, urls: URLBook, pinned: Set<String>) {
        val progress = ProgressCounter(requests, this)
        progress.report()

        val pending = ArrayList<ChunkRequest>()
        for (request in requests) {
            val file = cache.cachedFile(request.file.sha256, request.kind)
            if (file != null) {
                trySend(ChunkEvent.Loaded(LoadedChunkFile(request, file, fromCache = true)))
                progress.complete(request)
            } else {
                pending += request
            }
        }

        // `maxConcurrentDownloads` workers take the next pending file in plan order.
        val queue = pending.iterator()
        val queueLock = Any()
        fun next(): ChunkRequest? = synchronized(queueLock) { if (queue.hasNext()) queue.next() else null }
        repeat(maxConcurrentDownloads) {
            launch {
                while (currentCoroutineContext().isActive) {
                    val request = next() ?: break
                    fetch(request, urls, pinned, progress)
                }
            }
        }
    }

    private suspend fun ProducerScope<ChunkEvent>.fetch(
        request: ChunkRequest,
        urls: URLBook,
        pinned: Set<String>,
        progress: ProgressCounter,
    ) {
        var failure: ChunkLoadError = ChunkLoadError.Transport("not attempted")
        attempts@ for (attempt in 0 until attempts) {
            if (!currentCoroutineContext().isActive) return
            progress.reset(request)
            val url = try {
                urls.url(request)
            } catch (e: Throwable) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                failure = loadError(e) ?: ChunkLoadError.Expired
                continue
            }
            val data = try {
                transport.download(url) { count -> progress.update(request, count) }
            } catch (e: ChunkTransportError.HttpStatus) {
                if (e.status != 403) {
                    failure = loadError(e) ?: return
                    continue
                }
                failure = ChunkLoadError.Expired
                urls.expire(url)
                continue
            } catch (e: Throwable) {
                failure = loadError(e) ?: return // cancelled
                continue
            }

            val actual = Checksum.sha256Hex(data)
            if (actual != request.file.sha256.lowercase()) {
                failure = if (data.size >= 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) {
                    ChunkLoadError.NotDecoded
                } else {
                    ChunkLoadError.ChecksumMismatch(expected = request.file.sha256, actual = actual)
                }
                continue
            }
            try {
                val file = cache.store(data, actual, request.kind, pinned)
                trySend(ChunkEvent.Loaded(LoadedChunkFile(request, file, fromCache = false)))
                progress.complete(request)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = ChunkLoadError.Storage(e.toString())
                break@attempts
            }
        }
        progress.reset(request)
        trySend(ChunkEvent.Failed(request, failure))
        progress.report()
    }

    companion object {
        /** `null` for cancellation, which isn't a failure. */
        fun loadError(error: Throwable): ChunkLoadError? = when (error) {
            is CancellationException -> null
            is ChunkTransportError.HttpStatus ->
                if (error.status == 403) ChunkLoadError.Expired else ChunkLoadError.Transport(error.toString())
            is ChunkTransportError -> ChunkLoadError.Transport(error.toString())
            // No DNS, no route, refused or dropped connections: what URLError's
            // notConnectedToInternet / networkConnectionLost cover on iOS.
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketException ->
                ChunkLoadError.Offline
            // OkHttp's `Call.cancel()`.
            is IOException -> if (error.message == "Canceled") null else ChunkLoadError.Transport(error.toString())
            else -> ChunkLoadError.Transport(error.toString())
        }
    }
}

/** Current URLs for each request, re-signed through a fresh manifest when they expire. */
private class URLBook(
    private var expiresAt: Instant?,
    private val refresh: (suspend () -> Manifest)?,
    private val now: () -> Instant,
) {
    private val mutex = Mutex()
    private val urls = HashMap<String, URI>()
    private var refreshing: CompletableDeferred<Unit>? = null

    /** URLs are re-signed this long before they expire. */
    private val marginSeconds = 30L

    suspend fun url(request: ChunkRequest): URI {
        val expired = mutex.withLock {
            val expiresAt = expiresAt
            expiresAt != null && refresh != null && !now().plusSeconds(marginSeconds).isBefore(expiresAt)
        }
        if (expired) refreshURLs()
        return mutex.withLock { urls[request.id] ?: request.file.url }
    }

    /** Storage said 403 for `url`: re-sign, unless another download already did. */
    suspend fun expire(url: URI) {
        val stale = mutex.withLock { refresh != null && (urls.isEmpty() || url in urls.values) }
        if (!stale) return
        try {
            refreshURLs()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /** One refresh at a time; everyone waiting resumes after the new URLs are in place. */
    private suspend fun refreshURLs() {
        val refresh = refresh ?: return
        var owner: CompletableDeferred<Unit>? = null
        val waiting = mutex.withLock {
            refreshing ?: CompletableDeferred<Unit>().also {
                refreshing = it
                owner = it
            }
        }
        val mine = owner ?: return waiting.await()
        try {
            val manifest = refresh()
            mutex.withLock {
                apply(manifest)
                refreshing = null
            }
            mine.complete(Unit)
        } catch (e: Throwable) {
            mutex.withLock { refreshing = null }
            mine.completeExceptionally(e)
            throw e
        }
    }

    private fun apply(manifest: Manifest) {
        expiresAt = manifest.urlsExpireAt
        for (chunk in manifest.chunks) {
            for (kind in ChunkFileKind.entries) {
                urls[ChunkRequest(chunk, kind).id] = chunk.file(kind).url
            }
        }
    }
}

/**
 * Byte counts across concurrent downloads. Progress events are sent while holding the lock, so
 * they arrive in order; byte updates are reported at most every half percent.
 */
private class ProgressCounter(requests: List<ChunkRequest>, private val channel: ProducerScope<ChunkEvent>) {
    private val totalBytes: Long = requests.sumOf { maxOf(0L, it.file.bytes) }
    private val totalFiles: Int = requests.size
    private val sizes: Map<String, Long> = HashMap<String, Long>().also { map ->
        requests.forEach { map.putIfAbsent(it.id, maxOf(0L, it.file.bytes)) }
    }
    private val lock = Any()
    private var completedBytes = 0L
    private var completedFiles = 0
    private val inFlight = HashMap<String, Long>()
    private var lastReported = -1.0

    /** Reports the current progress. */
    fun report() = synchronized(lock) { send() }

    /** Bytes received so far for a file in flight. */
    fun update(request: ChunkRequest, received: Long) {
        val size = sizes[request.id] ?: 0L
        synchronized(lock) {
            inFlight[request.id] = minOf(maxOf(0L, received), size)
            if (totalBytes <= 0) return
            val completed = completedBytes + inFlight.values.sum()
            if (completed.toDouble() / totalBytes.toDouble() - lastReported >= 0.005) send()
        }
    }

    /** A failed attempt: its bytes no longer count. */
    fun reset(request: ChunkRequest) {
        synchronized(lock) { inFlight.remove(request.id) }
    }

    fun complete(request: ChunkRequest) {
        val size = sizes[request.id] ?: 0L
        synchronized(lock) {
            inFlight.remove(request.id)
            completedBytes += size
            completedFiles += 1
            send()
        }
    }

    private fun send() {
        val progress = LoadProgress(
            completedBytes = completedBytes + inFlight.values.sum(),
            totalBytes = totalBytes,
            completedFiles = completedFiles,
            totalFiles = totalFiles,
        )
        lastReported = progress.fraction
        channel.trySend(ChunkEvent.Progress(progress))
    }
}
