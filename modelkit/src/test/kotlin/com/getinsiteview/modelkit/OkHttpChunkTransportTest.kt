package com.getinsiteview.modelkit

import java.net.URI
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * iOS `URLSessionChunkTransportTests`: a local server that answers by path, for testing the real
 * OkHttp transport. Storage serves chunk files with `Content-Encoding: gzip`.
 */
@DisplayName("OkHttp chunk transport")
class OkHttpChunkTransportTest {
    private lateinit var server: MockWebServer
    private val transport = OkHttpChunkTransport()

    private fun serve(responses: Map<String, MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                responses[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
        }
    }

    private fun gzipResponse(fixture: String): MockResponse =
        MockResponse.Builder()
            .addHeader("Content-Encoding", "gzip")
            .addHeader("Content-Type", "application/octet-stream")
            .body(Buffer().write(Fixtures.data("test-room/$fixture.gz")))
            .build()

    private fun url(path: String): URI = server.url(path).toUri()

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun `Gzip-encoded USDZ and meta arrive decoded and pass the manifest's SHA-256`() = runBlocking {
        serve(
            mapOf(
                "/electrical.usdz" to gzipResponse("electrical.usdz"),
                "/electrical.meta.json" to gzipResponse("electrical.meta.json"),
                "/expired.usdz" to MockResponse.Builder().code(403).build(),
            ),
        )
        val manifest = Fixtures.manifest()
        val chunk = assertNotNull(manifest.chunk("electrical"))

        val counts = Collections.synchronizedList(ArrayList<Long>())
        val usdz = transport.download(url("/electrical.usdz")) { counts += it }
        assertTrue(usdz.contentEquals(Fixtures.data("test-room/electrical.usdz")))
        assertEquals(chunk.usdz.sha256, Checksum.sha256Hex(usdz))
        // Progress counts decoded bytes, matching the manifest's `bytes`.
        assertEquals(chunk.usdz.bytes, counts.last())

        val meta = transport.download(url("/electrical.meta.json")) { }
        assertEquals(chunk.meta.sha256, Checksum.sha256Hex(meta))
        assertEquals(4, ChunkMeta.decode(meta).elements.size)

        val error = assertFailsWith<ChunkTransportError.HttpStatus> { transport.download(url("/expired.usdz")) { } }
        assertEquals(ChunkTransportError.HttpStatus(403), error)
    }

    @Test
    fun `The downloader loads gzip-encoded chunks end to end`() = runBlocking {
        serve(
            mapOf(
                "/electrical.usdz" to gzipResponse("electrical.usdz"),
                "/electrical.meta.json" to gzipResponse("electrical.meta.json"),
            ),
        )
        val original = assertNotNull(Fixtures.manifest().chunk("electrical"))
        val chunk = original.copy(
            usdz = original.usdz.copy(url = url("/electrical.usdz")),
            meta = original.meta.copy(url = url("/electrical.meta.json")),
        )

        val downloader = ChunkDownloader(transport = transport, cache = ChunkCache(Fixtures.temporaryDirectory()))
        val state = ModelLoadState()
        downloader.load(listOf(ChunkRequest(chunk, ChunkFileKind.META), ChunkRequest(chunk, ChunkFileKind.USDZ))).collect {
            state.apply(it)
        }
        assertTrue(state.failures.isEmpty())
        assertEquals(4, state.index.elementsInSystem("electrical").size)
        assertNotNull(state.models["electrical"])
        assertEquals(1.0, state.progress.fraction)
    }
}
