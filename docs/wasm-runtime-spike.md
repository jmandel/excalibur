# Android standalone WASM runtime spike

Goal: test the feasibility of running WASM inside the Android/Kotlin process without WebView/V8.

## What was spiked

Added Chicory, a pure-JVM WebAssembly runtime, to the Android app:

```kotlin
implementation("com.dylibso.chicory:runtime:1.7.5")
implementation("com.dylibso.chicory:wasi:1.7.5")
```

Bundled a tiny `add.wasm` under `app/src/main/assets/wasm-spike/add.wasm` and exposed a Kotlin bridge method:

```kotlin
AndroidKindle.runWasmSpike()
```

The app has a debug card that runs the module outside WebView and returns:

```text
Chicory native WASM OK
add(20, 22) = 42
```

This proves packaged `.wasm` can be loaded from APK assets and executed in-process by Kotlin/JVM.

## What this means for Pyodide/calibre

This is feasible for **WASI-style WASM modules** and for custom converter modules we control.

It does **not** mean existing Pyodide can simply be moved out of WebView:

- Pyodide is an Emscripten/browser-oriented distribution.
- `pyodide.asm.wasm` imports JS/Emscripten host functions, not just WASI.
- Pyodide package loading expects JS glue, fetch-like loading, and Emscripten filesystem behavior.
- Chicory can run core WASM/WASI, but it is not a drop-in JavaScript host for Pyodide's browser glue.

## Plausible paths

1. Keep WebView Pyodide for calibre while we polish Android UX.
2. Use Chicory for small, self-contained WASI tools if needed.
3. For a no-WebView converter, build or source a WASI Python distribution, then port the reduced calibre runtime into that filesystem.
4. If WASI Python cannot support required native wheels/extensions (`lxml`, Pillow, regex, msgpack, lzma), consider native CPython on Android instead.

## Next spike if pursuing Option D seriously

- Inspect `pyodide.asm.wasm` imports and classify Emscripten/JS dependencies.
- Try running a WASI CPython module under Chicory.
- Mount a zip/filesystem with pure-Python calibre subset.
- Test imports first, then EPUB input parsing, then AZW3 writer.
- Compare startup and conversion memory against WebView Pyodide.
