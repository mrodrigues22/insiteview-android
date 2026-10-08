package com.getinsiteview.features.guest

import com.getinsiteview.api.GuestProblem
import com.getinsiteview.features.building.BuildingSession
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class GuestFlowRulesTest {
    @Test
    fun `the summary's problem comes first`() {
        val problem = GuestFlowRules.problem(
            GuestFlowModel.State.Problem(GuestProblem.Paused), GuestProblem.LinkRevoked,
            BuildingSession.Phase.Failed(GuestProblem.Offline), isArchitectureReady = false,
        )
        assertEquals(GuestProblem.Paused, problem)
    }

    @Test
    fun `access ending replaces the landing`() {
        val problem = GuestFlowRules.problem(
            GuestFlowModel.State.Landing, GuestProblem.LinkRevoked, BuildingSession.Phase.Loading, isArchitectureReady = true,
        )
        assertEquals(GuestProblem.LinkRevoked, problem)
    }

    @Test
    fun `a failed load shows only before the architecture is there`() {
        val failed = BuildingSession.Phase.Failed(GuestProblem.Offline)
        assertEquals(GuestProblem.Offline, GuestFlowRules.problem(GuestFlowModel.State.Landing, null, failed, isArchitectureReady = false))
        assertNull(GuestFlowRules.problem(GuestFlowModel.State.Landing, null, failed, isArchitectureReady = true))
        assertNull(GuestFlowRules.problem(GuestFlowModel.State.Loading, null, BuildingSession.Phase.Connecting, isArchitectureReady = false))
    }
}
