package com.getinsiteview.modelkit

import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Chunk plan")
class ChunkPlanTest {
    @Test
    fun `Architecture first, then every meta, then models in priority order`() {
        val manifest = Fixtures.manifest()
        // Android's default kinds: meta and GLB.
        val ids = ChunkPlan.requests(manifest).map { it.id }
        assertEquals(
            listOf(
                "architecture.meta", "architecture.glb",
                "electrical.meta", "plumbing.meta",
                "electrical.glb", "plumbing.glb",
            ),
            ids,
        )
        val plumbingFirst = ChunkPlan.requests(manifest, kinds = Fixtures.iosKinds, priority = listOf("plumbing")).map { it.id }
        assertEquals(
            listOf(
                "architecture.meta", "architecture.usdz",
                "plumbing.meta", "electrical.meta",
                "plumbing.usdz", "electrical.usdz",
            ),
            plumbingFirst,
        )
    }

    @Test
    fun `Only the systems asked for, and only the kinds asked for`() {
        val manifest = Fixtures.manifest()
        assertEquals(
            listOf("architecture.meta", "architecture.glb", "plumbing.meta", "plumbing.glb"),
            ChunkPlan.requests(manifest, systems = setOf("architecture", "plumbing")).map { it.id },
        )
        assertEquals(
            listOf("architecture.meta", "electrical.meta", "plumbing.meta"),
            ChunkPlan.requests(manifest, kinds = listOf(ChunkFileKind.META)).map { it.id },
        )
        assertEquals(
            listOf("electrical.usdz"),
            ChunkPlan.requests(manifest, kinds = listOf(ChunkFileKind.USDZ), systems = setOf("electrical")).map { it.id },
        )
    }
}

@DisplayName("Chunk cache")
class ChunkCacheTest {
    /** A clock that moves one second per call. */
    private class Clock {
        private var seconds = 1_000_000L

        @Synchronized
        fun now(): Instant {
            seconds += 1
            return Instant.ofEpochSecond(seconds)
        }
    }

    @Test
    @DisplayName("Stores by SHA-256 with the file's extension; hits touch the file")
    fun `Stores by SHA-256 with the file's extension, hits touch the file`() = runTest {
        val cache = ChunkCache(Fixtures.temporaryDirectory())
        val data = "hello".encodeToByteArray()
        val sha = Checksum.sha256Hex(data)
        assertNull(cache.cachedFile(sha, ChunkFileKind.USDZ))
        val url = cache.store(data, sha, ChunkFileKind.USDZ)
        assertEquals("$sha.usdz", url.name)
        assertTrue(url.readBytes().contentEquals(data))
        assertEquals(url, cache.cachedFile(sha.uppercase(), ChunkFileKind.USDZ))
        assertNull(cache.cachedFile(sha, ChunkFileKind.META))
        assertEquals(5L, cache.totalSize())
        // Android's model files keep `.glb`.
        assertEquals("$sha.glb", cache.store(data, sha, ChunkFileKind.GLB).name)
    }

    @Test
    fun `Evicts least recently used files above the capacity, never pinned ones`() = runTest {
        val clock = Clock()
        val cache = ChunkCache(Fixtures.temporaryDirectory(), capacity = 30, now = clock::now)
        fun blob(name: String): Pair<ByteArray, String> {
            val data = ByteArray(10) { name[0].code.toByte() }
            return data to Checksum.sha256Hex(data)
        }
        val (a, shaA) = blob("a")
        val (b, shaB) = blob("b")
        val (c, shaC) = blob("c")
        val (d, shaD) = blob("d")
        cache.store(a, shaA, ChunkFileKind.USDZ)
        cache.store(b, shaB, ChunkFileKind.USDZ)
        cache.store(c, shaC, ChunkFileKind.USDZ)
        // Using A makes B the least recently used.
        assertNotNull(cache.cachedFile(shaA, ChunkFileKind.USDZ))
        cache.store(d, shaD, ChunkFileKind.USDZ)
        assertNull(cache.cachedFile(shaB, ChunkFileKind.USDZ))
        assertEquals(30L, cache.totalSize())

        // C is now the oldest, but pinned: A goes instead... unless A is pinned too.
        val (e, shaE) = blob("e")
        cache.store(e, shaE, ChunkFileKind.USDZ, pinned = setOf(shaC))
        assertNotNull(cache.cachedFile(shaC, ChunkFileKind.USDZ))
        assertNotNull(cache.cachedFile(shaE, ChunkFileKind.USDZ))
        assertEquals(30L, cache.totalSize())
    }

