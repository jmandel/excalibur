package dev.exe.kindleconverter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.exe.kindleconverter.wasm.WasmSpike
import dev.exe.kindleconverter.wasmtime.WasmtimeRuntime
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var server: KindleHttpServer
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var wasmtime: WasmtimeRuntime

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wasmtime = WasmtimeRuntime(this)
        if (intent?.getBooleanExtra("web", false) == true) {
            showWebViewUi()
        } else {
            showNativeWasmtimeDashboard()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebViewUi() {
        webView = WebView(this)
        setContentView(FrameLayout(this).apply { addView(webView, FrameLayout.LayoutParams(-1, -1)) })
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                android.util.Log.d("KindleLibrary", message.message())
                return true
            }
        }
        server = KindleHttpServer(this).also { it.start() }
        webView.addJavascriptInterface(AndroidBridge(this), "AndroidKindle")
        webView.loadUrl("http://127.0.0.1:${server.port}/app/")
    }


    private fun showNativeWasmtimeDashboard() {
        server = KindleHttpServer(this).also { it.start() }
        val log = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        fun append(line: String) = runOnUiThread { log.append(line + "\n") }
        val probe = Button(this).apply { text = "Run native Wasmtime CPython/calibre proof" }
        val openWeb = Button(this).apply { text = "Open legacy WebView UI"; setOnClickListener { startActivity(Intent(this@MainActivity, MainActivity::class.java).putExtra("web", true)) } }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            addView(TextView(this@MainActivity).apply {
                text = "Kindle Converter Native Runtime\nPreferred path: Kotlin → JNI/NDK → Wasmtime → shared exnref WASI CPython/calibre runtime.\nServer: ${serverUrlsJson(this@MainActivity, server.port)}"
                textSize = 18f
            })
            addView(probe)
            addView(openWeb)
            addView(ScrollView(this@MainActivity).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(layout)
        probe.setOnClickListener {
            probe.isEnabled = false
            io.execute {
                try {
                    append("Preparing runtime asset shared with web app...")
                    val root = wasmtime.prepareRuntime { append(it) }
                    val manifest = File(root, "runtime-manifest.json").takeIf { it.exists() }?.readText()?.trim().orEmpty()
                    append("Runtime: ${root.absolutePath}")
                    append(manifest)
                    val sample = File(filesDir, "native-samples/minimal.epub").also { it.parentFile?.mkdirs() }
                    assets.open("minimal.epub").use { input -> sample.outputStream().use { input.copyTo(it) } }
                    val out = File(filesDir, "converted/native-minimal.azw3").also { it.parentFile?.mkdirs() }
                    append("Running native conversion...")
                    val started = System.currentTimeMillis()
                    val r = wasmtime.convert(sample, out, File(filesDir, "wasmtime-work"))
                    val elapsed = System.currentTimeMillis() - started
                    append("exit=${r.exitCode} ok=${r.ok} precompiled=${r.usedPrecompiled} compileMs=${r.compileMs} runMs=${r.runMs} wallMs=$elapsed")
                    if (r.stdout.isNotBlank()) append("stdout:\n${r.stdout}")
                    if (r.stderr.isNotBlank()) append("stderr:\n${r.stderr}")
                    if (r.error.isNotBlank()) append("error:\n${r.error}")
                    if (out.exists()) {
                        append("AZW3 ready: ${out.absolutePath} (${out.length()} bytes)")
                        server.catalogJson = "[{\"id\":\"native-minimal\",\"title\":\"Native Wasmtime minimal proof\",\"author\":\"calibre via WASI CPython\",\"size\":${out.length()},\"tags\":[\"native\",\"wasmtime\",\"azw3\"],\"azw3Path\":${json(out.absolutePath)}}]"
                        append("Kindle download URL(s): ${serverUrlsJson(this@MainActivity, server.port)}")
                    }
                } catch (e: Throwable) {
                    append("FAILED: ${e.stackTraceToString()}")
                } finally {
                    runOnUiThread { probe.isEnabled = true }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::server.isInitialized) server.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated by Android; kept dependency-free for this prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_BOOKS || resultCode != RESULT_OK || data == null) return
        data.clipData?.let { clip -> repeat(clip.itemCount) { importUri(clip.getItemAt(it).uri) } } ?: data.data?.let { importUri(it) }
    }

    private fun importUri(uri: Uri) = io.execute {
        try {
            val name = displayName(uri) ?: "book-${UUID.randomUUID()}"
            val ext = name.substringAfterLast('.', "bin").lowercase(Locale.US)
            val dest = File(filesDir, "library/${UUID.randomUUID()}.$ext").also { it.parentFile?.mkdirs() }
            contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            runJs("window.NativeLibrary&&window.NativeLibrary.importedFile(${json(name)},${json(ext)},${json(dest.absolutePath)},${dest.length()})")
        } catch (e: Exception) {
            runJs("alert(${json("Import failed: ${e.message}")})")
        }
    }

    private fun displayName(uri: Uri): String? = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }

    private fun runJs(js: String) = runOnUiThread { webView.evaluateJavascript(js, null) }

    inner class AndroidBridge(private val ctx: Context) {
        @JavascriptInterface fun pickBooks() {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, PICK_BOOKS)
        }
        @JavascriptInterface fun readAssetBase64(name: String): String = ctx.assets.open("app/$name").use { Base64.encodeToString(it.readBytesCompat(), Base64.NO_WRAP) }
        @JavascriptInterface fun readFileBase64(path: String): String = Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
        @JavascriptInterface fun writeBookBase64(id: String, name: String, base64: String): String {
            val dir = File(ctx.filesDir, "converted/$id").also { it.mkdirs() }
            val file = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]+"), "-"))
            file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
            return file.absolutePath
        }
        @JavascriptInterface fun runWasmSpike(): String = WasmSpike.run(ctx)
        @JavascriptInterface fun serverPort(): Int = server.port
        @JavascriptInterface fun serverUrls(): String = serverUrlsJson(ctx, server.port)
        @JavascriptInterface fun setCatalogJson(json: String) { server.catalogJson = json }
        @JavascriptInterface fun log(message: String) { android.util.Log.d("KindleLibrary", message) }
    }

    companion object {
        private const val PICK_BOOKS = 42
        fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        fun serverUrlsJson(ctx: Context, port: Int): String = discoverIpv4(ctx).joinToString(prefix = "[", postfix = "]") { json("http://$it:$port/") }
        fun discoverIpv4(ctx: Context): List<String> {
            val out = linkedSetOf<String>()
            runCatching {
                val wm = ctx.applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                wm?.connectionInfo?.ipAddress?.takeIf { it != 0 }?.let { out += Formatter.formatIpAddress(it) }
            }
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                    if (!nif.isUp || nif.isLoopback) return@forEach
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { if (!it.isLoopbackAddress) out += it.hostAddress.orEmpty() }
                }
            }
            return out.filter { it.isNotBlank() }.ifEmpty { listOf("127.0.0.1") }
        }
    }
}

