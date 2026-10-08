package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.Manifest
import kotlin.math.abs

/**
 * Where a room really is compared with the model (docs/PLAN.md §3 "Room corrections", master
 * PLAN §9): turned by [yaw] about a vertical axis through [pivot], then moved by [offset].
 * The model is the design and each room is built a few centimetres off it; an admin saves this
 * from a "Fix here", or visitors' fixes agree on it, so the next visit starts with the room where
 * it is.
 *
 * The offset's y is dropped and the yaw wrapped to (−π, π] on construction. Equality is IEEE.
 */
class RoomCorrection(
    /** Model coordinates; only its x and z matter. */
    val pivot: Vec3,
    offset: Vec3,
    yaw: Double,
) {
    /** Horizontal, model metres. */
    val offset: Vec3 = Vec3(offset.x, 0.0, offset.z)

    /** Radians about +Y, as [YawTransform]. */
    val yaw: Double = YawTransform.normalized(yaw)

    /** The same move expressed about [pivot]: what [transform] does to the room, horizontally. */
    constructor(transform: YawTransform, pivot: Vec3) : this(
        pivot = pivot,
        offset = transform.apply(pivot).let { moved -> Vec3(moved.x - pivot.x, 0.0, moved.z - pivot.z) },
        yaw = transform.yaw,
    )

    /** Model → as built: p ↦ Rot(yaw)·(p − pivot) + pivot + offset. */
    val transform: YawTransform
        get() {
            val flatPivot = Vec3(pivot.x, 0.0, pivot.z)
            return YawTransform(yaw = yaw, translation = flatPivot + offset - YawTransform.rotate(flatPivot, by = yaw))
        }

    /** For the API: model coordinates, as the manifest has it. */
    val manifest: Manifest.Space.Correction
        get() = Manifest.Space.Correction(pivot = listOf(pivot.x, pivot.y, pivot.z), offset = listOf(offset.x, 0.0, offset.z), yaw = yaw)

    /** How far the room moves at its pivot, metres. */
    val shift: Double get() = Vector.length(offset)

    fun copy(pivot: Vec3 = this.pivot, offset: Vec3 = this.offset, yaw: Double = this.yaw): RoomCorrection = RoomCorrection(pivot, offset, yaw)

    override fun equals(other: Any?): Boolean =
        other is RoomCorrection && pivot == other.pivot && offset == other.offset && yaw == other.yaw

    override fun hashCode(): Int = (pivot.hashCode() * 31 + offset.hashCode()) * 31 + (yaw + 0.0).hashCode()

    override fun toString(): String = "RoomCorrection(pivot=$pivot, offset=$offset, yaw=$yaw)"

    companion object {
        /** No correction, about [pivot]. */
        fun none(pivot: Vec3): RoomCorrection = RoomCorrection(pivot = pivot, offset = Vec3.zero, yaw = 0.0)

        /** From the manifest; `null` when it isn't three finite components each (iOS `init?(_ correction:)`). */
        fun of(correction: Manifest.Space.Correction): RoomCorrection? {
            if (correction.pivot.size != 3 || correction.offset.size != 3 || !correction.yaw.isFinite() ||
                !(correction.pivot + correction.offset).all { it.isFinite() }
            ) {
                return null
            }
            return RoomCorrection(pivot = Vec3.of(correction.pivot), offset = Vec3.of(correction.offset), yaw = correction.yaw)
        }
    }
}

/**
 * A "Fix here" as the API pools it (`POST /v1/visit/room-observations`): where a corner of room
 * [spaceID] really is, measured from the alignment that was placing the building, made in
 * another room or on a plate. One fix mixes the room's real offset with the session's drift; the
 * server applies a correction once fixes from several devices agree (master PLAN §9).
 *
 * It's relative to the base room's model frame, so the server resolves it with the base room's
 * correction as it stands then, not as it was served for this visit.
 */
