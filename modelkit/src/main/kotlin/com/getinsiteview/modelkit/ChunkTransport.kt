package com.getinsiteview.modelkit

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Downloads one chunk file from its presigned URL. Injectable so tests use fixtures. */
fun interface ChunkTransport {
    /**
     * Returns the file's bytes after transfer decoding (`Content-Encoding: gzip` removed) and calls
     * `received` with the running count of decoded bytes, for progress.
     */
    suspend fun download(url: URI, received: (Long) -> Unit): ByteArray
}

sealed class ChunkTransportError(message: String) : Exception(message) {
    /** Non-2xx from storage. 403 usually means the presigned URL expired. */
    data class HttpStatus(val status: Int) : ChunkTransportError("HTTP status $status")

    data object NotHTTP : ChunkTransportError("Not an HTTP response")
}

/**
 * [ChunkTransport] over OkHttp (iOS: `URLSessionChunkTransport`). Storage sends chunk files with
 * `Content-Encoding: gzip`. OkHttp decodes it only when it added `Accept-Encoding` itself, so
 * requests never set that header: the bytes returned and the counts reported are the decoded
 * ones, matching the manifest's `bytes` and `sha256`. If storage ever serves gzip without saying
 * so, the bytes stay compressed and [ChunkDownloader] reports [ChunkLoadError.NotDecoded].
 *
 * @param client a shared client; its HTTP cache is turned off ([ChunkCache] replaces it).
 */
class OkHttpChunkTransport(client: OkHttpClient = OkHttpClient()) : ChunkTransport {
    private val client: OkHttpClient = client.newBuilder().cache(null).build()

    override suspend fun download(url: URI, received: (Long) -> Unit): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url.toString()).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { read(response, received) }
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            })
        }

    private fun read(response: Response, received: (Long) -> Unit): ByteArray = response.use {
        if (it.code !in 200..299) throw ChunkTransportError.HttpStatus(it.code)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        it.body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                received(output.size().toLong())
            }
        }
        output.toByteArray()
    }
}
