package com.getinsiteview.android

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The scanner's repeat filter (iOS `DataScanner.Coordinator`): the same code stays in view for a
 * while, so a payload is handled at most once every [window]. A different payload is handled at
 * once. Pure, so it's tested on the JVM.
 *
 * @param now a monotonic clock.
 */
class ScanDebouncer(
    private val window: Duration = 2.seconds,
    private val now: () -> Duration,
) {
    private var lastPayload: String? = null
    private var lastSeen: Duration? = null

    /** Whether to handle [payload] now; records it when so. */
    fun shouldHandle(payload: String): Boolean {
        val time = now()
        val seen = lastSeen
        if (payload == lastPayload && seen != null && time - seen < window) return false
        lastPayload = payload
        lastSeen = time
        return true
    }
}
