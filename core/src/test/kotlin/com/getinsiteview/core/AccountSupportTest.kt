package com.getinsiteview.core

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Web links, terms and device lists. */
class AccountSupportTest {
    @Test
    fun `Web pages the app opens, on any WEB_BASE_URL`() {
        val links = WebLinks(URI("https://staging.getinsiteview.com"))
        assertEquals("https://staging.getinsiteview.com/forgot-password", links.forgotPassword.toString())
        assertEquals("https://staging.getinsiteview.com/app/buildings/new", links.addBuilding.toString())
        assertEquals("https://staging.getinsiteview.com/terms", links.terms.toString())
        assertEquals("https://staging.getinsiteview.com/privacy", links.privacy.toString())
        assertEquals("https://staging.getinsiteview.com/faq", links.help.toString())
        val local = WebLinks(URI("http://localhost:3000/"))
        assertEquals("http://localhost:3000/forgot-password", local.forgotPassword.toString())
        assertEquals("2026-09", TermsVersion.CURRENT)
    }

    @Test
    fun `Play Store page from PLAY_STORE_PACKAGE - no package, no link`() {
        val link = assertNotNull(PlayStoreLink.of(" com.getinsiteview.android "))
        assertEquals("market://details?id=com.getinsiteview.android", link.appURL.toString())
        assertEquals("https://play.google.com/store/apps/details?id=com.getinsiteview.android", link.webURL.toString())
        assertNull(PlayStoreLink.of(null))
        assertNull(PlayStoreLink.of(""))
        assertNull(PlayStoreLink.of("\${PLAY_STORE_PACKAGE}"))
        assertNull(PlayStoreLink.of("getinsiteview"))
        assertNull(PlayStoreLink.of("com.1getinsiteview"))
    }

    @Test
    fun `Favorites - toggled by code, kept on the device`() = blocking {
        val store = InMemoryKeyValueStore()
        val favorites = FavoriteBuildings(store)
        val code = assertNotNull(BuildingCode.parse("iv-8k29-x7"))
        assertFalse(favorites.contains(code))
        assertTrue(favorites.toggle(code))
        assertEquals(setOf("8K29X7"), FavoriteBuildings(store).codes())
        assertFalse(favorites.toggle(code))
        assertTrue(favorites.codes().isEmpty())
        favorites.toggle(code)
        favorites.removeAll()
        assertTrue(favorites.codes().isEmpty())
    }

    @Test
    fun `Recent - newest first, one entry per building, at most 20`() = blocking {
        val recents = RecentBuildings(InMemoryKeyValueStore())
        val a = assertNotNull(BuildingCode.parse("AAAAAA"))
        val b = assertNotNull(BuildingCode.parse("BBBBBB"))
        recents.record(a, at = Instant.ofEpochSecond(100))
        recents.record(b, at = Instant.ofEpochSecond(200))
        recents.record(a, at = Instant.ofEpochSecond(300))
        assertEquals(listOf(a, b), recents.entries().map { it.code })
        assertEquals(Instant.ofEpochSecond(300), recents.entries().first().openedAt)
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        for (index in 0 until 25) {
            recents.record(BuildingCode.parse("C0DE" + alphabet[index] + "0")!!, at = Instant.ofEpochSecond(1000L + index))
        }
        assertEquals(RecentBuildings.LIMIT, recents.entries().size)
        assertEquals("C0DE" + alphabet[24] + "0", recents.entries().first().code.raw)
        recents.removeAll()
        assertTrue(recents.entries().isEmpty())
    }
}
