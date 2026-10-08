package com.getinsiteview.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Guest onboarding and handoff. */
class GuestOnboardingTest {
    private val code = BuildingCode.parse("8K29X7")!!

    @Test
    fun `See in AR - explainer first, then the camera explainer, the denied state or AR`() {
        assertEquals(ARPreflightStep.Explainer, ARPreflightStep.next(hasSeenExplainer = false, camera = CameraAccess.AUTHORIZED))
        assertEquals(ARPreflightStep.CameraExplainer, ARPreflightStep.next(hasSeenExplainer = true, camera = CameraAccess.NOT_DETERMINED))
        assertEquals(ARPreflightStep.CameraDenied(canOpenSettings = true), ARPreflightStep.next(hasSeenExplainer = true, camera = CameraAccess.DENIED))
        assertEquals(ARPreflightStep.CameraDenied(canOpenSettings = false), ARPreflightStep.next(hasSeenExplainer = true, camera = CameraAccess.RESTRICTED))
        assertEquals(ARPreflightStep.Ready, ARPreflightStep.next(hasSeenExplainer = true, camera = CameraAccess.AUTHORIZED))
        assertEquals(3, ARExplainerFrame.entries.size)
        assertEquals(ARExplainerFrame.ALIGN, ARExplainerFrame.POINT.next)
        assertNull(ARExplainerFrame.EXPLORE.next)
    }

    @Test
    fun `Once-per-device flags`() = blocking {
        val store = InMemoryKeyValueStore()
        val preferences = GuestPreferences(store)
        assertTrue(!preferences.hasSeenARExplainer() && !preferences.hasSeenSafetyNote())
        preferences.setHasSeenARExplainer(true)
        preferences.setHasSeenSafetyNote(true)
        val again = GuestPreferences(store)
        assertTrue(again.hasSeenARExplainer() && again.hasSeenSafetyNote())
    }

    @Test
    fun `Handoff - the app opens the saved building once`() = blocking {
        val handoff = BuildingHandoff(InMemoryKeyValueStore())
        val now = Instant.ofEpochSecond(1_790_856_900)
        assertNull(handoff.take(now = now))

        handoff.save(code, plate = 2, at = now)
        assertEquals(DeepLink.Building(code, plate = 2), handoff.take(now = now.plusSeconds(60)))
        assertNull(handoff.take(now = now.plusSeconds(61)))

        handoff.save(code, plate = null, at = now)
        assertNull(handoff.take(now = now + (BuildingHandoff.MAX_AGE + 1.seconds).toJavaDuration()))
    }

    // Android only: the Play Install Referrer replaces the App Clip's App Group handoff.

    @Test
    fun `Install Referrer - code and optional plate`() {
        assertEquals(DeepLink.Building(code, plate = 2), BuildingHandoff.parseReferrer("code=8K29X7&plate=2"))
        assertEquals(DeepLink.Building(code, plate = null), BuildingHandoff.parseReferrer("code=8K29X7"))
        assertEquals(DeepLink.Building(code, plate = 3), BuildingHandoff.parseReferrer("plate=3&utm_source=web&code=8k29x7"))
        assertEquals(DeepLink.Building(code, plate = 12), BuildingHandoff.parseReferrer("code=IV-8K29-X7&plate=12"))
        assertEquals(DeepLink.Building(code, plate = 2), BuildingHandoff.parseReferrer("?code=8K29X7&plate=2"))
    }

    @Test
    fun `Install Referrer - URL-encoded values and a referrer encoded twice`() {
        assertEquals(DeepLink.Building(code, plate = 2), BuildingHandoff.parseReferrer("code=IV%2D8K29%2DX7&plate=2"))
        assertEquals(DeepLink.Building(code, plate = 2), BuildingHandoff.parseReferrer("code%3D8K29X7%26plate%3D2"))
        assertEquals(DeepLink.Building(code, plate = null), BuildingHandoff.parseReferrer("code=+8K29X7+"))
    }

    @Test
    fun `Install Referrer - invalid plates are dropped, the building still opens`() {
        for (plate in listOf("0", "-1", "+2", "2x", "", "99999999999999999999")) {
            assertEquals(DeepLink.Building(code, plate = null), BuildingHandoff.parseReferrer("code=8K29X7&plate=$plate"), plate)
        }
        assertEquals(DeepLink.Building(code, plate = 1), BuildingHandoff.parseReferrer("code=8K29X7&plate=1&plate=2"))
    }

    @Test
    fun `Install Referrer - organic installs and bad codes name no building`() {
        for (referrer in listOf(
            null, "", "utm_source=google-play&utm_medium=organic", "code=8K29XO", "code=", "code",
            "CODE=8K29X7", "building=8K29X7", "code=%ZZ", "https://getinsiteview.com/b/8K29X7",
        )) {
            assertNull(BuildingHandoff.parseReferrer(referrer), referrer.toString())
        }
    }

    @Test
    fun `Install Referrer - read once per install, then taken once`() = blocking {
        val store = InMemoryKeyValueStore()
        val handoff = BuildingHandoff(store)
        val clickedAt = Instant.ofEpochSecond(1_790_856_900)
        assertFalse(handoff.hasReadReferrer())
        assertEquals(DeepLink.Building(code, plate = 2), handoff.saveReferrer("code=8K29X7&plate=2", at = clickedAt))
        assertTrue(handoff.hasReadReferrer())
        // Play returns the same referrer on every query: a second read saves nothing.
        assertNull(handoff.saveReferrer("code=8K29X7&plate=2", at = clickedAt))
        assertEquals(DeepLink.Building(code, plate = 2), BuildingHandoff(store).take(now = clickedAt.plusSeconds(3600)))
        assertNull(handoff.take(now = clickedAt.plusSeconds(3601)))

        val organic = BuildingHandoff(InMemoryKeyValueStore())
        assertNull(organic.saveReferrer("utm_source=google-play&utm_medium=organic", at = clickedAt))
        assertTrue(organic.hasReadReferrer())
        assertNull(organic.take(now = clickedAt))
    }

    @Test
    fun `Install Referrer - a referrer clicked more than a week before the first launch is ignored`() = blocking {
        val handoff = BuildingHandoff(InMemoryKeyValueStore())
        val clickedAt = Instant.ofEpochSecond(1_790_856_900)
        handoff.saveReferrer("code=8K29X7", at = clickedAt)
        assertNull(handoff.take(now = clickedAt + (BuildingHandoff.MAX_AGE + 1.seconds).toJavaDuration()))

        val withinAWeek = BuildingHandoff(InMemoryKeyValueStore())
        withinAWeek.saveReferrer("code=8K29X7", at = clickedAt)
        assertEquals(DeepLink.Building(code, plate = null), withinAWeek.take(now = clickedAt + BuildingHandoff.MAX_AGE.toJavaDuration()))
    }

    @Test
    fun `Handoff - a corrupt stored value is dropped`() = blocking {
        val store = InMemoryKeyValueStore(mapOf(BuildingHandoff.KEY to "not json"))
        assertNull(BuildingHandoff(store).take())
        assertFalse(BuildingHandoff.KEY in store.snapshot)
    }
}
