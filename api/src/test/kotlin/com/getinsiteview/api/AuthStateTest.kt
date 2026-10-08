package com.getinsiteview.api

import com.getinsiteview.api.account.AuthState
import com.getinsiteview.api.account.OAuthProvider
import com.getinsiteview.api.account.SignInError
import com.getinsiteview.api.account.SignInMethod
import java.net.ProtocolException
import java.net.UnknownHostException
import java.util.stream.Stream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** A mutable holder for the immutable [AuthState], like the Swift tests' `var state`. */
private class StateBox(var state: AuthState = AuthState()) {
    /** Applies [event]; returns whether it was accepted (Swift's `apply`). */
    fun apply(event: AuthState.Event): Boolean {
        val next = state.applying(event) ?: return false
        state = next
        return true
    }
}

@DisplayName("Auth state machine")
class AuthStateTest {
    private fun me(): Me = ApiClient.json.decodeFromString(AuthResponse.serializer(), APIFixtures.auth("a")).user

    @Test
    fun `Launch - the token store decides signed in or out`() {
        val box = StateBox()
        assertEquals(AuthState.Phase.Restoring, box.state.phase)
        assertTrue(box.apply(AuthState.Event.Restored(signedIn = true)))
        assertTrue(box.state.isSignedIn && box.state.me == null)
        assertFalse(box.apply(AuthState.Event.Restored(signedIn = false)), "restoring happens once")

        val out = StateBox()
        out.apply(AuthState.Event.Restored(signedIn = false))
        assertEquals(AuthState.Phase.SignedOut, out.state.phase)
    }

    @Test
    fun `One sign-in at a time, success keeps the user, failure the reason`() {
        val box = StateBox()
        box.apply(AuthState.Event.Restored(signedIn = false))
        assertTrue(box.apply(AuthState.Event.Started(SignInMethod.Provider(OAuthProvider.APPLE))))
        assertTrue(box.state.isSigningIn)
        assertFalse(box.apply(AuthState.Event.Started(SignInMethod.Email)), "a second sign-in waits")
        assertTrue(box.apply(AuthState.Event.Failed(SignInError.EmailInUse)))
        assertTrue(box.state.phase == AuthState.Phase.SignedOut && box.state.error == SignInError.EmailInUse)

        assertTrue(box.apply(AuthState.Event.Started(SignInMethod.Provider(OAuthProvider.GOOGLE))))
        assertNull(box.state.error, "a new attempt clears the last error")
        val user = me()
        assertTrue(box.apply(AuthState.Event.Succeeded(user)))
        assertTrue(box.state.isSignedIn && box.state.me?.email == "ana@example.com")
        assertFalse(box.apply(AuthState.Event.Succeeded(user)), "no sign-in is running")
        assertFalse(box.apply(AuthState.Event.Started(SignInMethod.Email)), "already signed in")
    }

    @Test
    fun `Cancelling shows nothing`() {
        val box = StateBox()
        box.apply(AuthState.Event.Restored(signedIn = false))
        box.apply(AuthState.Event.Started(SignInMethod.Provider(OAuthProvider.MICROSOFT)))
        box.apply(AuthState.Event.Failed(SignInError.Cancelled))
        assertTrue(box.state.phase == AuthState.Phase.SignedOut && box.state.error == null)
    }

    @Test
    fun `Signing out, the session ending and deleting the account`() {
        val box = StateBox()
        box.apply(AuthState.Event.Restored(signedIn = true))
        assertTrue(box.apply(AuthState.Event.ProfileLoaded(me())))
        assertTrue(box.apply(AuthState.Event.SessionEnded))
        assertTrue(box.state.phase == AuthState.Phase.SignedOut && box.state.sessionEnded && box.state.me == null)
        assertFalse(box.apply(AuthState.Event.SessionEnded), "only a signed-in session can end")
        assertFalse(box.apply(AuthState.Event.ProfileLoaded(me())), "a late profile doesn't sign anyone in")

        box.apply(AuthState.Event.Started(SignInMethod.Email))
        assertFalse(box.state.sessionEnded)
        box.apply(AuthState.Event.Succeeded(me()))
        assertTrue(box.apply(AuthState.Event.SignedOut))
        assertTrue(box.state.phase == AuthState.Phase.SignedOut && !box.state.sessionEnded)

        box.apply(AuthState.Event.Started(SignInMethod.Email))
        box.apply(AuthState.Event.Succeeded(me()))
        assertTrue(box.apply(AuthState.Event.Deleted))
        assertTrue(box.state.phase == AuthState.Phase.SignedOut && box.state.me == null)
    }

    @ParameterizedTest
    @MethodSource("errorMappings")
    fun `API errors become sign-in errors by code`(error: ApiError, expected: SignInError) {
        assertEquals(expected, SignInError.from(error))
    }

    @Test
    fun `Transport errors and cancellation`() {
        assertEquals(SignInError.Offline, SignInError.from(UnknownHostException("offline")))
        assertEquals(SignInError.Cancelled, SignInError.from(CancellationException("Custom Tab closed")))
        assertEquals(SignInError.Cancelled, SignInError.from(CancellationException()))
        assertEquals(SignInError.Unavailable, SignInError.from(ProtocolException("bad response")))
        assertEquals(
            listOf("At least 10 characters."),
            SignInError.Validation(mapOf("Password" to listOf("At least 10 characters."))).messages("password"),
        )
        assertTrue(SignInError.Offline.messages("password").isEmpty())
    }

    companion object {
        @JvmStatic
        fun errorMappings(): Stream<Arguments> = Stream.of(
            Arguments.of(ApiError(ApiError.Code.AUTH_INVALID_CREDENTIALS, 401), SignInError.InvalidCredentials),
            Arguments.of(ApiError(ApiError.Code.AUTH_EMAIL_TAKEN, 409), SignInError.EmailTaken),
            Arguments.of(ApiError(ApiError.Code.AUTH_LOCKED_OUT, 423, retryAfter = 900), SignInError.LockedOut(retryAfter = 900)),
            Arguments.of(ApiError(ApiError.Code.AUTH_EMAIL_IN_USE, 409), SignInError.EmailInUse),
            Arguments.of(ApiError(ApiError.Code.AUTH_TOKEN_INVALID, 400), SignInError.CodeExpired),
            Arguments.of(
                ApiError(ApiError.Code.VALIDATION, 400, fieldErrors = mapOf("password" to listOf("Too short"))),
                SignInError.Validation(mapOf("password" to listOf("Too short"))),
            ),
            Arguments.of(ApiError(ApiError.Code.RATE_LIMITED, 429, retryAfter = 30), SignInError.RateLimited(retryAfter = 30)),
            Arguments.of(ApiError(ApiError.Code.NOT_FOUND, 404), SignInError.ProviderUnavailable),
            Arguments.of(ApiError(null, 401), SignInError.InvalidCredentials),
            Arguments.of(ApiError(null, 502), SignInError.Unavailable),
        )
    }
}