data class RoomObservation(
    val spaceID: String,
    /** The room the alignment it was measured from was made in (`null`: a plate on no room's wall). */
    val baseSpaceID: String?,
    /** The model corner fixed (uncorrected model coordinates). */
    val anchor: Vec3,
    /** Where it really is, in the base room's model frame. */
    val markInBase: Vec3,
    /**
     * The room's turn relative to the base room's model frame, when the fix measured it (two of
     * its corners in a row, or a wall).
     */
    val turn: Double?,
    /** `false` for a fix against a wall: it gives the turn, not where the room is along the wall. */
    val hasTranslation: Boolean,
    /** From that alignment's spot to the mark, metres: tracking drifts with distance. */
    val baseDistance: Double,
    /**
     * The room's correction this fix alone implies (with the base room as served), about the
     * room's centroid: what an admin saves.
     */
    val correction: RoomCorrection,
)

/**
 * The building's rooms with their corrections (docs/PLAN.md §3 "Room corrections"). Alignment
 * works in the as-built frame: room outlines and plates move with their room's correction before
 * the marks and detections are matched, so a local alignment's transform places the as-built
 * building. The building is then drawn with the correction of the room the camera is in.
 */
data class RoomCorrections(
    /** Rooms with an outline, in the manifest's order. */
    val rooms: List<Room>,
) {
    data class Room(
        val id: String,
        /** As in the model. */
        val outline: RoomOutline,
        val correction: RoomCorrection?,
    ) {
        /** Where the room really is. */
        val corrected: RoomOutline = correction?.let { outline.moved(by = it.transform) } ?: outline

        /** Model → as built (identity without a correction). */
        val transform: YawTransform get() = correction?.transform ?: YawTransform.identity

        /** The correction, or none about the room's centroid. */
        val correctionOrNone: RoomCorrection get() = correction ?: RoomCorrection.none(pivot = outline.centroid)
    }

    /** How far a correction would move a room ([change]). */
    data class Change(val shift: Double, val turn: Double)

    /** Whether any room is corrected. */
    val hasCorrections: Boolean get() = rooms.any { it.correction != null }

    fun room(id: String?): Room? {
        if (id == null) return null
        return rooms.firstOrNull { it.id == id }
    }

    /** Model → as built for [room] (identity when unknown or uncorrected). */
    fun transform(of: String?): YawTransform = room(of)?.transform ?: YawTransform.identity

    /** The building drawn for a camera in [room]: its correction, then the alignment. */
    fun display(alignment: YawTransform, room: String?): YawTransform = transform(of = room).then(alignment)

    /**
     * The room [containing] (as built) is in. Another room than [previous] takes over only once the
     * point is [SWITCH_DEPTH] inside it, so a doorway doesn't flick between two corrections;
     * outside every room, [previous] stays.
     */
    fun room(containing: Vec3, previous: String?): String? {
        val point = containing
        val index = RoomOutline.index(containing = point, outlines = rooms.map { it.corrected }) ?: return previous
        val candidate = rooms[index]
        if (previous == null || candidate.id == previous || room(previous) == null) return candidate.id
        val depth = candidate.corrected.nearestWall(to = point)?.signedDistance ?: 0.0
        return if (depth >= SWITCH_DEPTH) candidate.id else previous
    }

    /**
     * The room a plate is on the wall, floor or ceiling of, in the model: the one just in front
     * of it (the smallest, where rooms overlap).
     */
    fun room(ofPlate: PlateFrame): String? {
        val front = ofPlate.position + Vector.normalized(ofPlate.normal) * PLATE_REACH
        return rooms
            .filter { (front.y - it.outline.floorY) in PLATE_HEIGHTS && it.outline.contains(front) }
            .minByOrNull { it.outline.area }?.id
    }

    /** A plate where it really is: moved with its room. */
    fun corrected(plate: Manifest.Plate): Manifest.Plate {
        val move = transform(of = room(ofPlate = plate.frame))
        if (move == YawTransform.identity) return plate
        val frame = plate.frame
        val position = move.apply(frame.position)
        val normal = YawTransform.rotate(frame.normal, by = move.yaw)
        val up = YawTransform.rotate(frame.up, by = move.yaw)
        return plate.copy(
            position = listOf(position.x, position.y, position.z),
            normal = listOf(normal.x, normal.y, normal.z),
            up = listOf(up.x, up.y, up.z),
        )
    }

    /**
     * "Fix here" against the rooms where they really are ([ReferenceAlignment.reanchor]),
     * keeping which room the corner is in.
     */
    fun reanchor(alignment: YawTransform, mark: Vec3, seenFrom: Vec3?): ReferenceAlignment.CornerFix? {
        val fix = ReferenceAlignment.reanchor(alignment, mark = mark, seenFrom = seenFrom, rooms = rooms.map { it.corrected }) ?: return null
        return fix.copy(spaceID = rooms[fix.room].id)
    }

    /**
     * How far [to] would move room [of] from the correction in use: metres at its pivot,
     * and the turn in radians (0…π).
     */
    fun change(of: String, to: RoomCorrection): Change {
        val correction = to
        val current = room(of)?.correctionOrNone ?: RoomCorrection.none(pivot = correction.pivot)
        val shift = ReferenceAlignment.horizontalDistance(
            correction.transform.apply(correction.pivot),
            current.transform.apply(correction.pivot),
        )
        return Change(shift = shift, turn = abs(YawTransform.normalized(correction.yaw - current.yaw)))
    }

    /**
     * "Fix here" against a wall (depth) where the rooms really are
     * ([ReferenceAlignment.reanchor] with a wall), keeping which room it's in.
     */
    fun reanchor(alignment: YawTransform, wall: Vec3, normal: Vec3, seenFrom: Vec3?): ReferenceAlignment.CornerFix? {
        val fix = ReferenceAlignment.reanchor(alignment, wall = wall, normal = normal, seenFrom = seenFrom, rooms = rooms.map { it.corrected })
            ?: return null
        return fix.copy(spaceID = rooms[fix.room].id)
    }

    /** The same rooms with [room]'s correction replaced (an admin just saved it). */
    fun replacing(room: String, with: RoomCorrection?): RoomCorrections =
        RoomCorrections(rooms = rooms.map { if (it.id == room) Room(id = it.id, outline = it.outline, correction = with) else it })

    /**
     * What a fix of room `fix.spaceID` says about where it is, measured from [base] (the
     * alignment that was placing the building, made in room [baseRoom] at [basePoint]). [newTurn] is
     * the alignment's new turn when the fix measured it; `null` keeps the room's turn.
     */
    fun observation(
        fix: ReferenceAlignment.CornerFix,
        base: YawTransform,
        baseRoom: String?,
        basePoint: Vec3,
        newTurn: Double?,
    ): RoomObservation? {
        val id = fix.spaceID ?: return null
        if (id == baseRoom) return null
        val room = room(id) ?: return null
        val old = room.correctionOrNone
        // The corner was matched where the room's correction puts it; the observation is about the model's.
        val anchor = old.transform.inverseApply(fix.corner)
        val asBuilt = base.inverseApply(fix.mark)
        val baseCorrection = transform(of = baseRoom)
        // The room's turn as built, when measured: the alignment turned by the fix, on top of its correction.
        val yaw = newTurn?.let { YawTransform.normalized(old.yaw + it - base.yaw) }
        val pivot = room.outline.centroid
        val turn = yaw ?: old.yaw
        val turned = YawTransform.rotate(anchor - pivot, by = turn) + pivot
        return RoomObservation(
            spaceID = id,
            baseSpaceID = baseRoom,
            anchor = anchor,
            markInBase = baseCorrection.inverseApply(asBuilt),
            turn = yaw?.let { YawTransform.normalized(it - baseCorrection.yaw) },
            hasTranslation = fix.kind == ReferenceAlignment.CornerFix.Kind.CORNER,
            baseDistance = ReferenceAlignment.horizontalDistance(basePoint, fix.mark),
            correction = RoomCorrection(pivot = pivot, offset = asBuilt - turned, yaw = turn),
        )
    }

    companion object {
        /** The camera takes another room's correction once it's this far inside it (doorways). */
        const val SWITCH_DEPTH = 0.3

        /** A wall plate's room is the one this far in front of it. */
        internal const val PLATE_REACH = 0.1

        /** Plates can be up to this far above their room's floor (on the ceiling). */
        internal val PLATE_HEIGHTS = -0.3..4.0

        val none = RoomCorrections(rooms = emptyList())

        /** The manifest's rooms that have an outline, with their corrections (iOS `init(spaces:)`). */
        fun of(spaces: List<Manifest.Space>): RoomCorrections = RoomCorrections(
            rooms = spaces.mapNotNull { space ->
                RoomOutline.of(space)?.let { Room(id = space.id, outline = it, correction = space.correction?.let { c -> RoomCorrection.of(c) }) }
            },
        )
    }
}
