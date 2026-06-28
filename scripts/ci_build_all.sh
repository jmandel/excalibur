#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

: "${WASI_SDK_PATH:=$HOME/.local/share/wasmpy-build/wasi-sdk}"
export BINARYEN_WASM_OPT="${BINARYEN_WASM_OPT:-$ROOT/.toolchain/binaryen/bin/wasm-opt}"
export WASMTIME="${WASMTIME:-$ROOT/.toolchain/wasmtime/wasmtime}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [[ ! -f third_party/calibre/src/calibre/__init__.py ]]; then
  git submodule update --init --recursive third_party/calibre
fi

echo "python: $(python3 --version)"
echo "node: $(node --version) at $(command -v node)"
echo "bun: $(bun --version) at $(command -v bun)"

python3 scripts/static_wasi/build_wasi_libs.py
python3 scripts/static_wasi/build_static_wasi_python.py
# Do NOT embed the ~62MB aarch64 .cwasm in the APK: the Android app pre-warms on
# first launch and serializes its own .cwasm to disk, so shipping one is redundant
# bloat. (Generating it on device also speeds up CI.)
python3 scripts/build_runtime_artifacts.py
python3 scripts/static_wasi/probe_static_wasi_conversion.py

(
  cd consumer-app
  bun install --frozen-lockfile
  bun build src/wasiPythonWorker.ts --outfile src/assets/wasiPythonWorker.bundle.txt --target browser --format esm
  bun run typecheck
  bun build index.html --outdir dist --target browser
)

# Release build: R8-minified and signed with a stable key (from KEYSTORE_* env when
# present, else the debug key as a fallback). Smaller, non-debuggable, updatable.
./android-app/gradlew -p android-app assembleRelease

mkdir -p public/downloads
cp -a consumer-app/dist/. public/
cp android-app/app/build/outputs/apk/release/app-release.apk public/downloads/excalibur.apk
