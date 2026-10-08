package com.getinsiteview.api

import com.getinsiteview.core.BuildingCode
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Middlewares")
class MiddlewareTest {
    private val code = BuildingCode.parse("TEST01")!!

    @Test
    fun `X-Device-Id and Accept-Language on every call, generated operations included`() = test {
        val transport = RoutingTransport { sent ->
            if (sent.operationId == "health_ready") {
                RoutingTransport.Reply.json("""{"status":"Healthy","checks":[]}""")
            } else {
                RoutingTransport.Reply.json(APIFixtures.publicBuilding)
            }
        }
        val api = transport.api(
            listOf(
                DeviceIdInterceptor("3f1c2d4e-0000-4000-8000-000000000001"),
                AcceptLanguageInterceptor { listOf("pt-BR", "en-US") },
            ),
        )
        api.publicBuilding(code)
        api.readiness()
        for (sent in transport.requests) {
            assertEquals("3f1c2d4e-0000-4000-8000-000000000001", sent.header(ApiHeaders.DEVICE_ID))
            assertEquals("pt-BR, en-US;q=0.9", sent.header("Accept-Language"))
        }
        assertEquals(listOf("public_building", "health_ready"), transport.requests.map { it.operationId })
    }

    @Test
    fun `X-Client - android and X-Client-Version on every call, no version, no version header`() = test {
        val transport = RoutingTransport { sent ->
            if (sent.operationId == "health_ready") {
                RoutingTransport.Reply.json("""{"status":"Healthy","checks":[]}""")
            } else {
                RoutingTransport.Reply.json(APIFixtures.publicBuilding)
            }
        }
        val api = transport.api(listOf(DeviceIdInterceptor("d1"), ClientVersionInterceptor("1.2.0")))
        api.publicBuilding(code)
        api.readiness()
        assertEquals(2, transport.requests.size)
        for (sent in transport.requests) {
            assertEquals("android", sent.header(ApiHeaders.CLIENT))
            assertEquals("1.2.0", sent.header(ApiHeaders.CLIENT_VERSION))
        }

        val unversioned = RoutingTransport { RoutingTransport.Reply.json(APIFixtures.publicBuilding) }
        unversioned.api(listOf(ClientVersionInterceptor(" "))).publicBuilding(code)
        assertEquals("android", unversioned.requests.first().header(ApiHeaders.CLIENT))
        assertNull(unversioned.requests.first().header(ApiHeaders.CLIENT_VERSION))
    }

    @Test
    fun `426 client-outdated calls the client's hook, on derived clients and send(_) too`() = test {
        val transport = RoutingTransport { sent ->
            if (sent.operationId == "visit_element") {
                RoutingTransport.Reply.json(APIFixtures.element)
            } else {
                RoutingTransport.Reply.problem(426, code = "client.outdated")
            }
        }
        val calls = CallCounter()
        val api = transport.api(listOf(ClientVersionInterceptor("0.9.0")), onClientOutdated = { calls.increment() })
        assertFailsWith<ApiError> { api.publicBuilding(code) }
        assertEquals(1, calls.count)
        val error = assertFailsWith<ApiError> { api.publicBuilding(code) }
        assertEquals(ApiError.Code.CLIENT_OUTDATED, error.code)
        assertEquals(GuestProblem.UpdateRequired, GuestProblem.from(error))
        assertEquals(2, calls.count)

        val visit = api.withVisitToken(VisitTokenStore()).adding(AcceptLanguageInterceptor { listOf("en") })
        visit.visitElement("e1")
        assertEquals(2, calls.count)
        assertFailsWith<ApiError> { visit.publicBuilding(code) }
        assertEquals(3, calls.count)

        // The catalog's conditional GET (hand-written on iOS, outside the generated client).
        assertFailsWith<ApiError> { api.catalog(ifNoneMatch = null) }
        assertEquals(4, calls.count)

        // Other errors don't.
        val other = RoutingTransport { RoutingTransport.Reply.problem(404, code = "not_found") }
        val quiet = other.api(onClientOutdated = { calls.increment() })
        assertFailsWith<ApiError> { quiet.publicBuilding(code) }
        assertEquals(4, calls.count)
    }

