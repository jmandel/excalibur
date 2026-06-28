package dev.exe.kindleconverter.server

import android.util.Log
import dev.exe.kindleconverter.data.BookDao
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny single-purpose HTTP server for the Kindle: it serves the library page and
 * streams the converted AZW3 files. Reads the catalog straight from Room (synchronous
 * DAO calls, off the main thread). Binds the preferred (stable) port when free so the
 * URL the user types stays the same across launches, falling back to an ephemeral port.
 */
class KindleHttpServer(private val dao: BookDao) {
    @Volatile var port: Int = 0; private set
    private var socket: ServerSocket? = null
    @Volatile private var running = false

    fun start(preferredPort: Int): Int {
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) } }
            .getOrElse { ServerSocket(0) }
        socket = s
        port = s.localPort
        running = true
        thread(name = "kindle-http") {
            while (running) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { client.use { handle(it) } }
                    .onFailure { Log.e("kindle-http", "request failed", it) }
            }
        }
        return port
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(sock: Socket) {
        val request = readRequestLine(BufferedInputStream(sock.getInputStream()))
        val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        val out = sock.getOutputStream()
        when {
            path == "/" -> html(out, renderKindlePage(dao.readySync().map { it.toPageBook() }))
            path == "/catalog.json" -> json(out)
            path.startsWith("/download/") -> download(out, path.removePrefix("/download/"))
            path == "/favicon.ico" -> status(out, "204 No Content")
            else -> status(out, "404 Not Found")
        }
    }

    private fun download(out: OutputStream, rawId: String) {
        val id = rawId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val book = dao.getSync(id)
        val file = book?.azw3Path?.let(::File)
        if (book == null || file == null || !file.exists()) { status(out, "404 Not Found"); return }
        val name = safeFileName(book.title) + ".azw3"
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${file.length()}\r\n" +
                "Content-Disposition: attachment; filename=\"$name\"\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        FileInputStream(file).use { it.copyTo(out) }
    }

    private fun json(out: OutputStream) {
        val items = dao.readySync().joinToString(",") { b ->
            """{"id":"${b.id}","title":${jsonStr(b.title)},"author":${jsonStr(b.author)},"size":${b.azw3Size}}"""
        }
        respond(out, "application/json", "[$items]".toByteArray())
    }

    private fun html(out: OutputStream, body: String) = respond(out, "text/html; charset=utf-8", body.toByteArray())

    private fun respond(out: OutputStream, type: String, bytes: ByteArray) {
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
    }

    private fun status(out: OutputStream, status: String) {
        out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun readRequestLine(input: BufferedInputStream): String {
        val buf = ByteArrayOutputStream()
        var last = 0
        while (true) {
            val c = input.read()
            if (c < 0 || (last == '\r'.code && c == '\n'.code)) break
            if (c != '\r'.code) buf.write(c)
            last = c
        }
        return buf.toString()
    }

    private fun safeFileName(title: String): String =
        title.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().ifBlank { "book" }.take(60)

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
