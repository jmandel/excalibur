#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

WASI_SDK_VERSION="${WASI_SDK_VERSION:-33.0}"
BINARYEN_VERSION="${BINARYEN_VERSION:-130}"
WASMTIME_VERSION="${WASMTIME_VERSION:-46.0.1}"
WASI_SDK_PATH="${WASI_SDK_PATH:-$HOME/.local/share/wasmpy-build/wasi-sdk}"
BINARYEN_WASM_OPT="${BINARYEN_WASM_OPT:-$ROOT/.toolchain/binaryen/bin/wasm-opt}"
WASMTIME="${WASMTIME:-$ROOT/.toolchain/wasmtime/wasmtime}"

export WASI_SDK_PATH BINARYEN_WASM_OPT WASMTIME

ANDROID_PRECOMPILE=0

usage() {
  cat <<'USAGE'
Usage: scripts/build_web_app.sh [--android-precompile]

Build the WASI CPython/calibre runtime artifacts from source, then build the
browser app at consumer-app/dist.

Options:
  --android-precompile  Also add wasi/python-aarch64-android.cwasm to the
                        Android runtime zip.
USAGE
}

while (($#)); do
  case "$1" in
    --android-precompile|--android)
      ANDROID_PRECOMPILE=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

missing=()
for cmd in python3 curl tar cmake make pkg-config autoconf automake libtool unzip zip bun node; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    missing+=("$cmd")
  fi
done
if ((${#missing[@]})); then
  printf 'Missing required host tools: %s\n' "${missing[*]}" >&2
  printf 'On Ubuntu, install: build-essential cmake ninja-build pkg-config autoconf automake libtool curl python3 python3-venv unzip zip\n' >&2
  exit 1
fi

ensure_calibre_submodule() {
  if [[ -f third_party/calibre/src/calibre/__init__.py ]]; then
    return
  fi
  if ! command -v git >/dev/null 2>&1; then
    printf 'Missing calibre submodule and git is not available.\n' >&2
    printf 'Run: git submodule update --init --recursive third_party/calibre\n' >&2
    exit 1
  fi
  echo "Initializing calibre submodule"
  git submodule update --init --recursive third_party/calibre
  if [[ ! -f third_party/calibre/src/calibre/__init__.py ]]; then
    printf 'calibre submodule did not provide third_party/calibre/src/calibre/__init__.py\n' >&2
    exit 1
  fi
}

download() {
  local url=$1
  local out=$2
  mkdir -p "$(dirname "$out")"
  curl --fail --location --retry 5 --retry-all-errors --connect-timeout 30 -o "$out" "$url"
}

install_wasi_sdk() {
  if [[ -x "$WASI_SDK_PATH/bin/clang" ]]; then
    return
  fi
  local tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  echo "Installing WASI SDK $WASI_SDK_VERSION"
  download "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-33/wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux.tar.gz" "$tmp/wasi-sdk.tar.gz"
  tar -C "$tmp" -xzf "$tmp/wasi-sdk.tar.gz"
  mkdir -p "$(dirname "$WASI_SDK_PATH")"
  rm -rf "$WASI_SDK_PATH"
  mv "$tmp/wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux" "$WASI_SDK_PATH"
  trap - RETURN
  rm -rf "$tmp"
}

install_binaryen() {
  if [[ -x "$BINARYEN_WASM_OPT" ]] && "$BINARYEN_WASM_OPT" --help 2>&1 | grep -q -- '--translate-to-exnref'; then
    return
  fi
  local tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  echo "Installing Binaryen $BINARYEN_VERSION"
  download "https://github.com/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}/binaryen-version_${BINARYEN_VERSION}-x86_64-linux.tar.gz" "$tmp/binaryen.tar.gz"
  rm -rf "$ROOT/.toolchain/binaryen"
  mkdir -p "$ROOT/.toolchain/binaryen"
  tar -C "$ROOT/.toolchain/binaryen" --strip-components=1 -xzf "$tmp/binaryen.tar.gz"
  trap - RETURN
  rm -rf "$tmp"
}

install_wasmtime() {
  if [[ -x "$WASMTIME" ]]; then
    return
  fi
  local tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  echo "Installing Wasmtime $WASMTIME_VERSION"
  download "https://github.com/bytecodealliance/wasmtime/releases/download/v${WASMTIME_VERSION}/wasmtime-v${WASMTIME_VERSION}-x86_64-linux.tar.xz" "$tmp/wasmtime.tar.xz"
  rm -rf "$ROOT/.toolchain/wasmtime"
  mkdir -p "$ROOT/.toolchain/wasmtime"
  tar -C "$ROOT/.toolchain/wasmtime" --strip-components=1 -xf "$tmp/wasmtime.tar.xz"
  trap - RETURN
  rm -rf "$tmp"
}

install_wasi_sdk
install_binaryen
install_wasmtime
ensure_calibre_submodule

echo "Building WASI third-party libraries"
python3 scripts/static_wasi/build_wasi_libs.py

echo "Building static WASI CPython/calibre runtime"
python3 scripts/static_wasi/build_static_wasi_python.py

echo "Generating runtime zips"
runtime_args=()
if ((ANDROID_PRECOMPILE)); then
  runtime_args+=(--android-precompile)
fi
python3 scripts/build_runtime_artifacts.py "${runtime_args[@]}"

echo "Probing generated runtime"
python3 scripts/static_wasi/probe_static_wasi_conversion.py

echo "Building web app"
(
  cd consumer-app
  bun install --frozen-lockfile
  bun build src/wasiPythonWorker.ts --outfile src/assets/wasiPythonWorker.bundle.txt --target browser --format esm
  bun run typecheck
  rm -rf dist
  bun build index.html --outdir dist --target browser
)

echo "Web app built at consumer-app/dist"
