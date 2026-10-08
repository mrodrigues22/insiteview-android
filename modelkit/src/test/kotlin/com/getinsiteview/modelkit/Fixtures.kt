package com.getinsiteview.modelkit

import com.getinsiteview.core.APIJSON
import com.getinsiteview.core.Catalog
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Files from `src/test/resources` (iOS `Fixtures/`): the `test-room` converter output (API fixture
 * `make_test_room.py`: walls, a door, 3 outlets, a switch and a cold-water pipe) and the API catalog.
 */
object Fixtures {
    /** What the iOS tests load: the fixtures have USDZ model files, not GLB. */
    val iosKinds = listOf(ChunkFileKind.META, ChunkFileKind.USDZ)

    fun data(path: String): ByteArray {
        val stream = assertNotNull(Fixtures::class.java.getResourceAsStream("/$path"), "Missing fixture $path")
        return stream.use { it.readBytes() }
    }

    /** The test-room manifest as the API serves it (presigned URLs on `storage.test`). */
    fun manifest(): Manifest = Manifest.decode(data("test-room/manifest.json"))

    fun catalog(): Catalog = APIJSON.decode(data("catalog.json").decodeToString())

    fun meta(chunk: String): ChunkMeta = ChunkMeta.decode(data("test-room/$chunk.meta.json"))

    /** A fresh empty directory under the temporary directory. */
    fun temporaryDirectory(): File = Files.createTempDirectory("iv-tests-").toFile().also { it.deleteOnExit() }
}

/** The last path component of a URI. */
val URI.lastPathComponent: String get() = path.substringAfterLast('/')

/** Serves chunk files by the last path component of the URL, from `test-room`. */
class FixtureTransport(
    overrides: Map<String, List<Result<ByteArray>>> = emptyMap(),
    validSignature: String? = null,
    private val delay: Duration = 5.milliseconds,
) : ChunkTransport {
    private val lock = Any()
    private val _calls = ArrayList<URI>()
    private var now = 0
    private var _peak = 0

    /** Per file name: what to serve instead of the fixture (first entry per call, then the fixture). */
    private val overrides: MutableMap<String, ArrayDeque<Result<ByteArray>>> =
        overrides.mapValues { ArrayDeque(it.value) }.toMutableMap()

    /** Only URLs whose query contains this are valid (older ones get 403). */
    private val validSignature: String? = validSignature

    val calls: List<URI> get() = synchronized(lock) { _calls.toList() }
    val callCount: Int get() = synchronized(lock) { _calls.size }
    val peak: Int get() = synchronized(lock) { _peak }

    override suspend fun download(url: URI, received: (Long) -> Unit): ByteArray {
        synchronized(lock) {
            _calls += url
            now += 1
            _peak = maxOf(_peak, now)
        }
        try {
            delay(delay)
            if (validSignature != null && !(url.query ?: "").contains(validSignature)) {
                throw ChunkTransportError.HttpStatus(403)
            }
            val name = url.lastPathComponent
            val override = synchronized(lock) { overrides[name]?.removeFirstOrNull() }
            val data = override?.getOrThrow() ?: Fixtures.data("test-room/$name")
            // Report progress in two steps, like a network would.
            received((data.size / 2).toLong())
            received(data.size.toLong())
            return data
        } finally {
            synchronized(lock) { now -= 1 }
        }
    }
}
