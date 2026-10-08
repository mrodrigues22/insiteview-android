package com.getinsiteview.modelkit

import com.getinsiteview.core.ProfessionalNeed
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("Model filters")
class ModelFiltersTest {
    private fun record(system: String, subsystem: String? = null, storey: String? = "s0", room: String? = null): ElementRecord =
        ElementRecord(
            id = "e1", chunk = system, system = system,
            meta = ElementMeta(kind = "outlet", storeyID = storey, roomID = room, subsystem = subsystem),
        )

    @Test
    @DisplayName("Storey, subsystem and room filters; architecture only follows the storey")
    fun `Storey, subsystem and room filters, architecture only follows the storey`() {
        var filters = ModelFilters(systems = setOf("architecture", "electrical", "plumbing"))
        assertTrue(filters.showsElement(record("electrical", subsystem = "power"), system = "electrical"))
        assertTrue(filters.showsElement(null, system = "electrical"))

        filters = filters.copy(storeyID = "s1")
        assertFalse(filters.showsElement(record("electrical"), system = "electrical"))
        assertFalse(filters.showsElement(record("architecture"), system = "architecture"))
        assertTrue(filters.showsElement(record("electrical", storey = null), system = "electrical"))
        filters = filters.copy(storeyID = null)

        filters = filters.toggledSubsystem("hot_water", of = "plumbing")
        assertFalse(filters.showsSubsystem("hot_water", of = "plumbing"))
        assertFalse(filters.showsElement(record("plumbing", subsystem = "hot_water"), system = "plumbing"))
        assertTrue(filters.showsElement(record("plumbing", subsystem = "cold_water"), system = "plumbing"))
        // Subsystem keys are per system.
        assertTrue(filters.showsElement(record("hvac", subsystem = "hot_water"), system = "hvac"))
        filters = filters.toggledSubsystem("hot_water", of = "plumbing")
        assertTrue(filters.hiddenSubsystems.isEmpty())

        filters = filters.copy(roomID = "kitchen")
        assertTrue(filters.showsElement(record("electrical", room = "kitchen"), system = "electrical"))
        assertFalse(filters.showsElement(record("electrical", room = "bath"), system = "electrical"))
        assertFalse(filters.showsElement(record("electrical", room = null), system = "electrical"))
        assertTrue(filters.showsElement(record("architecture", room = null), system = "architecture"))
    }

    @Test
    @DisplayName("Chunks: in the chosen systems and not faded out")
    fun `Chunks - in the chosen systems and not faded out`() {
        var filters = ModelFilters(systems = setOf("architecture", "electrical"))
        assertTrue(filters.showsChunk(system = "electrical"))
        assertFalse(filters.showsChunk(system = "plumbing"))
        filters = filters.toggledSystem("plumbing")
        val on = "plumbing" in filters.systems
        assertTrue(on)
        assertTrue(filters.showsChunk(system = "plumbing"))
        filters = filters.toggledSystem("plumbing")
        val off = "plumbing" in filters.systems
        assertFalse(off)
        filters = SeeInside.apply(SeeInside.defaultValue, to = filters)
        assertFalse(filters.showsChunk(system = "architecture"))
        assertTrue(filters.showsChunk(system = "electrical"))
        assertEquals(0.0, filters.opacity(ofSystem = "structure"))
    }

    @Test
    fun `What do you need? preselects the trade's systems and subsystems in scope`() {
        val manifest = Fixtures.manifest()
        val electrical = assertNotNull(ModelFilters.preselected(ProfessionalNeed.ELECTRICAL, manifest, includeArchitecture = true))
        assertEquals(setOf("architecture", "electrical"), electrical.systems)
        assertTrue(electrical.hiddenSubsystems.isEmpty())
        val inAR = assertNotNull(ModelFilters.preselected(ProfessionalNeed.PLUMBING, manifest, includeArchitecture = false))
        assertEquals(setOf("plumbing"), inAR.systems)
        assertNull(ModelFilters.preselected(ProfessionalNeed.HVAC, manifest, includeArchitecture = true))

        // A subsystem the trade doesn't list starts hidden.
        val custom = manifest.copy(
            systems = listOf(Manifest.SystemSummary(key = "plumbing", elementCount = 3, subsystems = listOf("cold_water", "irrigation"))),
        )
        val plumbing = assertNotNull(ModelFilters.preselected(ProfessionalNeed.PLUMBING, custom, includeArchitecture = false))
        assertEquals(setOf(SubsystemKey(system = "plumbing", subsystem = "irrigation")), plumbing.hiddenSubsystems)
    }
}

@DisplayName("See inside: opacity slider")
class SeeInsideTest {
    @ParameterizedTest
    @CsvSource("0.0, 0.0, 0.0", "0.25, 0.5, 0.0", "0.5, 1.0, 0.0", "0.75, 1.0, 0.5", "1.0, 1.0, 1.0", "-1.0, 0.0, 0.0", "2.0, 1.0, 1.0")
    @DisplayName("Opacity mapping: systems min(1, 2s), architecture max(0, 2s − 1)")
    fun `Opacity mapping - systems min(1, 2s), architecture max(0, 2s - 1)`(value: Double, systems: Double, architecture: Double) {
        assertEquals(systems, SeeInside.systemsOpacity(value))
        assertEquals(architecture, SeeInside.architectureOpacity(value))
    }

    @Test
    @DisplayName("Three detents; values near one snap to it")
    fun `Three detents, values near one snap to it`() {
        assertEquals(listOf(0.0, 0.5, 1.0), SeeInside.Detent.entries.map { it.raw })
        assertEquals(0.5, SeeInside.defaultValue)
        assertEquals(0.5, SeeInside.snapped(0.46))
        assertEquals(1.0, SeeInside.snapped(0.97))
        assertEquals(0.3, SeeInside.snapped(0.3))
        assertEquals(SeeInside.Detent.REALITY, SeeInside.nearestDetent(0.2))
        assertEquals(SeeInside.Detent.MODEL, SeeInside.nearestDetent(0.8))
    }

    @Test
    fun `Haptic ticks when a drag reaches or crosses a detent`() {
        assertEquals(SeeInside.Detent.REALITY_AND_MODEL, SeeInside.detentCrossed(from = 0.4, to = 0.6))
        assertNull(SeeInside.detentCrossed(from = 0.4, to = 0.45))
        assertNull(SeeInside.detentCrossed(from = 0.5, to = 0.6))
        assertEquals(SeeInside.Detent.MODEL, SeeInside.detentCrossed(from = 0.9, to = 1.0))
        assertEquals(SeeInside.Detent.MODEL, SeeInside.detentCrossed(from = 0.1, to = 1.0))
        assertNull(SeeInside.detentCrossed(from = 0.3, to = 0.3))
    }
}
