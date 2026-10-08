package com.getinsiteview.api

import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.BuildingStatus
import com.getinsiteview.core.ISO8601InstantSerializer
import com.getinsiteview.core.JSONValue
import com.getinsiteview.core.UnitSystem
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

// The API records the app uses, hand-written from openapi/openapi.json (iOS generates them and
// aliases them in Models.swift). Names follow the spec's schemas with the iOS aliases where iOS
// has one (`PublicBuilding` = `PublicBuildingResponse`, …); `ApiContractTest` checks every
// property's name and requiredness against the spec.
//
// Rules: a property in the schema's `required` list has no default; one that isn't has a default
// (`null`). A nullable type in the spec (`["null", …]`) or an optional property is a Kotlin
// nullable. Ids with `format: uuid` stay strings, as on iOS; `format: uri` strings too, with
// `…URL` conveniences. Timestamps are `Instant`s.

private fun uuidOrNull(string: String): UUID? = try {
    UUID.fromString(string)
} catch (_: IllegalArgumentException) {
    null
}

private fun uriOrNull(string: String?): URI? {
    if (string == null) return null
    return try {
        URI(string)
    } catch (_: Exception) {
        null
    }
}

// MARK: Health

/** `GET /health/ready`. */
@Serializable
data class HealthResponse(val status: String, val checks: List<HealthCheckResponse>)

@Serializable
data class HealthCheckResponse(val name: String, val status: String)

// MARK: Guests

/** `GET /v1/public/buildings/{code}`: what the guest landing shows first (`PublicBuildingResponse`). */
@Serializable
data class PublicBuilding(
    val code: String,
    val displayCode: String,
    val name: String,
    val addressLine: String?,
    val city: String?,
    val builder: PublicBuilder,
    @Serializable(with = ISO8601InstantSerializer::class) val verifiedAt: Instant?,
    val status: PublicBuildingStatus,
    val pinRequired: Boolean,
    val thumbnailUrl: String?,
) {
    val thumbnailURL: URI? get() = uriOrNull(thumbnailUrl)
}

/** Builder branding; both are `null` when the company turned branding off. */
@Serializable
data class PublicBuilder(val name: String?, val logoUrl: String?) {
    val logoURL: URI? get() = uriOrNull(logoUrl)
}

/** `POST …/visits` (`CreateVisitRequest`). */
@Serializable
data class CreateVisitRequest(val deviceId: String?, val platform: VisitPlatform, val plate: Int?, val pin: String?)

/** `POST /v1/public/links/{token}/visits` (`CreateLinkVisitRequest`). */
@Serializable
data class CreateLinkVisitRequest(val deviceId: String?, val platform: VisitPlatform)

/** `POST /v1/visit/unlock`. */
@Serializable
data class UnlockVisitRequest(val pin: String?)

/**
 * `POST …/visits` and `POST /v1/visit/unlock` → a visit token for `/v1/visit/…`
 * (`VisitResponse`). `systems == null` means every system; `pinLocked`: architecture only until
 * the PIN is entered.
 */
@Serializable
data class Visit(
    val visitId: String,
    val visitToken: String,
    @Serializable(with = ISO8601InstantSerializer::class) val expiresAt: Instant,
    val via: VisitVia,
    val systems: List<String>?,
    val pinLocked: Boolean,
    val readOnly: Boolean,
    val building: PublicBuilding,
) {
    val visitUUID: UUID? get() = uuidOrNull(visitId)

    /** Whether the visit sees this system (`systems == null` is every system). */
    fun allows(system: String): Boolean = systems?.contains(system) ?: true
}

/** `{ id, name }` (`NamedRef`), e.g. an element's storey. */
@Serializable
data class NamedRef(val id: String, val name: String?)

/** An element's room (`SpaceRef`). */
@Serializable
data class SpaceRef(val id: String, val name: String?, val longName: String?) {
    /** "Kitchen" over "R1" when the model has both. */
    val displayName: String? get() = longName ?: name
}

/** A document linked to an element (`DocumentRef`); `url` is a presigned download valid for 15 minutes. */
@Serializable
data class DocumentRef(val id: String, val title: String, val kind: String, val mime: String, val url: String) {
    /** Presigned download, valid for 15 minutes. */
    val downloadURL: URI? get() = uriOrNull(url)
}

