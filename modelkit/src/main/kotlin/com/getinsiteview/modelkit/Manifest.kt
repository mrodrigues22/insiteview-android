package com.getinsiteview.modelkit

import com.getinsiteview.core.APIJSON
import com.getinsiteview.core.ISO8601InstantSerializer
import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.Vec3
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The manifest a building loads from (master PLAN §9 "Manifest"), as served by
 * `GET /v1/visit/manifest` and `GET /v1/buildings/{id}/manifest`: the chunks the caller's scope
 * allows, with presigned URLs that expire at [urlsExpireAt].
 *
 * Model coordinates: metres, right-handed, Y up. IFC `(x, y, z)` → model
 * `(x − ox, z − oz, −(y − oy))` with `origin.offset = (ox, oy, oz)`; see [ModelFrame].
 *
 * `spaces` and `plates` arrived after schema 1's first release: a manifest without them has none.
 */
@Serializable
data class Manifest(
    val schema: Int,
    val building: Building,
    @Serializable(with = UUIDSerializer::class) val versionId: UUID,
    val units: String,
    val upAxis: String,
    val origin: Origin,
    val bounds: BoundsDTO,
    val storeys: List<Storey>,
    /** Rooms (IFC spaces) with their names; element meta `r` refers to them by id. */
    val spaces: List<Space> = emptyList(),
    val systems: List<SystemSummary>,
    val chunks: List<Chunk>,
    val plates: List<Plate> = emptyList(),
    val stats: Stats,
    @Serializable(with = URISerializer::class) val thumbnailUrl: URI? = null,
    @Serializable(with = ISO8601InstantSerializer::class) val urlsExpireAt: Instant,
) {
    @Serializable
    data class Building(val code: String, val name: String)

    @Serializable
    data class Origin(
        /** `[ox, oy, oz]` in IFC coordinates. */
        val offset: List<Double>,
    )

    /** `{ min: [x, y, z], max: [x, y, z] }` in model coordinates. */
    @Serializable
    data class BoundsDTO(val min: List<Double>, val max: List<Double>)

    @Serializable
    data class Storey(
        /** `e` + hex GlobalId, the same id elements refer to in meta `s`. */
        val id: String,
        val name: String? = null,
        /** Model metres (the lowest storey is at 0). */
        val elevation: Double,
        /** 0 for the lowest storey. */
        val order: Int,
    )

    /** A room (IFC space). The Rooms list and "focus the room" (IOS-M2-08) come from these. */
    @Serializable
    data class Space(
        /** `e` + hex GlobalId, the id element meta `r` refers to. */
        val id: String,
        /** Usually the room number ("R1"). */
        val name: String? = null,
        /** Usually the room's name ("Kitchen"). */
        val longName: String? = null,
        val storeyId: String? = null,
        val areaM2: Double? = null,
        /** The floor height, model metres; `null` when the file has no room geometry. */
        val floorY: Double? = null,
        /**
         * The floor outline seen from above as model `[x, z]` pairs; `null` without room geometry.
         * The room is on the left of every edge: inward normal = up × (next − this). AR alignment
         * by reference points takes the room's corners and walls from it.
         */
        val outline: List<List<Double>>? = null,
        /**
         * Where the room really is compared with the model, once an admin saved it or visitors'
         * "Fix here"s agree on it (master PLAN §9 "Room corrections"); see `RoomCorrection`.
         */
        val correction: Correction? = null,
    ) {
        /**
         * A model point p of the room is really at Rot(yaw)·(p − pivot) + pivot + offset: `yaw`
         * radians about +Y, `offset` horizontal, model coordinates.
         */
        @Serializable
        data class Correction(val pivot: List<Double>, val offset: List<Double>, val yaw: Double)

        /** "Kitchen" over "R1" when the model has both. */
        val displayName: String? get() = longName ?: name
    }

    @Serializable
    data class SystemSummary(val key: String, val elementCount: Int, val subsystems: List<String>) {
        val id: String get() = key
    }

    @Serializable
    data class Chunk(
        /** `architecture`, `electrical`, … (one per system for now). */
        val key: String,
        val system: String,
        /** `null` means every storey (large buildings may later split chunks per storey). */
        val storey: String? = null,
        val glb: File,
        val usdz: File,
        val meta: File,
    ) {
        val id: String get() = key

        fun file(kind: ChunkFileKind): File = when (kind) {
            ChunkFileKind.GLB -> glb
            ChunkFileKind.USDZ -> usdz
            ChunkFileKind.META -> meta
        }
    }

    /**
     * One chunk file. [bytes] and [sha256] describe the file after transfer decoding (the stored
     * object is gzip, `Content-Encoding: gzip`, which the transport decodes).
     */
    @Serializable
    data class File(
        @Serializable(with = URISerializer::class) val url: URI,
        val bytes: Long,
        /** Lower-case hex; the cache key. */
        val sha256: String,
        /** `gzip` when stored compressed. */
        val encoding: String? = null,
    )

    /** A placed plate, in model coordinates, with the QR image the app registers for AR. */
    @Serializable
    data class Plate(
        val number: Int,
        val label: String,
        /** Centre of the plate image, model metres. */
        val position: List<Double>,
        /** Out of the surface the plate is on. */
        val normal: List<Double>,
        /** The image's up direction (for a wall plate, usually +Y). */
        val up: List<Double>,
        /** Printed width of the whole image, quiet zone included: the reference image's physical width. */
        val sizeMm: Int,
        /** `/v1/plates/{id}/image.png`: the QR with its 4-module quiet zone. */
        @Serializable(with = URISerializer::class) val imageUrl: URI,
    ) {
        val id: Int get() = number

        /** Printed width in metres. */
        val physicalWidth: Double get() = sizeMm.toDouble() / 1000
    }

    @Serializable
    data class Stats(val elements: Int, val floorAreaM2: Double? = null)

    // Decoding

    sealed class DecodingProblem(message: String) : Exception(message) {
        /** The manifest's `schema` is outside [supportedSchemas]: the app needs an update. */
        data class UnsupportedSchema(val schema: Int) :
            DecodingProblem("Manifest schema $schema is not supported (this app reads $schemaRangeDescription).")

        data class UnsupportedAxes(val units: String, val upAxis: String) :
            DecodingProblem("Manifest units $units / up axis $upAxis are not supported (expected m / Y).")

        data class InvalidVector(val field: String) :
            DecodingProblem("Manifest field $field is not a 3-component vector.")

        data class InvalidField(val field: String) : DecodingProblem("Manifest field $field is invalid.")

        /** Swift's `description`. */
        val description: String get() = message!!
    }

    /** Checks a manifest: a supported schema, metres, Y up, 3-component vectors. */
    @Throws(DecodingProblem::class)
    fun validate() {
        if (schema !in supportedSchemas) throw DecodingProblem.UnsupportedSchema(schema)
        if (units.lowercase() != "m" || upAxis.uppercase() != "Y") {
            throw DecodingProblem.UnsupportedAxes(units = units, upAxis = upAxis)
        }
        if (origin.offset.size != 3) throw DecodingProblem.InvalidVector("origin.offset")
        if (bounds.min.size != 3 || bounds.max.size != 3) throw DecodingProblem.InvalidVector("bounds")
        for (plate in plates) {
            if (plate.position.size != 3 || plate.normal.size != 3 || plate.up.size != 3) {
                throw DecodingProblem.InvalidVector("plates[${plate.number}]")
            }
        }
    }

    // Helpers

    val frame: ModelFrame get() = ModelFrame(offset = Vec3.of(origin.offset))

    /** Whole-model bounds in model coordinates. */
    val modelBounds: Bounds get() = Bounds(min = Vec3.of(bounds.min), max = Vec3.of(bounds.max))

    fun chunk(key: String): Chunk? = chunks.firstOrNull { it.key == key }

    /** Storeys from the lowest up. */
    val storeysByOrder: List<Storey> get() = storeys.sortedBy { it.order }

    /**
     * The level AR starts on, whose floor goes on the detected floor: the lowest with rooms. The
     * lowest of all is often a foundation (the Duplex's "T/FDN", a Revit "Foundation") holding only
     * footings, which left AR with nothing to show. The lowest level when no room says its storey.
     */
    val startingStorey: Storey?
        get() {
            val withRooms = spaces.mapNotNull { it.storeyId }.toSet()
            val ordered = storeysByOrder
            return ordered.firstOrNull { it.id in withRooms } ?: ordered.firstOrNull()
        }

    fun storey(id: String): Storey? = storeys.firstOrNull { it.id == id }

    fun space(id: String): Space? = spaces.firstOrNull { it.id == id }

    fun plate(number: Int): Plate? = plates.firstOrNull { it.number == number }

    /** System keys in scope (the served manifest lists only those). */
    val systemKeys: Set<String> get() = systems.map { it.key }.toSet()

    /**
     * Whether the presigned URLs are about to expire; fetch the manifest again first (unchanged
     * chunks then come from the cache).
     */
    fun urlsNeedRefresh(at: Instant, margin: Duration = 60.seconds): Boolean =
        !at.plusNanos(margin.inWholeNanoseconds).isBefore(urlsExpireAt)

    companion object {
        /**
         * The asset contract versions this client reads (master PLAN §9 "Schema and client
         * compatibility"). A breaking change bumps the schema: widen the range once this app reads
         * the new one, and keep older ones while it still can.
         */
        val supportedSchemas: IntRange = 1..1

        /** "schema 1" or "schemas 1–2". */
        internal val schemaRangeDescription: String
            get() {
                val range = supportedSchemas
                return if (range.first == range.last) "schema ${range.first}" else "schemas ${range.first}–${range.last}"
            }

        /** Decodes and checks a manifest: a supported schema, metres, Y up, 3-component vectors. */
        fun decode(data: ByteArray): Manifest = decode(data.decodeToString())

        fun decode(string: String): Manifest {
            val manifest = APIJSON.json.decodeFromString(serializer(), string)
            manifest.validate()
            return manifest
        }
    }
}

