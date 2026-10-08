package com.getinsiteview.api

import com.getinsiteview.api.account.AccountError
import com.getinsiteview.api.account.AccountService
import com.getinsiteview.api.account.AuthState
import com.getinsiteview.api.account.OAuthProvider
import com.getinsiteview.api.account.SignInError
import com.getinsiteview.api.account.SignInMethod
import com.getinsiteview.core.TermsVersion
import com.getinsiteview.core.UnitSystem
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Account service")
class AccountServiceTest {
    private class Harness(
        val api: ApiClient,
        val transport: RoutingTransport,
        val store: InMemoryTokenStore,
        val credentials: UserCredentials,
        val account: AccountService,
    )

    private fun harness(
        tokens: AuthTokens? = null,
        refresher: suspend (String) -> AuthTokens = { throw SocketTimeoutException() },
        handler: (RoutingTransport.Sent) -> RoutingTransport.Reply,
    ): Harness {
        val transport = RoutingTransport(handler)
        val store = InMemoryTokenStore(tokens)
        val credentials = UserCredentials(store, refresher = refresher)
        val api = transport.api(listOf(BearerInterceptor(credentials)))
        val account = AccountService(api, credentials, deviceName = "Pixel 9", locale = { "pt-BR" })
        return Harness(api, transport, store, credentials, account)
    }

    private val noContent = RoutingTransport.Reply.empty(204)

    private fun tokens(access: String, expiresIn: Long = 900) = AuthTokens(
        accessToken = access, accessTokenExpiresAt = Instant.now().plusSeconds(expiresIn),
        refreshToken = "refresh-1", refreshTokenExpiresAt = Instant.now().plusSeconds(86_400),
    )

