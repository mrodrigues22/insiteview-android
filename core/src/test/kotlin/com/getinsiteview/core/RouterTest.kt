package com.getinsiteview.core

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RouterTest {
    private val router = Router(webBaseURL = URI("http://localhost:3000"))

    private fun link(string: String): DeepLink? = router.deepLink(URI(string))

    private fun code(raw: String): BuildingCode = assertNotNull(BuildingCode.parse(raw))

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://getinsiteview.com/b/8K29X7",
            "https://staging.getinsiteview.com/b/8K29X7",
            "http://localhost:3000/b/8K29X7",
            "https://GetInsiteView.Com/b/8K29X7",
            "https://getinsiteview.com/b/8k29x7/",
            "https://getinsiteview.com/b/8K29X7?utm_source=plate#top",
        ],
    )
    fun `Building links on every accepted host`(url: String) {
        assertEquals(DeepLink.Building(code("8K29X7"), plate = null), link(url))
    }

    @Test
    fun `Building links with a plate number`() {
        assertEquals(DeepLink.Building(code("8K29X7"), plate = 2), link("https://getinsiteview.com/b/8K29X7/2"))
        assertEquals(DeepLink.Building(code("TEST01"), plate = 12), link("https://staging.getinsiteview.com/b/TEST01/12"))
    }

    @Test
    fun `Access links`() {
        assertEquals(DeepLink.AccessLink(token = "Zm9vYmFy-_x.~1"), link("https://getinsiteview.com/a/Zm9vYmFy-_x.~1"))
        assertEquals(DeepLink.AccessLink(token = "abc123"), link("https://staging.getinsiteview.com/a/abc123"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://example.com/b/8K29X7",
            "https://getinsiteview.com.example.com/b/8K29X7",
            "https://api.getinsiteview.com/b/8K29X7",
            "https://evil.com/getinsiteview.com/b/8K29X7",
            "ftp://getinsiteview.com/b/8K29X7",
            "insiteview://b/8K29X7",
            "https://getinsiteview.com/",
            "https://getinsiteview.com/b",
            "https://getinsiteview.com/b/8K29X7/2/3",
            "https://getinsiteview.com/B/8K29X7",
            "https://getinsiteview.com/pricing",
            "https://getinsiteview.com/a",
            "https://getinsiteview.com/a/abc/def",
            "https://getinsiteview.com/a/ab%20c",
            "https://getinsiteview.com/a/%C3%A9t%C3%A9",
        ],
    )
    fun `Rejects other hosts, schemes and paths`(url: String) {
        assertNull(link(url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://getinsiteview.com/b/8K29XO",
            "https://getinsiteview.com/b/8K29X",
            "https://getinsiteview.com/b/8K29X7/0",
            "https://getinsiteview.com/b/8K29X7/-1",
            "https://getinsiteview.com/b/8K29X7/+2",
            "https://getinsiteview.com/b/8K29X7/2x",
            "https://getinsiteview.com/b/8K29X7/99999999999999999999",
        ],
    )
    fun `Rejects invalid codes and plate numbers`(url: String) {
        assertNull(link(url))
    }

    @Test
    fun `Scanned QR payloads`() {
        assertEquals(DeepLink.Building(code("8K29X7"), plate = 2), router.deepLinkForScannedText("https://getinsiteview.com/b/8K29X7/2\n"))
        assertEquals(DeepLink.AccessLink(token = "abc123"), router.deepLinkForScannedText("  https://staging.getinsiteview.com/a/abc123 "))
        assertEquals(DeepLink.Building(code("8K29X7"), plate = null), router.deepLinkForScannedText("HTTPS://GETINSITEVIEW.COM/b/8k29x7"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "8K29X7", "IV-8K29-X7", "WIFI:S:home;T:WPA;P:secret;;", "https://example.com/b/8K29X7",
            "https://getinsiteview.com/b/8K29X7 extra", "mailto:hi@getinsiteview.com",
        ],
    )
    fun `Scanned payloads that aren't Insite View codes`(text: String) {
        assertNull(router.deepLinkForScannedText(text))
    }

    @Test
    fun `Plate number accessor`() {
        assertEquals(3, DeepLink.Building(code("8K29X7"), plate = 3).plate)
        assertNull(DeepLink.AccessLink(token = "x").plate)
    }

    @Test
    fun `The extra host is optional and only adds itself`() {
        assertEquals(setOf("getinsiteview.com", "staging.getinsiteview.com"), Router().hosts)
        assertEquals(setOf("getinsiteview.com", "staging.getinsiteview.com", "localhost"), router.hosts)
        assertNull(Router().deepLink(URI("http://localhost:3000/b/8K29X7")))
        val tunnel = Router(additionalHost = "Abc-Def.trycloudflare.com")
        assertEquals(DeepLink.Building(code("8K29X7"), plate = 1), tunnel.deepLink(URI("https://abc-def.trycloudflare.com/b/8K29X7/1")))
    }
}
