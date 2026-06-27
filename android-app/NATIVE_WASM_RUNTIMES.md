# Native Android WebAssembly Runtime Spike

Purpose: evaluate replacing the Android WebView/Pyodide path and the pure-JVM Chicory interpreter with an Android NDK/JNI-embedded WebAssembly runtime.

The runtime target is the existing static WASI CPython/calibre artifact. The currently viable artifact for modern hosts is the Binaryen-translated exnref build at `/tmp/python-exnref.wasm`; the original legacy-EH `python.wasm` remains browser/V8-compatible but is rejected by Chicory 1.7.5.

## Host-side probe results

All timings below were run on this VM against `/tmp/python-exnref.wasm` with the same preopens/PYTHONPATH shape as the Chicory probe.

| Runtime | Result | Minimal conversion time | Notes |
| --- | --- | ---: | --- |
| Chicory 1.7.5 JVM | Pass | ~216s | Pure Kotlin/JVM path, but too slow for the app UX. |
| WasmEdge 0.17.0 interpreter | Pass | ~73s | Android aarch64 release exists and is small. AOT/JIT currently falls back because Exception Handling is not supported by WasmEdge AOT/JIT for this artifact. |
| Wasmtime 46.0.1 | Pass | ~1.2s after first compile/cache | Requires `-W exceptions=y`. Android aarch64 C API release exists. First trivial run paid a compile/cache cost (~16s); subsequent import/conversion runs were fast. |
| Wasmer 7.1.0 | Fail | n/a | CLI reported no backend supports the required feature set even with exceptions/reference/all proposals enabled. |
| WAMR/iwasm 2.4.4 gc-eh | Fail | n/a | `unsupported opcode 1f` on the exnref artifact. Release notes show legacy EH support but not current EH. |
| Wasm3 0.5.0 | Fail | n/a | Rejected the module before execution (`out of order Wasm section`); not a fit for this modern CPython/WASI artifact. |

## Android packaging observations

Downloaded release assets for the promising Android runtimes:

- Wasmtime `wasmtime-v46.0.1-aarch64-android-c-api.tar.xz`
  - `lib/libwasmtime.so`: ~24.8 MiB
  - `min/lib/libwasmtime.so`: ~1.2 MiB, but the min API is probably too small for our WASI embedding needs until proven otherwise.
  - Includes C headers for `wasmtime`, `wasm`, and `wasi` APIs.
- WasmEdge `WasmEdge-0.17.0-android_aarch64.tar.gz`
  - `libwasmedge.so`: ~3.1 MiB
  - Includes C headers.

## Interpretation

The user's proposed architecture is practical and should now become the primary Android runtime direction:

Kotlin app -> JNI/NDK bridge -> native runtime shared library -> WASI CPython/calibre wasm.

Wasmtime is the leading candidate because it executes the exnref/EH artifact dramatically faster on the host probe and publishes an Android aarch64 C API package. WasmEdge remains a useful fallback because its Android package is much smaller and it runs the artifact correctly, but its current interpreter-only EH path is still much slower than Wasmtime.

## Recommended next implementation step

Implemented direction: make Android NDK Wasmtime the preferred path, not just a spike. The app now packages Wasmtime through JNI and exposes a native dashboard/probe while the WebView UI remains a fallback/debug path. Implementation checklist:

1. Add a Gradle/CMake build path that references the Wasmtime Android C API package. The current repo includes the aarch64 JNI packaging path; future cleanup can download the large binary via Gradle instead of committing it.
2. Add `libkindle_wasm_runtime.so` JNI glue exposing a small Kotlin API:
   - `runPython(wasmPath, runtimeRoot, thirdPartySite, repoRoot, workDir, scriptText): RuntimeResult`
3. In native code:
   - enable the WebAssembly exceptions proposal in Wasmtime config;
   - enable WASI preview1;
   - preopen `/`, `/third_party_site`, `/repo`, and `/work`;
   - pass the same `PYTHONPATH`, `HOME`, `XDG_CONFIG_HOME`, and `PYTHONDONTWRITEBYTECODE` values used by the JVM probes.
4. Run probe sequence on device/emulator: print, `import browser_convert`, `minimal.epub` conversion.
5. Only after this succeeds, wire the native Kotlin library queue around the runtime.

Keep Chicory as a pure-JVM reference/probe path, but do not build the production Android conversion queue on it unless native runtime packaging fails.


## Shared artifact policy

Both web and Android must consume the same generated exnref `wasi/python.wasm`. Use:

```bash
python3 scripts/build_runtime_artifacts.py --android-precompile
```

This translates the legacy-EH CPython artifact with Binaryen `--translate-to-exnref`, writes the same `wasi/python.wasm` into `web/calibre-runtime.zip`, `consumer-app/src/assets/calibre-runtime.zip`, and `android-app/app/src/main/assets/app/calibre-runtime.zip`, and optionally adds Android-only `wasi/python-aarch64-android.cwasm` for ahead-of-time Wasmtime deserialization.

The `.cwasm` file is target-specific (`aarch64-linux-android`) and is an acceleration artifact, not a semantic source of truth. The source of truth remains the shared exnref wasm plus Python/calibre files in the zip.