/**
 * `GET /v1/visit/elements/{id}` and `GET /v1/buildings/{id}/elements/{id}` (`ElementResponse`).
 * `ifcClass`, `globalId` and `props` are "Technical details" for members only (A-01).
 */
@Serializable
data class ElementDetail(
    val id: String,
    val globalId: String?,
    val ifcClass: String?,
    val kind: String,
    val name: String?,
    val tag: String?,
    val typeName: String?,
    val system: String,
    val subsystem: String?,
    val storey: NamedRef? = null,
    val space: SpaceRef? = null,
    val keyProps: Map<String, String>,
    /** Property sets by name, then properties by name; see [propertySets]. */
    val props: Map<String, JSONValue>,
    val documents: List<DocumentRef>,
) {
    /** Catalog property key → display value. */
    val keyProperties: Map<String, String> get() = keyProps

    /** Property set → property → value ("Technical details", members only). Sets that aren't objects are left out. */
    val propertySets: Map<String, Map<String, JSONValue>>
        get() = props.mapNotNull { (key, value) -> (value as? JSONValue.Object)?.let { key to it.fields } }.toMap()
}

/** A building document (`DocumentResponse`): `GET /v1/visit/documents`, `GET /v1/buildings/{id}/documents`. */
@Serializable
data class BuildingDocument(
    val id: String,
    val title: String,
    val kind: DocumentKind,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val elementId: String?,
    val visibleToGuests: Boolean,
    val uploaded: Boolean,
    val url: String?,
    @Serializable(with = ISO8601InstantSerializer::class) val createdAt: Instant,
) {
    /** Presigned download, valid for 15 minutes; `null` until the upload is confirmed. */
    val downloadURL: URI? get() = uriOrNull(url)
}

/** `GET /v1/visit/search` and `GET /v1/buildings/{id}/search` (`SearchResponse`). */
@Serializable
data class SearchResults(val query: String, val hits: List<SearchHit>)

/** One result: `id` is the element or space id to select in the viewer. */
@Serializable
data class SearchHit(
    val type: SearchHitType,
    val id: String,
    val title: String,
    val kind: String?,
    val kindName: String?,
    val system: String?,
    val tag: String?,
    val room: String?,
    val storey: String?,
)

// MARK: Analytics

/** One event in `POST /v1/events` (`EventInput`). */
@Serializable
data class EventInput(
    val type: String,
    val props: JSONValue? = null,
    @Serializable(with = ISO8601InstantSerializer::class) val occurredAt: Instant?,
)

/** `POST /v1/events` (`EventsRequest`). `buildingId` is required with a user token, ignored with a visit token. */
@Serializable
data class EventsRequest(val buildingId: String?, val events: List<EventInput>)

@Serializable
data class EventsResponse(val accepted: Int)

// MARK: Auth

@Serializable
data class LoginRequest(val email: String, val password: String, val deviceName: String?)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
    val termsVersion: String,
    val locale: String?,
    val deviceName: String?,
)

@Serializable
data class RefreshRequest(val refreshToken: String?)

@Serializable
data class OAuthExchangeRequest(val code: String, val deviceName: String?)

@Serializable
data class OAuthProvidersResponse(val providers: List<String>)

/**
 * Tokens from `POST /v1/auth/login`, `/register`, `/refresh` and `/oauth/exchange`. Native clients
 * get the refresh token in the body (the web gets a cookie instead).
 */
@Serializable
data class AuthResponse(
    val accessToken: String,
    @Serializable(with = ISO8601InstantSerializer::class) val accessTokenExpiresAt: Instant,
    val refreshToken: String?,
    @Serializable(with = ISO8601InstantSerializer::class) val refreshTokenExpiresAt: Instant,
    val user: Me,
) {
    /** `null` for a web-style response (refresh token in a cookie, not the body). */
    val tokens: AuthTokens?
        get() = refreshToken?.let {
            AuthTokens(
                accessToken = accessToken,
                accessTokenExpiresAt = accessTokenExpiresAt,
                refreshToken = it,
                refreshTokenExpiresAt = refreshTokenExpiresAt,
            )
        }
}

