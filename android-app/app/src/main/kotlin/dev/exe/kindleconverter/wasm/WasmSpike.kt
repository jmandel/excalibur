package dev.exe.kindleconverter.wasm

import android.content.Context
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import java.io.File

object WasmSpike {
    fun run(context: Context): String {
        val wasm = File(context.cacheDir, "wasm-spike/add.wasm").also { it.parentFile?.mkdirs() }
        context.assets.open("wasm-spike/add.wasm").use { input -> wasm.outputStream().use { input.copyTo(it) } }
        val started = System.nanoTime()
        val module = Parser.parse(wasm)
        val instance = Instance.builder(module).build()
        val result = instance.export("add").apply(20, 22)[0]
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        return "Chicory native WASM OK\nadd(20, 22) = $result\nmodule = ${wasm.length()} bytes\ntime = %.2f ms\nengine = com.dylibso.chicory runtime".format(elapsedMs)
    }
}
