package com.joshuamandel.excalibur.drive

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class PublicDriveListing(
    val folderName: String,
    val files: List<PublicDriveFile>,
)

data class PublicDriveFile(
    val id: String,
    val name: String,
    val resourceKey: String? = null,
    val path: String = "",
) {
    val displayName: String get() = listOf(path, name).filter { it.isNotBlank() }.joinToString("/")
}

object DrivePublicFolder {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36"
    private const val MAX_REDIRECTS = 8
    private const val MAX_FOLDERS = 50

    internal data class FolderRef(val id: String, val resourceKey: String? = null)

    fun isFolderUrl(raw: String): Boolean = parseFolderUrl(raw) != null

    internal fun parseFolderUrl(raw: String): FolderRef? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val segments = uri?.rawPath?.trim('/')?.split('/').orEmpty().map(::urlDecode)
        val folderId = segments.indexOf("folders")
            .takeIf { it >= 0 && it < segments.lastIndex }
            ?.let { segments[it + 1] }
            ?: queryParameter(trimmed, "id")
            ?: Regex("""(?:^|[?&])id=([-\w]{10,})""").find(trimmed)?.groupValues?.get(1)
        val cleanId = folderId?.takeIf { it.matches(Regex("""[-\w]{10,}""")) } ?: return null
        return FolderRef(cleanId, queryParameter(trimmed, "resourcekey"))
    }

    fun list(url: String, onLog: (String) -> Unit = {}): PublicDriveListing {
        val root = parseFolderUrl(url) ?: throw IOException("Paste a public Google Drive folder link.")
        val jar = CookieJar()
        val visited = linkedSetOf<String>()
        val files = mutableListOf<PublicDriveFile>()
        var folderName = "Public Drive folder"
        fun visit(folder: FolderRef, path: String) {
            if (!visited.add(folder.id)) return
            if (visited.size > MAX_FOLDERS) throw IOException("Drive folder has too many nested folders.")
            onLog(if (path.isBlank()) "Reading public Drive folder..." else "Reading $path...")
            val parsed = parseEmbeddedFolderHtml(fetchText(embeddedFolderUrl(folder), jar))
            if (path.isBlank()) folderName = parsed.folderName.ifBlank { folderName }
            parsed.files.forEach { files += it.copy(path = path) }
            parsed.folders.forEach { child ->
                visit(child.ref, listOf(path, child.name).filter { it.isNotBlank() }.joinToString("/"))
            }
        }
        visit(root, "")
        return PublicDriveListing(folderName, files.distinctBy { it.id })
    }

    fun download(context: Context, file: PublicDriveFile): File {
        val dir = File(context.cacheDir, "drive-inbox-downloads").apply { mkdirs() }
        val safeName = safeFilename(file.name).ifBlank { "${file.id}.bin" }
        val itemDir = File(dir, file.id).apply {
            deleteRecursively()
            mkdirs()
        }
        val dest = File(itemDir, safeName)
        val jar = CookieJar()
        val firstUrl = downloadUrl(file)
        val html = downloadUrlToFile(firstUrl, dest, jar)
        if (html == null) return dest
        val confirmUrl = parseConfirmDownloadUrl(html)
            ?: throw IOException("Drive returned a web page instead of file bytes for ${file.name}.")
        val secondHtml = downloadUrlToFile(confirmUrl, dest, jar)
        if (secondHtml != null) throw IOException("Drive did not provide file bytes for ${file.name}.")
        return dest
    }

    private fun embeddedFolderUrl(ref: FolderRef): String =
        buildString {
            append("https://drive.google.com/embeddedfolderview?id=")
            append(urlEncode(ref.id))
            ref.resourceKey?.takeIf { it.isNotBlank() }?.let {
                append("&resourcekey=")
                append(urlEncode(it))
            }
        }

    private fun downloadUrl(file: PublicDriveFile): String =
        buildString {
            append("https://drive.google.com/uc?export=download&id=")
            append(urlEncode(file.id))
            file.resourceKey?.takeIf { it.isNotBlank() }?.let {
                append("&resourcekey=")
                append(urlEncode(it))
            }
        }

    private fun fetchText(url: String, jar: CookieJar): String {
        val conn = openFollowingRedirects(url, jar)
        try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IOException("Drive returned HTTP $code.")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadUrlToFile(url: String, dest: File, jar: CookieJar): String? {
        val conn = openFollowingRedirects(url, jar)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                throw IOException("Drive returned HTTP $code: ${body.take(120)}")
            }
            val contentType = conn.contentType.orEmpty().lowercase(Locale.US)
            val disposition = conn.getHeaderField("Content-Disposition").orEmpty()
            if ("text/html" in contentType && disposition.isBlank()) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            dest.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun openFollowingRedirects(initialUrl: String, jar: CookieJar): HttpURLConnection {
        var url = initialUrl
        repeat(MAX_REDIRECTS) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                jar.header()?.let { setRequestProperty("Cookie", it) }
            }
            val code = conn.responseCode
            jar.store(conn)
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: return conn
                url = URL(URL(url), location).toString()
                conn.disconnect()
            } else {
                return conn
            }
        }
        throw IOException("Drive redirected too many times.")
    }

    internal data class ParsedFolderHtml(
        val folderName: String,
        val files: List<PublicDriveFile>,
        val folders: List<PublicDriveFolder>,
    )

    internal data class PublicDriveFolder(
        val ref: FolderRef,
        val name: String,
    )

    internal fun parseEmbeddedFolderHtml(html: String): ParsedFolderHtml {
        val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::stripTags)
            ?.removeSuffix(" - Google Drive")
            ?.trim()
            .orEmpty()
        val files = linkedMapOf<String, PublicDriveFile>()
        val folders = linkedMapOf<String, PublicDriveFolder>()
        val anchor = Regex("""<a\b[^>]*\bhref\s*=\s*(['"])(.*?)\1[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        anchor.findAll(html).forEach { match ->
            val href = decodeHtmlEntities(match.groupValues[2])
            val name = stripTags(match.groupValues[3]).ifBlank { "book" }
            val fileId = Regex("""https?://drive\.google\.com/file/d/([-\w]{10,})/""").find(href)?.groupValues?.get(1)
                ?: Regex("""https?://docs\.google\.com/\w+/d/([-\w]{10,})/""").find(href)?.groupValues?.get(1)
            if (fileId != null) {
                files[fileId] = PublicDriveFile(fileId, name, resourceKey(href))
                return@forEach
            }
            val folderId = Regex("""https?://drive\.google\.com/drive/(?:u/\d+/)?folders/([-\w]{10,})""").find(href)?.groupValues?.get(1)
            if (folderId != null) {
                folders[folderId] = PublicDriveFolder(FolderRef(folderId, resourceKey(href)), name)
            }
        }
        return ParsedFolderHtml(title, files.values.toList(), folders.values.toList())
    }

    private fun parseConfirmDownloadUrl(html: String): String? {
        val decoded = decodeHtmlEntities(html)
        val href = Regex("""href\s*=\s*['"]([^'"]*(?:/uc|download)[^'"]*confirm=[^'"]*)['"]""", RegexOption.IGNORE_CASE)
            .find(decoded)
            ?.groupValues
            ?.get(1)
            ?: Regex("""(/uc\?[^'"\s<>]*confirm=[^'"\s<>]*)""", RegexOption.IGNORE_CASE)
                .find(decoded)
                ?.groupValues
                ?.get(1)
        return href?.let {
            if (it.startsWith("http")) it else URL(URL("https://drive.google.com"), it).toString()
        }
    }

    private fun resourceKey(url: String): String? =
        queryParameter(url, "resourcekey")?.takeIf { it.isNotBlank() }

    private fun stripTags(raw: String): String =
        decodeHtmlEntities(Regex("""<[^>]+>""").replace(raw, " "))
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun decodeHtmlEntities(raw: String): String {
        val common = raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        return Regex("""&#(x?[0-9A-Fa-f]+);""").replace(common) { match ->
            val token = match.groupValues[1]
            val code = runCatching {
                if (token.startsWith("x", ignoreCase = true)) token.drop(1).toInt(16) else token.toInt()
            }.getOrNull()
            code?.let { String(Character.toChars(it)) } ?: match.value
        }
    }

    private fun safeFilename(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().take(120)

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun urlDecode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun queryParameter(raw: String, name: String): String? {
        val query = runCatching { URI(raw).rawQuery }.getOrNull()
            ?: raw.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        if (query.isBlank()) return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (urlDecode(key) == name) urlDecode(part.substringAfter('=', "")) else null
        }
    }

    private class CookieJar {
        private val cookies = linkedMapOf<String, String>()

        fun store(conn: HttpURLConnection) {
            conn.headerFields["Set-Cookie"].orEmpty().forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotBlank()) cookies[name] = value
            }
        }

        fun header(): String? =
            cookies.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