/**
 * A signed-in user's tokens, kept encrypted on the device (docs/PLAN.md §3). Not an API record: the
 * stored form of an [AuthResponse].
 */
@Serializable
data class AuthTokens(
    val accessToken: String,
    @Serializable(with = ISO8601InstantSerializer::class) val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    @Serializable(with = ISO8601InstantSerializer::class) val refreshTokenExpiresAt: Instant,
)

// MARK: Me

/** `GET /v1/me` (`MeResponse`). */
@Serializable
data class Me(
    val id: String,
    val email: String,
    val emailConfirmed: Boolean,
    val displayName: String,
    val locale: String,
    val units: UnitSystem,
    val marketingEmails: Boolean,
    val organizations: List<MyOrganization>,
)

/** One of my organizations in `Me.organizations`, with my role. */
@Serializable
data class MyOrganization(val id: String, val name: String, val role: OrganizationRole) {
    val organizationID: UUID? get() = uuidOrNull(id)
}

/** `PATCH /v1/me`: only the fields given change. */
@Serializable
data class UpdateMeRequest(
    val displayName: String? = null,
    val locale: String? = null,
    val units: UnitSystem? = null,
    val marketingEmails: Boolean? = null,
)

/** `GET /v1/me/buildings`: a building I can open, and how (member, grant, saved) (`MyBuildingResponse`). */
@Serializable
data class MyBuilding(
    val id: String,
    val code: String,
    val displayCode: String,
    val name: String,
    val city: String?,
    val status: BuildingStatus,
    val isLive: Boolean,
    val organizationId: String,
    val organizationName: String?,
    val via: MyBuildingVia,
    /** The organization role (Owner, Admin, Member) or the grant role (Owner, Viewer, Contractor). */
    val role: String,
    /** `null` means every system. */
    val systems: List<String>?,
    @Serializable(with = ISO8601InstantSerializer::class) val accessExpiresAt: Instant?,
    @Serializable(with = ISO8601InstantSerializer::class) val verifiedAt: Instant?,
) {
    val buildingID: UUID? get() = uuidOrNull(id)
    val organizationUUID: UUID? get() = uuidOrNull(organizationId)
    val buildingCode: BuildingCode? get() = BuildingCode.parse(code)

    /**
     * Members see the building's billing status (A-02: "Trial · 9 days left", "Active until
     * 2036", "Paused"); grant holders and saved buildings don't.
     */
    val showsStatus: Boolean get() = via == MyBuildingVia.MEMBER

    /** Members can open anything with a model; a draft has none yet. */
    val canOpen: Boolean get() = status != BuildingStatus.DRAFT
}

/** `GET /v1/me/search`: buildings by name or address, and elements across my buildings (`MySearchResponse`). */
@Serializable
data class MySearchResults(val query: String, val buildings: List<MySearchBuilding>, val hits: List<MySearchHit>)

@Serializable
data class MySearchBuilding(val id: String, val code: String, val name: String, val addressLine: String?, val city: String?) {
    val buildingCode: BuildingCode? get() = BuildingCode.parse(code)
}

@Serializable
data class MySearchHit(val buildingId: String, val buildingName: String, val hit: SearchHit) {
    val buildingUUID: UUID? get() = uuidOrNull(buildingId)
}

// MARK: Members

/** `GET /v1/orgs`: one of my organizations, with its trial's end (`OrganizationResponse`). */
@Serializable
data class Organization(
    val id: String,
    val name: String,
    val type: OrganizationType,
    val country: String,
    val timeZone: String,
    val currency: Currency,
    @Serializable(with = ISO8601InstantSerializer::class) val trialEndsAt: Instant,
    val inTrial: Boolean,
    val showBranding: Boolean,
    val logoUrl: String?,
    val role: OrganizationRole,
    @Serializable(with = ISO8601InstantSerializer::class) val createdAt: Instant,
    val billing: BillingDetailsResponse? = null,
) {
    val organizationID: UUID? get() = uuidOrNull(id)
}

@Serializable
data class BillingDetailsResponse(val legalName: String, val taxId: String, val postalCode: String, val addressNumber: String)

