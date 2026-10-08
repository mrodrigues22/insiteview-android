package com.getinsiteview.features.building

import com.getinsiteview.api.GuestProblem
import com.getinsiteview.api.MyBuildingVia
import com.getinsiteview.api.SearchHit
import com.getinsiteview.api.SearchHitType
import com.getinsiteview.modelkit.ChunkLoadError
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class BuildingSessionRulesTest {
    private fun hit(id: String, system: String?, type: SearchHitType = SearchHitType.ELEMENT, room: String? = null, storey: String? = null) =
        SearchHit(type = type, id = id, title = "Title $id", kind = null, kindName = null, system = system, tag = null, room = room, storey = storey)

    @Test
    fun `save state follows how my buildings list the building`() {
        assertEquals(BuildingSession.SaveState.NOT_SAVED, BuildingSessionRules.saveState(null))
        assertEquals(BuildingSession.SaveState.SAVED, BuildingSessionRules.saveState(MyBuildingVia.SAVED))
        assertEquals(BuildingSession.SaveState.MINE, BuildingSessionRules.saveState(MyBuildingVia.MEMBER))
        assertEquals(BuildingSession.SaveState.MINE, BuildingSessionRules.saveState(MyBuildingVia.GRANT))
        assertEquals(BuildingSession.SaveState.MINE, BuildingSessionRules.saveState(MyBuildingVia("Future")))
    }

    @Test
    fun `loading fails only when nothing loaded and something failed`() {
        assertEquals(BuildingSession.Phase.Ready, BuildingSessionRules.finalPhase(noModels = true, failures = emptyList()))
        assertEquals(
            BuildingSession.Phase.Ready,
            BuildingSessionRules.finalPhase(noModels = false, failures = listOf(ChunkLoadError.Offline)),
        )
        assertEquals(
            BuildingSession.Phase.Failed(GuestProblem.Offline),
            BuildingSessionRules.finalPhase(noModels = true, failures = listOf(ChunkLoadError.Expired, ChunkLoadError.Offline)),
        )
        assertEquals(
            BuildingSession.Phase.Failed(GuestProblem.Unavailable),
            BuildingSessionRules.finalPhase(noModels = true, failures = listOf(ChunkLoadError.Transport("x"))),
        )
    }

    @Test
    fun `place line joins room and level`() {
        assertEquals("Kitchen · Level 1", BuildingSessionRules.placeLine("Kitchen", "Level 1"))
        assertEquals("Level 1", BuildingSessionRules.placeLine(null, "Level 1"))
        assertNull(BuildingSessionRules.placeLine(null, null))
    }

    @Test
    fun `server rows keep the scope, architecture and hits without a system`() {
        val rows = BuildingSessionRules.serverSearchRows(
            listOf(
                hit("e1", "electrical", room = "Kitchen", storey = "Level 1"),
                hit("e2", "plumbing"),
                hit("e3", "architecture"),
                hit("s1", null, type = SearchHitType.ROOM, storey = "Level 2"),
            ),
            scope = setOf("electrical"),
        ) { id -> if (id == "e1") "power" else null }
        assertEquals(listOf("e1", "e3", "s1"), rows.map { it.id })
        assertEquals("power", rows[0].subsystem)
        assertEquals("Kitchen · Level 1", rows[0].place)
        assertEquals(true, rows[2].isRoom)
        assertEquals("Level 2", rows[2].place)
    }
}
