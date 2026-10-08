package com.getinsiteview.features.app

import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.DeepLink
import com.getinsiteview.core.ProfessionalNeed
import com.getinsiteview.features.guest.OpenBuilding
import com.getinsiteview.features.viewer.ViewerFocus
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RoutesTest {
    @Test
    fun `a building link round-trips through its route`() {
        val open = OpenBuilding(DeepLink.Building(BuildingCode.parse("8K29X7")!!, plate = 2), elementID = "e1")
        assertEquals(open, GuestFlowRoute.of(open).open)
        val noPlate = OpenBuilding(DeepLink.Building(BuildingCode.parse("IV-8K29-X7")!!, plate = null))
        assertEquals(noPlate, GuestFlowRoute.of(noPlate).open)
        assertEquals(0, GuestFlowRoute.of(noPlate).plate)
    }

    @Test
    fun `an access link round-trips through its route`() {
        val open = OpenBuilding(DeepLink.AccessLink("abc-123"))
        assertEquals(open, GuestFlowRoute.of(open).open)
    }

    @Test
    fun `the viewer's focus round-trips through its route`() {
        val focus = ViewerFocus(need = ProfessionalNeed.PLUMBING, systems = listOf("plumbing", "gas"), storeyID = "s1", roomID = "r1", elementID = "e1")
        assertEquals(focus, BuildingRoute.Viewer.of(focus).focus)
        assertEquals(ViewerFocus(), BuildingRoute.Viewer().focus)
    }

    @Test
    fun `rooms route keeps the trade`() {
        assertEquals(ProfessionalNeed.HVAC, BuildingRoute.Rooms.of(ProfessionalNeed.HVAC).professionalNeed)
        assertNull(BuildingRoute.Rooms.of(null).professionalNeed)
    }
}
