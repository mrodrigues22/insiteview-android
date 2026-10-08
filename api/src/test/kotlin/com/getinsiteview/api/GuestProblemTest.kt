package com.getinsiteview.api

import com.getinsiteview.modelkit.Manifest
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

@DisplayName("Guest problem states")
class GuestProblemTest {
    @ParameterizedTest
    @MethodSource("mappings")
    fun `Codes map to state screens`(code: String, status: Int, expected: GuestProblem) {
        assertEquals(expected, GuestProblem.from(ApiError(code = ApiError.Code(code), status = status)))
    }

    @Test
    fun `PIN and rate-limit details carry over`() {
        assertEquals(
            GuestProblem.PinInvalid(attemptsLeft = 2),
            GuestProblem.from(ApiError(code = ApiError.Code.PIN_INVALID, status = 403, attemptsLeft = 2)),
        )
        assertEquals(
            GuestProblem.PinLocked(retryAfter = 3600),
            GuestProblem.from(ApiError(code = ApiError.Code.PIN_LOCKED, status = 429, retryAfter = 3600)),
        )
        assertEquals(
            GuestProblem.RateLimited(retryAfter = 5),
            GuestProblem.from(ApiError(code = ApiError.Code.RATE_LIMITED, status = 429, retryAfter = 5)),
        )
    }

    @Test
    fun `Network errors and building statuses`() {
        // iOS: URLError(.notConnectedToInternet) and URLError(.badServerResponse).
        assertEquals(GuestProblem.Offline, GuestProblem.from(UnknownHostException("api.staging.getinsiteview.com")))
        assertEquals(GuestProblem.Unavailable, GuestProblem.from(ProtocolException("Unexpected status line")))
        assertEquals(GuestProblem.Unavailable, GuestProblem.from(CancellationException()))
        assertEquals(GuestProblem.NotLive, GuestProblem.from(PublicBuildingStatus.NOT_LIVE))
        assertEquals(GuestProblem.Paused, GuestProblem.from(PublicBuildingStatus.PAUSED))
        assertNull(GuestProblem.from(PublicBuildingStatus.LIVE))
        assertNull(GuestProblem.from(PublicBuildingStatus.EXPIRED))
        assertTrue(GuestProblem.Offline.isRetryable && !GuestProblem.NotFound.isRetryable)
    }

    @Test
    fun `An outdated app or a newer manifest schema - update required, which ends access`() {
        assertEquals(GuestProblem.UpdateRequired, GuestProblem.from(ApiError(code = ApiError.Code.CLIENT_OUTDATED, status = 426)))
        assertEquals(GuestProblem.UpdateRequired, GuestProblem.from(Manifest.DecodingProblem.UnsupportedSchema(2)))
        assertEquals(GuestProblem.Unavailable, GuestProblem.from(Manifest.DecodingProblem.InvalidField("versionId")))
        assertTrue(GuestProblem.UpdateRequired.endsAccess)
        assertFalse(GuestProblem.UpdateRequired.isRetryable)
    }

    companion object {
        @JvmStatic
        fun mappings(): Stream<Arguments> = Stream.of(
            Arguments.of("not_found", 404, GuestProblem.NotFound),
            Arguments.of("building.not_live", 403, GuestProblem.NotLive),
            Arguments.of("version.not_ready", 409, GuestProblem.NotLive),
            Arguments.of("building.paused", 403, GuestProblem.Paused),
            Arguments.of("link.expired", 410, GuestProblem.LinkExpired),
            Arguments.of("link.revoked", 410, GuestProblem.LinkRevoked),
            Arguments.of("pin.required", 401, GuestProblem.PinRequired),
            Arguments.of("client.outdated", 426, GuestProblem.UpdateRequired),
            Arguments.of("something.new", 426, GuestProblem.UpdateRequired),
            Arguments.of("internal", 500, GuestProblem.Unavailable),
            Arguments.of("something.new", 404, GuestProblem.NotFound),
            Arguments.of("something.new", 410, GuestProblem.LinkExpired),
        )
    }
}
