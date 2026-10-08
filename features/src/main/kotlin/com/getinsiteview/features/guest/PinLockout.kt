package com.getinsiteview.features.guest

/** A unit of the PIN lockout's countdown ("Try again in 2 minutes, 30 seconds"). */
enum class LockoutUnit { HOURS, MINUTES, SECONDS }

/**
 * The units [formatLockout] writes: the largest non-zero unit and the next one when it isn't zero
 * (at most two; the rest is dropped). Zero or less is "0 seconds".
 */
fun lockoutUnits(seconds: Int): List<Pair<LockoutUnit, Int>> {
    if (seconds <= 0) return listOf(LockoutUnit.SECONDS to 0)
    val parts = listOf(
        LockoutUnit.HOURS to seconds / 3600,
        LockoutUnit.MINUTES to (seconds % 3600) / 60,
        LockoutUnit.SECONDS to seconds % 60,
    )
    val first = parts.indexOfFirst { it.second > 0 }
    return parts.subList(first, minOf(first + 2, parts.size)).filter { it.second > 0 }
}
