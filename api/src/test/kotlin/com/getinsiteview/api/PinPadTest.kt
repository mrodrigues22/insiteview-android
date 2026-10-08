package com.getinsiteview.api

import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PIN keypad")
class PinPadTest {
    private val now = fixtureNow

    @Test
    fun `Four digits send the PIN, delete works until then`() {
        var pad = PinPad()
        fun type(digit: Int, at: java.time.Instant = now): String? = pad.type(digit, at).also { pad = it.pad }.pin
        assertNull(type(4))
        assertNull(type(8))
        pad = pad.deleteLast()
        assertEquals("4", pad.digits)
        assertNull(type(12))
        assertEquals("4", pad.digits)
        for (digit in listOf(8, 2)) assertNull(type(digit))
        assertEquals("4821", type(1))
        assertEquals(PinPad.Status.Checking, pad.status)
        // No typing or deleting while the PIN is being checked.
        assertNull(type(5))
        pad = pad.deleteLast()
        assertEquals("4821", pad.digits)
    }

    @Test
    fun `Wrong PIN - cleared, with the attempts left, typing again starts over`() {
        var pad = PinPad()
        for (digit in listOf(1, 1, 1, 1)) pad = pad.type(digit, now).pad
        pad = pad.failed(ApiError(code = ApiError.Code.PIN_INVALID, status = 403, attemptsLeft = 2), now)
        assertTrue(pad.digits.isEmpty())
        assertEquals(PinPad.Status.Wrong(attemptsLeft = 2), pad.status)
        pad = pad.type(3, now).pad
        assertEquals(PinPad.Status.Entering, pad.status)
    }

    @Test
    fun `Locked - no typing until the retry time`() {
        var pad = PinPad()
        for (digit in listOf(1, 2, 3, 4)) pad = pad.type(digit, now).pad
        pad = pad.failed(ApiError(code = ApiError.Code.PIN_LOCKED, status = 429, retryAfter = 90), now)
        assertTrue(pad.isLocked(now))
        assertEquals(90, pad.secondsUntilUnlock(now.plusMillis(500)))
        var typed = pad.type(1, now.plusSeconds(60))
        pad = typed.pad
        assertNull(typed.pin)
        assertTrue(pad.digits.isEmpty())
        typed = pad.type(1, now.plusSeconds(90))
        pad = typed.pad
        assertNull(typed.pin)
        assertEquals("1", pad.digits)
        assertEquals(PinPad.Status.Entering, pad.status)
    }

    @Test
    fun `Other failures keep their guest problem`() {
        var pad = PinPad()
        pad = pad.failed(UnknownHostException("offline"), now)
        assertEquals(PinPad.Status.Failed(GuestProblem.Offline), pad.status)
        pad = pad.type(1, now).pad
        assertEquals(PinPad.Status.Entering, pad.status)
    }
}
