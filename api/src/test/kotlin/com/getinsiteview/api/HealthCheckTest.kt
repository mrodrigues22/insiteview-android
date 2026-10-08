package com.getinsiteview.api

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Generated client: GET /health/ready")
class HealthCheckTest {
    @Test
    @DisplayName("Sends GET /health/ready and decodes a ready response")
    fun `Sends GET health ready and decodes a ready response`() = test {
        val transport = RoutingTransport {
            RoutingTransport.Reply.json(
                """{"status":"Healthy","checks":[{"name":"database","status":"Healthy"},{"name":"storage","status":"Healthy"}]}""",
            )
        }
        val readiness = transport.api().readiness()

        assertTrue(readiness.isReady)
        assertEquals("Healthy", readiness.health.status)
        assertEquals(listOf("database", "storage"), readiness.health.checks.map { it.name })
        val sent = transport.requests.first()
        assertEquals("GET", sent.method)
        assertEquals("/health/ready", sent.path)
        assertEquals(APIFixtures.baseURL.host, sent.request.url.host)
        assertEquals("https", sent.request.url.scheme)
        assertEquals("health_ready", sent.operationId)
    }

    @Test
    fun `503 is reachable but not ready, with the failing check`() = test {
        val transport = RoutingTransport {
            RoutingTransport.Reply.json("""{"status":"Unhealthy","checks":[{"name":"database","status":"Unhealthy"}]}""", status = 503)
        }
        val readiness = transport.api().readiness()

        assertFalse(readiness.isReady)
        assertEquals("Unhealthy", readiness.health.status)
        assertEquals("Unhealthy", readiness.health.checks.firstOrNull()?.status)
    }

    @Test
    fun `An undocumented status throws UnexpectedStatus`() = test {
        val transport = RoutingTransport { RoutingTransport.Reply.json("{}", status = 500) }
        val error = assertFailsWith<UnexpectedStatus> { transport.api().readiness() }
        assertEquals(UnexpectedStatus(500), error)
    }
}
