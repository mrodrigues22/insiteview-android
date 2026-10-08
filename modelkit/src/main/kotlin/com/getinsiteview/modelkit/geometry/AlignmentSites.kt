package com.getinsiteview.modelkit.geometry

import java.util.UUID

/**
 * One local alignment (docs/PLAN.md §3 "Drift afterwards"): where the building goes, as measured
 * at one spot (a plate, the corners marked, a "Fix here"), tied to an AR anchor there. `:ar`
 * adds an anchor per site; when the AR session refines its map and moves the anchor, the site's
 * transform moves with it, turning about the anchor.
 *
 * Immutable: the changes `AlignmentSites` makes ([anchorMoved], [retarget], [raise]) return a new
 * site.
 */
@ConsistentCopyVisibility
data class AlignmentSite private constructor(
    val id: UUID,
    /** What it was aligned with (a "Fix here" keeps the alignment's method). */
    val method: PlateAnchoring.Method,
    /** The building's transform when the site was made, in the AR world as it was then. */
    val base: YawTransform,
    /** The anchor's pose when the site was made: on the spot, unturned. */
    val anchorAtCreation: YawTransform,
    /** The anchor's pose as the AR session has it now. */
    val anchor: YawTransform,
    /** Where the camera was, for the automatic re-anchoring on plates. */
    val camera: Vec3?,
    /** Seconds (the AR clock). */
    val createdAt: Double,
    /** The corner and mark of a "Fix here", for the turn from the next one. */
    val fix: ReferenceAlignment.CornerFix?,
    /**
     * The room it was measured in (the marks', the plate's, the fixed corner's), whose correction
     * it holds: when that changes, the site moves so the room stays put ([RoomCorrections]).
     */
    val room: String?,
    /**
     * For a "Fix here" that measured its room from another alignment: that alignment, so the
     * next fix in the room measures it from there too.
     */
    val observationBase: ObservationBase?,
    /**
     * Whether the room's turn was measured on the way here from that alignment (two corners of
     * the room in a row, or a wall).
     */
    val turnMeasured: Boolean,
) {
    /** The alignment a fix was measured from: its transform then, its room and its spot. */
    data class ObservationBase(val transform: YawTransform, val room: String?, val point: Vec3)

    constructor(
        id: UUID = UUID.randomUUID(),
        method: PlateAnchoring.Method,
        transform: YawTransform,
        around: Vec3,
        camera: Vec3?,
        at: Double,
        fix: ReferenceAlignment.CornerFix? = null,
        room: String? = null,
        observationBase: ObservationBase? = null,
        turnMeasured: Boolean = false,
    ) : this(
        id = id,
        method = method,
        base = transform,
        anchorAtCreation = YawTransform(yaw = 0.0, translation = around),
        anchor = YawTransform(yaw = 0.0, translation = around),
        camera = camera,
        createdAt = at,
        fix = fix,
        room = room,
        observationBase = observationBase,
        turnMeasured = turnMeasured,
    )

    /** How the AR session moved the anchor since the site was made: the world as it was then → now. */
    val anchorMove: YawTransform get() = anchorAtCreation.inverse.then(anchor)

    /** Where the building goes near this site now. */
    val transform: YawTransform get() = base.then(anchorMove)

    /** The site's spot, AR world now. */
    val point: Vec3 get() = anchor.translation

    internal fun anchorMoved(to: YawTransform): AlignmentSite = copy(anchor = to)

    /** Makes the site place the building at [to] now, whatever the AR session did to its anchor. */
    internal fun retarget(to: YawTransform): AlignmentSite = copy(base = to.then(anchorMove.inverse))

    /** Up or down by [by] metres (the floor glue): the anchor only turns about vertical, so this commutes. */
    internal fun raise(by: Double): AlignmentSite = copy(base = base.translated(by = Vec3(0.0, by, 0.0)))
}

/**
 * The building's local alignments, and which one places it: the one nearest the camera. World
 * tracking drifts as the user walks, so one transform for the whole building is only right near
 * where it was measured; a "Fix here" in the next room used to replace it, putting the first
 * room out again.
 *
 * A mutable class (a Swift struct with mutating methods); [copy] gives an independent one.
 */