/** `GET /v1/buildings/{id}` (members): status, expiry, role (`BuildingResponse`). */
@Serializable
data class BuildingDetail(
    val id: String,
    val organizationId: String,
    val code: String,
    val displayCode: String,
    val name: String,
    val addressLine: String?,
    val city: String?,
    val region: String?,
    val country: String?,
    val postalCode: String?,
    val status: BuildingStatus,
    val isLive: Boolean,
    val floorAreaM2: Double?,
    val floorAreaMethod: String?,
    val units: Int,
    val currentVersionId: String?,
    @Serializable(with = ISO8601InstantSerializer::class) val verifiedAt: Instant?,
    @Serializable(with = ISO8601InstantSerializer::class) val trialLiveAt: Instant?,
    @Serializable(with = ISO8601InstantSerializer::class) val activatedAt: Instant?,
    @Serializable(with = ISO8601InstantSerializer::class) val expiresAt: Instant?,
    val pinEnabled: Boolean,
    @Serializable(with = ISO8601InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = ISO8601InstantSerializer::class) val archivedAt: Instant?,
    val role: OrganizationRole,
    val tier: Tier,
    val activationCredits: Int?,
) {
    val buildingID: UUID? get() = uuidOrNull(id)
    val organizationUUID: UUID? get() = uuidOrNull(organizationId)
}

/** `GET /v1/buildings/{id}/plates`: a plate, placed or not, with its AR reference image (`PlateResponse`). */
@Serializable
data class BuildingPlate(
    val id: String,
    val number: Int,
    val label: String,
    val targetUrl: String,
    /** Printed width of the plate image, quiet zone included. */
    val sizeMm: Int,
    val placed: Boolean,
    val qrSvgUrl: String,
    val qrPngUrl: String,
    val imageUrl: String,
) {
    val plateID: UUID? get() = uuidOrNull(id)

    /** The QR with its quiet zone, ~1024 px: the AR reference image at `sizeMm`. */
    val imageURL: URI? get() = uriOrNull(imageUrl)
}

/** `PATCH /v1/plates/{id}`: every field is optional; `clearPlacement` removes the placement. */
@Serializable
data class UpdatePlateRequest(
    val label: String?,
    val sizeMm: Int?,
    val placement: PlatePlacementRequest? = null,
    val clearPlacement: Boolean?,
)

/** A placement in the model coordinates (metres, Y-up) of `versionId`. */
@Serializable
data class PlatePlacementRequest(
    val versionId: String,
    val position: List<Double>,
    val normal: List<Double>,
    val up: List<Double>,
)

// MARK: Room corrections

/** `POST /v1/visit/room-observations`: a "Fix here" in AR, in the visited version's model coordinates. */
@Serializable
data class RoomObservationRequest(
    val spaceId: String,
    val baseSpaceId: String?,
    val anchor: List<Double>,
    val markInBase: List<Double>,
    val turn: Double?,
    val hasTranslation: Boolean,
    val baseDistance: Double,
    val lidar: Boolean,
)

/** `PUT /v1/buildings/{id}/room-corrections/{spaceId}`: as in the manifest's `correction`. */
@Serializable
data class SetRoomCorrectionRequest(val versionId: String, val pivot: List<Double>, val offset: List<Double>, val yaw: Double)

/** A room's corrections as the dashboard lists them (`RoomCorrectionResponse`). */
@Serializable
data class RoomCorrectionSummary(
    val spaceId: String,
    val name: String?,
    val longName: String?,
    /** The one in use; `null`: none yet. */
    val source: RoomCorrectionSource? = null,
    val admin: RoomAdjustment? = null,
    @Serializable(with = ISO8601InstantSerializer::class) val adminUpdatedAt: Instant?,
    val visitors: RoomAdjustment? = null,
    val measurements: Int,
    val devices: Int,
    val agreeingDevices: Int,
    val devicesNeeded: Int,
    @Serializable(with = ISO8601InstantSerializer::class) val lastMeasuredAt: Instant?,
)

/** How far a room is moved (metres) and turned (degrees) from the model. */
@Serializable
data class RoomAdjustment(val shiftM: Double, val turnDeg: Double)

/** `ProblemDetails` as the spec documents it; [ApiError.decode] reads error bodies leniently. */
@Serializable
data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val code: String,
    val traceId: String? = null,
    val errors: Map<String, List<String>>? = null,
    val attemptsLeft: Int? = null,
    val retryAfter: Int? = null,
)
