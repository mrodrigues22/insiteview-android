package com.getinsiteview.api.account

import com.getinsiteview.api.ApiClient
import com.getinsiteview.api.ApiError
import com.getinsiteview.api.AuthResponse
import com.getinsiteview.api.GuestProblem
import com.getinsiteview.api.Me
import com.getinsiteview.api.UserCredentials
import com.getinsiteview.api.deleteAccount
import com.getinsiteview.api.exchangeOAuthCode
import com.getinsiteview.api.login
import com.getinsiteview.api.logout
import com.getinsiteview.api.me
import com.getinsiteview.api.oauthProviders
import com.getinsiteview.api.register
import com.getinsiteview.api.sendEmailConfirmation
import com.getinsiteview.api.updateMe
import com.getinsiteview.core.TermsVersion
import com.getinsiteview.core.UnitSystem
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why an account action (not sign-in) failed. */
sealed class AccountError : Exception(null, null, false, false) {
    /**
     * `DELETE /v1/me` → 409: the only owner of an organization with other members must hand it
     * over on the web first.
     */
    data object SoleOwner : AccountError()

    data object Offline : AccountError()

    data object Unavailable : AccountError()

    companion object {
        fun from(error: Throwable): AccountError = when {
            error is ApiError && (error.code == ApiError.Code.CONFLICT || error.status == 409) -> SoleOwner
            GuestProblem.from(error) == GuestProblem.Offline -> Offline
            else -> Unavailable
        }
    }
}

/**
 * Sign-in, the signed-in user and account actions (IOS-M3-01, M3-07), on top of [UserCredentials]
 * (the stored tokens the bearer interceptor sends). The state machine is [AuthState]; screens
 * collect [states] (or register with [observe]).
 *
 * @param api the shared client (its bearer interceptor reads [credentials]).
 * @param deviceName stored with the refresh token (e.g. the device model), shown in the web's sessions.
 * @param locale a new account's language (`en`, `pt-BR` or `es`).
 */
