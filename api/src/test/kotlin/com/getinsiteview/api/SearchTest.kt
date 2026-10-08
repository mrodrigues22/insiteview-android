package com.getinsiteview.api

import com.getinsiteview.api.buildings.AccountSearchResults
import com.getinsiteview.api.buildings.BuildingSearch
import com.getinsiteview.api.buildings.BuildingSearchOutcome
import com.getinsiteview.core.BuildingCode
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Search")
class SearchTest {
    private fun results(): MySearchResults = ApiClient.json.decodeFromString(MySearchResults.serializer(), APIFixtures.mySearch)

    @Test
    fun `Across my buildings - grouped by building, then room, no room last`() {
        val grouped = AccountSearchResults.of(
            results(), codes = mapOf("01926f3a-0000-7000-8000-000000000001" to BuildingCode.parse("TEST01")!!),
        )
        assertEquals(listOf("Casa Azul"), grouped.buildings.map { it.name })
        assertEquals(listOf("Test room", "Casa Azul"), grouped.groups.map { it.name })
        assertEquals(
            listOf(BuildingCode.parse("TEST01"), BuildingCode.parse("8K29X7")),
            grouped.groups.map { it.code },
            "codes from my buildings and the matched buildings",
        )
        val rooms = grouped.groups[0].rooms
        assertEquals(listOf("Kitchen", "Living", null), rooms.map { it.room })
        assertEquals(listOf("e1", "e3"), rooms[0].hits.map { it.id })
        assertEquals(listOf("e2"), rooms[2].hits.map { it.id })
        assertEquals(4, grouped.groups[0].hitCount)
        assertFalse(grouped.isEmpty)
    }

    @Test
    @DisplayName("GET /v1/me/search?q=")
    fun `GET v1 me search?q=`() = test {
        val transport = RoutingTransport { RoutingTransport.Reply.json(APIFixtures.mySearch) }
        val found = transport.api().searchMyBuildings("tomadas cozinha")
        assertEquals(5, found.hits.size)
        assertEquals("/v1/me/search?q=tomadas%20cozinha", transport.requests.first().path)
        assertEquals(UUID.fromString("01926f3a-0000-7000-8000-000000000001"), found.hits.firstOrNull()?.buildingUUID)
    }

    private val hits =
        """{"query":"k-01","hits":[{"type":"Element","id":"e1","title":"Outlet K-01","kind":"outlet","kindName":"Outlet","system":"electrical","tag":"K-01","room":"Kitchen","storey":"Level 1"}]}"""

    @Test
    fun `In a building - the server when online, the phone's copy when not`() = test {
        val online = BuildingSearch.run(
            "k-01",
            server = { ApiClient.json.decodeFromString(SearchResults.serializer(), hits) },
            offline = { listOf("local") },
        )
        assertEquals(listOf("e1"), assertIs<BuildingSearchOutcome.Server>(online).hits.map { it.id })

        val errors: List<Throwable> = listOf(
            UnknownHostException("offline"),
            ApiError(code = null, status = 503),
            ApiError(code = ApiError.Code.RATE_LIMITED, status = 429),
        )
        for (error in errors) {
            val outcome = BuildingSearch.run("k-01", server = { throw error }, offline = { listOf("local") })
            assertEquals(listOf("local"), assertIs<BuildingSearchOutcome.Offline<String>>(outcome, "expected offline results for $error").results)
        }
    }

    @Test
    fun `In a building - access ending isn't hidden behind offline results, empty queries don't call`() = test {
        assertFailsWith<ApiError> {
            BuildingSearch.run("k-01", server = { throw ApiError(ApiError.Code.BUILDING_PAUSED, 403) }, offline = { listOf(1) })
        }
        val calls = AtomicInteger(0)
        val outcome = BuildingSearch.run("  ", server = {
            calls.incrementAndGet()
            throw UnknownHostException("offline")
        }, offline = { listOf(1) })
        assertTrue(assertIs<BuildingSearchOutcome.Server>(outcome).hits.isEmpty() && calls.get() == 0)
    }

    @Test
    @DisplayName("Member search - GET /v1/buildings/{id}/search")
    fun `Member search - GET v1 buildings {id} search`() = test {
        val transport = RoutingTransport { RoutingTransport.Reply.json(hits) }
        val connection = BuildingConnection(
            BuildingAccess.Member(UUID.fromString("01926F3A-0000-7000-8000-000000000001")), transport.api(),
            deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP,
        )
        connection.search("k-01")
        assertEquals("/v1/buildings/01926f3a-0000-7000-8000-000000000001/search?q=k-01", transport.requests.first().path)
    }
}
