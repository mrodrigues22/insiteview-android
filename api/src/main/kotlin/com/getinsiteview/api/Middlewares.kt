package com.getinsiteview.api

import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

// Interceptors for every call (docs/PLAN.md §3 "Networking and auth"), the iOS middlewares. They
// key on the spec's operation ids, which every request carries as its `OperationId` tag.
//
// Order in `ApiClient(interceptors = …)`:
// `[DeviceIdInterceptor, ClientVersionInterceptor, AcceptLanguageInterceptor, BearerInterceptor]`.
// `ApiClient.withVisitToken` puts `VisitTokenInterceptor` just before the bearer one. The bearer
// interceptor sits innermost so its retry after a refresh only repeats the transport call, and
// `ApiClient` turns what's left of a 4xx/5xx into `ApiError` once the chain returns.

/** The spec's operation id (`.WithName` in the API), as a request tag: `request.tag(OperationId::class)`. */
data class OperationId(val value: String) {
    override fun toString(): String = value
}

/** The operation id of a request built by [ApiClient]; empty for any other request. */
val Request.operationId: String get() = tag(OperationId::class)?.value ?: ""

object ApiHeaders {
    /** Random per-install id (docs/PLAN.md §3 "Device ID"). */
    const val DEVICE_ID = "X-Device-Id"

    /** `android` for the app (master PLAN §9 "Schema and client compatibility"). */
    const val CLIENT = "X-Client"

    /** `versionName`; the API answers 426 `client.outdated` below its minimum. */
    const val CLIENT_VERSION = "X-Client-Version"

    const val ACCEPT_LANGUAGE = "Accept-Language"
    const val AUTHORIZATION = "Authorization"
}

/** Sends `X-Device-Id` on every call. */
class DeviceIdInterceptor(val deviceId: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header(ApiHeaders.DEVICE_ID, deviceId).build())
}

/**
 * Sends `X-Client: android` and `X-Client-Version` on every call, so the API can turn away versions
 * it no longer serves (426 `client.outdated`, master PLAN §9). Without a version, only `X-Client`
 * goes, and the API lets the call through.
 */
class ClientVersionInterceptor(version: String?) : Interceptor {
    val version: String? = version?.trim(' ', '\t')?.ifEmpty { null }

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder().header(ApiHeaders.CLIENT, CLIENT)
        version?.let { builder.header(ApiHeaders.CLIENT_VERSION, it) }
        return chain.proceed(builder.build())
    }

    companion object {
        const val CLIENT = "android"
    }
}

/**
 * Sends `Accept-Language` from the user's preferred languages, so API messages (and the
 * server-side defaults, e.g. a new account's locale) follow the device.
 *
 * @param preferredLanguages read on every call, so a language change applies at once (the app
 *   passes its per-app locales, e.g. `LocaleListCompat.toLanguageTags()` split on commas).
 */
class AcceptLanguageInterceptor(private val preferredLanguages: () -> List<String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(ApiHeaders.ACCEPT_LANGUAGE) != null) return chain.proceed(request)
        val value = headerValue(preferredLanguages()) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(ApiHeaders.ACCEPT_LANGUAGE, value).build())
    }

    companion object {
        /** `["pt-BR", "en-US"]` → `pt-BR, en-US;q=0.9`. At most 6 languages; `null` for none. */
        fun headerValue(languages: List<String>): String? {
            val seen = HashSet<String>()
            val tags = languages
                .map { it.replace('_', '-').trim(' ', '\t') }
                .filter { tag -> tag.isNotEmpty() && tag.all { it.code < 128 && (it.isLetterOrDigit() || it == '-') } }
                .filter { seen.add(it.lowercase()) }
                .take(6)
            if (tags.isEmpty()) return null
            return tags.mapIndexed { index, tag -> if (index == 0) tag else "$tag;q=0.${10 - index}" }.joinToString(", ")
        }
    }
}

/** Which token an operation takes, from its operation id. */
enum class AuthorizationKind {
    /** No token: sign-in (`auth_*` but `auth_verify_email_send`), health, the catalog (cacheable), QR images. */
    NONE,

    /** The visit token: `/v1/visit/…`. */
    VISIT,

    /**
     * The signed-in user's access token, when there is one. Public visit creation takes it too, so
     * members get their own scope (docs/PLAN.md §3 "Signed-in users").
     */
    USER,

    /** The visit token on a visit's client, else the user's token (`POST /v1/events`). */
    VISIT_OR_USER,
    ;

    companion object {
        fun forOperation(operationId: String): AuthorizationKind {
            if (operationId.startsWith("visit_")) return VISIT
            if (operationId == "events_create") return VISIT_OR_USER
            // The one auth operation for a signed-in user: "send the confirmation email again".
            if (operationId == "auth_verify_email_send") return USER
            if (operationId.startsWith("auth_") || operationId.startsWith("health_") ||
                operationId.startsWith("plates_qr_") || operationId == "catalog_get"
            ) {
                return NONE
            }
            return USER
        }
    }
}

/** Holds one visit's token (`POST …/visits` → `visitToken`). Thread-safe. */
class VisitTokenStore {
    private data class State(val token: String, val expiresAt: Instant)

    @Volatile
    private var state: State? = null

    fun set(token: String, expiresAt: Instant) {
        state = State(token, expiresAt)
    }

    fun clear() {
        state = null
    }

    val token: String? get() = state?.token

    /** The token unless it has expired (with a minute to spare). */
    fun validToken(at: Instant = Instant.now()): String? {
        val current = state ?: return null
        return if (at.plusSeconds(60).isBefore(current.expiresAt)) current.token else null
    }
}

/** Sends `Authorization: Bearer <visit token>` on `visit_*` operations and `events_create`. */
class VisitTokenInterceptor(val store: VisitTokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val kind = AuthorizationKind.forOperation(request.operationId)
        val token = store.token
        if ((kind == AuthorizationKind.VISIT || kind == AuthorizationKind.VISIT_OR_USER) &&
            request.header(ApiHeaders.AUTHORIZATION) == null && token != null
        ) {
            return chain.proceed(request.newBuilder().header(ApiHeaders.AUTHORIZATION, "Bearer $token").build())
        }
        return chain.proceed(request)
    }
}

/**
 * Sends the signed-in user's access token (refreshed first when it's about to expire) and, on a
 * 401, refreshes it once (one refresh in flight for all calls) and retries the call. A refresh the
 * API rejects signs the user out.
 *
 * OkHttp interceptors are blocking: the credentials' suspend calls run in `runBlocking` on the
 * calling thread ([ApiClient] executes calls on `Dispatchers.IO`).
 */
class BearerInterceptor(val credentials: UserCredentials) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val kind = AuthorizationKind.forOperation(request.operationId)
        if ((kind != AuthorizationKind.USER && kind != AuthorizationKind.VISIT_OR_USER) ||
            request.header(ApiHeaders.AUTHORIZATION) != null
        ) {
            return chain.proceed(request)
        }
        val token = runBlocking { credentials.accessToken() } ?: return chain.proceed(request)
        val response = chain.proceed(request.newBuilder().header(ApiHeaders.AUTHORIZATION, "Bearer $token").build())
        // A one-shot body (a stream) can't be sent twice.
        if (response.code != 401 || request.body?.isOneShot() == true) return response
        val refreshed = runBlocking { credentials.refreshedAccessToken(replacing = token) } ?: return response
        response.close()
        return chain.proceed(request.newBuilder().header(ApiHeaders.AUTHORIZATION, "Bearer $refreshed").build())
    }
}
