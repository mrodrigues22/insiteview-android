package com.getinsiteview.api

import com.getinsiteview.core.AnalyticsEvent
import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.CatalogLanguage
import com.getinsiteview.core.ISO8601Timestamp
import com.getinsiteview.core.JSONValue
import com.getinsiteview.core.UnitSystem
import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.geometry.RoomCorrection
import com.getinsiteview.modelkit.geometry.RoomCorrections
import com.getinsiteview.modelkit.geometry.RoomObservation
import com.getinsiteview.modelkit.geometry.Vec3
import java.net.UnknownHostException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Endpoints on the generated client")
class EndpointTest {
    private val code = BuildingCode.parse("TEST01")!!

    private fun client(handler: (RoutingTransport.Sent) -> RoutingTransport.Reply): Pair<ApiClient, RoutingTransport> {
        val transport = RoutingTransport(handler)
        return transport.api() to transport
    }

    private fun RoutingTransport.Sent.string(key: String): String? =
        json()[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    private fun doubles(element: kotlinx.serialization.json.JsonElement?): List<Double>? =
        element?.jsonArray?.map { it.jsonPrimitive.double }

    @Test
    @DisplayName("GET /v1/public/buildings/{code} decodes the landing summary")
    fun `GET v1 public buildings {code} decodes the landing summary`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json(APIFixtures.publicBuilding) }
        val building = api.publicBuilding(code)
        assertEquals("IV-TEST-01", building.displayCode)
        assertEquals("Construtora Exemplo", building.builder.name)
        assertEquals(PublicBuildingStatus.LIVE, building.status)
        assertTrue(building.status.isViewable)
        assertFalse(building.pinRequired)
        assertEquals("2026-09-28T14:03:11.517Z", ISO8601Timestamp.format(assertNotNull(building.verifiedAt)))
        val sent = transport.requests.first()
        assertEquals("GET", sent.method)
        assertEquals("/v1/public/buildings/TEST01", sent.path)
        assertEquals("public_building", sent.operationId)
        assertTrue(sent.header("Accept")?.contains("application/json") == true)
    }

    @Test
    fun `Enum values the app doesn't know yet still decode (type overrides)`() = test {
        val json = APIFixtures.publicBuilding.replace("\"Live\"", "\"Archived\"")
        val (api, _) = client { RoutingTransport.Reply.json(json) }
        val building = api.publicBuilding(code)
        assertEquals("Archived", building.status.raw)
        assertFalse(building.status.isViewable)

        val visitJSON = APIFixtures.visit().replace("\"via\":\"Plate\"", "\"via\":\"Invitation\"")
        val (visitAPI, _) = client { RoutingTransport.Reply.json(visitJSON) }
        val visit = visitAPI.startVisit(code, plate = null, deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP)
        assertEquals("Invitation", visit.via.raw)
    }

    @Test
    @DisplayName("POST …/visits sends the device, platform and plate")
    fun `POST visits sends the device, platform and plate`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json(APIFixtures.visit(systems = """["electrical","plumbing"]""")) }
        val visit = api.startVisit(code, plate = 2, deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP)
        assertEquals("visit-token-1", visit.visitToken)
        assertEquals("01926f3a-0000-7000-8000-0000000000aa", visit.visitUUID?.toString()?.lowercase())
        assertTrue(!visit.pinLocked && !visit.readOnly)
        assertEquals(VisitVia.PLATE, visit.via)
        assertEquals(listOf("electrical", "plumbing"), visit.systems)
        assertTrue(visit.allows("plumbing") && !visit.allows("gas"))
        assertEquals("TEST01", visit.building.code)
        assertEquals("2026-10-01T23:00:00Z", ISO8601Timestamp.format(visit.expiresAt))
        val sent = transport.requests.first()
        assertEquals("POST", sent.method)
        assertEquals("/v1/public/buildings/TEST01/visits", sent.path)
        assertEquals("public_visit_by_code", sent.operationId)
        assertTrue(sent.request.body?.contentType()?.toString()?.startsWith("application/json") == true)
        val body = sent.json()
        assertNull(body["pin"])
        assertEquals("device-1234", sent.string("deviceId"))
        assertEquals("AndroidApp", sent.string("platform"))
        assertEquals(2, body["plate"]?.jsonPrimitive?.int)
    }

    @Test
    fun `A visit without a plate omits it, null systems means every system`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json(APIFixtures.visit()) }
        val visit = api.startVisit(code, plate = null, deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP)
        assertNull(visit.systems)
        assertTrue(visit.allows("gas"))
        val body = transport.requests.first().json()
        assertNull(body["plate"])
        assertEquals("AndroidApp", body["platform"]?.jsonPrimitive?.content)
    }

    @Test
    fun `A PIN building - the visit with the PIN, and a locked visit without it`() = test {
        val (api, transport) = client { sent ->
            val pin = sent.jsonOrNull()?.get("pin")?.jsonPrimitive?.content
            RoutingTransport.Reply.json(APIFixtures.visit(systems = if (pin == null) "[]" else "null", pinLocked = pin == null))
        }
        val locked = api.startVisit(code, plate = null, deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP)
        assertTrue(locked.pinLocked && locked.systems == emptyList<String>())
        val unlocked = api.startVisit(code, plate = null, pin = "4821", deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP)
        assertTrue(!unlocked.pinLocked && unlocked.systems == null)
        assertEquals("4821", transport.requests[1].string("pin"))
    }

    @Test
    @DisplayName("POST /v1/visit/unlock sends the PIN, wrong PINs and lockouts carry their details")
    fun `POST v1 visit unlock sends the PIN, wrong PINs and lockouts carry their details`() = test {
        val (api, transport) = client { sent ->
            when (sent.jsonOrNull()?.get("pin")?.jsonPrimitive?.content) {
                "4821" -> RoutingTransport.Reply.json(APIFixtures.visit(token = "unlocked"))
                "0000" -> RoutingTransport.Reply.problem(429, code = "pin.locked", extra = ""","retryAfter":3540""")
                else -> RoutingTransport.Reply.problem(403, code = "pin.invalid", extra = ""","attemptsLeft":3""")
            }
        }
        val visit = api.unlockVisit("4821")
        assertEquals("unlocked", visit.visitToken)
        assertEquals("/v1/visit/unlock", transport.requests.first().path)
        assertEquals("visit_unlock", transport.requests.first().operationId)

        val wrong = assertFailsWith<ApiError> { api.unlockVisit("1111") }
        assertEquals(ApiError(code = ApiError.Code.PIN_INVALID, status = 403, title = "Problem", attemptsLeft = 3), wrong)
        val locked = assertFailsWith<ApiError> { api.unlockVisit("0000") }
        assertEquals(GuestProblem.PinLocked(retryAfter = 3540), GuestProblem.from(locked))
    }

    @Test
    @DisplayName("POST /v1/public/links/{token}/visits")
    fun `POST v1 public links {token} visits`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json(APIFixtures.visit()) }
        api.startLinkVisit("Zm9v-_x.~1", deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP)
        val sent = transport.requests.first()
        assertEquals("/v1/public/links/Zm9v-_x.~1/visits", sent.path)
        assertEquals("public_visit_by_link", sent.operationId)
        assertEquals(listOf("deviceId", "platform"), sent.json().keys.sorted())
    }

    @Test
    @DisplayName("GET /v1/visit/manifest decodes and validates the manifest")
    fun `GET v1 visit manifest decodes and validates the manifest`() = test {
        val manifestJSON = APIFixtures.string("manifest")
        val (api, transport) = client { RoutingTransport.Reply.json(manifestJSON) }
        val manifest = api.visitManifest()
        assertEquals(3, manifest.chunks.size)
        assertEquals(listOf<String?>("Test room"), manifest.spaces.map { it.displayName })
        assertEquals(manifest.storeys.firstOrNull()?.id, manifest.spaces.firstOrNull()?.storeyId)
        assertTrue(manifest.plates.firstOrNull()?.imageUrl?.toString()?.endsWith("/image.png") == true)
        assertEquals(0.05, manifest.plates.firstOrNull()?.physicalWidth)
        assertEquals("2026-10-01T12:15:00.123Z", ISO8601Timestamp.format(manifest.urlsExpireAt))
        assertEquals("/v1/visit/manifest", transport.requests.first().path)
        assertEquals("visit_manifest", transport.requests.first().operationId)

        val newer = manifestJSON.replace("\"schema\": 1", "\"schema\": 2")
        val (future, _) = client { RoutingTransport.Reply.json(newer) }
        // A schema this app can't read reaches the caller as such: "Update required".
        val unsupported = assertFailsWith<Manifest.DecodingProblem.UnsupportedSchema> { future.visitManifest() }
        assertEquals(Manifest.DecodingProblem.UnsupportedSchema(2), unsupported)
        assertEquals(GuestProblem.UpdateRequired, GuestProblem.from(unsupported))
        // Other validation failures stay UnexpectedResponse.
        val millimetres = manifestJSON.replace("\"units\": \"m\"", "\"units\": \"mm\"")
        val (other, _) = client { RoutingTransport.Reply.json(millimetres) }
        assertFailsWith<UnexpectedResponse> { other.visitManifest() }
    }

    @Test
    fun `Element detail from the visit and member endpoints`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json(APIFixtures.element) }
        val element = api.visitElement("e22dff25b5a264301813afd2f07c0ff68")
        assertEquals("outlet", element.kind)
        assertEquals("Test room", element.space?.displayName)
        assertEquals("Level 1", element.storey?.name)
        assertEquals("120 V", element.keyProperties["voltage"])
        assertEquals(JSONValue.Number(0.3), element.propertySets["Pset_Common"]?.get("Height"))
        assertEquals(JSONValue.Bool(false), element.propertySets["Pset_Common"]?.get("IsExternal"))
        assertEquals("/v1/visit/elements/e22dff25b5a264301813afd2f07c0ff68", transport.requests.first().path)

        val building = UUID.fromString("01926F3A-0000-7000-8000-000000000001")
        val version = UUID.fromString("01926F3A-0000-7000-8000-000000000002")
        api.buildingElement(building, "e1", version = version)
        val member = transport.requests.last()
        assertEquals(
            "/v1/buildings/01926f3a-0000-7000-8000-000000000001/elements/e1?version=01926f3a-0000-7000-8000-000000000002",
            member.path,
        )
        assertEquals("model_element", member.operationId)
    }

    @Test
    fun `Guests get no technical details - null GlobalId and IFC class, empty property sets`() = test {
        val json = APIFixtures.element
            .replace(""""globalId":"0YtvxbrQHA6Q5rY2wfM0VQ","ifcClass":"IfcOutlet"""", """"globalId":null,"ifcClass":null""")
            .replace(""""props":{"Pset_Common":{"Reference":"K-01","IsExternal":false,"Height":0.3}}""", """"props":{}""")
        assertTrue(json.contains(""""globalId":null""") && json.contains(""""props":{}"""))
        val (api, _) = client { RoutingTransport.Reply.json(json) }
        val element = api.visitElement("e22dff25b5a264301813afd2f07c0ff68")
        assertTrue(element.globalId == null && element.ifcClass == null)
        assertTrue(element.propertySets.isEmpty())
        assertTrue(element.kind == "outlet" && element.keyProperties["circuit"] == "K-04")
    }

    @Test
    @DisplayName("GET /v1/buildings/{id}/manifest for members")
    fun `GET v1 buildings {id} manifest for members`() = test {
        val manifestJSON = APIFixtures.string("manifest")
        val (api, transport) = client { RoutingTransport.Reply.json(manifestJSON) }
        api.buildingManifest(UUID.fromString("01926F3A-0000-7000-8000-000000000001"))
        assertEquals("/v1/buildings/01926f3a-0000-7000-8000-000000000001/manifest", transport.requests.first().path)
        assertEquals("model_manifest", transport.requests.first().operationId)
    }

    @Test
    @DisplayName("GET /v1/catalog with an ETag")
    fun `GET v1 catalog with an ETag`() = test {
        val (api, transport) = client { sent ->
            if (sent.header("If-None-Match") == "\"abc\"") {
                RoutingTransport.Reply.empty(304)
            } else {
                RoutingTransport.Reply(
                    status = 200,
                    headers = mapOf("Content-Type" to "application/json", "ETag" to "\"abc\""),
                    body = """{"version":1,"systems":[{"key":"electrical","color":"#F2B72E","names":{"en":"Electrical","pt-BR":"Elétrica","es":"Electricidad"},"subsystems":[]}],"kinds":[],"properties":[]}""",
                )
            }
        }
        val result = assertIs<CatalogResult.Fetched>(api.catalog())
        assertEquals("\"abc\"", result.etag)
        assertEquals("Elétrica", result.catalog.systemName("electrical", CatalogLanguage.PT_BR))
        assertEquals(CatalogResult.NotModified, api.catalog(ifNoneMatch = "\"abc\""))
        assertEquals(listOf("catalog_get", "catalog_get"), transport.requests.map { it.operationId })
    }

    @Test
    fun `Auth - login, refresh and logout bodies, tokens for native clients`() = test {
        val (api, transport) = client { sent ->
            if (sent.operationId == "auth_logout") RoutingTransport.Reply.empty(204) else RoutingTransport.Reply.json(APIFixtures.auth("access-1"))
        }
        val login = api.login("ana@example.com", "correct horse", deviceName = "Pixel 9")
        assertEquals("refresh-2", login.tokens?.refreshToken)
        assertEquals(UnitSystem.METRIC, login.user.units)
        assertEquals(OrganizationRole.OWNER, login.user.organizations.firstOrNull()?.role)
        val loginRequest = transport.requests.first()
        assertEquals("ana@example.com", loginRequest.string("email"))
        assertEquals("Pixel 9", loginRequest.string("deviceName"))

        api.refresh("refresh-1")
        assertEquals("refresh-1", transport.requests[1].string("refreshToken"))
        assertEquals("/v1/auth/refresh", transport.requests[1].path)

        api.logout("refresh-2")
        assertEquals("auth_logout", transport.requests[2].operationId)
    }

    @Test
    fun `A web-style response without a refresh token has no native tokens`() {
        val response = ApiClient.json.decodeFromString(AuthResponse.serializer(), APIFixtures.auth("a", refresh = null))
        assertNull(response.tokens)
    }

    @Test
    fun `Guest documents and search`() = test {
        val (api, transport) = client { sent ->
            if (sent.operationId == "visit_documents") {
                RoutingTransport.Reply.json(
                    """[{"id":"01926f3a-0000-7000-8000-0000000000d1","title":"Panel schedule","kind":"Drawing","fileName":"panel.pdf","mime":"application/pdf","sizeBytes":1234,"elementId":null,"visibleToGuests":true,"uploaded":true,"url":"https://storage.test/doc?sig=1","createdAt":"2026-09-01T10:00:00+00:00"}]""",
                )
            } else {
                RoutingTransport.Reply.json(
                    """{"query":"outlet","hits":[{"type":"Element","id":"e22dff25b5a264301813afd2f07c0ff68","title":"Outlet K-01","kind":"outlet","kindName":"Outlet","system":"electrical","tag":"K-01","room":"Test room","storey":"Level 1"}]}""",
                )
            }
        }
        val documents = api.visitDocuments(elementId = "e1")
        assertEquals(DocumentKind.DRAWING, documents.firstOrNull()?.kind)
        assertEquals("storage.test", documents.firstOrNull()?.downloadURL?.host)
        assertEquals("/v1/visit/documents?elementId=e1", transport.requests[0].path)

        val results = api.visitSearch("outlet")
        assertEquals(SearchHitType.ELEMENT, results.hits.firstOrNull()?.type)
        assertEquals("Test room", results.hits.firstOrNull()?.room)
        assertEquals("/v1/visit/search?q=outlet", transport.requests[1].path)
    }

    @Test
    @DisplayName("POST /v1/events - types, props and times as the API reads them")
    fun `POST v1 events - types, props and times as the API reads them`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json("""{"accepted":2}""", status = 202) }
        val date = fixtureNow
        val accepted = api.sendEvents(
            listOf(
                AnalyticsEvent.arAligned(AnalyticsEvent.AlignmentMethod.PLATE, 1.2345.seconds, date),
                AnalyticsEvent.systemToggled("plumbing", on = false, at = date),
            ),
        )
        assertEquals(2, accepted)
        val sent = transport.requests.first()
        assertEquals("/v1/events", sent.path)
        assertEquals("events_create", sent.operationId)
        val body = sent.json()
        assertNull(body["buildingId"])
        val events = assertNotNull(body["events"]).jsonArray.map { it.jsonObject }
        assertEquals(listOf("ar_aligned", "system_toggled"), events.map { it["type"]?.jsonPrimitive?.content })
        val props = assertNotNull(events.first()["props"]).jsonObject
        assertEquals("plate", props["method"]?.jsonPrimitive?.content)
        assertEquals(1235.0, props["ms"]?.jsonPrimitive?.double)
        assertEquals("2026-10-01T12:15:00Z", events.first()["occurredAt"]?.jsonPrimitive?.content)
        assertEquals(false, events.last()["props"]?.jsonObject?.get("on")?.jsonPrimitive?.boolean)
    }

    @Test
    fun `A room's correction decodes from the manifest, a malformed one is dropped, not thrown`() = test {
        val manifestJSON = APIFixtures.string("manifest").replace(
            "\"id\": \"ed3083b6225a94e56856253148965de2f\",",
            "\"id\": \"ed3083b6225a94e56856253148965de2f\", \"correction\": {\"pivot\": [2, 0, 1.75], \"offset\": [0.04, 0, -0.01], \"yaw\": 0.01},",
        )
        val (api, _) = client { RoutingTransport.Reply.json(manifestJSON) }
        val space = assertNotNull(api.visitManifest().spaces.firstOrNull())
        val correction = assertNotNull(space.correction?.let(RoomCorrection::of))
        assertTrue(
            correction.pivot == Vec3(2.0, 0.0, 1.75) && correction.offset == Vec3(0.04, 0.0, -0.01) && correction.yaw == 0.01,
        )

        val malformed = manifestJSON.replace("\"pivot\": [2, 0, 1.75]", "\"pivot\": [2, 0]")
        val (other, _) = client { RoutingTransport.Reply.json(malformed) }
        val rooms = RoomCorrections.of(other.visitManifest().spaces)
        assertFalse(rooms.hasCorrections)
    }

    @Test
    @DisplayName("POST /v1/visit/room-observations sends the fix, PUT …/room-corrections/{spaceId} the admin's correction")
    fun `POST v1 visit room-observations sends the fix, PUT room-corrections {spaceId} the admin's correction`() = test {
        val (api, transport) = client { sent ->
            if (sent.operationId == "room_corrections_set") {
                RoutingTransport.Reply.json(
                    """{"spaceId":"e1","name":"R1","longName":"Kitchen","source":"Admin","admin":{"shiftM":0.0412,"turnDeg":0.57},"adminUpdatedAt":"2026-10-07T12:00:00Z","visitors":null,"measurements":2,"devices":2,"agreeingDevices":2,"devicesNeeded":3,"lastMeasuredAt":"2026-10-07T11:00:00Z"}""",
                )
            } else {
                RoutingTransport.Reply.empty(204)
            }
        }
        val correction = RoomCorrection(pivot = Vec3(2.0, 0.0, 1.75), offset = Vec3(0.04, 0.0, -0.01), yaw = 0.01)
        val observation = RoomObservation(
            spaceID = "e1", baseSpaceID = "e2", anchor = Vec3(4.0, 0.0, 3.5), markInBase = Vec3(4.04, 0.0, 3.49), turn = null,
            hasTranslation = true, baseDistance = 3.2, correction = correction,
        )
        api.visitRoomObservation(observation, lidar = true)
        val sent = transport.requests.first()
        assertTrue(sent.method == "POST" && sent.path == "/v1/visit/room-observations")
        val body = sent.json()
        assertTrue(sent.string("spaceId") == "e1" && sent.string("baseSpaceId") == "e2")
        assertEquals(listOf(4.04, 0.0, 3.49), doubles(body["markInBase"]))
        assertTrue(body["hasTranslation"]?.jsonPrimitive?.boolean == true && body["lidar"]?.jsonPrimitive?.boolean == true)
        assertTrue(body["turn"] == null || body["turn"] == JsonNull)

        val saved = api.saveRoomCorrection(
            UUID.fromString("01926f3a-0000-7000-8000-000000000001"), spaceId = "e1",
            versionId = UUID.fromString("01926f3a-0000-7000-8000-000000000002"), correction = correction,
        )
        assertEquals(RoomCorrectionSource.ADMIN, saved.source)
        val put = transport.requests.last()
        assertEquals("PUT", put.method)
        assertEquals("/v1/buildings/01926f3a-0000-7000-8000-000000000001/room-corrections/e1", put.path)
        val putBody = put.json()
        assertTrue(doubles(putBody["offset"]) == listOf(0.04, 0.0, -0.01) && putBody["yaw"]?.jsonPrimitive?.double == 0.01)
    }

    @Test
    fun `Path parameters are percent-encoded`() = test {
        val (api, transport) = client { RoutingTransport.Reply.json(APIFixtures.element) }
        api.visitElement("a/b c")
        assertEquals("/v1/visit/elements/a%2Fb%20c", transport.requests.first().path)
    }

    @Test
    fun `A 2xx body that doesn't match the spec is UnexpectedResponse, offline is an IOException`() = test {
        val (api, _) = client { RoutingTransport.Reply.json("""{"code":"TEST01"}""") }
        assertFailsWith<UnexpectedResponse> { api.publicBuilding(code) }

        val offline = RoutingTransport { throw UnknownHostException("api.staging.getinsiteview.com") }.api()
        val error = assertFailsWith<UnknownHostException> { offline.publicBuilding(code) }
        assertEquals(GuestProblem.Offline, GuestProblem.from(error))
    }
}
