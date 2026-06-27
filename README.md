# Excalibur Kindle Converter

Excalibur is a local-first Kindle library/conversion project. It builds one static WASI CPython + calibre runtime and uses it in two hosts:

- **Static web app**: browser WebAssembly/WASI worker, no server-side conversion.
- **Android app**: native Kotlin app using JNI/NDK + Wasmtime, with the same `wasi/python.wasm` and an optional Android precompiled `.cwasm` acceleration artifact.

Pyodide is no longer part of the product path. The remaining Python runtime is the repository-built static WASI CPython artifact.

## Runtime artifact pipeline

The source of truth is the static WASI CPython/calibre build under `experiments/static-wasi-python` plus `third_party/calibre`.

```bash
python3 scripts/static_wasi/build_wasi_libs.py
python3 scripts/static_wasi/build_static_wasi_python.py
python3 scripts/build_runtime_artifacts.py --android-precompile
```

`build_runtime_artifacts.py` translates the legacy-EH CPython wasm to exnref with Binaryen and writes identical `wasi/python.wasm` files into:

- `web/calibre-runtime.zip`
- `consumer-app/src/assets/calibre-runtime.zip`
- `android-app/app/src/main/assets/app/calibre-runtime.zip`

For Android it can also embed target-specific `wasi/python-aarch64-android.cwasm`, produced by Wasmtime for `aarch64-linux-android`. That file is a cache/acceleration artifact; it is not the semantic source of truth.

## Build web app

```bash
cd consumer-app
bun install
bun build index.html --outdir dist --target browser
```

## Build Android app

```bash
ANDROID_HOME=/path/to/android-sdk ANDROID_SDK_ROOT=/path/to/android-sdk \
  ./android-app/gradlew -p android-app assembleDebug
```

The Android launcher opens the native Wasmtime dashboard. It converts a bundled EPUB to AZW3 and serves it through the local Kindle HTTP server.

## CI/release

`.github/workflows/build.yml` is intended to build from scratch: checkout submodules, install toolchains, build WASI CPython/calibre, generate shared runtime artifacts, build the web app, build the Android APK, publish the web app to GitHub Pages, and upload/copy the APK.
