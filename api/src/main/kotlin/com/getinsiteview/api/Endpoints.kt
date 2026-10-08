package com.getinsiteview.api

import com.getinsiteview.core.APIJSON
import com.getinsiteview.core.AnalyticsEvent
import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.Catalog
import com.getinsiteview.core.JSONValue
import com.getinsiteview.core.UnitSystem
import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.geometry.PlateFrame
import com.getinsiteview.modelkit.geometry.RoomCorrection
import com.getinsiteview.modelkit.geometry.RoomObservation
import com.getinsiteview.modelkit.geometry.Vec3
import java.util.UUID
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

// The API calls the app makes (iOS: on the generated operations). Responses of 400 or more
// arrive as ApiError from ApiClient.send, so each call only decodes its success case.

private fun lower(id: UUID): String = id.toString().lowercase()

private fun <T> ApiClient.jsonEndpoint(
    operationId: String,
    method: String,
    path: List<String>,
    response: DeserializationStrategy<T>,
    query: List<Pair<String, String?>> = emptyList(),
    body: ByteArray? = null,
): Endpoint<T> = Endpoint(operationId, method, path, query, body = body) { _, _, data ->
    ApiClient.json.decodeFromString(response, data.decodeToString())
}

private fun noContent(
    operationId: String,
    method: String,
    path: List<String>,
    body: ByteArray? = null,
): Endpoint<Unit> = Endpoint(operationId, method, path, body = body) { _, _, _ -> }

/** Request bodies: JSON with sorted keys and no `null`s (iOS `.sortedKeys`, optional fields left out). */
private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
    APIJSON.encode(serializer, value).encodeToByteArray()

private fun Vec3.array(): List<Double> = listOf(x, y, z)

// MARK: Guests

/** `GET /v1/public/buildings/{code}`: the landing summary. */
suspend fun ApiClient.publicBuilding(code: BuildingCode): PublicBuilding =
    send(jsonEndpoint("public_building", "GET", listOf("v1", "public", "buildings", code.raw), PublicBuilding.serializer()))

/**
 * `POST /v1/public/buildings/{code}/visits`: starts a visit from a QR code (or a plate). Without
 * the PIN of a PIN building, the visit is `pinLocked` (architecture only). A signed-in member's
 * bearer token, added by [BearerInterceptor], gets their own scope.
 */
suspend fun ApiClient.startVisit(
    code: BuildingCode,
    plate: Int?,
    pin: String? = null,
    deviceId: String,
    platform: VisitPlatform,
): Visit {
    // The device id also goes in `X-Device-Id` (DeviceIdInterceptor), which the API prefers; the
    // body copy covers a client without the interceptor.
    val body = CreateVisitRequest(deviceId = deviceId, platform = platform, plate = plate, pin = pin)
    return send(
        jsonEndpoint(
            "public_visit_by_code", "POST", listOf("v1", "public", "buildings", code.raw, "visits"), Visit.serializer(),
            body = encode(CreateVisitRequest.serializer(), body),
        ),
    )
}

/** `POST /v1/public/links/{token}/visits`: starts a visit from a temporary or preview link. */
suspend fun ApiClient.startLinkVisit(token: String, deviceId: String, platform: VisitPlatform): Visit {
    val body = CreateLinkVisitRequest(deviceId = deviceId, platform = platform)
    return send(
        jsonEndpoint(
            "public_visit_by_link", "POST", listOf("v1", "public", "links", token, "visits"), Visit.serializer(),
            body = encode(CreateLinkVisitRequest.serializer(), body),
        ),
    )
}

/**
 * `POST /v1/visit/unlock`: the building's PIN for a `pinLocked` visit → a new visit (and token)
 * with every system. Throws `pin.invalid` (with `attemptsLeft`), `pin.locked` (with `retryAfter`)
 * or `pin.required`. Needs the visit token.
 */
suspend fun ApiClient.unlockVisit(pin: String): Visit = send(
    jsonEndpoint(
        "visit_unlock", "POST", listOf("v1", "visit", "unlock"), Visit.serializer(),
        body = encode(UnlockVisitRequest.serializer(), UnlockVisitRequest(pin)),
    ),
)

/** `GET /v1/visit/manifest`, validated. Needs the visit token. */
suspend fun ApiClient.visitManifest(): Manifest =
    send(Endpoint("visit_manifest", "GET", listOf("v1", "visit", "manifest")) { _, _, data -> decodeManifest(data) })

/** `GET /v1/visit/elements/{elementId}`. Needs the visit token. */
suspend fun ApiClient.visitElement(id: String): ElementDetail =
    send(jsonEndpoint("visit_element", "GET", listOf("v1", "visit", "elements", id), ElementDetail.serializer()))

