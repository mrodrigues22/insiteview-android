package com.getinsiteview.api

import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.DeepLink
import com.getinsiteview.core.ISO8601InstantSerializer
import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.geometry.RoomObservation
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** How a building was opened: a guest visit from a QR code or link, or a member's own scope. */
sealed interface BuildingAccess {
    /** A Building QR, optionally from a numbered plate (`/b/{code}/{plate}`). */
    data class Code(val code: BuildingCode, override val plate: Int?, val pin: String? = null) : BuildingAccess

    /** A temporary or preview link (`/a/{token}`). */
    data class Link(val token: String) : BuildingAccess

    /** Signed in with access to the building (`/v1/buildings/{id}/…`). */
    data class Member(val buildingId: UUID) : BuildingAccess

    /** The plate number from a plate's QR, which AR aligns on first (IOS-M2-05). */
    val plate: Int? get() = null

    companion object {
        fun of(deepLink: DeepLink): BuildingAccess = when (deepLink) {
            is DeepLink.Building -> Code(deepLink.code, plate = deepLink.plate)
            is DeepLink.AccessLink -> Link(deepLink.token)
        }
    }
}

/** Who analytics events are sent as (`POST /v1/events`). */
@Serializable
sealed interface AnalyticsAudience {
    /** A guest visit: its token, valid until [expiresAt]. */
    @Serializable
    @SerialName("visit")
    data class Visit(
        val token: String,
        @Serializable(with = ISO8601InstantSerializer::class) val expiresAt: Instant,
    ) : AnalyticsAudience

    /** A signed-in member or grant holder: their bearer token plus the building id. */
    @Serializable
    @SerialName("member")
    data class Member(@Serializable(with = UUIDStringSerializer::class) val buildingId: UUID) : AnalyticsAudience
}

/** `UUID` as its string form. */
internal object UUIDStringSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.getinsiteview.api.UUID", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString().lowercase())
    }
}

/**
 * One open building's calls: starts the visit, then serves the manifest, element details,
 * documents and search with the visit token, starting a new visit when the token expires (≤ 12 h,
 * master PLAN §10; an unlock keeps the original expiry). A PIN entered with [unlock] is kept for
 * those new visits. Every `/v1/visit/…` call re-checks access, so any of them can throw
 * `link.revoked`, `link.expired`, `building.paused`, `building.not_live` or `not_found`
 * mid-session. Signed in, the visit request carries the user's token (bearer interceptor), so a
 * member or grant holder gets their own scope (`via` says which); with [BuildingAccess.Member]
 * access the member endpoints are used instead of a visit.
 *
 * @param api the shared client (device id, language and bearer interceptors).
 */
