package com.getinsiteview.api

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a signed-in user's tokens persist: encrypted with an Android Keystore key in DataStore in
 * the app (iOS: the Keychain), memory in tests.
 */
interface TokenStore {
    suspend fun load(): AuthTokens?

    suspend fun save(tokens: AuthTokens)

    suspend fun clear()
}

class InMemoryTokenStore(tokens: AuthTokens? = null) : TokenStore {
    @Volatile
    private var tokens: AuthTokens? = tokens

    override suspend fun load(): AuthTokens? = tokens

    override suspend fun save(tokens: AuthTokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}

/**
 * The signed-in user's tokens for [BearerInterceptor], with one refresh in flight at a time
 * (docs/PLAN.md §3 "Signed-in users"). [AccountService] signs in and out through it.
 *
 * The access token is refreshed shortly before it expires, not only after a 401: the public visit
 * endpoints treat an expired token as no token (they allow anonymous calls), so a member with a
 * stale token would silently get a guest's scope.
 *
 * @param refresher exchanges a refresh token for new tokens (`POST /v1/auth/refresh` on a client
 *   without the bearer interceptor).
 */
class UserCredentials(
    private val store: TokenStore,
    private val now: () -> Instant = Instant::now,
    private val refresher: suspend (refreshToken: String) -> AuthTokens,
) {
    private val mutex = Mutex()
    private var tokens: AuthTokens? = null
    private var loaded = false
    private var refreshing: CompletableDeferred<Result<AuthTokens>>? = null
    private val observers = ConcurrentHashMap<UUID, (signedIn: Boolean) -> Unit>()

    suspend fun isSignedIn(): Boolean {
        loadIfNeeded()
        return mutex.withLock { tokens != null }
    }

    /**
     * The access token for a call: refreshed first when it's about to expire. A refresh that fails
     * for lack of network returns the old token (the call decides); one the API rejects signs the
     * user out and returns `null`.
     */
    suspend fun accessToken(): String? {
        loadIfNeeded()
        val current = mutex.withLock { tokens } ?: return null
        if (Duration.between(now(), current.accessTokenExpiresAt) >= REFRESH_MARGIN) {
            return current.accessToken
        }
        return refresh(current).fold(
            onSuccess = { it.accessToken },
            onFailure = { mutex.withLock { tokens?.accessToken } },
        )
    }

    /** The refresh token, e.g. for `POST /v1/auth/logout`. */
    suspend fun refreshToken(): String? {
        loadIfNeeded()
        return mutex.withLock { tokens?.refreshToken }
    }

    suspend fun signIn(tokens: AuthTokens) {
        mutex.withLock {
            loaded = true
            refreshing = null
            this.tokens = tokens
            store.save(tokens)
        }
        notify(signedIn = true)
    }

    suspend fun signOut() {
        mutex.withLock {
            loaded = true
            tokens = null
            refreshing = null
            store.clear()
        }
        notify(signedIn = false)
    }

    /**
     * Called after a 401 with [replacing]. Returns a fresh access token, or `null` when the refresh
     * failed. If another call already refreshed, returns that token without refreshing again. A
     * refresh the API rejects (401: expired, revoked or `auth.refresh_reused`) signs the user out;
     * a network failure keeps the tokens for the next try.
     */
    suspend fun refreshedAccessToken(replacing: String): String? {
        loadIfNeeded()
        val current = mutex.withLock { tokens } ?: return null
        if (current.accessToken != replacing) return current.accessToken
        return refresh(current).getOrNull()?.accessToken
    }

    /** One refresh at a time: callers that arrive while one runs share its result. */
    private suspend fun refresh(current: AuthTokens): Result<AuthTokens> {
        var owner = false
        val deferred = mutex.withLock {
            refreshing ?: CompletableDeferred<Result<AuthTokens>>().also {
                refreshing = it
                owner = true
            }
        }
        if (!owner) return deferred.await()
        val result = try {
            Result.success(refresher(current.refreshToken))
        } catch (e: CancellationException) {
            mutex.withLock { if (refreshing === deferred) refreshing = null }
            deferred.complete(Result.failure(e))
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        var signedOut = false
        mutex.withLock {
            // Signed out (or in again) meanwhile: that wins over this refresh.
            if (refreshing === deferred) {
                refreshing = null
                result.onSuccess {
                    tokens = it
                    store.save(it)
                }
                val error = result.exceptionOrNull() as? ApiError
                if (error != null && (error.status == 401 || error.code == ApiError.Code.AUTH_REFRESH_REUSED)) {
                    tokens = null
                    store.clear()
                    signedOut = true
                }
            }
        }
        if (signedOut) notify(signedIn = false)
        deferred.complete(result)
        return result
    }

    /** Calls [observer] whenever the user signs in or out (including a failed refresh). */
    fun observe(observer: (signedIn: Boolean) -> Unit): UUID {
        val id = UUID.randomUUID()
        observers[id] = observer
        return id
    }

    fun removeObserver(id: UUID) {
        observers.remove(id)
    }

    private fun notify(signedIn: Boolean) {
        for (observer in observers.values) observer(signedIn)
    }

    /** Reads the store once; concurrent first calls resume after the tokens are in place. */
    private suspend fun loadIfNeeded() {
        mutex.withLock {
            if (loaded) return
            tokens = store.load()
            loaded = true
        }
    }

    companion object {
        /** Refresh when the access token has less than this left. */
        val REFRESH_MARGIN: Duration = Duration.ofSeconds(60)
    }
}