class AlignmentSites {
    var all: List<AlignmentSite> = emptyList()
        private set
    var currentID: UUID? = null
        private set

    val current: AlignmentSite? get() = all.firstOrNull { it.id == currentID }

    val ids: Set<UUID> get() = all.map { it.id }.toSet()

    /** A new alignment: this is the only site. */
    internal fun startOver(with: AlignmentSite) {
        all = listOf(with)
        currentID = with.id
    }

    /** Another site, which places the building from now on; replaces those within [REPLACE_RADIUS]. */
    internal fun add(site: AlignmentSite) {
        val sites = all.filterNot { ReferenceAlignment.horizontalDistance(it.point, site.point) <= REPLACE_RADIUS }.toMutableList()
        sites.add(site)
        currentID = site.id
        while (sites.size > MAXIMUM_COUNT) {
            val oldest = sites.indexOfFirst { it.id != currentID }
            if (oldest < 0) break
            sites.removeAt(oldest)
        }
        all = sites
    }

    internal fun removeAll() {
        all = emptyList()
        currentID = null
    }

    /** The AR session's latest pose for a site's anchor. Returns whether it changed. */
    internal fun anchorMoved(id: UUID, to: YawTransform): Boolean {
        val index = all.indexOfFirst { it.id == id }
        if (index < 0 || all[index].anchor == to) return false
        all = all.toMutableList().also { it[index] = it[index].anchorMoved(to = to) }
        return true
    }

    /** The sites within [within] of [around] place the building at [to] from now on. */
    internal fun retarget(around: Vec3, within: Double, to: YawTransform) {
        all = all.map { if (ReferenceAlignment.horizontalDistance(it.point, around) <= within) it.retarget(to = to) else it }
    }

    internal fun raise(by: Double) {
        all = all.map { it.raise(by = by) }
    }

    /** Each site placing the building where [transform] says from now on (`null`: unchanged). */
    internal fun retargetEach(transform: (AlignmentSite) -> YawTransform?) {
        all = all.map { site -> transform(site)?.let { site.retarget(to = it) } ?: site }
    }

    /**
     * The site nearest [near] (the camera, seen from above, on its storey) takes over when it's more
     * than [SWITCH_MARGIN] nearer than the current one. Returns whether it changed.
     */
    internal fun select(near: Vec3): Boolean {
        val camera = near
        val current = current ?: return false
        fun distance(site: AlignmentSite): Double = ReferenceAlignment.horizontalDistance(site.point, camera)
        val onStorey = all.filter { (camera.y - it.point.y) in STOREY_BAND }
        val nearest = onStorey.minByOrNull { distance(it) } ?: return false
        if (nearest.id == current.id) return false
        if (!(distance(nearest) + SWITCH_MARGIN < distance(current) || (camera.y - current.point.y) !in STOREY_BAND)) return false
        currentID = nearest.id
        return true
    }

    /** An independent copy (Swift value semantics). */
    fun copy(): AlignmentSites {
        val copy = AlignmentSites()
        copy.all = all
        copy.currentID = currentID
        return copy
    }

    override fun equals(other: Any?): Boolean = other is AlignmentSites && all == other.all && currentID == other.currentID

    override fun hashCode(): Int = all.hashCode() * 31 + (currentID?.hashCode() ?: 0)

    override fun toString(): String = "AlignmentSites(all=$all, currentID=$currentID)"

    companion object {
        /**
         * Another site must be this much nearer the camera to take over, so walking along the middle
         * between two doesn't switch back and forth.
         */
        const val SWITCH_MARGIN = 1.0

        /** A new site replaces those this close to it (fixing the same spot again). */
        const val REPLACE_RADIUS = 1.5

        /** The oldest go first beyond this many. */
        const val MAXIMUM_COUNT = 16

        /**
         * Sites this far above or below the camera are on another storey: (camera − site) heights.
         * Sites sit on the floor, or on a plate on a wall or the ceiling.
         */
        internal val STOREY_BAND = -1.5..2.5
    }
}
