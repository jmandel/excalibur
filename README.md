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

On Linux x86_64, the easiest local path builds the WASI runtime artifacts from source and then builds the browser app:

```bash
scripts/build_web_app.sh
```

Host tools expected on `PATH`:

- `python3`, `curl`, `tar`, `cmake`, `make`, `pkg-config`
- `autoconf`, `automake`, `libtool`
- `unzip`, `zip`
- `bun`, `node`, `git`

On Ubuntu, the non-JS tools are:

```bash
sudo apt-get install -y build-essential cmake ninja-build pkg-config autoconf automake libtool curl python3 python3-venv unzip zip git
```

The script installs/uses repository-local toolchains where possible:

- WASI SDK `33.0` under `$HOME/.local/share/wasmpy-build/wasi-sdk`
- Binaryen `130` under `.toolchain/binaryen`
- Wasmtime `46.0.1` under `.toolchain/wasmtime`

It also initializes `third_party/calibre` if the submodule has not been checked out.

To also include the Android precompiled Wasmtime artifact in the Android runtime zip:

```bash
scripts/build_web_app.sh --android-precompile
```

If the runtime artifacts already exist and you only need to rebuild the frontend:

```bash
cd consumer-app
bun install
bun build index.html --outdir dist --target browser
```

The build script runs the same runtime probe as CI: generated smoke fixtures plus the larger bundled sample EPUBs. To run only the generated fixtures while iterating locally:

```bash
python3 scripts/static_wasi/probe_static_wasi_conversion.py --generated-only
```

## Build Android app

CI installs the Android SDK automatically. For a local Android build, install/configure:

- Android platform `android-35`
- Android build-tools `35.0.0`
- Android NDK `27.2.12479018`
- Android CMake `3.22.1`
- Java 21

The app currently targets `arm64-v8a` / `aarch64-linux-android` for the precompiled Wasmtime artifact.

```bash
ANDROID_HOME=/path/to/android-sdk ANDROID_SDK_ROOT=/path/to/android-sdk \
  ./android-app/gradlew -p android-app assembleDebug
```

The Android launcher opens the native Wasmtime dashboard. It converts a bundled EPUB to AZW3 and serves it through the local Kindle HTTP server.

## CI/release

`.github/workflows/build.yml` is intended to build from scratch: checkout submodules, install toolchains, build WASI CPython/calibre, generate shared runtime artifacts, build the web app, build the Android APK, publish the web app to GitHub Pages, and upload/copy the APK.
