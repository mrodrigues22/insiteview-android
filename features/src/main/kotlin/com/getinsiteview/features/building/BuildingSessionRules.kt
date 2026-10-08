package com.getinsiteview.features.building

import com.getinsiteview.api.GuestProblem
import com.getinsiteview.api.MyBuildingVia
import com.getinsiteview.api.SearchHit
import com.getinsiteview.api.SearchHitType
import com.getinsiteview.modelkit.ChunkLoadError
import com.getinsiteview.modelkit.ChunkPlan

/**
 * [BuildingSession]'s decisions that don't need a session (pure, so they're tested on the JVM).
 */
object BuildingSessionRules {
    /** "Save this building" for how my buildings list it (`null`: not in the list). */
    fun saveState(via: MyBuildingVia?): BuildingSession.SaveState = when (via) {
        null -> BuildingSession.SaveState.NOT_SAVED
        MyBuildingVia.SAVED -> BuildingSession.SaveState.SAVED
        else -> BuildingSession.SaveState.MINE // member, grant, or a way the app doesn't know yet
    }

    /**
     * After every file loaded or failed: failed when nothing loaded and something failed (offline
     * when a file failed for lack of a connection), else ready.
     */
    fun finalPhase(noModels: Boolean, failures: Collection<ChunkLoadError>): BuildingSession.Phase =
        if (noModels && failures.isNotEmpty()) {
            BuildingSession.Phase.Failed(
                if (ChunkLoadError.Offline in failures) GuestProblem.Offline else GuestProblem.Unavailable,
            )
        } else {
            BuildingSession.Phase.Ready
        }

    /** "Kitchen · Level 1"; `null` when neither is known. */
    fun placeLine(room: String?, storey: String?): String? =
        listOfNotNull(room, storey).joinToString(" · ").ifEmpty { null }

    /**
     * The server's hits as rows: hits of a system outside [scope] are left out (architecture is
     * always searchable; hits without a system stay); [subsystem] looks an element's subsystem up
     * in the loaded meta.
     */
    fun serverSearchRows(
        hits: List<SearchHit>,
        scope: Set<String>,
        subsystem: (String) -> String?,
    ): List<BuildingSession.SearchRow> = hits
        .filter { hit -> hit.system?.let { it in scope || it == ChunkPlan.architecture } ?: true }
        .map { hit ->
            BuildingSession.SearchRow(
                id = hit.id, isRoom = hit.type == SearchHitType.ROOM, title = hit.title, system = hit.system,
                subsystem = subsystem(hit.id), place = placeLine(hit.room, hit.storey),
            )
        }
}
