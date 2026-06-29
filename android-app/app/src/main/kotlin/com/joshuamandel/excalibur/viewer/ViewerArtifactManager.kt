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
        val cleaned = VIEWER_INJECTION.replace(html, "")
        val injected = """
            <meta name="viewport" content="width=device-width, initial-scale=1" data-excalibur-viewer="1">
            <link rel="stylesheet" href="excalibur-theme.css" data-excalibur-viewer="1">
            <style data-excalibur-viewer="1">
            :root {
              --excalibur-background: Canvas;
              --excalibur-surface: Canvas;
              --excalibur-on-background: CanvasText;
              --excalibur-on-surface-variant: CanvasText;
              --excalibur-primary: LinkText;
              --excalibur-outline: GrayText;
              --excalibur-page-pad-x: 22px;
            }
            * { scroll-behavior: auto !important; }
            html {
              background: var(--excalibur-background) !important;
              color: var(--excalibur-on-background) !important;
              overflow-x: auto;
              overflow-y: hidden;
            }
            body {
              box-sizing: border-box;
              height: 100vh;
              max-width: none !important;
              margin: 0 !important;
              padding: 24px var(--excalibur-page-pad-x);
              background: var(--excalibur-background) !important;
              color: var(--excalibur-on-background) !important;
              line-height: 1.58;
              word-break: normal;
              overflow-wrap: anywhere;
              overflow: visible;
              column-fill: auto;
              column-gap: calc(var(--excalibur-page-pad-x) * 2);
              column-width: calc(100vw - (var(--excalibur-page-pad-x) * 2));
            }
            body, body * {
              background-color: transparent !important;
              color: var(--excalibur-on-background) !important;
            }
            a, a * { color: var(--excalibur-primary) !important; }
            h1, h2, h3, h4, h5, h6 {
              color: var(--excalibur-primary) !important;
              break-after: avoid;
            }
            img, svg, video { max-width: 100%; height: auto; }
            pre { white-space: pre-wrap; }
            blockquote, pre {
              border-inline-start: 3px solid var(--excalibur-outline);
              color: var(--excalibur-on-surface-variant) !important;
              padding-inline-start: 0.8rem;
            }
            blockquote, pre, table, img, svg, video {
              break-inside: avoid-column;
            }
            @media (min-width: 720px) {
              :root { --excalibur-page-pad-x: 48px; }
              body { padding-top: 32px; padding-bottom: 32px; }
            }
            </style>
        """.trimIndent()
        val closeHead = Regex("</head>", RegexOption.IGNORE_CASE)
        val updated = if (closeHead.containsMatchIn(cleaned)) {
            closeHead.replaceFirst(cleaned, "$injected\n</head>")
        } else {
            "$injected\n$cleaned"
        }
        entry.writeText(updated)
    }

    private companion object {
        const val VERSION = 2
        const val MANIFEST = "viewer-manifest.json"
        val VIEWER_INJECTION = Regex(
            """(?is)\s*<meta\b[^>]*data-excalibur-viewer=["']1["'][^>]*>\s*(?:<link\b[^>]*data-excalibur-viewer=["']1["'][^>]*>\s*)?<style\b[^>]*data-excalibur-viewer=["']1["'][^>]*>.*?</style>""",
        )
    }
}
