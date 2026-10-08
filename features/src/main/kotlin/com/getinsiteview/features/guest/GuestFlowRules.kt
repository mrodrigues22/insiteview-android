package com.getinsiteview.features.guest

import com.getinsiteview.api.GuestProblem
import com.getinsiteview.features.building.BuildingSession

/** [GuestFlowModel]'s decisions (pure, so they're tested on the JVM). */
object GuestFlowRules {
    /**
     * What replaces the landing: the summary's problem (paused, not live, not found), then access
     * ending mid-session, then a load that failed before the architecture showed.
     */
    fun problem(
        state: GuestFlowModel.State,
        accessProblem: GuestProblem?,
        phase: BuildingSession.Phase,
        isArchitectureReady: Boolean,
    ): GuestProblem? {
        if (state is GuestFlowModel.State.Problem) return state.problem
        if (accessProblem != null) return accessProblem
        if (phase is BuildingSession.Phase.Failed && !isArchitectureReady) return phase.problem
        return null
    }
}
