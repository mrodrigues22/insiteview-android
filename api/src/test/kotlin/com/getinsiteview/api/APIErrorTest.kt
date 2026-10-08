package com.getinsiteview.api

import com.getinsiteview.core.BuildingCode
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("APIError from ProblemDetails")
class APIErrorTest {
    @Test
    fun `Every non-2xx response throws APIError with the code`() = test {
        val transport = RoutingTransport { RoutingTransport.Reply.problem(403, code = "building.paused") }
        val api = transport.api()
        val error = assertFailsWith<ApiError> { api.publicBuilding(BuildingCode.parse("TEST01")!!) }
        assertEquals(403, error.status)
        assertEquals(ApiError.Code.BUILDING_PAUSED, error.code)
        assertEquals("Problem", error.title)
        assertEquals(GuestProblem.Paused, GuestProblem.from(error))
    }

    @Test
    fun `Extensions - attempts left, retry after, field errors`() {
        val pin = ApiError.decode(status = 403, body = """{"code":"pin.invalid","attemptsLeft":3}""")
        assertTrue(pin.code == ApiError.Code.PIN_INVALID && pin.attemptsLeft == 3)

        val locked = ApiError.decode(status = 423, body = """{"code":"auth.locked_out","retryAfter":"900"}""")
        assertTrue(locked.code == ApiError.Code.AUTH_LOCKED_OUT && locked.retryAfter == 900)

        val limited = ApiError.decode(
            status = 429, retryAfterHeader = "30", body = """{"code":"rate_limited","title":"Too many requests."}""",
        )
        assertTrue(limited.code == ApiError.Code.RATE_LIMITED && limited.retryAfter == 30)

        val validation = ApiError.decode(
            status = 400,
            body = """{"code":"validation","errors":{"deviceId":["The field deviceId must be at least 8 characters."]}}""",
        )
        assertEquals(1, validation.fieldErrors["deviceId"]?.size)
    }

    @Test
    fun `A body that isn't ProblemDetails still gives the status`() {
        val error = ApiError.decode(status = 502, body = "<html>Bad gateway</html>")
        assertEquals(ApiError(code = null, status = 502), error)
        assertEquals(GuestProblem.Unavailable, GuestProblem.from(error))
    }

    @Test
    fun `Unknown codes keep their raw value`() {
        val error = ApiError.decode(status = 409, body = """{"code":"building.archived"}""")
        assertEquals("building.archived", error.code?.raw)
        assertEquals(error.code, ApiError(code = ApiError.Code("building.archived")).code)
    }

    /** iOS unwraps the generated client's `ClientError`; here errors arrive unwrapped. */
    @Test
    fun `Unwraps the generated client's ClientError`() {
        val underlying = ApiError(code = ApiError.Code.NOT_FOUND, status = 404)
        val thrown: Throwable = underlying
        assertEquals(underlying, ApiError.from(thrown))
        assertNull(ApiError.from(SocketTimeoutException()))
    }
}