/** `GET /v1/visit/documents`: documents shared with guests, optionally for one element. */
suspend fun ApiClient.visitDocuments(elementId: String? = null): List<BuildingDocument> = send(
    jsonEndpoint(
        "visit_documents", "GET", listOf("v1", "visit", "documents"), ListSerializer(BuildingDocument.serializer()),
        query = listOf("elementId" to elementId),
    ),
)

/** `GET /v1/visit/search?q=`, limited to the visit's systems. */
suspend fun ApiClient.visitSearch(query: String): SearchResults = send(
    jsonEndpoint("visit_search", "GET", listOf("v1", "visit", "search"), SearchResults.serializer(), query = listOf("q" to query)),
)

// MARK: Members

/** `GET /v1/buildings/{id}/manifest`: every system, for members and grants. */
suspend fun ApiClient.buildingManifest(buildingId: UUID, version: UUID? = null): Manifest = send(
    Endpoint(
        "model_manifest", "GET", listOf("v1", "buildings", lower(buildingId), "manifest"),
        query = listOf("version" to version?.let(::lower)),
    ) { _, _, data -> decodeManifest(data) },
)

/** `GET /v1/buildings/{id}/elements/{elementId}`. */
suspend fun ApiClient.buildingElement(buildingId: UUID, elementId: String, version: UUID? = null): ElementDetail = send(
    jsonEndpoint(
        "model_element", "GET", listOf("v1", "buildings", lower(buildingId), "elements", elementId), ElementDetail.serializer(),
        query = listOf("version" to version?.let(::lower)),
    ),
)

// MARK: Analytics

/**
 * `POST /v1/events`: up to [AnalyticsEvent.MAX_PER_CALL] events. With a visit token (guests)
 * [buildingId] is ignored; with a user token it is required. Returns how many were accepted.
 */
suspend fun ApiClient.sendEvents(events: List<AnalyticsEvent>, buildingId: UUID? = null): Int {
    val inputs = events.map { event ->
        EventInput(
            type = event.type.raw,
            props = if (event.props.isEmpty()) null else JSONValue.Object(event.props),
            occurredAt = event.occurredAt,
        )
    }
    val request = EventsRequest(buildingId = buildingId?.let(::lower), events = inputs)
    val body = try {
        encode(EventsRequest.serializer(), request)
    } catch (e: IllegalArgumentException) {
        // e.g. a NaN in props: a request that can't be built.
        throw UnexpectedResponse("events_create", 0, e.message ?: e.toString())
    }
    return send(jsonEndpoint("events_create", "POST", listOf("v1", "events"), EventsResponse.serializer(), body = body)).accepted
}

// MARK: Catalog

sealed interface CatalogResult {
    data class Fetched(val catalog: Catalog, val etag: String?) : CatalogResult

    /** The cached copy with the `If-None-Match` ETag is current. */
    data object NotModified : CatalogResult
}

/**
 * `GET /v1/catalog`, conditional on the cached copy's ETag (iOS hand-writes it too: its generated
 * client would URI-encode the quoted ETag, so it never matched).
 */
suspend fun ApiClient.catalog(ifNoneMatch: String? = null): CatalogResult = send(
    Endpoint(
        "catalog_get", "GET", listOf("v1", "catalog"),
        headers = ifNoneMatch?.let { mapOf("If-None-Match" to it) } ?: emptyMap(),
    ) { status, headers, data ->
        if (status == 304) {
            CatalogResult.NotModified
        } else {
            CatalogResult.Fetched(APIJSON.json.decodeFromString(Catalog.serializer(), data.decodeToString()), headers["ETag"])
        }
    },
)

// MARK: Auth

suspend fun ApiClient.login(email: String, password: String, deviceName: String?): AuthResponse = send(
    jsonEndpoint(
        "auth_login", "POST", listOf("v1", "auth", "login"), AuthResponse.serializer(),
        body = encode(LoginRequest.serializer(), LoginRequest(email, password, deviceName)),
    ),
)

suspend fun ApiClient.register(
    email: String,
    password: String,
    displayName: String,
    termsVersion: String,
    locale: String?,
    deviceName: String?,
): AuthResponse = send(
    jsonEndpoint(
        "auth_register", "POST", listOf("v1", "auth", "register"), AuthResponse.serializer(),
        body = encode(
            RegisterRequest.serializer(),
            RegisterRequest(email, password, displayName, termsVersion, locale, deviceName),
        ),
    ),
)

/**
 * `GET /v1/auth/oauth/providers`: the social providers this environment has keys for (`google`,
 * `microsoft`, `apple`).
 */
suspend fun ApiClient.oauthProviders(): List<String> =
    send(jsonEndpoint("auth_oauth_providers", "GET", listOf("v1", "auth", "oauth", "providers"), OAuthProvidersResponse.serializer()))
        .providers

