package com.getinsiteview.modelkit

import com.getinsiteview.core.ProfessionalNeed
import kotlin.math.abs

/**
 * A subsystem of one system (`plumbing` / `hot_water`): subsystem keys are only unique within
 * their system.
 */
data class SubsystemKey(val system: String, val subsystem: String) : Comparable<SubsystemKey> {
    override fun compareTo(other: SubsystemKey): Int =
        compareValuesBy(this, other, { it.system }, { it.subsystem })
}

/**
 * What the 3D viewer or AR shows (IOS-M2-03, M2-06, M2-08): systems in scope, one storey or all,
 * hidden subsystems, one room, and how opaque systems and architecture are. The scene (`:scene`)
 * applies these; the rules live here so they're tested on the JVM.
 *
 * Immutable: iOS's `mutating` toggles return a new value here ([toggledSubsystem], [toggledSystem]).
 */
data class ModelFilters(
    /**
     * Chunks (systems) shown. Architecture is one of them in the 3D viewer; in AR its visibility
     * comes from [architectureOpacity].
     */
    val systems: Set<String>,
    /** `null` shows every storey. */
    val storeyID: String? = null,
    val hiddenSubsystems: Set<SubsystemKey> = emptySet(),
    /** Only system elements in this room (architecture and structure stay, for context). */
    val roomID: String? = null,
    val systemsOpacity: Double = 1.0,
    val architectureOpacity: Double = 1.0,
) {
    fun opacity(ofSystem: String): Double = if (isContext(ofSystem)) architectureOpacity else systemsOpacity

    /** Whether a chunk is on at all. */
    fun showsChunk(system: String): Boolean = system in systems && opacity(system) > 0

    /** Whether an element of a visible chunk shows. Elements without meta (yet) show. */
    fun showsElement(record: ElementRecord?, system: String): Boolean {
        if (record == null) return true
        val storey = record.storeyID
        if (storeyID != null && storey != null && storey != storeyID) return false
        if (isContext(system)) return true
        val subsystem = record.subsystem
        if (subsystem != null && SubsystemKey(system, subsystem) in hiddenSubsystems) return false
        if (roomID != null && record.roomID != roomID) return false
        return true
    }

    fun showsSubsystem(subsystem: String, of: String): Boolean = SubsystemKey(of, subsystem) !in hiddenSubsystems

    /** iOS `toggleSubsystem(_:of:)`. */
    fun toggledSubsystem(subsystem: String, of: String): ModelFilters {
        val key = SubsystemKey(of, subsystem)
        return copy(hiddenSubsystems = if (key in hiddenSubsystems) hiddenSubsystems - key else hiddenSubsystems + key)
    }

    /** iOS `toggleSystem(_:)`: toggles a system chip; whether it's on now is `system in result.systems`. */
    fun toggledSystem(system: String): ModelFilters =
        copy(systems = if (system in systems) systems - system else systems + system)

    companion object {
        /**
         * Chunks that are the building rather than its systems: never recoloured, never filtered
         * by subsystem or room, and faded by [architectureOpacity].
         */
        fun isContext(system: String): Boolean = system == ChunkPlan.architecture || system == "structure"

        /**
         * The filters a "What do you need?" chip preselects (A-05 professional flow): the need's
         * systems in scope (plus architecture in the 3D viewer, for context) and its subsystems; a
         * system's other subsystems start hidden. `null` when the building has none of them.
         */
        fun preselected(need: ProfessionalNeed, manifest: Manifest, includeArchitecture: Boolean): ModelFilters? {
            val inScope = manifest.systems.filter { it.key in need.systems }
            if (inScope.isEmpty()) return null
            val systems = inScope.map { it.key }.toMutableSet()
            if (includeArchitecture && ChunkPlan.architecture in manifest.systemKeys) {
                systems += ChunkPlan.architecture
            }
            val hidden = HashSet<SubsystemKey>()
            for (system in inScope) {
                val wanted = need.subsystems[system.key] ?: continue
                for (subsystem in system.subsystems) {
                    if (subsystem !in wanted) hidden += SubsystemKey(system.key, subsystem)
                }
            }
            return ModelFilters(systems = systems, hiddenSubsystems = hidden)
        }
    }
}

/**
 * The See inside sheet's model-opacity slider (docs/PLAN.md §3 "See inside", IOS-M2-06): three
 * detents, Reality · Reality + model · Model. With slider value `s` in 0…1, systems opacity is
 * `min(1, 2s)` and architecture opacity `max(0, 2s − 1)`: in the middle pipes and cables show over
 * the real walls; at the right the virtual building replaces the camera view.
 */
object SeeInside {
    enum class Detent(val raw: Double) {
        REALITY(0.0),
        REALITY_AND_MODEL(0.5),
        MODEL(1.0),
    }

    /** Where AR starts: systems over the camera, architecture hidden (the real walls are there). */
    val defaultValue: Double = Detent.REALITY_AND_MODEL.raw

    /** Values this close to a detent snap to it when the finger lifts. */
    const val snapDistance = 0.06

    fun systemsOpacity(value: Double): Double = minOf(1.0, maxOf(0.0, 2 * clamped(value)))

    fun architectureOpacity(value: Double): Double = maxOf(0.0, minOf(1.0, 2 * clamped(value) - 1))

    /** The filters with the value's opacities applied. */
    fun apply(value: Double, to: ModelFilters): ModelFilters =
        to.copy(systemsOpacity = systemsOpacity(value), architectureOpacity = architectureOpacity(value))

    /** Snaps to a detent within [snapDistance]. */
    fun snapped(value: Double): Double {
        val clamped = clamped(value)
        val nearest = nearestDetent(clamped)
        return if (abs(nearest.raw - clamped) <= snapDistance) nearest.raw else clamped
    }

    fun nearestDetent(value: Double): Detent =
        Detent.entries.minByOrNull { abs(it.raw - value) } ?: Detent.REALITY_AND_MODEL

    /**
     * The detent a drag from `old` to `new` reached or crossed, for a haptic tick; `null` when it
     * stayed between detents or started on the one it ends at.
     */
    fun detentCrossed(from: Double, to: Double): Detent? {
        val old = clamped(from)
        val new = clamped(to)
        if (old == new) return null
        val crossed = Detent.entries.filter { detent ->
            val d = detent.raw
            d != old && d >= minOf(old, new) && d <= maxOf(old, new)
        }
        // The one nearest the finger when a fast drag crosses two.
        return crossed.minByOrNull { abs(it.raw - new) }
    }

    internal fun clamped(value: Double): Double = if (value.isFinite()) minOf(1.0, maxOf(0.0, value)) else defaultValue
}
