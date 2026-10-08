package com.getinsiteview.modelkit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The three files a chunk has. Android loads `meta` and `glb` (iOS loads `meta` and `usdz`); see
 * [ChunkPlan.defaultKinds].
 */
enum class ChunkFileKind(val raw: String) {
    META("meta"),
    USDZ("usdz"),
    GLB("glb");

    /** The renderer picks the loader from the extension, so cached files keep it. */
    val fileExtension: String
        get() = when (this) {
            META -> "json"
            USDZ -> "usdz"
            GLB -> "glb"
        }
}

/**
 * Chunk files on disk, keyed by their SHA-256 (`{directory}/{sha256}.{ext}`; the app passes
 * `cacheDir/models`), evicted least recently used first above a size limit (docs/PLAN.md §2: 1 GB).
 *
 * Files are only stored after their hash checks out, so a cache hit is trusted as is. The last use
 * is the file's modification time, updated on every hit.
 *
 * iOS's actor: every operation runs under one [Mutex]. The file I/O is blocking, so call it from
 * an I/O dispatcher (``ChunkDownloader`` does).
 *
 * @param now the clock used for last-use times (tests pass a fixed one).
 */
class ChunkCache(
    val directory: File,
    val capacity: Long = DEFAULT_CAPACITY,
    private val now: () -> Instant = { Instant.now() },
) {
    private val mutex = Mutex()
    private var didCreateDirectory = false

    fun fileURL(sha256: String, kind: ChunkFileKind): File =
        File(directory, "${sha256.lowercase()}.${kind.fileExtension}")

    /** The cached file, marked as just used; `null` on a miss. */
    suspend fun cachedFile(sha256: String, kind: ChunkFileKind): File? = mutex.withLock {
        val file = fileURL(sha256, kind)
        if (!file.exists()) return@withLock null
        touch(file)
        file
    }

    /**
     * Writes a verified file, then evicts old files above the capacity. Files in `pinned` (SHA-256s
     * of the building on screen) are never evicted.
     */
    suspend fun store(data: ByteArray, sha256: String, kind: ChunkFileKind, pinned: Set<String> = emptySet()): File =
        mutex.withLock {
            createDirectoryIfNeeded()
            val file = fileURL(sha256, kind)
            // Atomic: a temporary file (skipped by `entries()`) moved into place.
            val temporary = File(directory, ".${file.name}.${System.nanoTime()}.tmp")
            try {
                temporary.writeBytes(data)
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.delete()
            }
            touch(file)
            evictLocked(pinned + sha256.lowercase())
            file
        }

    /** Total size of the cached files. */
    suspend fun totalSize(): Long = mutex.withLock { entries().sumOf { it.size } }

    /** Removes least recently used files until the total fits the capacity. */
    suspend fun evict(keeping: Set<String> = emptySet()) = mutex.withLock { evictLocked(keeping) }

    /** Deletes every cached file. */
    suspend fun removeAll() = mutex.withLock {
        for (entry in entries()) {
            if (!entry.file.delete() && entry.file.exists()) throw IOException("Couldn't delete ${entry.file}")
        }
    }

    private fun evictLocked(pinned: Set<String>) {
        val entries = entries()
        var total = entries.sumOf { it.size }
        if (total <= capacity) return
        val sorted = entries.sortedWith(compareBy<Entry> { it.lastUse }.thenBy { it.file.name })
        val lowerPinned = pinned.map { it.lowercase() }.toSet()
        for (entry in sorted) {
            if (total <= capacity) break
            val sha = entry.file.name.substringBeforeLast('.')
            if (sha in lowerPinned) continue
            if (!entry.file.delete() && entry.file.exists()) throw IOException("Couldn't delete ${entry.file}")
            total -= entry.size
        }
    }

    private data class Entry(val file: File, val size: Long, val lastUse: Long)

    private fun entries(): List<Entry> {
        val names = directory.list() ?: return emptyList()
        return names.mapNotNull { name ->
            // Skips temporary files from interrupted atomic writes and anything that isn't ours.
            if (name.startsWith(".") || !name.contains(".")) return@mapNotNull null
            val file = File(directory, name)
            if (!file.isFile) return@mapNotNull null
            Entry(file, file.length(), file.lastModified())
        }
    }

    private fun touch(file: File) {
        file.setLastModified(now().toEpochMilli())
    }

    private fun createDirectoryIfNeeded() {
        if (didCreateDirectory) return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Couldn't create $directory")
        }
        didCreateDirectory = true
    }

    companion object {
        const val DEFAULT_CAPACITY: Long = 1_000_000_000
    }
}
