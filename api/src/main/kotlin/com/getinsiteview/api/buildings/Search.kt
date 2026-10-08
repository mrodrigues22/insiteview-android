package com.getinsiteview.api.buildings

import com.getinsiteview.api.GuestProblem
import com.getinsiteview.api.MySearchBuilding
import com.getinsiteview.api.MySearchHit
import com.getinsiteview.api.MySearchResults
import com.getinsiteview.api.SearchHit
import com.getinsiteview.api.SearchResults
import com.getinsiteview.core.BuildingCode
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Search tab's results (IOS-M3-04, `GET /v1/me/search`): buildings that match by name or
 * address, then element hits grouped by building and room, in the order the API ranked them.
 */
data class AccountSearchResults(
    val query: String,
    val buildings: List<MySearchBuilding>,
    val groups: List<BuildingGroup>,
) {
    data class BuildingGroup(
        /** The building's id (lower-case UUID). */
        val id: String,
        val name: String,
        /** For opening it; `null` when the building isn't in my buildings any more. */
        val code: BuildingCode?,
        val rooms: List<RoomGroup>,
    ) {
        val hitCount: Int get() = rooms.sumOf { it.hits.size }
    }

    data class RoomGroup(
        /** The room's name; `null` for elements outside any room (listed last). */
        val room: String?,
        val hits: List<SearchHit>,
    ) {
        val id: String get() = room ?: ""
    }

    val isEmpty: Boolean get() = buildings.isEmpty() && groups.isEmpty()

    companion object {
        /**
         * @param codes building id (lower-case) → code, from my buildings; hits carry only the id.
         */
        fun of(results: MySearchResults, codes: Map<String, BuildingCode>): AccountSearchResults {
            val allCodes = codes.toMutableMap()
            for (building in results.buildings) {
                building.buildingCode?.let { allCodes[building.id.lowercase()] = it }
            }
            val order = mutableListOf<String>()
            val names = HashMap<String, String>()
            val byBuilding = HashMap<String, MutableList<MySearchHit>>()
            for (hit in results.hits) {
                val id = hit.buildingId.lowercase()
                if (id !in byBuilding) {
                    order.add(id)
                    names[id] = hit.buildingName
                }
                byBuilding.getOrPut(id) { mutableListOf() }.add(hit)
            }
            val groups = order.map { id ->
                val roomOrder = mutableListOf<String?>()
                val byRoom = HashMap<String, MutableList<SearchHit>>()
                for (hit in byBuilding[id].orEmpty()) {
                    val room = hit.hit.room?.trim(' ', '\t')?.ifEmpty { null }
                    if ((room ?: "") !in byRoom) roomOrder.add(room)
                    byRoom.getOrPut(room ?: "") { mutableListOf() }.add(hit.hit)
                }
                // Elements outside any room go last.
                val rooms = roomOrder.filterNotNull() + roomOrder.filter { it == null }
                BuildingGroup(
                    id = id,
                    name = names[id] ?: "",
                    code = allCodes[id],
                    rooms = rooms.map { RoomGroup(it, byRoom[it ?: ""].orEmpty()) },
                )
            }
            return AccountSearchResults(query = results.query, buildings = results.buildings, groups = groups)
        }
    }
}

/** Where in-building search results came from. */
sealed interface BuildingSearchOutcome<out Offline> {
    /** `GET /v1/visit/search` (or the member endpoint): ranked, with typo tolerance. */
    data class Server(val hits: List<SearchHit>) : BuildingSearchOutcome<Nothing>

    /** The loaded chunks' meta on this phone: the server couldn't be reached. */
    data class Offline<T>(val results: List<T>) : BuildingSearchOutcome<T>
}

/**
 * In-building search (IOS-M3-04): the server's search when online, the offline meta search
 * (`ElementIndex.search`) when it can't be reached. An error that ends access (paused, link
 * revoked, …) is thrown, not hidden behind offline results.
 */
object BuildingSearch {
    suspend fun <T> run(
        query: String,
        server: suspend () -> SearchResults,
        offline: suspend () -> List<T>,
    ): BuildingSearchOutcome<T> {
        if (query.isBlank()) return BuildingSearchOutcome.Server(emptyList())
        return try {
            BuildingSearchOutcome.Server(server().hits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (GuestProblem.from(e)) {
                GuestProblem.Offline, GuestProblem.Unavailable, is GuestProblem.RateLimited -> BuildingSearchOutcome.Offline(offline())
                else -> throw e
            }
        }
    }
}
