package com.getinsiteview.api

import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Catalog repository")
class CatalogRepositoryTest {
    private val catalogJSON =
        """{"version":1,"systems":[{"key":"gas","color":"#EE6A60","names":{"en":"Gas","pt-BR":"Gás","es":"Gas"},"subsystems":[]}],"kinds":[],"properties":[]}"""

    private fun cacheFile(): File = File(Files.createTempDirectory("iv-catalog").toFile(), "catalog.json")

    @Test
    fun `Fetches, caches with the ETag, then revalidates with If-None-Match`() = test {
        val file = cacheFile()
        val transport = RoutingTransport { sent ->
            if (sent.header("If-None-Match") == "\"v1\"") {
                RoutingTransport.Reply.empty(304)
            } else {
                RoutingTransport.Reply(200, mapOf("Content-Type" to "application/json", "ETag" to "\"v1\""), catalogJSON)
            }
        }
        val api = transport.api()

        val first = CatalogRepository(api, file)
        assertNull(first.cached())
        assertEquals("#EE6A60", first.catalog().system("gas")?.color?.hex)
        first.catalog()
        assertEquals(1, transport.requests.size)

        // Next launch: the cached copy is revalidated.
        val second = CatalogRepository(api, file)
        assertEquals(1, second.cached()?.systems?.size)
        assertEquals(1, second.catalog().systems.size)
        assertEquals(2, transport.requests.size)
        assertEquals("\"v1\"", transport.requests[1].header("If-None-Match"))
    }

    @Test
    fun `Offline - the cached copy, or the error when there is none`() = test {
        val file = cacheFile()
        val online = RoutingTransport { RoutingTransport.Reply.json(catalogJSON) }
        CatalogRepository(online.api(), file).catalog()

        val offline = RoutingTransport { throw UnknownHostException("offline") }
        val api = offline.api()
        assertNotNull(CatalogRepository(api, file).catalog().system("gas"))
        assertFailsWith<UnknownHostException> { CatalogRepository(api, cacheFile()).catalog() }
    }
}
