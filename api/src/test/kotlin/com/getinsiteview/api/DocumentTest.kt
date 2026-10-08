package com.getinsiteview.api

import com.getinsiteview.api.buildings.DocumentDownloader
import com.getinsiteview.api.buildings.DocumentError
import com.getinsiteview.api.buildings.DocumentItem
import com.getinsiteview.modelkit.ChunkTransport
import com.getinsiteview.modelkit.ChunkTransportError
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Documents")
class DocumentTest {
    @Test
    fun `Member list - every document, unconfirmed uploads have no link`() = test {
        val transport = RoutingTransport { RoutingTransport.Reply.json(APIFixtures.documents) }
        val connection = BuildingConnection(
            BuildingAccess.Member(UUID.fromString("01926F3A-0000-7000-8000-000000000001")), transport.api(),
            deviceId = "device-1234", platform = VisitPlatform.ANDROID_APP,
        )
        val items = connection.documents(elementId = "e1").map(DocumentItem::of)
        assertEquals("/v1/buildings/01926f3a-0000-7000-8000-000000000001/documents?elementId=e1", transport.requests.first().path)
        assertEquals(listOf("01926f3a-0000-7000-8000-0000000000d1", "01926f3a-0000-7000-8000-0000000000d2"), items.map { it.id })
        assertEquals("files.example.com", items[0].url?.host)
        assertTrue(items[0].elementID == "e1" && items[0].kind == DocumentKind.MANUAL)
        assertNull(items[1].url)
    }

    @Test
    fun `File names the viewer can open - the title, made safe, with the right extension`() {
        fun item(title: String, mime: String, fileName: String? = null) =
            DocumentItem(id = "d", title = title, kind = DocumentKind.MANUAL, mime = mime, fileName = fileName, url = null)
        assertEquals("Panel manual.pdf", item("Panel manual", "application/pdf").localFileName)
        assertEquals("Garantia 2026-27- aquecedor.jpg", item("Garantia 2026/27: aquecedor", "image/jpeg").localFileName)
        assertEquals("Document.png", item("  ..", "image/png").localFileName)
        assertEquals("Plan.dwg", item("Plan", "application/octet-stream", fileName = "plan.DWG").localFileName)
        assertEquals("Plan", item("Plan", "application/octet-stream", fileName = "noext").localFileName)
        assertEquals(84, item("a".repeat(100), "application/pdf").localFileName.length)
        assertEquals("pdf", DocumentItem.fileExtension("application/pdf; charset=binary", null))
    }

    /** Serves files; a URL with `expired` answers 403. */
    private class FileTransport : ChunkTransport {
        private val list = mutableListOf<URI>()
        val calls: List<URI> get() = synchronized(list) { list.toList() }

        override suspend fun download(url: URI, received: (Long) -> Unit): ByteArray {
            synchronized(list) { list.add(url) }
            if (url.toString().contains("expired")) throw ChunkTransportError.HttpStatus(403)
            received(4)
            return "%PDF".encodeToByteArray()
        }
    }

    private fun directory(): File = File(Files.createTempDirectory("iv-docs").toFile(), "Documents")

    @Test
    fun `Downloads once to a named file, then reuses it`() = test {
        val transport = FileTransport()
        val folder = directory()
        try {
            val downloader = DocumentDownloader(transport, folder)
            val item = DocumentItem(
                id = "01926f3a-d1", title = "Panel manual", kind = DocumentKind.MANUAL, mime = "application/pdf",
                url = URI("https://files.example.com/d1"),
            )
            val file = downloader.file(item)
            assertEquals("Panel manual.pdf", file.name)
            assertEquals("01926f3a-d1", file.parentFile.name)
            assertContentEquals("%PDF".encodeToByteArray(), file.readBytes())
            downloader.file(item)
            assertEquals(1, transport.calls.size)
            downloader.clear()
            assertFalse(folder.exists())
        } finally {
            folder.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `An expired link is renewed once, no link at all is unavailable`() = test {
        val transport = FileTransport()
        val folder = directory()
        try {
            val downloader = DocumentDownloader(transport, folder)
            val item = DocumentItem(
                id = "d2", title = "Warranty", kind = DocumentKind.WARRANTY, mime = "image/png",
                url = URI("https://files.example.com/expired"),
            )
            val file = downloader.file(item, renewURL = { URI("https://files.example.com/fresh") })
            assertEquals("Warranty.png", file.name)
            assertEquals(listOf("expired", "fresh"), transport.calls.map { it.path.substringAfterLast('/') })

            val gone = DocumentItem(
                id = "d3", title = "Old", kind = DocumentKind.OTHER, mime = "application/pdf", url = URI("https://files.example.com/expired"),
            )
            assertEquals(DocumentError.Unavailable, assertFailsWith<DocumentError> { downloader.file(gone) })
            val pending = DocumentItem(id = "d4", title = "Pending", kind = DocumentKind.OTHER, mime = "application/pdf", url = null)
            assertEquals(DocumentError.Unavailable, assertFailsWith<DocumentError> { downloader.file(pending) })
        } finally {
            folder.parentFile.deleteRecursively()
        }
    }
}
