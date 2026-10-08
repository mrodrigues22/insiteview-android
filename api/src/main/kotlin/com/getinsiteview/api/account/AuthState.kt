package com.getinsiteview.api.account

import com.getinsiteview.api.ApiError
import com.getinsiteview.api.GuestProblem
import com.getinsiteview.api.Me
import kotlin.coroutines.cancellation.CancellationException

/** How someone signs in (IOS-M3-01). */
sealed interface SignInMethod {
    /** Google, Microsoft or Apple in a Custom Tab (iOS signs in with Apple natively). */
    data class Provider(val provider: OAuthProvider) : SignInMethod

    data object Email : SignInMethod

    /** A new account with email and password. */
    data object Register : SignInMethod
}

/**
 * Why signing in (or an account action) didn't work, for the screen to say so. Built from the API's
 * `code`, never its message.
 */
sealed class SignInError : Exception(null, null, false, false) {
    /** Wrong email or password (`auth.invalid_credentials`). */
    data object InvalidCredentials : SignInError()

    /** Registering with an email that has an account (`auth.email_taken`). */
    data object EmailTaken : SignInError()

    /** Too many wrong passwords (`auth.locked_out`); seconds to wait when the API says. */
    data class LockedOut(val retryAfter: Int?) : SignInError()

    /**
     * A provider login whose email belongs to an account that can't be linked automatically
     * (`auth.email_in_use`, or `error=email_in_use` on the callback): sign in with the password.
     */
    data object EmailInUse : SignInError()

    /** The one-time code expired or was used (`auth.token_invalid`): start again. */
    data object CodeExpired : SignInError()

    /** The provider sign-in failed or was refused (`error=oauth_failed`). */
    data object ProviderFailed : SignInError()

    /** The provider isn't configured on this server (404). */
    data object ProviderUnavailable : SignInError()

    /** Field errors (`validation`), e.g. a password under 10 characters. */
    data class Validation(val fields: Map<String, List<String>>) : SignInError()

    data class RateLimited(val retryAfter: Int?) : SignInError()

    data object Offline : SignInError()

    /** The person closed the sign-in tab: nothing to show. */
    data object Cancelled : SignInError()

    /** Anything else: "Something went wrong. Try again." */
    data object Unavailable : SignInError()

    /** Messages for one field of a `validation` error (`email`, `password`, `displayName`). */
    fun messages(field: String): List<String> {
        val fields = (this as? Validation)?.fields ?: return emptyList()
        return fields.entries.firstOrNull { it.key.equals(field, ignoreCase = true) }?.value ?: emptyList()
    }

    companion object {
        /**
         * Maps any error. The browser step reports a closed tab by throwing
         * `CancellationException`, which maps to [Cancelled].
         */
        fun from(error: Throwable): SignInError = when (error) {
            is SignInError -> error
            is CancellationException -> Cancelled
            is ApiError -> from(error)
            else -> if (GuestProblem.isOffline(error)) Offline else Unavailable
        }

        fun from(error: ApiError): SignInError = when (error.code) {
            ApiError.Code.AUTH_INVALID_CREDENTIALS -> InvalidCredentials
            ApiError.Code.AUTH_EMAIL_TAKEN -> EmailTaken
            ApiError.Code.AUTH_LOCKED_OUT -> LockedOut(error.retryAfter)
            ApiError.Code.AUTH_EMAIL_IN_USE -> EmailInUse
            ApiError.Code.AUTH_TOKEN_INVALID -> CodeExpired
            ApiError.Code.VALIDATION -> Validation(error.fieldErrors)
            ApiError.Code.RATE_LIMITED -> RateLimited(error.retryAfter)
            ApiError.Code.NOT_FOUND -> ProviderUnavailable
            else -> when (error.status) {
                401 -> InvalidCredentials
                409 -> EmailInUse
                423 -> LockedOut(error.retryAfter)
                429 -> RateLimited(error.retryAfter)
                else -> Unavailable
            }
        }
    }
}

/**
 * Where the signed-in state stands (the app's sign-in screens, Profile and the Buildings tab read
 * it). An immutable value with explicit transitions, so the rules are tested on the JVM;
 * [AccountService] drives it.
 */
data class AuthState(
    val phase: Phase = Phase.Restoring,
    /** The signed-in user; `null` until `/v1/me` answers (e.g. launched offline). */
    val me: Me? = null,
    /** Why the last sign-in failed, until the next attempt. `null` after a cancel. */
    val error: SignInError? = null,
    /** The session ended without the user signing out: "Your session ended. Sign in again." */
    val sessionEnded: Boolean = false,
) {
    sealed interface Phase {
        /** Reading the stored tokens at launch. */
        data object Restoring : Phase

        data object SignedOut : Phase

        data class SigningIn(val method: SignInMethod) : Phase

        data object SignedIn : Phase
    }

    sealed interface Event {
        /** The token store was read: tokens or none. */
        data class Restored(val signedIn: Boolean) : Event

        data class Started(val method: SignInMethod) : Event

        data class Succeeded(val me: Me) : Event

        data class Failed(val error: SignInError) : Event

        /** `GET /v1/me` or `PATCH /v1/me` answered. */
        data class ProfileLoaded(val me: Me) : Event

        /** The user signed out. */
        data object SignedOut : Event

        /** The API refused the refresh token (expired, revoked, reused): sign in again. */
        data object SessionEnded : Event

        /** The account was deleted. */
        data object Deleted : Event
    }

    val isSignedIn: Boolean get() = phase == Phase.SignedIn

    val isSigningIn: Boolean get() = phase is Phase.SigningIn

    /**
     * The state after [event], or `null` when it isn't accepted (Swift's mutating `apply` returning
     * `false`). Only one sign-in runs at a time, and results of a sign-in nobody is waiting for are
     * ignored.
     */
    fun applying(event: Event): AuthState? = when (event) {
        is Event.Restored ->
            if (phase == Phase.Restoring) copy(phase = if (event.signedIn) Phase.SignedIn else Phase.SignedOut) else null
        is Event.Started ->
            if (phase == Phase.Restoring || phase == Phase.SignedOut) {
                copy(phase = Phase.SigningIn(event.method), error = null, sessionEnded = false)
            } else {
                null
            }
        is Event.Succeeded -> if (phase is Phase.SigningIn) copy(phase = Phase.SignedIn, me = event.me, error = null) else null
        is Event.Failed ->
            if (phase is Phase.SigningIn) {
                copy(phase = Phase.SignedOut, me = null, error = if (event.error == SignInError.Cancelled) null else event.error)
            } else {
                null
            }
        is Event.ProfileLoaded -> if (phase == Phase.SignedIn) copy(me = event.me) else null
        Event.SignedOut, Event.Deleted -> AuthState(phase = Phase.SignedOut)
        Event.SessionEnded -> if (phase == Phase.SignedIn) copy(phase = Phase.SignedOut, me = null, sessionEnded = true) else null
    }
}
