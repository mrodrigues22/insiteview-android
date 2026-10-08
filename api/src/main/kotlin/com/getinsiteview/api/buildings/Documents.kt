package com.getinsiteview.api.buildings

import com.getinsiteview.api.BuildingDocument
import com.getinsiteview.api.DocumentKind
import com.getinsiteview.api.DocumentRef
import com.getinsiteview.modelkit.ChunkTransport
import com.getinsiteview.modelkit.ChunkTransportError
import java.io.File
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A building or element document (IOS-M3-06), from a document list ([BuildingDocument]) or an
 * element's detail ([DocumentRef]).
 */
data class DocumentItem(
    val id: String,
    val title: String,
    val kind: DocumentKind,
    val mime: String,
    /** The stored file name (lists only), for the extension the viewer app needs. */
    val fileName: String? = null,
    /** The element it belongs to; `null` for the whole building. */
    val elementID: String? = null,
    val sizeBytes: Long? = null,
    /** Presigned download, valid for 15 minutes; `null` until the upload is confirmed. */
    val url: URI?,
) {
    /**
     * The file name the viewer gets (`ACTION_VIEW` picks the app by type and extension; iOS
     * QuickLook by extension): the title, made safe for a file name, with the extension of the
     * MIME type (else of the stored name).
     */
    val localFileName: String
        get() {
            val unsafe = "/\\:?%*|\"<>"
            var stem = title.replacingEach { it in unsafe || Character.isISOControl(it) || it == '\u2028' || it == '\u2029' || it == '\u0085' }
                .trim { it.isWhitespace() || it == '.' || it == '-' }
            if (stem.isEmpty()) stem = "Document"
            if (stem.length > 80) stem = stem.take(80).trim { it.isWhitespace() }
            return fileExtension(mime, fileName)?.let { "$stem.$it" } ?: stem
        }

    companion object {
        fun of(document: BuildingDocument): DocumentItem = DocumentItem(
            id = document.id.lowercase(),
            title = document.title,
            kind = document.kind,
            mime = document.mime,
            fileName = document.fileName,
            elementID = document.elementId,
            sizeBytes = document.sizeBytes,
            url = if (document.uploaded) document.downloadURL else null,
        )

        fun of(reference: DocumentRef): DocumentItem = DocumentItem(
            id = reference.id.lowercase(),
            title = reference.title,
            kind = DocumentKind(reference.kind),
            mime = reference.mime,
            url = reference.downloadURL,
        )

        internal fun fileExtension(mime: String, fileName: String?): String? {
            when (mime.lowercase().split(';').first().trim()) {
                "application/pdf" -> return "pdf"
                "image/jpeg", "image/jpg" -> return "jpg"
                "image/png" -> return "png"
                "image/heic" -> return "heic"
            }
            if (fileName == null) return null
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0) return null
            val ext = fileName.substring(dot + 1).lowercase()
            return if (ext.isEmpty() || ext.length > 5 || !ext.all { it.isLetterOrDigit() }) null else ext
        }

        /** Splits on every character [isSeparator] matches and joins the parts with `-`. */
        private inline fun String.replacingEach(isSeparator: (Char) -> Boolean): String =
            map { if (isSeparator(it)) '-' else it }.joinToString("")
    }
}

sealed class DocumentError(message: String) : Exception(message) {
    /** The upload isn't confirmed yet, or the link couldn't be renewed. */
    data object Unavailable : DocumentError("The document isn't available.")
}

/**
 * Downloads documents to files for the viewer (IOS-M3-06; on Android they open with `ACTION_VIEW`
 * through a FileProvider). Presigned links last 15 minutes: a 403 fetches a fresh link once (from
 * the list or the element detail) and retries. Files stay for the app's session under [directory]
 * and are reused.
 *
 * @param directory e.g. `cacheDir/Documents`; cleared by [clear].
 */
class DocumentDownloader(private val transport: ChunkTransport, private val directory: File) {
    private val lock = Any()
    private val inFlight = HashMap<String, CompletableDeferred<File>>()

    /** Where a document's file goes: one folder per document id, named for the viewer. */
    fun localFile(item: DocumentItem): File = File(File(directory, folderName(item.id)), item.localFileName)

    /**
     * The document as a local file: downloaded once, then reused. [renewURL] returns a fresh
     * presigned link after a 403 (`null` when there is none).
     */
    suspend fun file(
        item: DocumentItem,
        renewURL: suspend () -> URI? = { null },
        received: (Long) -> Unit = {},
    ): File {
        val destination = localFile(item)
        if (destination.exists()) return destination
        var owner = false
        val deferred = synchronized(lock) {
            inFlight[item.id] ?: CompletableDeferred<File>().also {
                inFlight[item.id] = it
                owner = true
            }
        }
        if (!owner) return deferred.await()
        try {
            val url = item.url ?: throw DocumentError.Unavailable
            val data = try {
                transport.download(url, received)
            } catch (e: ChunkTransportError.HttpStatus) {
                if (e.status != 403) throw e
                val renewed = renewURL() ?: throw DocumentError.Unavailable
                transport.download(renewed, received)
            }
            withContext(Dispatchers.IO) {
                destination.parentFile?.mkdirs()
                val temporary = File(destination.parentFile, ".${destination.name}.download")
                temporary.writeBytes(data)
                if (!temporary.renameTo(destination)) {
                    destination.delete()
                    temporary.renameTo(destination)
                }
            }
            deferred.complete(destination)
            return destination
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            synchronized(lock) { inFlight.remove(item.id) }
        }
    }

    /** Removes every downloaded document. */
    fun clear() {
        directory.deleteRecursively()
    }

    companion object {
        internal fun folderName(id: String): String {
            val safe = id.filter { it.code < 128 && (it.isLetterOrDigit() || it == '-') }
            return safe.ifEmpty { "document" }
        }
    }
}