    @Test
    fun `The file just stored is never evicted, even alone above the capacity`() = runTest {
        val cache = ChunkCache(Fixtures.temporaryDirectory(), capacity = 4)
        val data = "too big".encodeToByteArray()
        val url = cache.store(data, Checksum.sha256Hex(data), ChunkFileKind.META)
        assertTrue(url.exists())
    }
}

@DisplayName("Chunk downloader")
class ChunkDownloaderTest {
    private data class Collected(
        val loaded: List<LoadedChunkFile>,
        val failed: List<Pair<ChunkRequest, ChunkLoadError>>,
        val progress: List<LoadProgress>,
    )

    private suspend fun collect(flow: Flow<ChunkEvent>): Collected {
        val loaded = ArrayList<LoadedChunkFile>()
        val failed = ArrayList<Pair<ChunkRequest, ChunkLoadError>>()
        val progress = ArrayList<LoadProgress>()
        flow.collect { event ->
            when (event) {
                is ChunkEvent.Loaded -> loaded += event.file
                is ChunkEvent.Failed -> failed += event.request to event.error
                is ChunkEvent.Progress -> progress += event.progress
            }
        }
        return Collected(loaded, failed, progress)
    }

    /** A downloader on the test's virtual clock. */
    private fun TestScope.downloader(transport: ChunkTransport, now: (() -> Instant)? = null): ChunkDownloader =
        ChunkDownloader(
            transport = transport,
            cache = ChunkCache(Fixtures.temporaryDirectory()),
            now = now ?: { Instant.now() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun `Downloads every file, verifies it, reports byte progress and caches it`() = runTest {
        val manifest = Fixtures.manifest()
        val transport = FixtureTransport()
        val downloader = downloader(transport)
        val requests = ChunkPlan.requests(manifest, kinds = Fixtures.iosKinds)

        val result = collect(downloader.load(requests))
        assertTrue(result.failed.isEmpty())
        assertEquals(requests.map { it.id }.toSet(), result.loaded.map { it.request.id }.toSet())
        assertTrue(result.loaded.all { !it.fromCache })
        for (file in result.loaded) {
            assertEquals(file.request.file.sha256, Checksum.sha256Hex(file.url.readBytes()))
            assertEquals(file.request.kind.fileExtension, file.url.extension)
        }
        // Progress is monotonic and ends at the manifest's total bytes.
        val total = requests.sumOf { it.file.bytes }
        assertEquals(LoadProgress(completedBytes = total, totalBytes = total, completedFiles = 6, totalFiles = 6), result.progress.last())
        assertTrue(result.progress.zipWithNext().all { (a, b) -> a.completedBytes <= b.completedBytes })
        assertTrue(transport.peak <= 3)
        assertEquals(6, transport.callCount)

        // A second load (a refreshed manifest, same hashes) comes from the cache.
        val again = collect(downloader.load(requests))
        assertTrue(again.loaded.size == 6 && again.loaded.all { it.fromCache })
        assertEquals(6, transport.callCount)
        assertEquals(1.0, again.progress.lastOrNull()?.fraction)
    }

    @Test
    fun `Starts files in plan order, three at a time`() = runTest {
        val manifest = Fixtures.manifest()
        val transport = FixtureTransport(delay = 20.milliseconds)
        collect(downloader(transport).load(ChunkPlan.requests(manifest, kinds = Fixtures.iosKinds)))
        val started = transport.calls.map { it.lastPathComponent }
        assertEquals(listOf("architecture.meta.json", "architecture.usdz", "electrical.meta.json"), started.take(3).sorted())
        assertEquals("plumbing.usdz", started.last())
    }

    @Test
    fun `A bad checksum is retried once, then only that file fails`() = runTest {
        val manifest = Fixtures.manifest()
        val corrupt = "not the file".encodeToByteArray()
        val transport = FixtureTransport(overrides = mapOf("electrical.usdz" to listOf(Result.success(corrupt), Result.success(corrupt))))
        val result = collect(downloader(transport).load(ChunkPlan.requests(manifest, kinds = Fixtures.iosKinds)))
        assertEquals(5, result.loaded.size)
        val failure = assertNotNull(result.failed.firstOrNull())
        assertEquals("electrical.usdz", failure.first.id)
        assertEquals(
            ChunkLoadError.ChecksumMismatch(
                expected = "2b639f4e6c1095afe222db9f95dcfa5b289eda93acf5c9add069cfb80f8d594b",
                actual = Checksum.sha256Hex(corrupt),
            ),
            failure.second,
        )
        assertEquals(5, result.progress.last().completedFiles)

        // One bad response, then the right file: loads.
        val flaky = FixtureTransport(overrides = mapOf("electrical.usdz" to listOf(Result.success(corrupt))))
        val retried = collect(downloader(flaky).load(ChunkPlan.requests(manifest, kinds = Fixtures.iosKinds)))
        assertTrue(retried.failed.isEmpty() && retried.loaded.size == 6)
    }

    @Test
    fun `Gzip bytes that the transport didn't decode are reported as such`() = runTest {
        val manifest = Fixtures.manifest()
        val gzip = Fixtures.data("test-room/electrical.usdz.gz")
        val transport = FixtureTransport(overrides = mapOf("electrical.usdz" to listOf(Result.success(gzip), Result.success(gzip))))
        val chunk = assertNotNull(manifest.chunk("electrical"))
        val result = collect(downloader(transport).load(listOf(ChunkRequest(chunk, ChunkFileKind.USDZ))))
        assertEquals(listOf<ChunkLoadError>(ChunkLoadError.NotDecoded), result.failed.map { it.second })
    }

    @Test
    fun `Expired URLs are re-signed through a fresh manifest`() = runTest {
        val manifest = Fixtures.manifest()
        // Storage only accepts the new signature; the fixture manifest has the old one.
        val transport = FixtureTransport(validSignature = "fresh")
        fun resign(file: Manifest.File) =
            file.copy(url = URI(file.url.toString().replace("Signature=abc", "Signature=fresh")))
        val fresh = manifest.copy(
            urlsExpireAt = manifest.urlsExpireAt.plusSeconds(900),
            chunks = manifest.chunks.map { it.copy(meta = resign(it.meta), usdz = resign(it.usdz), glb = resign(it.glb)) },
        )
        val refreshes = AtomicInteger(0)
        val downloader = downloader(transport, now = { manifest.urlsExpireAt.minusSeconds(600) })
        val result = collect(
            downloader.load(
                ChunkPlan.requests(manifest, kinds = Fixtures.iosKinds),
                urlsExpireAt = manifest.urlsExpireAt,
                refreshManifest = {
                    refreshes.incrementAndGet()
                    fresh
                },
            ),
        )
        assertTrue(result.failed.isEmpty())
        assertEquals(6, result.loaded.size)
        // The first downloads got 403 and shared one refresh.
        assertEquals(1, refreshes.get())

        // Past the expiry, URLs are re-signed before downloading at all.
        val early = FixtureTransport(validSignature = "fresh")
        val late = downloader(early, now = { manifest.urlsExpireAt.plusSeconds(10) })
        val lateResult = collect(
            late.load(ChunkPlan.requests(manifest, kinds = Fixtures.iosKinds), urlsExpireAt = manifest.urlsExpireAt, refreshManifest = { fresh }),
        )
        assertEquals(6, lateResult.loaded.size)
        assertEquals(6, early.callCount)
    }

    @Test
    @DisplayName("Without a refresher a 403 fails the file as expired; offline is reported as offline")
    fun `Without a refresher a 403 fails the file as expired, offline is reported as offline`() = runTest {
        val manifest = Fixtures.manifest()
        val chunk = assertNotNull(manifest.chunk("plumbing"))
        val transport = FixtureTransport(validSignature = "fresh")
        val result = collect(downloader(transport).load(listOf(ChunkRequest(chunk, ChunkFileKind.META))))
        assertEquals(listOf<ChunkLoadError>(ChunkLoadError.Expired), result.failed.map { it.second })
        assertEquals(ChunkLoadError.Offline, ChunkDownloader.loadError(UnknownHostException("storage.test")))
        assertNull(ChunkDownloader.loadError(IOException("Canceled")))
        assertNull(ChunkDownloader.loadError(CancellationException()))
    }

    @Test
    fun `ModelLoadState builds the index from meta and lists ready models`() = runTest {
        val manifest = Fixtures.manifest()
        val state = ModelLoadState()
        val ready = ArrayList<String>()
        downloader(FixtureTransport()).load(ChunkPlan.requests(manifest, kinds = Fixtures.iosKinds)).collect { event ->
            val change = state.apply(event)
            if (change is ModelLoadState.Change.ModelReady) ready += change.file.request.chunk
        }
        assertEquals(12, state.index.count)
        assertEquals(setOf("architecture", "electrical", "plumbing"), ready.toSet())
        assertEquals("usdz", state.models["architecture"]?.url?.extension)
        assertTrue(state.failures.isEmpty())
        assertTrue(state.progress.isComplete)

        val request = ChunkRequest(assertNotNull(manifest.chunk("plumbing")), ChunkFileKind.META)
        state.apply(ChunkEvent.Failed(request, ChunkLoadError.Offline))
        assertEquals(setOf("plumbing"), state.failedChunks)
    }
}
