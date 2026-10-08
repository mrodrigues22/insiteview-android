package com.getinsiteview.features.guest

import com.getinsiteview.api.GuestProblem
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ProblemActionTest {
    @Test
    fun `update required offers update`() {
        assertEquals(ProblemAction.UPDATE, ProblemAction.of(GuestProblem.UpdateRequired, canRetry = true))
        assertEquals(ProblemAction.UPDATE, ProblemAction.of(GuestProblem.UpdateRequired, canRetry = false))
    }

    @Test
    fun `try again only when it could help and the screen can retry`() {
        assertEquals(ProblemAction.RETRY, ProblemAction.of(GuestProblem.Offline, canRetry = true))
        assertEquals(ProblemAction.RETRY, ProblemAction.of(GuestProblem.RateLimited(30), canRetry = true))
        assertEquals(ProblemAction.NONE, ProblemAction.of(GuestProblem.Offline, canRetry = false))
        assertEquals(ProblemAction.NONE, ProblemAction.of(GuestProblem.Paused, canRetry = true))
        assertEquals(ProblemAction.NONE, ProblemAction.of(GuestProblem.LinkRevoked, canRetry = true))
    }
}
