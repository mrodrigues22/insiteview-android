package com.getinsiteview.api

import com.getinsiteview.core.BuildingCode
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("User credentials")
class CredentialsTest {
    @Test
    fun `The access token is refreshed shortly before it expires, once for concurrent calls`() = test {
        val now = Instant.ofEpochSecond(1_000_000)
        val calls = AtomicInteger(0)
        val store = InMemoryTokenStore(
            AuthTokens("old", now.plusSeconds(30), "refresh-1", now.plusSeconds(86_400)),
        )
        val credentials = UserCredentials(store, now = { now }) { refreshToken ->
            assertEquals("refresh-1", refreshToken)
            calls.incrementAndGet()
            delay(10)
            AuthTokens("new", now.plusSeconds(900), "refresh-2", now.plusSeconds(86_400))
        }
        val first = async { credentials.accessToken() }
        val second = async { credentials.accessToken() }
        assertEquals(listOf<String?>("new", "new"), listOf(first.await(), second.await()))
        assertEquals(1, calls.get())
        assertEquals("refresh-2", store.load()?.refreshToken)
        assertEquals("new", credentials.accessToken())
        assertEquals(1, calls.get(), "fresh tokens aren't refreshed again")
    }

    @Test
    fun `Offline near expiry - the old token, the session kept`() = test {
        val now = Instant.ofEpochSecond(1_000_000)
        val credentials = UserCredentials(
            InMemoryTokenStore(AuthTokens("old", now.plusSeconds(10), "r", now.plusSeconds(86_400))),
            now = { now },
        ) { throw UnknownHostException("offline") }
        assertEquals("old", credentials.accessToken())
        assertTrue(credentials.isSignedIn())
    }

    @Test
    fun `A visit request goes with a fresh token, so members get their own scope`() = test {
        val transport = RoutingTransport { RoutingTransport.Reply.json(APIFixtures.visit()) }
        val credentials = UserCredentials(
            InMemoryTokenStore(AuthTokens("expired", Instant.now().minusSeconds(60), "r", Instant.now().plusSeconds(86_400))),
        ) {
            AuthTokens("fresh", Instant.now().plusSeconds(900), "r2", Instant.now().plusSeconds(86_400))
        }
        val api = transport.api(listOf(BearerInterceptor(credentials)))
        api.startVisit(BuildingCode.parse("TEST01")!!, plate = null, deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP)
        assertEquals(listOf<String?>("Bearer fresh"), transport.requests.map { it.authorization })
    }
}