    @Test
    fun `Accept-Language values`() {
        assertEquals("es-MX", AcceptLanguageInterceptor.headerValue(listOf("es_MX")))
        assertEquals(
            "pt-BR, en;q=0.9, fr;q=0.8, de;q=0.7, it;q=0.6, nl;q=0.5",
            AcceptLanguageInterceptor.headerValue(listOf("pt-BR", "PT-br", "en", "fr", "de", "it", "nl", "sv")),
        )
        assertNull(AcceptLanguageInterceptor.headerValue(listOf("", "x y", "en\r\nX-Evil: 1")))
        assertNull(AcceptLanguageInterceptor.headerValue(emptyList()))
    }

    @Test
    fun `Which token each operation takes`() {
        assertEquals(AuthorizationKind.VISIT, AuthorizationKind.forOperation("visit_manifest"))
        assertEquals(AuthorizationKind.VISIT, AuthorizationKind.forOperation("visit_element"))
        assertEquals(AuthorizationKind.USER, AuthorizationKind.forOperation("public_visit_by_code"))
        assertEquals(AuthorizationKind.USER, AuthorizationKind.forOperation("public_building"))
        assertEquals(AuthorizationKind.USER, AuthorizationKind.forOperation("model_manifest"))
        assertEquals(AuthorizationKind.USER, AuthorizationKind.forOperation("me_get"))
        assertEquals(AuthorizationKind.VISIT, AuthorizationKind.forOperation("visit_unlock"))
        assertEquals(AuthorizationKind.VISIT_OR_USER, AuthorizationKind.forOperation("events_create"))
        assertEquals(AuthorizationKind.NONE, AuthorizationKind.forOperation("auth_refresh"))
        assertEquals(AuthorizationKind.NONE, AuthorizationKind.forOperation("auth_login"))
        assertEquals(AuthorizationKind.NONE, AuthorizationKind.forOperation("auth_apple_native"))
        assertEquals(AuthorizationKind.USER, AuthorizationKind.forOperation("auth_verify_email_send"))
        assertEquals(AuthorizationKind.USER, AuthorizationKind.forOperation("me_buildings"))
        assertEquals(AuthorizationKind.USER, AuthorizationKind.forOperation("documents_list"))
        assertEquals(AuthorizationKind.NONE, AuthorizationKind.forOperation("catalog_get"))
        assertEquals(AuthorizationKind.NONE, AuthorizationKind.forOperation("health_ready"))
        assertEquals(AuthorizationKind.NONE, AuthorizationKind.forOperation("plates_qr_png"))
    }

    @Test
    @DisplayName("The visit token goes only to /v1/visit/…")
    fun `The visit token goes only to v1 visit`() = test {
        val transport = RoutingTransport { sent ->
            RoutingTransport.Reply.json(if (sent.operationId == "visit_element") APIFixtures.element else APIFixtures.publicBuilding)
        }
        val store = VisitTokenStore()
        store.set("visit-1", Instant.now().plusSeconds(3600))
        val api = transport.api(listOf(VisitTokenInterceptor(store)))
        api.visitElement("e1")
        api.publicBuilding(code)
        assertEquals("Bearer visit-1", transport.requests[0].authorization)
        assertNull(transport.requests[1].authorization)
    }

    @Test
    fun `Visit tokens expire a minute early`() {
        val store = VisitTokenStore()
        val now = Instant.ofEpochSecond(1_000_000)
        store.set("t", now.plusSeconds(120))
        assertEquals("t", store.validToken(now))
        assertNull(store.validToken(now.plusSeconds(61)))
        store.clear()
        assertNull(store.token)
    }

    private fun tokens(access: String, refresh: String = "refresh-1") = AuthTokens(
        accessToken = access, accessTokenExpiresAt = Instant.now().plusSeconds(900),
        refreshToken = refresh, refreshTokenExpiresAt = Instant.now().plusSeconds(86_400),
    )

