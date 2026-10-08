package com.getinsiteview.api

import com.getinsiteview.core.AnalyticsEvent
import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.DeepLink
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

@DisplayName("Building connection")
class BuildingConnectionTest {
    private val code = BuildingCode.parse("TEST01")!!

    /** Before the fixture visits expire (2026-10-01T23:00Z). */
    private val now: () -> Instant = { fixtureNow }

    private val farFuture: Instant = Instant.parse("4000-01-01T00:00:00Z")

    private fun RoutingTransport.Sent.pin(): String? =
        jsonOrNull()?.get("pin")?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    @Test
    fun `QR visit - start once, then the manifest and elements carry the visit token`() = test {
        val manifestJSON = APIFixtures.string("manifest")
        val transport = RoutingTransport { sent ->
            when (sent.operationId) {
                "public_visit_by_code" -> RoutingTransport.Reply.json(APIFixtures.visit(token = "visit-1"))
                "visit_manifest" -> RoutingTransport.Reply.json(manifestJSON)
                "visit_element" -> RoutingTransport.Reply.json(APIFixtures.element)
                else -> RoutingTransport.Reply.problem(404, code = "not_found")
            }
        }
        val api = transport.api(listOf(DeviceIdInterceptor("device-1234")))
        val connection = BuildingConnection(
            BuildingAccess.of(DeepLink.Building(code, plate = 2)), api, deviceId = "device-1234",
            platform = VisitPlatform.ANDROID_APP, now = now,
        )

        val visit = assertNotNull(connection.start())
        assertEquals("Test room", visit.building.name)
        val manifest = connection.manifest()
        assertEquals("TEST01", manifest.building.code)
        val element = connection.element("e22dff25b5a264301813afd2f07c0ff68")
        assertEquals("K-01", element.tag)

        assertEquals(listOf("public_visit_by_code", "visit_manifest", "visit_element"), transport.requests.map { it.operationId })
        assertNull(transport.requests[0].authorization)
        assertEquals(2, transport.requests[0].json()["plate"]?.jsonPrimitive?.int)
        assertEquals("Bearer visit-1", transport.requests[1].authorization)
        assertEquals("Bearer visit-1", transport.requests[2].authorization)
        assertTrue(transport.requests.all { it.header(ApiHeaders.DEVICE_ID) == "device-1234" })
    }

    @Test
    fun `Concurrent first calls share one visit`() = test {
        val manifestJSON = APIFixtures.string("manifest")
        val transport = RoutingTransport { sent ->
            RoutingTransport.Reply.json(if (sent.operationId == "public_visit_by_code") APIFixtures.visit(token = "visit-1") else manifestJSON)
        }
        val connection = BuildingConnection(
            BuildingAccess.Code(code, plate = null), transport.api(), deviceId = "device-1234",
            platform = VisitPlatform.ANDROID_APP, now = now,
        )
        (0 until 3).map { async { connection.manifest() } }.awaitAll()
        assertEquals(1, transport.requests.count { it.operationId == "public_visit_by_code" })
        assertTrue(transport.requests.filter { it.operationId == "visit_manifest" }.all { it.authorization == "Bearer visit-1" })
    }

    @Test
    fun `An expired visit token starts a new visit and retries`() = test {
        val manifestJSON = APIFixtures.string("manifest")
        val visits = AtomicInteger(0)
        val transport = RoutingTransport { sent ->
            when (sent.operationId) {
                "public_visit_by_code" -> RoutingTransport.Reply.json(APIFixtures.visit(token = "visit-${visits.incrementAndGet()}"))
                // The server has already expired the first token.
                else -> if (sent.authorization == "Bearer visit-2") {
                    RoutingTransport.Reply.json(manifestJSON)
                } else {
                    RoutingTransport.Reply.problem(401, code = "internal")
                }
            }
        }
        val connection = BuildingConnection(
            BuildingAccess.Code(code, plate = null), transport.api(), deviceId = "device-1234",
            platform = VisitPlatform.ANDROID_APP, now = now,
        )
        connection.start()
        connection.manifest()
        assertEquals(
            listOf("public_visit_by_code", "visit_manifest", "public_visit_by_code", "visit_manifest"),
            transport.requests.map { it.operationId },
        )
    }

    @Test
    fun `A token past its expiry isn't sent - a new visit starts first`() = test {
        val manifestJSON = APIFixtures.string("manifest")
        val transport = RoutingTransport { sent ->
            if (sent.operationId == "public_visit_by_code") {
                RoutingTransport.Reply.json(APIFixtures.visit(expiresAt = "2026-10-01T12:15:30+00:00"))
            } else {
                RoutingTransport.Reply.json(manifestJSON)
            }
        }
        val connection = BuildingConnection(
            BuildingAccess.Code(code, plate = null), transport.api(), deviceId = "device-1234",
            platform = VisitPlatform.ANDROID_APP, now = now,
        )
        connection.start()
        connection.manifest()
        assertEquals(listOf("public_visit_by_code", "public_visit_by_code", "visit_manifest"), transport.requests.map { it.operationId })
    }

