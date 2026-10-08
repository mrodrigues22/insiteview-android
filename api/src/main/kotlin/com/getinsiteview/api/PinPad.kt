package com.getinsiteview.api

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/**
 * The PIN keypad's state (IOS-M2-02, master PLAN §10: 4 digits; `pin.invalid` with attempts left,
 * `pin.locked` with a retry time). JVM-tested; the Compose keypad draws it. Immutable: every change
 * returns a new pad.
 */
data class PinPad(
    val digits: String = "",
    val status: Status = Status.Entering,
) {
    sealed interface Status {
        data object Entering : Status

        /** The PIN was sent; waiting for the API. */
        data object Checking : Status

        /** Wrong PIN; `null` when the API didn't say how many tries are left. */
        data class Wrong(val attemptsLeft: Int?) : Status

        /** Too many wrong PINs: try again at [until]. */
        data class Locked(val until: Instant) : Status

        /** Anything else (offline, not found, …). */
        data class Failed(val problem: GuestProblem) : Status
    }

    /** The result of [type]: the new pad and, once the fourth digit is in, the PIN to send. */
    data class Typed(val pad: PinPad, val pin: String?)

    val isComplete: Boolean get() = digits.length == LENGTH

    fun isLocked(at: Instant): Boolean = (status as? Status.Locked)?.let { it.until.isAfter(at) } ?: false

    /** Seconds until PIN entry opens again (rounded up), `null` when not locked. */
    fun secondsUntilUnlock(at: Instant): Int? {
        val locked = status as? Status.Locked ?: return null
        if (!locked.until.isAfter(at)) return null
        val nanos = Duration.between(at, locked.until).toNanos()
        return ceil(nanos / 1e9).toInt()
    }

    /** Adds a digit. [Typed.pin] is the PIN to send once the fourth digit is in. */
    fun type(digit: Int, at: Instant = Instant.now()): Typed {
        val pad = refresh(at)
        if (digit !in 0..9 || pad.isComplete || pad.status == Status.Checking || pad.isLocked(at)) return Typed(pad, null)
        val status = if (pad.status is Status.Wrong || pad.status is Status.Failed) Status.Entering else pad.status
        val digits = pad.digits + digit
        if (digits.length < LENGTH) return Typed(PinPad(digits, status), null)
        return Typed(PinPad(digits, Status.Checking), digits)
    }

    fun deleteLast(): PinPad {
        if (status == Status.Checking || digits.isEmpty()) return this
        return copy(digits = digits.dropLast(1))
    }

    /** The API refused the PIN: clear it and show why. */
    fun failed(error: Throwable, at: Instant = Instant.now()): PinPad = when (val problem = GuestProblem.from(error)) {
        is GuestProblem.PinInvalid -> PinPad("", Status.Wrong(problem.attemptsLeft))
        is GuestProblem.PinLocked -> PinPad("", Status.Locked(at.plusSeconds((problem.retryAfter ?: 3600).toLong())))
        is GuestProblem.RateLimited -> PinPad("", Status.Locked(at.plusSeconds((problem.retryAfter ?: 3600).toLong())))
        else -> PinPad("", Status.Failed(problem))
    }

    /** The lockout ended: back to entering. */
    fun refresh(at: Instant): PinPad {
        val locked = status as? Status.Locked ?: return this
        return if (!locked.until.isAfter(at)) copy(status = Status.Entering) else this
    }

    companion object {
        const val LENGTH = 4
    }
}
