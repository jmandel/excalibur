package dev.exe.kindleconverter.wasmtime

import android.content.Context
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class WasmtimeRuntime(private val context: Context) {

    @Volatile private var lineListener: ((String) -> Unit)? = null

    /** Invoked from native (kindle_wasm_runtime.cpp) for each line of calibre output. */
    @Keep
    private fun onNativeLine(line: String) {
        lineListener?.invoke(line)
    }
    data class Result(
        val exitCode: Int,
        val compileMs: Long,
        val runMs: Long,
        val usedPrecompiled: Boolean,
        val stdout: String,
        val stderr: String,
        val error: String,
    ) { val ok: Boolean get() = exitCode == 0 && error.isBlank() }

    private external fun nativeRunPython(wasmPath: String, cwasmPath: String, runtimeRoot: String, workDir: String, preferPrecompiled: Boolean): String
    private external fun nativePrewarm(wasmPath: String, cwasmPath: String, preferPrecompiled: Boolean): Boolean

    companion object {
        init { System.loadLibrary("kindle_wasm_runtime") }
        private const val ASSET = "app/calibre-runtime.zip"
    }

    private fun wasmPath(root: File) = File(root, "wasi/python.wasm").absolutePath
    // Writable, persistent path: the native side deserializes it if present, else
    // compiles the module and writes it here so later launches start fast.
    private fun cwasmPath(root: File) = File(root, "wasi/python-aarch64-android.cwasm").absolutePath

    /**
     * Compile (or load the on-disk) module ahead of any conversion. Heavy on first
     * ever launch (~4s compile + serialize); fast afterwards (deserialize). Call off
     * the main thread at app start so conversions never wait on the compile.
     */
    fun prewarm(progress: (String) -> Unit = {}): Boolean {
        val root = prepareRuntime(progress)
        return runCatching { nativePrewarm(wasmPath(root), cwasmPath(root), true) }.getOrDefault(false)
    }

    fun prepareRuntime(progress: (String) -> Unit = {}): File {
        val root = File(context.filesDir, "wasmtime-runtime")
        val marker = File(root, ".ready")
        val assetStamp = context.assets.open(ASSET).use { it.available().toString() }
        if (marker.exists() && marker.readText() == assetStamp && File(root, "wasi/python.wasm").exists()) return root
        progress("Unpacking shared exnref WASI runtime from APK asset...")
        root.deleteRecursively(); root.mkdirs()
        ZipInputStream(context.assets.open(ASSET)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = File(root, entry.name).also { it.parentFile?.mkdirs() }
                out.outputStream().use { zip.copyTo(it) }
            }
        }
        marker.writeText(assetStamp)
        return root
    }

    fun runPython(code: String, workDir: File, preferPrecompiled: Boolean = true, onLine: (String) -> Unit = {}): Result {
        val root = prepareRuntime()
        workDir.mkdirs(); File(workDir, ".config").mkdirs(); File(workDir, "tmp").mkdirs()
        File(workDir, "probe.py").writeText(code)
        lineListener = onLine
        val json = try {
            nativeRunPython(
                wasmPath(root),
                cwasmPath(root),
                root.absolutePath,
                workDir.absolutePath,
                preferPrecompiled,
            )
        } finally {
            lineListener = null
        }
        val o = JSONObject(json)
        return Result(
            exitCode = o.getInt("exitCode"),
            compileMs = o.getLong("compileMs"),
            runMs = o.getLong("runMs"),
            usedPrecompiled = o.getBoolean("usedPrecompiled"),
            stdout = o.getString("stdout"),
            stderr = o.getString("stderr"),
            error = o.getString("error"),
        )
    }

    fun convert(input: File, output: File, workDir: File, profile: String = "kindle_oasis", onLine: (String) -> Unit = {}): Result {
        input.copyTo(File(workDir, input.name), overwrite = true)
        val script = """
            import os, json
            os.chdir('/')
            import browser_convert
            print('native Wasmtime imported browser_convert', flush=True)
            info = browser_convert.convert_file('/work/${input.name}', '/work/${output.name}', {'output_profile':'$profile','base_font_size':0,'margin_left':5,'margin_right':5,'margin_top':5,'margin_bottom':5,'dont_compress':False,'no_inline_toc':False})
            print('native Wasmtime converted ' + json.dumps(info, sort_keys=True), flush=True)
        """.trimIndent()
        val r = runPython(script, workDir, onLine = onLine)
        val generated = File(workDir, output.name)
        if (r.ok && generated.exists()) generated.copyTo(output, overwrite = true)
        return r
    }
}