/**
 * `POST /v1/auth/oauth/exchange`: the one-time code from the provider redirect (valid 60 s) →
 * tokens. `auth.token_invalid` when it expired or was used.
 */
suspend fun ApiClient.exchangeOAuthCode(code: String, deviceName: String?): AuthResponse = send(
    jsonEndpoint(
        "auth_oauth_exchange", "POST", listOf("v1", "auth", "oauth", "exchange"), AuthResponse.serializer(),
        body = encode(OAuthExchangeRequest.serializer(), OAuthExchangeRequest(code, deviceName)),
    ),
)

/** Rotates the refresh token. Reusing an old one revokes the family (`auth.refresh_reused`). */
suspend fun ApiClient.refresh(refreshToken: String): AuthResponse = send(
    jsonEndpoint(
        "auth_refresh", "POST", listOf("v1", "auth", "refresh"), AuthResponse.serializer(),
        body = encode(RefreshRequest.serializer(), RefreshRequest(refreshToken)),
    ),
)

suspend fun ApiClient.logout(refreshToken: String) {
    send(
        noContent(
            "auth_logout", "POST", listOf("v1", "auth", "logout"),
            body = encode(RefreshRequest.serializer(), RefreshRequest(refreshToken)),
        ),
    )
}

/** `POST /v1/auth/verify-email/send`: emails a new confirmation link to the signed-in user. */
suspend fun ApiClient.sendEmailConfirmation() {
    send(noContent("auth_verify_email_send", "POST", listOf("v1", "auth", "verify-email", "send")))
}

// MARK: Me

/** `GET /v1/me`. */
suspend fun ApiClient.me(): Me = send(jsonEndpoint("me_get", "GET", listOf("v1", "me"), Me.serializer()))

/** `PATCH /v1/me`: only the fields given change. */
suspend fun ApiClient.updateMe(displayName: String? = null, locale: String? = null, units: UnitSystem? = null): Me = send(
    jsonEndpoint(
        "me_update", "PATCH", listOf("v1", "me"), Me.serializer(),
        body = encode(UpdateMeRequest.serializer(), UpdateMeRequest(displayName = displayName, locale = locale, units = units)),
    ),
)

/**
 * `DELETE /v1/me` (Google Play's account deletion policy, App Store guideline 5.1.1(v) on iOS).
 * 409 `conflict` when the user is the only owner of an organization with other members.
 */
suspend fun ApiClient.deleteAccount() {
    send(noContent("me_delete", "DELETE", listOf("v1", "me")))
}

/** `GET /v1/me/buildings`: my organizations' buildings, buildings shared with me and saved ones. */
suspend fun ApiClient.myBuildings(): List<MyBuilding> =
    send(jsonEndpoint("me_buildings", "GET", listOf("v1", "me", "buildings"), ListSerializer(MyBuilding.serializer())))

/** `POST /v1/me/saved-buildings/{code}`: saves a live building to a free account (IOS-M3-08). 403 `building.not_live`, 404. */
suspend fun ApiClient.saveBuilding(code: BuildingCode) {
    send(noContent("me_save_building", "POST", listOf("v1", "me", "saved-buildings", code.raw)))
}

suspend fun ApiClient.unsaveBuilding(code: BuildingCode) {
    send(noContent("me_unsave_building", "DELETE", listOf("v1", "me", "saved-buildings", code.raw)))
}

/** `GET /v1/me/search?q=`: buildings (by name or address) and elements across my buildings. */
suspend fun ApiClient.searchMyBuildings(query: String): MySearchResults = send(
    jsonEndpoint("search_mine", "GET", listOf("v1", "me", "search"), MySearchResults.serializer(), query = listOf("q" to query)),
)

/** `GET /v1/orgs`: my organizations (the trial's end, for members' status lines). */
suspend fun ApiClient.organizations(): List<Organization> =
    send(jsonEndpoint("orgs_list", "GET", listOf("v1", "orgs"), ListSerializer(Organization.serializer())))

/** `GET /v1/buildings/{id}`: status, expiry and role (members only; 404 otherwise). */
suspend fun ApiClient.building(id: UUID): BuildingDetail =
    send(jsonEndpoint("buildings_get", "GET", listOf("v1", "buildings", lower(id)), BuildingDetail.serializer()))

/** `GET /v1/buildings/{id}/plates`: the building's plates, placed or not, with each one's AR reference image (members). */
suspend fun ApiClient.plates(buildingId: UUID): List<BuildingPlate> = send(
    jsonEndpoint("plates_list", "GET", listOf("v1", "buildings", lower(buildingId), "plates"), ListSerializer(BuildingPlate.serializer())),
)