    @Test
    fun `Link visits and their 410 states`() = test {
        val transport = RoutingTransport { sent ->
            if (sent.path.contains("/revoked/")) {
                RoutingTransport.Reply.problem(410, code = "link.revoked")
            } else {
                RoutingTransport.Reply.json(APIFixtures.visit())
            }
        }
        val api = transport.api()
        val ok = BuildingConnection(BuildingAccess.of(DeepLink.AccessLink("tok")), api, "device-1234", VisitPlatform.ANDROID_APP, now)
        ok.start()
        assertEquals("/v1/public/links/tok/visits", transport.requests.last().path)

        val revoked = BuildingConnection(BuildingAccess.Link("revoked"), api, "device-1234", VisitPlatform.ANDROID_APP, now)
        val error = assertFailsWith<ApiError> { revoked.start() }
        assertEquals(GuestProblem.LinkRevoked, GuestProblem.from(error))
    }

    @Test
    fun `PIN - a locked visit unlocks, and a new visit after expiry sends the PIN`() = test {
        val manifestJSON = APIFixtures.string("manifest")
        val visits = AtomicInteger(0)
        val transport = RoutingTransport { sent ->
            when (sent.operationId) {
                "public_visit_by_code" -> {
                    val pin = sent.pin()
                    RoutingTransport.Reply.json(
                        APIFixtures.visit(
                            token = "visit-${visits.incrementAndGet()}", systems = if (pin == null) "[]" else "null", pinLocked = pin == null,
                        ),
                    )
                }
                "visit_unlock" -> when {
                    sent.authorization != "Bearer visit-1" -> RoutingTransport.Reply.problem(401, code = "auth.required")
                    sent.pin() == "4821" -> RoutingTransport.Reply.json(APIFixtures.visit(token = "unlocked-1"))
                    else -> RoutingTransport.Reply.problem(403, code = "pin.invalid", extra = ""","attemptsLeft":4""")
                }
                // The unlocked token has expired by the time of this call.
                else -> if (sent.authorization == "Bearer unlocked-1") {
                    RoutingTransport.Reply.problem(401, code = "auth.required")
                } else {
                    RoutingTransport.Reply.json(manifestJSON)
                }
            }
        }
        val connection = BuildingConnection(
            BuildingAccess.Code(code, plate = null), transport.api(), deviceId = "device-1234",
            platform = VisitPlatform.ANDROID_APP, now = now,
        )
        val locked = assertNotNull(connection.start())
        assertTrue(locked.pinLocked)

        val error = assertFailsWith<ApiError> { connection.unlock("1111") }
        assertEquals(GuestProblem.PinInvalid(attemptsLeft = 4), GuestProblem.from(error))
        assertEquals(true, connection.visit?.pinLocked)

        val unlocked = connection.unlock("4821")
        assertTrue(!unlocked.pinLocked && unlocked.systems == null)
        assertEquals("unlocked-1", connection.visit?.visitToken)

        // auth.required → a new visit, with the PIN this time, so it isn't locked again.
        connection.manifest()
        val restart = transport.requests.last { it.operationId == "public_visit_by_code" }
        assertEquals("4821", restart.pin())
        assertEquals(false, connection.visit?.pinLocked)
        assertEquals("Bearer visit-2", transport.requests.last().authorization)
    }

    @Test
    fun `A remembered PIN that stopped working - the new visit starts locked instead of failing`() = test {
        val manifestJSON = APIFixtures.string("manifest")
        val transport = RoutingTransport { sent ->
            when (sent.operationId) {
                "public_visit_by_code" -> if (sent.pin() == null) {
                    RoutingTransport.Reply.json(APIFixtures.visit(token = "locked", systems = "[]", pinLocked = true))
                } else {
                    RoutingTransport.Reply.problem(403, code = "pin.invalid", extra = ""","attemptsLeft":4""")
                }
                else -> RoutingTransport.Reply.json(manifestJSON)
            }
        }
        val connection = BuildingConnection(
            BuildingAccess.Code(code, plate = null, pin = "4821"), transport.api(), deviceId = "device-1234",
            platform = VisitPlatform.ANDROID_APP, now = now,
        )
        val visit = assertNotNull(connection.start())
        assertTrue(visit.pinLocked)
        assertEquals(listOf("public_visit_by_code", "public_visit_by_code"), transport.requests.map { it.operationId })
        assertNull(transport.requests[1].pin())
    }