    private fun RoutingTransport.Sent.string(key: String): String? =
        json()[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    @Test
    fun `Email sign-in stores the tokens and signs in, observers see each step`() = test {
        val h = harness { RoutingTransport.Reply.json(APIFixtures.auth("access-1")) }
        val phases = mutableListOf<AuthState.Phase>()
        h.account.observe { state -> synchronized(phases) { phases.add(state.phase) } }
        h.account.restore()
        val me = h.account.signIn(email = "  ana@example.com ", password = "correct horse battery")
        assertEquals("Ana", me.displayName)
        assertTrue(h.account.state.isSignedIn)
        assertEquals("refresh-2", h.store.load()?.refreshToken)
        assertEquals("access-1", h.credentials.accessToken())
        val login = h.transport.requests.first { it.operationId == "auth_login" }
        assertEquals("ana@example.com", login.string("email"))
        assertEquals("Pixel 9", login.string("deviceName"))
        assertEquals(
            listOf(
                AuthState.Phase.Restoring, AuthState.Phase.SignedOut, AuthState.Phase.SigningIn(SignInMethod.Email),
                AuthState.Phase.SignedIn,
            ),
            synchronized(phases) { phases.toList() },
        )
    }

    @Test
    fun `Wrong password - signed out with the reason`() = test {
        val h = harness { RoutingTransport.Reply.problem(401, code = "auth.invalid_credentials") }
        h.account.restore()
        val error = assertFailsWith<SignInError> { h.account.signIn("ana@example.com", "nope nope nope") }
        assertEquals(SignInError.InvalidCredentials, error)
        val state = h.account.state
        assertTrue(state.phase == AuthState.Phase.SignedOut && state.error == SignInError.InvalidCredentials)
        assertNull(h.store.load())
    }

    @Test
    fun `Register sends the name, the terms version and the language`() = test {
        val h = harness { RoutingTransport.Reply.json(APIFixtures.auth("access-1")) }
        h.account.restore()
        h.account.register("ana@example.com", "correct horse battery", displayName = " Ana ")
        val sent = h.transport.requests.first()
        assertEquals("Ana", sent.string("displayName"))
        assertEquals(TermsVersion.CURRENT, sent.string("termsVersion"))
        assertEquals("pt-BR", sent.string("locale"))
        assertEquals("auth_register", sent.operationId)
    }

    /** iOS: native Sign in with Apple. Android: Apple goes through the same web flow as Google. */
    @Test
    fun `Sign in with Apple - the web flow's start URL and the exchanged code`() = test {
        val h = harness { sent ->
            if (sent.operationId == "auth_oauth_providers") {
                RoutingTransport.Reply.json("""{"providers":["google","microsoft","apple"]}""")
            } else {
                RoutingTransport.Reply.json(APIFixtures.auth("access-1"))
            }
        }
        h.account.restore()
        assertTrue(OAuthProvider.APPLE in h.account.oauthProviders())
        var opened: URI? = null
        h.account.signIn(OAuthProvider.APPLE) { url ->
            opened = url
            URI("insiteview://auth/callback?code=apple-code")
        }
        assertEquals("/v1/auth/oauth/apple/start", opened?.path)
        assertTrue(opened?.rawQuery?.contains("terms=${TermsVersion.CURRENT}") == true)
        val exchange = h.transport.requests.first { it.operationId == "auth_oauth_exchange" }
        assertNull(exchange.authorization)
        assertEquals("apple-code", exchange.string("code"))
        assertEquals("Pixel 9", exchange.string("deviceName"))
        assertTrue(h.account.state.isSignedIn)
    }

    /** iOS: a 404 from `/apple/native`. Android: the server doesn't list Apple, so it isn't offered. */
    @Test
    fun `Apple not configured on this server - not offered`() = test {
        val h = harness { RoutingTransport.Reply.json("""{"providers":["google"]}""") }
        h.account.restore()
        assertEquals(listOf(OAuthProvider.GOOGLE), h.account.oauthProviders())
        val offline = harness { throw UnknownHostException("offline") }
        assertTrue(offline.account.oauthProviders().isEmpty())
        assertEquals(SignInError.ProviderUnavailable, SignInError.from(ApiError(ApiError.Code.NOT_FOUND, 404)))
    }

    @Test
    fun `Google - the session gets the start URL, the callback's code is exchanged`() = test {
        val h = harness { sent ->
            if (sent.operationId == "auth_oauth_providers") {
                RoutingTransport.Reply.json("""{"providers":["google","facebook"]}""")
            } else {
                RoutingTransport.Reply.json(APIFixtures.auth("access-1"))
            }
        }
        h.account.restore()
        assertEquals(listOf(OAuthProvider.GOOGLE), h.account.oauthProviders())
        var opened: URI? = null
        h.account.signIn(OAuthProvider.GOOGLE) { url ->
            opened = url
            URI("insiteview://auth/callback?code=one-time")
        }
        assertEquals("/v1/auth/oauth/google/start", opened?.path)
        val exchange = h.transport.requests.first { it.operationId == "auth_oauth_exchange" }
        assertEquals("one-time", exchange.string("code"))
        assertTrue(h.account.state.isSignedIn)
    }

    @Test
    fun `Provider errors and a closed sheet`() = test {
        val h = harness { RoutingTransport.Reply.json(APIFixtures.auth("access-1")) }
        h.account.restore()
        val inUse = assertFailsWith<SignInError> {
            h.account.signIn(OAuthProvider.GOOGLE) { URI("insiteview://auth/callback?error=email_in_use") }
        }
        assertEquals(SignInError.EmailInUse, inUse)
        val cancelled = assertFailsWith<SignInError> {
            h.account.signIn(OAuthProvider.MICROSOFT) { throw CancellationException("Custom Tab closed") }
        }
        assertEquals(SignInError.Cancelled, cancelled)
        assertNull(h.account.state.error)
        assertTrue(h.transport.requests.isEmpty(), "nothing was exchanged")
    }

    @Test
    fun `Restoring loads the profile, offline keeps the session without one`() = test {
        val h = harness(tokens = tokens("access-1")) { sent ->
            if (sent.operationId == "me_get") RoutingTransport.Reply.json(ME) else RoutingTransport.Reply.problem(500, code = "internal")
        }
        h.account.restore()
        assertEquals("ana@example.com", h.account.state.me?.email)
        assertEquals("Bearer access-1", h.transport.requests.first().authorization)

        val offline = harness(tokens = tokens("access-1")) { throw UnknownHostException("offline") }
        offline.account.restore()
        val state = offline.account.state
        assertTrue(state.isSignedIn && state.me == null)
    }

    @Test
    fun `A refresh the API refuses ends the session`() = test {
        val h = harness(
            tokens = tokens("access-1", expiresIn = 10),
            refresher = { throw ApiError(ApiError.Code.AUTH_REFRESH_REUSED, 401) },
        ) { RoutingTransport.Reply.json(ME) }
        h.account.restore()
        repeat(50) {
            if (!h.account.state.isSignedIn) return@repeat
            delay(5)
        }
        val state = h.account.state
        assertTrue(state.phase == AuthState.Phase.SignedOut && state.sessionEnded)
    }

    @Test
    @DisplayName("Units - PATCH /v1/me and the new profile")
    fun `Units - PATCH v1 me and the new profile`() = test {
        val h = harness(tokens = tokens("access-1")) { RoutingTransport.Reply.json(ME.replace("\"Metric\"", "\"Imperial\"")) }
        h.account.restore()
        val me = h.account.updateProfile(units = UnitSystem.IMPERIAL)
        assertEquals(UnitSystem.IMPERIAL, me.units)
        val patch = h.transport.requests.last()
        assertTrue(patch.method == "PATCH" && patch.path == "/v1/me")
        val body = patch.json()
        assertEquals("Imperial", patch.string("units"))
        assertTrue(body["displayName"] == null && body["locale"] == null)
    }

    @Test
    fun `Resending the confirmation email carries the user's token`() = test {
        val h = harness(tokens = tokens("access-1")) { sent ->
            if (sent.operationId == "auth_verify_email_send") RoutingTransport.Reply.empty(202) else RoutingTransport.Reply.json(ME)
        }
        h.account.restore()
        h.account.sendEmailConfirmation()
        val sent = h.transport.requests.last()
        assertTrue(sent.path == "/v1/auth/verify-email/send" && sent.authorization == "Bearer access-1")
    }

    @Test
    fun `Sign out - signed out at once, then the token family is revoked`() = test {
        val h = harness(tokens = tokens("access-1")) { sent ->
            if (sent.operationId == "auth_logout") noContent else RoutingTransport.Reply.json(ME)
        }
        h.account.restore()
        h.account.signOut()
        assertEquals(AuthState.Phase.SignedOut, h.account.state.phase)
        assertFalse(h.account.state.sessionEnded)
        assertNull(h.store.load())
        val logout = h.transport.requests.last()
        assertEquals("auth_logout", logout.operationId)
        assertEquals("refresh-1", logout.string("refreshToken"))
    }

    @Test
    fun `Deleting the account, the sole owner of a team must hand it over first`() = test {
        val blocked = harness(tokens = tokens("access-1")) { sent ->
            if (sent.operationId == "me_delete") {
                RoutingTransport.Reply.problem(409, code = "conflict", extra = ""","organizations":["01926f3a-0000-7000-8000-00000000000b"]""")
            } else {
                RoutingTransport.Reply.json(ME)
            }
        }
        blocked.account.restore()
        assertEquals(AccountError.SoleOwner, assertFailsWith<AccountError> { blocked.account.deleteAccount() })
        assertTrue(blocked.account.state.isSignedIn)

        val h = harness(tokens = tokens("access-1")) { sent ->
            if (sent.operationId == "me_delete") noContent else RoutingTransport.Reply.json(ME)
        }
        h.account.restore()
        h.account.deleteAccount()
        assertEquals(AuthState.Phase.SignedOut, h.account.state.phase)
        assertNull(h.store.load())
        assertEquals("DELETE", h.transport.requests.last().method)

        val offline = harness(tokens = tokens("access-1")) { sent ->
            if (sent.operationId == "me_delete") throw UnknownHostException("offline")
            RoutingTransport.Reply.json(ME)
        }
        offline.account.restore()
        assertEquals(AccountError.Offline, assertFailsWith<AccountError> { offline.account.deleteAccount() })
    }

    companion object {
        const val ME = """{"id":"01926f3a-0000-7000-8000-00000000000a","email":"ana@example.com","emailConfirmed":false,"displayName":"Ana",
 "locale":"pt-BR","units":"Metric","marketingEmails":true,"organizations":[{"id":"01926f3a-0000-7000-8000-00000000000b","name":"Construtora Exemplo","role":"Admin"}]}"""
    }
}