/**
 * `PATCH /v1/plates/{id}`: where the plate is, in the model coordinates of [versionId] (admins;
 * 403 `forbidden` otherwise). The API stores it in IFC coordinates.
 */
suspend fun ApiClient.placePlate(id: UUID, versionId: UUID, frame: PlateFrame): BuildingPlate {
    val placement = PlatePlacementRequest(
        versionId = lower(versionId), position = frame.position.array(), normal = frame.normal.array(), up = frame.up.array(),
    )
    val body = UpdatePlateRequest(label = null, sizeMm = null, placement = placement, clearPlacement = null)
    return send(
        jsonEndpoint(
            "plates_update", "PATCH", listOf("v1", "plates", lower(id)), BuildingPlate.serializer(),
            body = encode(UpdatePlateRequest.serializer(), body),
        ),
    )
}

/**
 * `POST /v1/visit/room-observations` (visit token): a "Fix here" that measured where its room is
 * (master PLAN §9 "Room corrections").
 */
suspend fun ApiClient.visitRoomObservation(observation: RoomObservation, lidar: Boolean) {
    val body = RoomObservationRequest(
        spaceId = observation.spaceID,
        baseSpaceId = observation.baseSpaceID,
        anchor = observation.anchor.array(),
        markInBase = observation.markInBase.array(),
        turn = observation.turn,
        hasTranslation = observation.hasTranslation,
        baseDistance = observation.baseDistance,
        lidar = lidar,
    )
    send(
        noContent(
            "visit_room_observations_create", "POST", listOf("v1", "visit", "room-observations"),
            body = encode(RoomObservationRequest.serializer(), body),
        ),
    )
}

/**
 * `PUT /v1/buildings/{id}/room-corrections/{spaceId}`: an admin's correction of a room, in the
 * model coordinates of [versionId] (403 `forbidden` for members who aren't admins).
 */
suspend fun ApiClient.saveRoomCorrection(
    buildingId: UUID,
    spaceId: String,
    versionId: UUID,
    correction: RoomCorrection,
): RoomCorrectionSummary {
    val manifest = correction.manifest
    val body = SetRoomCorrectionRequest(
        versionId = lower(versionId), pivot = manifest.pivot, offset = manifest.offset, yaw = correction.yaw,
    )
    return send(
        jsonEndpoint(
            "room_corrections_set", "PUT", listOf("v1", "buildings", lower(buildingId), "room-corrections", spaceId),
            RoomCorrectionSummary.serializer(), body = encode(SetRoomCorrectionRequest.serializer(), body),
        ),
    )
}

/**
 * `GET /v1/buildings/{id}/documents`: every document of a building (members only), optionally for
 * one element. Documents whose upload isn't confirmed have no `url`.
 */
suspend fun ApiClient.buildingDocuments(buildingId: UUID, elementId: String? = null): List<BuildingDocument> = send(
    jsonEndpoint(
        "documents_list", "GET", listOf("v1", "buildings", lower(buildingId), "documents"),
        ListSerializer(BuildingDocument.serializer()), query = listOf("elementId" to elementId),
    ),
)

/** `GET /v1/buildings/{id}/search?q=`: members and grant holders. */
suspend fun ApiClient.searchBuilding(buildingId: UUID, query: String): SearchResults = send(
    jsonEndpoint(
        "search_building", "GET", listOf("v1", "buildings", lower(buildingId), "search"), SearchResults.serializer(),
        query = listOf("q" to query),
    ),
)

// MARK: Manifest

/**
 * The asset-contract model (`:modelkit`) from the API's response, validated: a supported schema,
 * metres, Y up, 3-component vectors, absolute file URLs. A schema this app doesn't read throws
 * [Manifest.DecodingProblem.UnsupportedSchema] (update the app).
 */
internal fun decodeManifest(data: ByteArray): Manifest {
    val manifest = APIJSON.json.decodeFromString(Manifest.serializer(), data.decodeToString())
    // The schema first, so a newer manifest reads as "Update required" whatever else changed.
    if (manifest.schema !in Manifest.supportedSchemas) throw Manifest.DecodingProblem.UnsupportedSchema(manifest.schema)
    for (chunk in manifest.chunks) {
        for ((name, file) in listOf("glb" to chunk.glb, "usdz" to chunk.usdz, "meta" to chunk.meta)) {
            if (file.url.scheme == null) throw Manifest.DecodingProblem.InvalidField("chunks[${chunk.key}].$name")
        }
    }
    for (plate in manifest.plates) {
        if (plate.imageUrl.scheme == null) throw Manifest.DecodingProblem.InvalidField("plates[${plate.number}].imageUrl")
    }
    manifest.validate()
    return manifest
}