class KindleHttpServer(private val ctx: Context) {
    @Volatile var catalogJson: String = "[]"
    @Volatile var port: Int = 0
    private var socket: ServerSocket? = null

    fun start() {
        socket = ServerSocket(0).also { port = it.localPort }
        thread(name = "kindle-http") {
            runCatching {
                while (!Thread.currentThread().isInterrupted) socket?.accept()?.use { handle(it) }
            }
        }
    }
    fun stop() { runCatching { socket?.close() } }

    private fun handle(sock: Socket) {
        runCatching {
            val request = readHeader(BufferedInputStream(sock.getInputStream()))
            val path = request.split(' ').getOrNull(1) ?: "/"
            val out = sock.getOutputStream()
            when {
                path == "/" -> respond(out, "text/html; charset=utf-8", kindlePage(catalogJson).toByteArray())
                path == "/app/" || path == "/app" -> respondAsset(out, "app/app.html", "text/html; charset=utf-8")
                path.startsWith("/assets/") -> respondAsset(out, "app/" + path.removePrefix("/assets/"), assetMime(path))
                path.startsWith("/catalog.json") -> respond(out, "application/json", catalogJson.toByteArray())
                path.startsWith("/download/") -> download(out, path)
                else -> respond(out, "text/plain", "Not found".toByteArray(), "404 Not Found")
            }
        }
    }

