package com.getinsiteview.api

import com.getinsiteview.core.APIJSON
import com.getinsiteview.core.Catalog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable

/**
 * The catalog (`GET /v1/catalog`), cached on disk with its ETag and revalidated once per launch
 * (master PLAN §9: "Clients cache it by ETag"). Without a network the cached copy is used, so
 * colours and names work offline for a building opened before.
 *
 * @param cacheFile e.g. `cacheDir/catalog.json`; `null` keeps it in memory only.
 */
class CatalogRepository(private val api: ApiClient, private val cacheFile: File?) {
    private val lock = Any()
    private var current: Catalog? = null
    private var etag: String? = null
    private var revalidated = false
    private var loading: CompletableDeferred<Catalog>? = null

    /** The catalog on disk, without a network call. */
    fun cached(): Catalog? = synchronized(lock) {
        if (current == null) readCache()
        current
    }

    /**
     * The current catalog: revalidated with the server on the first call, then from memory. Falls
     * back to the cached copy when the server can't be reached.
     */
    suspend fun catalog(): Catalog {
        var owner = false
        val deferred = synchronized(lock) {
            val catalog = current
            if (revalidated && catalog != null) return catalog
            loading ?: CompletableDeferred<Catalog>().also {
                loading = it
                owner = true
            }
        }
        if (!owner) return deferred.await()
        try {
            val catalog = revalidate()
            deferred.complete(catalog)
            return catalog
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            synchronized(lock) { loading = null }
        }
    }

    private suspend fun revalidate(): Catalog {
        val ifNoneMatch = synchronized(lock) {
            if (current == null) readCache()
            if (current == null) null else etag
        }
        try {
            when (val result = api.catalog(ifNoneMatch = ifNoneMatch)) {
                is CatalogResult.Fetched -> synchronized(lock) {
                    current = result.catalog
                    etag = result.etag
                    writeCache()
                }
                CatalogResult.NotModified -> Unit
            }
            synchronized(lock) { revalidated = true }
        } catch (e: Exception) {
            synchronized(lock) { current }?.let { return it }
            throw e
        }
        return synchronized(lock) { current }
            ?: throw UnexpectedResponse("catalog_get", 304, "Not modified, but nothing is cached.")
    }

    @Serializable
    private data class CacheFile(val etag: String? = null, val catalog: Catalog)

    private fun readCache() {
        val file = cacheFile ?: return
        val cache = try {
            if (!file.exists()) return
            APIJSON.json.decodeFromString(CacheFile.serializer(), file.readText())
        } catch (_: IOException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        current = cache.catalog
        etag = cache.etag
    }

    private fun writeCache() {
        val file = cacheFile ?: return
        val catalog = current ?: return
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(APIJSON.encode(CacheFile.serializer(), CacheFile(etag, catalog)))
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        } catch (_: IOException) {
        } catch (_: IllegalArgumentException) {
        }
    }
}
