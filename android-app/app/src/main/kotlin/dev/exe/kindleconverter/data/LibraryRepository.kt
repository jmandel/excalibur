package dev.exe.kindleconverter.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LibraryRepository(
    private val context: Context,
    private val dao: BookDao,
    private val storage: Storage,
) {
    fun observeAll(): Flow<List<Book>> = dao.observeAll()
    fun observe(id: String): Flow<Book?> = dao.observe(id)
    suspend fun get(id: String) = dao.get(id)
    suspend fun ready() = dao.ready()
    suspend fun nextPending() = dao.nextPending()

    /** Copy a picked/shared Uri into the managed library and queue it. Returns the new id. */
    suspend fun importAndQueue(uri: Uri, profile: String): String = withContext(Dispatchers.IO) {
        val display = queryName(uri) ?: "book"
        val ext = display.substringAfterLast('.', "epub").lowercase().ifBlank { "epub" }
        val id = UUID.randomUUID().toString()
        val dest = storage.original(id, ext)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $uri" }
            dest.outputStream().use { input.copyTo(it) }
        }
        // Dedupe: if the exact same bytes are already in the library, drop this copy and
        // return the existing book instead of creating a duplicate row.
        val hash = sha256(dest)
        dao.findByHash(hash)?.let { existing ->
            storage.purge(id)
            return@withContext existing.id
        }
        // Prefer the book's own metadata over the filename so the library reads nicely.
        val meta = if (ext == "epub") readEpubMeta(dest) else null
        val fallback = display.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifBlank { "Untitled" }
        val now = System.currentTimeMillis()
        dao.upsert(
            Book(
                id = id, title = meta?.first ?: fallback, author = meta?.second.orEmpty(),
                originalName = display, ext = ext,
                originalSize = dest.length(), status = BookStatus.QUEUED, profile = profile,
                stage = Stage.IMPORT, createdAt = now, contentHash = hash,
            )
        )
        id
    }

    /** Best-effort EPUB title/author from the OPF package document. Null on any failure. */
    private fun readEpubMeta(file: File): Pair<String, String?>? = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return null
            val opfPath = zip.getInputStream(container).bufferedReader().use { it.readText() }
                .let { Regex("""full-path="([^"]+)"""").find(it)?.groupValues?.get(1) } ?: return null
            val opf = zip.getEntry(opfPath)?.let { e -> zip.getInputStream(e).bufferedReader().use { it.readText() } } ?: return null
            val title = Regex("""<dc:title[^>]*>([^<]+)</dc:title>""", RegexOption.IGNORE_CASE)
                .find(opf)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val author = Regex("""<dc:creator[^>]*>([^<]+)</dc:creator>""", RegexOption.IGNORE_CASE)
                .find(opf)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
            title to author
        }
    }.getOrNull()

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n = ins.read(buf)
            while (n >= 0) { md.update(buf, 0, n); n = ins.read(buf) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun decodeEntities(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    suspend fun requeue(id: String, profile: String) {
        val b = dao.get(id) ?: return
        dao.update(b.copy(status = BookStatus.QUEUED, stage = Stage.IMPORT, stageLabel = "", error = "", profile = profile))
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        storage.purge(id)
    }

    suspend fun deleteMany(ids: Collection<String>) = ids.forEach { delete(it) }

    /** Add [tag] to each of [ids] (idempotent; blank tags ignored). */
    suspend fun addTag(ids: Collection<String>, tag: String) {
        val t = tag.trim().takeIf { it.isNotEmpty() } ?: return
        ids.forEach { id ->
            dao.get(id)?.let { b ->
                if (t !in b.tagSet) dao.update(b.copy(tags = (b.tagSet + t).joinToString(",")))
            }
        }
    }

    /** Remove [tag] from a single book. */
    suspend fun removeTag(id: String, tag: String) {
        dao.get(id)?.let { b ->
            if (tag in b.tagSet) dao.update(b.copy(tags = (b.tagSet - tag).joinToString(",")))
        }
    }

    suspend fun update(book: Book) = dao.update(book)
    fun storage() = storage

    private fun queryName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}