    private fun readHeader(input: BufferedInputStream): String {
        val out = ByteArrayOutputStream()
        var tail = ""
        while (true) {
            val c = input.read()
            if (c < 0) break
            out.write(c)
            tail = (tail + c.toChar()).takeLast(4)
            if (tail == "\r\n\r\n") break
        }
        return out.toString()
    }

    private fun download(out: OutputStream, path: String) {
        val id = path.removePrefix("/download/").replace(Regex("[/?].*$"), "").replace(Regex("[^a-zA-Z0-9_-]"), "")
        val meta = Regex("\\{[^}]*\\\"id\\\"\\s*:\\s*\\\"${Pattern.quote(id)}\\\"[^}]*}").find(catalogJson)?.value.orEmpty()
        val file = Regex("\\\"azw3Path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(meta)?.groupValues?.get(1)?.replace("\\/", "/")?.replace("\\\\", "\\")
        val actual = file?.let { File(it) }
        if (actual?.exists() == true) respondFile(out, actual) else respond(out, "text/plain", "Not found".toByteArray(), "404 Not Found")
    }

    private fun respondAsset(out: OutputStream, assetPath: String, type: String) {
        runCatching {
            ctx.assets.open(assetPath).use { input ->
                val bytes = input.readBytesCompat()
                respond(out, type, bytes)
            }
        }.getOrElse { respond(out, "text/plain", "Not found".toByteArray(), "404 Not Found") }
    }
    private fun respondFile(out: OutputStream, file: File) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nContent-Disposition: attachment; filename=\"${file.name}\"\r\nConnection: close\r\n\r\n".toByteArray())
        FileInputStream(file).use { it.copyTo(out) }
    }
    private fun respond(out: OutputStream, type: String, bytes: ByteArray, status: String = "200 OK") {
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
    }

    private fun assetMime(path: String) = when {
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".mjs") -> "application/javascript"
        path.endsWith(".wasm") -> "application/wasm"
        path.endsWith(".zip") -> "application/zip"
        path.endsWith(".whl") -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    private fun kindlePage(catalog: String) = """<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'><title>Kindle Library</title><style>body{font-family:serif;background:#f8f3e8;color:#211b13;margin:18px;line-height:1.35}h1{font-size:26px}input{font-size:18px;width:96%;padding:8px;border:1px solid #8b7355;background:#fffaf0}.book{border-top:1px solid #cdbb9e;padding:12px 0}.book:first-of-type{border-top:3px solid #4b3826}a{font-size:20px;color:#111;display:inline-block;padding:8px 0}.meta{color:#665844;font-size:14px}.tag{font-size:13px;background:#eee0c9;padding:2px 5px;margin-right:4px}</style></head><body><h1>Kindle downloads</h1><p>Newest converted AZW3 books appear first. Tap a title to download.</p><input id='q' placeholder='Filter title, author, tag'><div id='books'></div><script>var books=$catalog;function r(){var q=document.getElementById('q').value.toLowerCase(),root=document.getElementById('books');root.innerHTML='';books.filter(function(b){return (b.title+' '+(b.author||'')+' '+(b.tags||[]).join(' ')).toLowerCase().indexOf(q)>=0}).forEach(function(b,i){var d=document.createElement('div');d.className='book';d.innerHTML=(i==0?'<div class=meta>Latest Kindle-ready book</div>':'')+'<a href="/download/'+b.id+'">'+b.title+'</a><div class=meta>'+(b.author||'')+' · '+Math.round((b.size||0)/1024)+' KB</div><div>'+((b.tags||[]).map(function(t){return '<span class=tag>'+t+'</span>'}).join(''))+'</div>';root.appendChild(d)})}document.getElementById('q').oninput=r;r()</script></body></html>"""
}

private fun java.io.InputStream.readBytesCompat(): ByteArray = ByteArrayOutputStream().also { copyTo(it) }.toByteArray()