class BuildingConnection(
    val access: BuildingAccess,
    private val api: ApiClient,
    private val deviceId: String,
    private val platform: VisitPlatform,
    private val now: () -> Instant = Instant::now,
) {
    private val tokens = VisitTokenStore()
    private val visitAPI: ApiClient = api.withVisitToken(tokens)
    private val mutex = Mutex()

    @Volatile
    var visit: Visit? = null
        private set

    /** The building's PIN once it unlocked this visit (memory only). */
    @Volatile
    private var pin: String? = (access as? BuildingAccess.Code)?.pin
    private var starting: CompletableDeferred<Visit>? = null

    /**
     * Starts the visit (guests). Throws [ApiError] with `building.not_live`, `building.paused`,
     * `link.expired`, `link.revoked`, `not_found`, … Members get `null`. A PIN building opened
     * without its PIN gives a visit with `pinLocked` (architecture only).
     */
    suspend fun start(): Visit? = when (access) {
        is BuildingAccess.Member -> null
        is BuildingAccess.Code, is BuildingAccess.Link -> startVisit()
    }

    /**
     * Enters the building's PIN (`POST /v1/visit/unlock`): the new visit sees every system, so
     * fetch the manifest again. Throws `pin.invalid` ([ApiError.attemptsLeft]), `pin.locked`
     * ([ApiError.retryAfter]) or `validation` for a malformed PIN.
     */
    suspend fun unlock(pin: String): Visit {
        if (tokens.validToken(now()) == null) startVisit()
        val unlocked = try {
            visitAPI.unlockVisit(pin)
        } catch (error: ApiError) {
            if (!isExpiredVisit(error)) throw error
            tokens.clear()
            startVisit()
            visitAPI.unlockVisit(pin)
        }
        this.pin = pin
        store(unlocked)
        return unlocked
    }

    /** One visit request at a time; everyone waiting resumes after the token is stored. */
    private suspend fun startVisit(): Visit {
        var owner = false
        val deferred = mutex.withLock {
            starting ?: CompletableDeferred<Visit>().also {
                starting = it
                owner = true
            }
        }
        if (!owner) return deferred.await()
        try {
            val visit = requestVisit()
            store(visit)
            deferred.complete(visit)
            return visit
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            mutex.withLock { if (starting === deferred) starting = null }
        }
    }

    private suspend fun requestVisit(): Visit = when (val access = access) {
        is BuildingAccess.Code -> {
            val pin = pin
            try {
                api.startVisit(access.code, plate = access.plate, pin = pin, deviceId = deviceId, platform = platform)
            } catch (error: ApiError) {
                if (pin == null || !isPinProblem(error)) throw error
                // The PIN changed (or is locked) since it was entered: carry on locked, with
                // architecture only, rather than lose the building.
                this.pin = null
                api.startVisit(access.code, plate = access.plate, pin = null, deviceId = deviceId, platform = platform)
            }
        }
        is BuildingAccess.Link -> api.startLinkVisit(access.token, deviceId = deviceId, platform = platform)
        is BuildingAccess.Member -> error("Members don't start visits.")
    }

    private fun store(visit: Visit) {
        this.visit = visit
        tokens.set(visit.visitToken, visit.expiresAt)
    }

    /** The scoped manifest. Fetch it again after `urlsExpireAt` for fresh URLs. */
    suspend fun manifest(): Manifest = when (val access = access) {
        is BuildingAccess.Member -> api.buildingManifest(access.buildingId)
        else -> withVisit { it.visitManifest() }
    }

    /** Every property of one element (the object card's full detail). */
    suspend fun element(id: String): ElementDetail = when (val access = access) {
        is BuildingAccess.Member -> api.buildingElement(access.buildingId, id)
        else -> withVisit { it.visitElement(id) }
    }

    /**
     * Documents, optionally for one element: those shared with guests (within the visit's systems)
     * on a visit, every document with member access.
     */
    suspend fun documents(elementId: String? = null): List<BuildingDocument> = when (val access = access) {
        is BuildingAccess.Member -> api.buildingDocuments(access.buildingId, elementId)
        else -> withVisit { it.visitDocuments(elementId) }
    }

    /** Server search within the visit's (or the member's) scope. */
    suspend fun search(query: String): SearchResults = when (val access = access) {
        is BuildingAccess.Member -> api.searchBuilding(access.buildingId, query)
        else -> withVisit { it.visitSearch(query) }
    }

    /**
     * Sends a "Fix here" that measured its room (master PLAN §9 "Room corrections"). Members
     * opening the building by id have no visit, and send nothing.
     */
    suspend fun recordRoomObservation(observation: RoomObservation, lidar: Boolean) {
        if (access is BuildingAccess.Member) return
        withVisit { it.visitRoomObservation(observation, lidar) }
    }

    /** Who this building's analytics events are sent as; `null` before a guest visit starts. */
    fun analyticsAudience(): AnalyticsAudience? = when (val access = access) {
        is BuildingAccess.Member -> AnalyticsAudience.Member(access.buildingId)
        else -> visit?.let { AnalyticsAudience.Visit(it.visitToken, it.expiresAt) }
    }

    /**
     * Runs a visit call with a valid token: starts a visit when there is none or it expired, and
     * once more after the API says the token is no good (`auth.required`).
     */
    private suspend fun <T> withVisit(call: suspend (ApiClient) -> T): T {
        if (tokens.validToken(now()) == null) startVisit()
        return try {
            call(visitAPI)
        } catch (error: ApiError) {
            if (!isExpiredVisit(error)) throw error
            tokens.clear()
            startVisit()
            call(visitAPI)
        }
    }

    companion object {
        internal fun isPinProblem(error: ApiError): Boolean =
            error.code == ApiError.Code.PIN_INVALID || error.code == ApiError.Code.PIN_LOCKED ||
                error.code == ApiError.Code.PIN_REQUIRED

        /** A 401 that isn't about the PIN: the visit token expired or was rejected. */
        internal fun isExpiredVisit(error: ApiError): Boolean =
            error.status == 401 && error.code != ApiError.Code.PIN_REQUIRED
    }
}