    @Test
    fun `Bearer token on user operations only`() = test {
        val transport = RoutingTransport { sent ->
            when (sent.operationId) {
                "visit_element" -> RoutingTransport.Reply.json(APIFixtures.element)
                "catalog_get" -> RoutingTransport.Reply.json("""{"version":1,"systems":[],"kinds":[],"properties":[]}""")
                "public_visit_by_code" -> RoutingTransport.Reply.json(APIFixtures.visit())
                else -> RoutingTransport.Reply.json(APIFixtures.publicBuilding)
            }
        }
        val credentials = UserCredentials(InMemoryTokenStore(tokens("access-1"))) { throw SocketTimeoutException() }
        val api = transport.api(listOf(BearerInterceptor(credentials)))
        api.publicBuilding(code)
        api.startVisit(code, plate = null, deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP)
        api.visitElement("e1")
        api.catalog()
        assertEquals(listOf("Bearer access-1", "Bearer access-1", null, null), transport.requests.map { it.authorization })
    }

    @Test
    fun `Signed out - no Authorization header`() = test {
        val transport = RoutingTransport { RoutingTransport.Reply.json(APIFixtures.publicBuilding) }
        val credentials = UserCredentials(InMemoryTokenStore()) { throw SocketTimeoutException() }
        transport.api(listOf(BearerInterceptor(credentials))).publicBuilding(code)
        assertNull(transport.requests.first().authorization)
    }

    @Test
    fun `A 401 refreshes once for concurrent calls and retries with the new token`() = test {
        val transport = RoutingTransport { sent ->
            if (sent.authorization == "Bearer access-2") {
                RoutingTransport.Reply.json(APIFixtures.publicBuilding)
            } else {
                RoutingTransport.Reply.problem(401, code = "internal")
            }
        }
        val refreshCalls = mutableListOf<String>()
        val credentials = UserCredentials(InMemoryTokenStore(tokens("access-1"))) { refreshToken ->
            synchronized(refreshCalls) { refreshCalls.add(refreshToken) }
            delay(20)
            AuthTokens(
                accessToken = "access-2", accessTokenExpiresAt = Instant.now().plusSeconds(900),
                refreshToken = "refresh-2", refreshTokenExpiresAt = Instant.now().plusSeconds(86_400),
            )
        }
        val api = transport.api(listOf(BearerInterceptor(credentials)))
        val buildings = (0 until 4).map { async { api.publicBuilding(code) } }.awaitAll()
        for (building in buildings) assertEquals("TEST01", building.code)
        assertEquals(listOf("refresh-1"), synchronized(refreshCalls) { refreshCalls.toList() })
        assertEquals("access-2", credentials.accessToken())
        assertEquals(4, transport.requests.count { it.authorization == "Bearer access-2" })
    }

    @Test
    fun `A rejected refresh signs the user out, a network failure keeps the session`() = test {
        val transport = RoutingTransport { RoutingTransport.Reply.problem(401, code = "internal") }

        var signedOut = false
        val store = InMemoryTokenStore(tokens("access-1"))
        val rejected = UserCredentials(store) { throw ApiError(code = ApiError.Code.AUTH_REFRESH_REUSED, status = 401) }
        rejected.observe { signedIn -> signedOut = !signedIn }
        val api = transport.api(listOf(BearerInterceptor(rejected)))
        assertFailsWith<ApiError> { api.me() }
        assertFalse(rejected.isSignedIn())
        assertNull(store.load())
        assertTrue(signedOut)

        val offline = UserCredentials(InMemoryTokenStore(tokens("access-1"))) { throw UnknownHostException("offline") }
        val offlineAPI = transport.api(listOf(BearerInterceptor(offline)))
        assertFailsWith<ApiError> { offlineAPI.me() }
        assertEquals("access-1", offline.accessToken())
    }

    @Test
    fun `Sign in and out through the credentials`() = test {
        val store = InMemoryTokenStore()
        val credentials = UserCredentials(store) { throw SocketTimeoutException() }
        assertFalse(credentials.isSignedIn())
        credentials.signIn(tokens("access-9"))
        assertEquals("access-9", credentials.accessToken())
        assertEquals("access-9", store.load()?.accessToken)
        credentials.signOut()
        assertNull(credentials.accessToken())
    }
}