/**
 * Conversions between IFC coordinates (Z up, project origin) and model coordinates (Y up,
 * recentred), master PLAN §9 "Axes and units".
 */
data class ModelFrame(
    /**
     * `(ox, oy, oz)` in IFC coordinates: the horizontal bounding-box centre and the elevation of
     * the lowest storey.
     */
    val offset: Vec3,
) {
    /** IFC `(x, y, z)` → model `(x − ox, z − oz, −(y − oy))`. */
    fun modelPoint(fromIFC: Vec3): Vec3 =
        Vec3(fromIFC.x - offset.x, fromIFC.z - offset.z, -(fromIFC.y - offset.y))

    /** Model → IFC, the inverse of [modelPoint]. */
    fun ifcPoint(fromModel: Vec3): Vec3 =
        Vec3(fromModel.x + offset.x, -fromModel.z + offset.y, fromModel.y + offset.z)

    companion object {
        /** Directions ignore the offset: IFC `(x, y, z)` → model `(x, z, −y)`. */
        fun modelDirection(fromIFC: Vec3): Vec3 = Vec3(fromIFC.x, fromIFC.z, -fromIFC.y)
    }
}

/** `UUID` as its string form (Swift's `Codable` UUID). */
internal object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.getinsiteview.modelkit.UUID", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
}

/** `URI` as a string (Swift's `Codable` URL). */
internal object URISerializer : KSerializer<URI> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.getinsiteview.modelkit.URI", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): URI = URI(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: URI) = encoder.encodeString(value.toString())
}
