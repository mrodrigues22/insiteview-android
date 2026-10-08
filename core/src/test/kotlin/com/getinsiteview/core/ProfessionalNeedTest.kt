package com.getinsiteview.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What do you need? */
class ProfessionalNeedTest {
    @Test
    fun `Each need maps to catalog systems`() {
        val catalog = CatalogTest.fixture()
        for (need in ProfessionalNeed.entries) {
            assertFalse(need.systems.isEmpty())
            for (system in need.systems) {
                assertNotNull(catalog.system(system), "$need → $system")
            }
        }
        assertEquals("plumbing", ProfessionalNeed.PLUMBING.systems.first())
    }

    @Test
    fun `Only needs the building can show`() {
        assertEquals(
            listOf(ProfessionalNeed.ELECTRICAL, ProfessionalNeed.PLUMBING, ProfessionalNeed.ARCHITECTURE),
            ProfessionalNeed.available(listOf("architecture", "electrical", "plumbing")),
        )
        assertEquals(
            listOf(ProfessionalNeed.ARCHITECTURE, ProfessionalNeed.OTHER),
            ProfessionalNeed.available(listOf("architecture", "fire")),
        )
        assertTrue(ProfessionalNeed.available(emptyList()).isEmpty())
    }
}
