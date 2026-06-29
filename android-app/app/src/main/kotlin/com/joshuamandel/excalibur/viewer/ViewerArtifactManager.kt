package com.joshuamandel.excalibur.viewer

import com.joshuamandel.excalibur.data.Book
import com.joshuamandel.excalibur.data.LibraryRepository
import com.joshuamandel.excalibur.wasmtime.WasmtimeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ViewerArtifact(val entry: File)

/**
 * Lazily converts a ready AZW3 into local HTML for in-app preview. This never
 * starts or talks to the Kindle HTTP server; the WebView loads app-private files.
 */
class ViewerArtifactManager(
    private val repo: LibraryRepository,
    private val runtime: WasmtimeRuntime,
) {
    private val lock = Mutex()

    suspend fun prepare(book: Book, onProgress: (String) -> Unit = {}): ViewerArtifact = lock.withLock {
        withContext(Dispatchers.IO) {
            require(book.isReady) { "Book is not ready to preview." }
            val source = File(requireNotNull(book.azw3Path))
            require(source.exists()) { "Converted AZW3 is missing." }

            val storage = repo.storage()
            val outputDir = storage.viewerDir(book.id)
            cached(outputDir, source)?.let { return@withContext it }

            onProgress("Preparing preview...")
            val workDir = storage.viewerWorkDir(book.id)
            val result = runtime.generateViewerHtml(source, outputDir, workDir, onLine = onProgress)
            runCatching { workDir.deleteRecursively() }

            if (!result.ok) {
                val message = result.stderr.ifBlank { result.stdout }
                    .ifBlank { result.error }
                    .takeLast(1200)
                    .ifBlank { "Preview generation failed." }
                throw IllegalStateException(message)
            }

            val entry = findEntry(outputDir)
                ?: throw IllegalStateException("Preview generation did not produce an HTML file.")
            polishHtml(entry)
            writeManifest(outputDir, source, entry)
            ViewerArtifact(entry)
        }
    }

    private fun cached(outputDir: File, source: File): ViewerArtifact? {
        val manifest = File(outputDir, MANIFEST)
        if (!manifest.exists()) return null
        val json = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return null
        if (json.optInt("version") != VERSION) return null
        if (json.optString("sourcePath") != source.absolutePath) return null
        if (json.optLong("sourceSize") != source.length()) return null
        if (json.optLong("sourceModified") != source.lastModified()) return null
        val entry = File(outputDir, json.optString("entry")).takeIf { it.exists() } ?: return null
        return ViewerArtifact(entry)
    }

    private fun writeManifest(outputDir: File, source: File, entry: File) {
        val rel = entry.relativeTo(outputDir).invariantSeparatorsPath
        File(outputDir, MANIFEST).writeText(
            JSONObject()
                .put("version", VERSION)
                .put("sourcePath", source.absolutePath)
                .put("sourceSize", source.length())
                .put("sourceModified", source.lastModified())
                .put("entry", rel)
                .toString(),
        )
    }

    private fun findEntry(outputDir: File): File? {
        listOf("index.html", "index.xhtml").forEach { name ->
            File(outputDir, name).takeIf { it.exists() }?.let { return it }
        }
        return outputDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("html", "xhtml", "htm") }
            .minByOrNull { it.relativeTo(outputDir).invariantSeparatorsPath.length }
    }

    private fun polishHtml(entry: File) {
        val html = runCatching { entry.readText() }.getOrNull() ?: return
        if (html.contains("data-excalibur-viewer")) return
        val injected = """
            <meta name="viewport" content="width=device-width, initial-scale=1" data-excalibur-viewer="1">
            <style data-excalibur-viewer="1">
            html { background: #ffffff; color: #111111; }
            body {
              max-width: 42rem;
              margin: 0 auto;
              padding: 1rem;
              line-height: 1.55;
              word-break: normal;
              overflow-wrap: anywhere;
            }
            img, svg, video { max-width: 100%; height: auto; }
            pre { white-space: pre-wrap; }
            </style>
        """.trimIndent()
        val closeHead = Regex("</head>", RegexOption.IGNORE_CASE)
        val updated = if (closeHead.containsMatchIn(html)) {
            closeHead.replaceFirst(html, "$injected\n</head>")
        } else {
            "$injected\n$html"
        }
        entry.writeText(updated)
    }

    private companion object {
        const val VERSION = 1
        const val MANIFEST = "viewer-manifest.json"
    }
}
