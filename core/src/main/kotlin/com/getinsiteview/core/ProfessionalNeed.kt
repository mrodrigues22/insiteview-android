package com.getinsiteview.core

/**
 * The guest landing's "What do you need?" chips (A-05 professional flow): each preselects the
 * systems and subsystems a trade cares about, then the Rooms list opens (IOS-M2-08).
 */
enum class ProfessionalNeed(val raw: String) {
    ELECTRICAL("electrical"),
    PLUMBING("plumbing"),
    HVAC("hvac"),
    STRUCTURE("structure"),
    ARCHITECTURE("architecture"),
    OTHER("other");

    val id: String get() = raw

    /** System keys this need shows, in catalog order. */
    val systems: List<String>
        get() = when (this) {
            ELECTRICAL -> listOf("electrical", "data")
            PLUMBING -> listOf("plumbing", "gas")
            HVAC -> listOf("hvac")
            STRUCTURE -> listOf("structure")
            ARCHITECTURE -> listOf("architecture")
            OTHER -> listOf("gas", "fire", "data", "other")
        }

    /**
     * Per system, the subsystem keys (from the catalog) this need preselects; a system that
     * isn't listed shows all of its subsystems. E.g. Plumbing → cold water, hot water,
     * drainage, stormwater, fixtures (docs/PLAN.md §3 "Guest flows").
     */
    val subsystems: Map<String, Set<String>>
        get() = when (this) {
            ELECTRICAL -> mapOf("electrical" to setOf("power", "lighting", "panels"))
            PLUMBING -> mapOf("plumbing" to setOf("cold_water", "hot_water", "drainage", "stormwater", "fixtures"))
            HVAC -> mapOf("hvac" to setOf("supply", "return", "exhaust", "refrigerant", "equipment"))
            STRUCTURE, ARCHITECTURE, OTHER -> emptyMap()
        }

    companion object {
        /**
         * The needs that match at least one of the building's systems, so a chip never opens an
         * empty model.
         */
        fun available(systems: Iterable<String>): List<ProfessionalNeed> {
            val present = systems.toSet()
            return entries.filter { need -> need.systems.any { it in present } }
        }
    }
}