    @ParameterizedTest
    @MethodSource("accessEndings")
    fun `Access ending mid-session - every visit call can say so, and it maps to the guest states`(
        code: String,
        status: Int,
        expected: GuestProblem,
    ) = test {
        val transport = RoutingTransport { sent ->
            if (sent.operationId == "public_visit_by_link") {
                RoutingTransport.Reply.json(APIFixtures.visit())
            } else {
                RoutingTransport.Reply.problem(status, code = code)
            }
        }
        val connection = BuildingConnection(
            BuildingAccess.Link("tok"), transport.api(), deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP, now = now,
        )
        connection.start()
        val calls: List<suspend () -> Unit> = listOf(
            { connection.manifest() },
            { connection.element("e1") },
            { connection.documents() },
            { connection.search("outlet") },
        )
        for (call in calls) {
            try {
                call()
                fail("Expected $code")
            } catch (error: ApiError) {
                assertEquals(expected, GuestProblem.from(error))
                assertTrue(GuestProblem.from(error).endsAccess)
            }
        }
        // None of them started a new visit: only an expired token (401) does that.
        assertEquals(1, transport.requests.count { it.operationId == "public_visit_by_link" })
        assertTrue(!GuestProblem.PinInvalid(attemptsLeft = 1).endsAccess && !GuestProblem.Offline.endsAccess)
    }

    @Test
    fun `pin-required (401) is a PIN problem, not an expired visit`() = test {
        val transport = RoutingTransport { sent ->
            if (sent.operationId == "visit_unlock") {
                RoutingTransport.Reply.problem(401, code = "pin.required")
            } else {
                RoutingTransport.Reply.json(APIFixtures.visit(pinLocked = true))
            }
        }
        val connection = BuildingConnection(
            BuildingAccess.Code(code, plate = null), transport.api(), deviceId = "device-1234",
            platform = VisitPlatform.ANDROID_APP, now = now,
        )
        connection.start()
        assertFailsWith<ApiError> { connection.unlock("") }
        assertEquals(listOf("public_visit_by_code", "visit_unlock"), transport.requests.map { it.operationId })
    }

    @Test
    fun `Guest analytics go with the visit token even when signed in, members send their own`() = test {
        val transport = RoutingTransport { sent ->
            if (sent.operationId == "events_create") {
                RoutingTransport.Reply.json("""{"accepted":1}""", status = 202)
            } else {
                RoutingTransport.Reply.json(APIFixtures.visit(token = "visit-1"))
            }
        }
        val credentials = UserCredentials(
            InMemoryTokenStore(AuthTokens("access-1", farFuture, "r", farFuture)),
        ) { throw SocketTimeoutException() }
        val api = transport.api(listOf(BearerInterceptor(credentials)))
        val connection = BuildingConnection(
            BuildingAccess.Code(code, plate = 1), api, deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP, now = now,
        )
        assertNull(connection.analyticsAudience())
        connection.start()
        val audience = assertIs<AnalyticsAudience.Visit>(connection.analyticsAudience())
        assertEquals("visit-1", audience.token)
        // The signed-in user's token went with the visit request, for their own scope.
        assertEquals("Bearer access-1", transport.requests.first().authorization)

        val uploader = AnalyticsUploader(api, storage = null, now = now)
        uploader.track(AnalyticsEvent.arOpened(now()), audience)
        uploader.flush()
        assertEquals("events_create", transport.requests.last().operationId)
        assertEquals("Bearer visit-1", transport.requests.last().authorization)

        val building = UUID.fromString("01926F3A-0000-7000-8000-000000000001")
        uploader.track(AnalyticsEvent.view3DOpened(now()), AnalyticsAudience.Member(building))
        uploader.flush()
        val member = transport.requests.last()
        assertEquals("Bearer access-1", member.authorization)
        assertEquals("01926f3a-0000-7000-8000-000000000001", member.json()["buildingId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Members use the model endpoints with their own token`() = test {
        val manifestJSON = APIFixtures.string("manifest")
        val transport = RoutingTransport { sent ->
            RoutingTransport.Reply.json(if (sent.operationId == "model_manifest") manifestJSON else APIFixtures.element)
        }
        val credentials = UserCredentials(
            InMemoryTokenStore(AuthTokens("access-1", farFuture, "r", farFuture)),
        ) { throw SocketTimeoutException() }
        val api = transport.api(listOf(BearerInterceptor(credentials)))
        val building = UUID.fromString("01926F3A-0000-7000-8000-000000000001")
        val connection = BuildingConnection(
            BuildingAccess.Member(building), api, deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP,
        )
        assertNull(connection.start())
        connection.manifest()
        connection.element("e1")
        assertEquals(listOf("model_manifest", "model_element"), transport.requests.map { it.operationId })
        assertTrue(transport.requests.all { it.authorization == "Bearer access-1" })
        assertFalse(transport.requests.isEmpty())
    }

    companion object {
        @JvmStatic
        fun accessEndings(): Stream<Arguments> = Stream.of(
            Arguments.of("link.revoked", 410, GuestProblem.LinkRevoked),
            Arguments.of("link.expired", 410, GuestProblem.LinkExpired),
            Arguments.of("building.paused", 403, GuestProblem.Paused),
            Arguments.of("building.not_live", 403, GuestProblem.NotLive),
            Arguments.of("not_found", 404, GuestProblem.NotFound),
        )
    }
}
