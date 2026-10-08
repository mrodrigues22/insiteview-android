package com.getinsiteview.api

import com.getinsiteview.api.buildings.BuildingListSegment
import com.getinsiteview.api.buildings.MyBuildingsRepository
import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.BuildingStatus
import com.getinsiteview.core.ISO8601Timestamp
import com.getinsiteview.core.RecentBuildings
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("My buildings")
class MyBuildingsTest {
    private fun client(handler: (RoutingTransport.Sent) -> RoutingTransport.Reply): Pair<ApiClient, RoutingTransport> {
        val transport = RoutingTransport(handler)
        return transport.api() to transport
    }

    private val noContent = RoutingTransport.Reply.empty(204)

    private fun code(string: String): BuildingCode = BuildingCode.parse(string)!!

    @Test
    @DisplayName("GET /v1/me/buildings decodes every kind, organizationName may be null")
    fun `GET v1 me buildings decodes every kind, organizationName may be null`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json(APIFixtures.myBuildings) }
        val buildings = api.myBuildings()
        assertEquals("/v1/me/buildings", transport.requests.first().path)
        assertEquals(listOf(MyBuildingVia.MEMBER, MyBuildingVia.GRANT, MyBuildingVia.SAVED, MyBuildingVia.MEMBER), buildings.map { it.via })
        assertEquals(
            listOf(BuildingStatus.TRIAL_LIVE, BuildingStatus.ACTIVE, BuildingStatus.PAUSED, BuildingStatus.DRAFT),
            buildings.map { it.status },
        )
        assertNull(buildings[2].organizationName)
        assertEquals(listOf("plumbing"), buildings[1].systems)
        assertEquals(code("TEST01"), buildings[0].buildingCode)
        assertEquals(UUID.fromString("01926f3a-0000-7000-8000-000000000001"), buildings[0].buildingID)
        assertEquals(listOf(true, false, false, true), buildings.map { it.showsStatus })
        assertEquals(listOf(true, true, true, false), buildings.map { it.canOpen })
    }

    @Test
    fun `Fetched once and shared, a code lookup, refresh fetches again, sign-out clears`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json(APIFixtures.myBuildings) }
        val repository = MyBuildingsRepository(api)
        assertNull(repository.cached)
        val first = async { repository.list() }
        val second = async { repository.list() }
        assertEquals(4, first.await().size)
        assertEquals(4, second.await().size)
        assertEquals("Casa Azul", repository.building(code("8k29x7"))?.name)
        assertNull(repository.building(code("ZZZZZZ")))
        assertEquals(1, transport.requests.size)
        repository.list(refresh = true)
        assertEquals(2, transport.requests.size)
        repository.clear()
        assertNull(repository.cached)
    }

    @Test
    fun `A code lookup loads the list when nothing is cached, nil when it can't`() = test {
        val (api, _) = client { RoutingTransport.Reply.json(APIFixtures.myBuildings) }
        assertEquals(MyBuildingVia.MEMBER, MyBuildingsRepository(api).building(code("TEST01"))?.via)
        val (offline, _) = client { throw UnknownHostException("offline") }
        assertNull(MyBuildingsRepository(offline).building(code("TEST01")))
    }

    @Test
    @DisplayName("Trial ends by organization, from GET /v1/orgs")
    fun `Trial ends by organization, from GET v1 orgs`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json(APIFixtures.organizations) }
        val repository = MyBuildingsRepository(api)
        val ends = repository.trialEnds()
        assertEquals("2026-10-09T12:00:00Z", ISO8601Timestamp.format(assertNotNull(ends[APIFixtures.ORGANIZATION_ID])))
        repository.trialEnds()
        assertEquals(listOf("/v1/orgs"), transport.requests.map { it.path })
    }

    @Test
    fun `Save and unsave by code, the list is fetched again after`() = test {
        val (api, transport) = client { sent ->
            if (sent.operationId == "me_buildings") RoutingTransport.Reply.json(APIFixtures.myBuildings) else noContent
        }
        val repository = MyBuildingsRepository(api)
        repository.list()
        repository.save(code("iv-abcd-ef"))
        assertNull(repository.cached)
        repository.unsave(code("ABCDEF"))
        val saves = transport.requests.filter { it.operationId != "me_buildings" }
        assertEquals(listOf("/v1/me/saved-buildings/ABCDEF", "/v1/me/saved-buildings/ABCDEF"), saves.map { it.path })
        assertEquals(listOf("POST", "DELETE"), saves.map { it.method })
    }

    @Test
    fun `Saving a building that isn't live`() = test {
        val (api, _) = client { RoutingTransport.Reply.problem(403, code = "building.not_live") }
        assertFailsWith<ApiError> { MyBuildingsRepository(api).save(code("ABCDEF")) }
    }

    @Test
    fun `Segments - All by name, Active is live now, Recent newest first, Favorites`() {
        val buildings = ApiClient.json.decodeFromString(ListSerializer(MyBuilding.serializer()), APIFixtures.myBuildings)
        assertEquals(
            listOf("apartamento 12", "Casa Azul", "Draft house", "Test room"),
            BuildingListSegment.ALL.rows(buildings, favorites = emptySet(), recents = emptyList()).map { it.name },
        )
        assertEquals(
            listOf("8K29X7", "TEST01"),
            BuildingListSegment.ACTIVE.rows(buildings, favorites = emptySet(), recents = emptyList()).map { it.code },
        )
        val recents = listOf(
            RecentBuildings.Entry(code("ABCDEF"), Instant.ofEpochSecond(300)),
            RecentBuildings.Entry(code("G0NE00"), Instant.ofEpochSecond(200)),
            RecentBuildings.Entry(code("TEST01"), Instant.ofEpochSecond(100)),
        )
        assertEquals(
            listOf("ABCDEF", "TEST01"),
            BuildingListSegment.RECENT.rows(buildings, favorites = emptySet(), recents = recents).map { it.code },
        )
        assertEquals(
            listOf("8K29X7", "TEST01"),
            BuildingListSegment.FAVORITES.rows(buildings, favorites = setOf("TEST01", "8K29X7"), recents = emptyList()).map { it.code },
        )
    }

    @Test
    @DisplayName("GET /v1/buildings/{id} and GET /v1/orgs for members")
    fun `GET v1 buildings {id} and GET v1 orgs for members`() = test {
        val (api, transport) = client { sent ->
            RoutingTransport.Reply.json(if (sent.operationId == "orgs_list") APIFixtures.organizations else APIFixtures.buildingDetail)
        }
        val detail = api.building(UUID.fromString("01926F3A-0000-7000-8000-000000000001"))
        assertEquals(BuildingStatus.ACTIVE, detail.status)
        assertEquals("2036-09-29T12:00:00Z", ISO8601Timestamp.format(assertNotNull(detail.expiresAt)))
        assertEquals(OrganizationRole.ADMIN, detail.role)
        assertEquals("Home", detail.tier.raw)
        assertEquals("/v1/buildings/01926f3a-0000-7000-8000-000000000001", transport.requests.first().path)
        val organizations = api.organizations()
        assertEquals("Brl", organizations.firstOrNull()?.currency?.raw)
        assertEquals("Builder", organizations.firstOrNull()?.type?.raw)
    }
}
