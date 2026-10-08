package com.getinsiteview.features.guest

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class PinLockoutTest {
    @Test
    fun `at most two units, largest first`() {
        assertEquals(listOf(LockoutUnit.SECONDS to 45), lockoutUnits(45))
        assertEquals(listOf(LockoutUnit.MINUTES to 2, LockoutUnit.SECONDS to 30), lockoutUnits(150))
        assertEquals(listOf(LockoutUnit.MINUTES to 59, LockoutUnit.SECONDS to 59), lockoutUnits(3599))
        assertEquals(listOf(LockoutUnit.HOURS to 1), lockoutUnits(3600))
        assertEquals(listOf(LockoutUnit.HOURS to 1, LockoutUnit.MINUTES to 1), lockoutUnits(3661))
        assertEquals(listOf(LockoutUnit.HOURS to 1), lockoutUnits(3601))
    }

    @Test
    fun `nothing left is zero seconds`() {
        assertEquals(listOf(LockoutUnit.SECONDS to 0), lockoutUnits(0))
        assertEquals(listOf(LockoutUnit.SECONDS to 0), lockoutUnits(-3))
    }
}