class AccountService(
    private val api: ApiClient,
    private val credentials: UserCredentials,
    private val deviceName: String?,
    private val locale: () -> String? = { null },
) {
    private val lock = Any()
    private val mutableStates = MutableStateFlow(AuthState())
    private val observers = ConcurrentHashMap<UUID, (AuthState) -> Unit>()

    @Volatile
    private var providers: List<OAuthProvider>? = null
    private var credentialsObserver: UUID? = null

    /** Every state, for Compose (`collectAsState`). */
    val states: StateFlow<AuthState> = mutableStates.asStateFlow()

    val state: AuthState get() = mutableStates.value

    /** Calls [observer] with every new state (and once now). */
    fun observe(observer: (AuthState) -> Unit): UUID {
        val id = UUID.randomUUID()
        observers[id] = observer
        observer(state)
        return id
    }

    fun removeObserver(id: UUID) {
        observers.remove(id)
    }

    private fun apply(event: AuthState.Event): Boolean = apply { it.applying(event) }

    /** Applies a transition atomically; observers hear about accepted ones. */
    private fun apply(transition: (AuthState) -> AuthState?): Boolean {
        val next = synchronized(lock) {
            val next = transition(mutableStates.value) ?: return false
            mutableStates.value = next
            next
        }
        for (observer in observers.values) observer(next)
        return true
    }

    // Launch

    /**
     * Reads the stored tokens, then loads the profile. Offline, the user stays signed in without a
     * profile until the next [refreshProfile].
     */
    suspend fun restore() {
        synchronized(lock) {
            if (credentialsObserver == null) {
                // The API refused the refresh token: the session is over.
                credentialsObserver = credentials.observe { signedIn -> if (!signedIn) apply(AuthState.Event.SessionEnded) }
            }
        }
        val signedIn = credentials.isSignedIn()
        apply(AuthState.Event.Restored(signedIn))
        if (signedIn) {
            try {
                refreshProfile()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // Sign-in

    /** Email and password. Throws [SignInError]. */
    suspend fun signIn(email: String, password: String): Me {
        val trimmed = email.trim()
        return signIn(SignInMethod.Email) { api.login(trimmed, password, deviceName) }
    }

    /** A new account; the person accepted [TermsVersion.CURRENT] on the form. Throws [SignInError]. */
    suspend fun register(email: String, password: String, displayName: String): Me {
        val trimmedEmail = email.trim()
        val name = displayName.trim()
        val language = locale()
        return signIn(SignInMethod.Register) {
            api.register(
                email = trimmedEmail, password = password, displayName = name, termsVersion = TermsVersion.CURRENT,
                locale = language, deviceName = deviceName,
            )
        }
    }

    /** The providers to offer (Google, Microsoft, Apple); empty when the server can't be reached. */
    suspend fun oauthProviders(): List<OAuthProvider> {
        providers?.let { return it }
        val names = try {
            api.oauthProviders()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        return OAuthProvider.offered(names).also { providers = it }
    }

    /**
     * Google, Microsoft or Apple: [authenticate] shows the start URL in a Custom Tab and returns the
     * callback URL (or throws `CancellationException` when the person closes it); the code in it is
     * exchanged for tokens. Throws [SignInError].
     */
    suspend fun signIn(provider: OAuthProvider, authenticate: suspend (startURL: URI) -> URI): Me {
        val url = OAuthFlow.startURL(apiBaseURL = api.baseURL, provider = provider, termsVersion = TermsVersion.CURRENT)
        return signIn(SignInMethod.Provider(provider)) {
            when (val callback = OAuthFlow.callback(authenticate(url))) {
                is OAuthFlow.Callback.Code -> api.exchangeOAuthCode(callback.code, deviceName)
                is OAuthFlow.Callback.Failed -> throw callback.error
            }
        }
    }

    /** One sign-in at a time: the tokens go to the store, then the state says who's in. */
    private suspend fun signIn(method: SignInMethod, operation: suspend () -> AuthResponse): Me {
        val started = apply { state ->
            if (state.phase == AuthState.Phase.SignedOut || state.phase == AuthState.Phase.Restoring) {
                state.applying(AuthState.Event.Started(method))
            } else {
                null
            }
        }
        if (!started) throw SignInError.Cancelled
        try {
            val response = operation()
            // A web-style response (refresh token in a cookie) would leave the app unable to refresh.
            val tokens = response.tokens ?: throw SignInError.Unavailable
            credentials.signIn(tokens)
            apply(AuthState.Event.Succeeded(response.user))
            return response.user
        } catch (error: Throwable) {
            val failure = SignInError.from(error)
            apply(AuthState.Event.Failed(failure))
            throw failure
        }
    }

    // Profile

    /** `GET /v1/me`. */
    suspend fun refreshProfile(): Me {
        val me = api.me()
        apply(AuthState.Event.ProfileLoaded(me))
        return me
    }

    /** `PATCH /v1/me` with the fields to change (Profile: units). */
    suspend fun updateProfile(displayName: String? = null, locale: String? = null, units: UnitSystem? = null): Me {
        val me = api.updateMe(displayName = displayName, locale = locale, units = units)
        apply(AuthState.Event.ProfileLoaded(me))
        return me
    }

    /** Emails a new confirmation link. */
    suspend fun sendEmailConfirmation() {
        api.sendEmailConfirmation()
    }

    // Sign out and delete

    /**
     * Signs out on this device at once; revoking the refresh token's family on the server is best
     * effort (offline, it expires on its own).
     */
    suspend fun signOut() {
        val refreshToken = credentials.refreshToken()
        apply(AuthState.Event.SignedOut)
        credentials.signOut()
        if (refreshToken != null) {
            try {
                api.logout(refreshToken)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * `DELETE /v1/me` (Google Play's account deletion requirement); provider tokens are revoked by
     * the server. Throws [AccountError.SoleOwner] when an organization must be handed over first.
     */
    suspend fun deleteAccount() {
        try {
            api.deleteAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            throw AccountError.from(error)
        }
        apply(AuthState.Event.Deleted)
        credentials.signOut()
    }
}
