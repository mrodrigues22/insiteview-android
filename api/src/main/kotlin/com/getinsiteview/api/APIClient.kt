package com.getinsiteview.api

import com.getinsiteview.modelkit.Manifest
import java.io.IOException
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Entry point to the Insite View API.
 *
 * iOS generates its client from `openapi.json`; here the calls in Endpoints.kt are hand-written on
 * [send], with the models in Models.kt (checked against the spec by `ApiContractTest`). Every call
 * throws [ApiError] for a response of 400 or more, `IOException` for transport failures (see
 * [GuestProblem.isOffline]), [Manifest.DecodingProblem.UnsupportedSchema] for a manifest schema this
 * app can't read, and [UnexpectedResponse] for a body that doesn't match the spec.
 *
 * @param transport the OkHttp client calls go through (connection pool, timeouts, and in tests a
 *   stub interceptor). Its own interceptors run inside [interceptors].
 * @param interceptors first is outermost. The error check runs outside these, so the bearer
 *   interceptor still sees a 401 before it becomes an error.
 * @param onClientOutdated called (on any thread) whenever a response is 426 `client.outdated`:
 *   the app shows its blocking "Update required" screen (master PLAN §9 "Schema and client
 *   compatibility").
 */
class ApiClient(
    val baseURL: URI,
    val transport: OkHttpClient = defaultTransport,
    val interceptors: List<Interceptor> = emptyList(),
    val onClientOutdated: (() -> Unit)? = null,
) {
    private val client: OkHttpClient by lazy {
        transport.newBuilder().apply { interceptors().addAll(0, this@ApiClient.interceptors) }.build()
    }

    /** The same client with one more (innermost) interceptor. */
    fun adding(interceptor: Interceptor): ApiClient =
        ApiClient(baseURL, transport, interceptors + interceptor, onClientOutdated)

    /**
     * The same client sending a visit's token. The visit interceptor goes just outside
     * [BearerInterceptor], so operations either token can authorize (`events_create`) carry the
     * visit's, and the user's token is only added where there is no visit token.
     */
    fun withVisitToken(store: VisitTokenStore): ApiClient {
        val list = interceptors.toMutableList()
        val index = list.indexOfFirst { it is BearerInterceptor }.let { if (it < 0) list.size else it }
        list.add(index, VisitTokenInterceptor(store))
        return ApiClient(baseURL, transport, list, onClientOutdated)
    }

    /** `GET /health/ready`, the M0 exit check. A 503 still has a body that says which check failed. */
    suspend fun readiness(): Readiness {
        // A 503 is data here, not an error.
        val response = execute(Endpoint("health_ready", "GET", listOf("health", "ready")) { _, _, _ -> })
        return when (response.status) {
            200 -> Readiness(true, decodeJson("health_ready", response, HealthResponse.serializer()))
            503 -> Readiness(false, decodeJson("health_ready", response, HealthResponse.serializer()))
            else -> throw UnexpectedStatus(response.status)
        }
    }

    /**
     * Runs the interceptors and the transport, then maps a status of 400 or more to [ApiError] and
     * a 2xx (or 304) body through the endpoint's decoder.
     */
    suspend fun <T> send(endpoint: Endpoint<T>): T {
        val response = execute(endpoint)
        val status = response.status
        if (status >= 400) {
            val error = ApiError.decode(status, response.headers["Retry-After"], response.body)
            if (error.code == ApiError.Code.CLIENT_OUTDATED) onClientOutdated?.invoke()
            throw error
        }
        if (status !in 200..299 && status != 304) {
            throw UnexpectedResponse(endpoint.operationId, status, "Undocumented status.")
        }
        return try {
            endpoint.decode(status, response.headers, response.body)
        } catch (e: ApiError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnexpectedResponse) {
            throw e
        } catch (e: Manifest.DecodingProblem.UnsupportedSchema) {
            // A manifest schema this app can't read: "Update required" (GuestProblem.UpdateRequired).
            throw e
        } catch (e: Exception) {
            // A body that doesn't match the spec, or a manifest that fails validation.
            throw UnexpectedResponse(endpoint.operationId, status, e.message ?: e.toString())
        }
    }

    internal class RawResponse(val status: Int, val headers: Headers, val body: ByteArray)

    private suspend fun execute(endpoint: Endpoint<*>): RawResponse {
        val url = baseURL.toString().toHttpUrl().newBuilder().apply {
            for (segment in endpoint.path) addPathSegment(segment)
            for ((name, value) in endpoint.query) if (value != null) addQueryParameter(name, value)
        }.build()
        val builder = Request.Builder().url(url).tag(OperationId::class, OperationId(endpoint.operationId))
        for ((name, value) in endpoint.headers) builder.header(name, value)
        if (endpoint.headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
            builder.header("Accept", "application/json, application/problem+json")
        }
        val body = endpoint.body?.toRequestBody(JSON_MEDIA_TYPE)
            ?: if (endpoint.method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(null) else null
        builder.method(endpoint.method, body)
        return client.newCall(builder.build()).await()
    }

    companion object {
        /** Largest response body read into memory (manifests and element details are far smaller). */
        const val MAX_BODY_BYTES: Long = 16L * 1024 * 1024

        internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** The OkHttp client used when none is given. */
        val defaultTransport: OkHttpClient by lazy { OkHttpClient() }

        /**
         * JSON as the API writes it: unknown keys ignored, missing nullable fields `null`, `null`s
         * not written (iOS omits `nil` fields too), .NET timestamps through `ISO8601Timestamp`.
         */
        val json: Json = Json(com.getinsiteview.core.APIJSON.json) { coerceInputValues = true }

        /**
         * Runs a call synchronously on `Dispatchers.IO` (interceptors may block on the credentials,
         * and synchronous calls don't count against the dispatcher's per-host limit, so a refresh
         * can't wait behind the calls it unblocks). Cancelling the coroutine cancels the call.
         */
        private suspend fun Call.await(): RawResponse = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            Dispatchers.IO.asExecutor().execute {
                try {
                    val result = execute().use { response ->
                        val source = response.body.source()
                        source.request(MAX_BODY_BYTES + 1)
                        if (source.buffer.size > MAX_BODY_BYTES) throw IOException("Response body over $MAX_BODY_BYTES bytes.")
                        RawResponse(response.code, response.headers, source.buffer.readByteArray())
                    }
                    continuation.resume(result)
                } catch (e: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        }
    }
}

/** `GET /health/ready`: `isReady` is `true` for 200, `false` for 503. */
data class Readiness(val isReady: Boolean, val health: HealthResponse)

/** A status code the spec doesn't document for an operation. */
data class UnexpectedStatus(val statusCode: Int) : Exception("Unexpected HTTP status $statusCode.")

/**
 * One request (iOS has these only for what the generated client can't express; here every call
 * is one). [operationId] is the spec's (`.WithName` in the API), so interceptors can key on it.
 */
class Endpoint<T>(
    val operationId: String,
    val method: String,
    /** Path segments, not encoded: `["v1", "visit", "elements", id]`. */
    val path: List<String>,
    val query: List<Pair<String, String?>> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    /** JSON. */
    val body: ByteArray? = null,
    /** Decodes a 2xx (or 304) response. */
    val decode: (status: Int, headers: Headers, body: ByteArray) -> T,
)

internal fun <T> decodeJson(
    operationId: String,
    response: ApiClient.RawResponse,
    deserializer: kotlinx.serialization.DeserializationStrategy<T>,
): T = try {
    ApiClient.json.decodeFromString(deserializer, response.body.decodeToString())
} catch (e: IllegalArgumentException) {
    throw UnexpectedResponse(operationId, response.status, e.message ?: e.toString())
}
