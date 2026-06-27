# Android Native Kindle Library Plan

Goal: replace the Android WebView/Pyodide converter shell with a native Kotlin library manager that uses the static WASI CPython/calibre runtime, auto-converts imported books, and serves converted AZW3 files to Kindle over the local network while the app is running.

## Proven baseline

- Static WASI CPython + Pillow/lxml/msgpack/regex/calibre conversion works in the repo's Node/V8 WASI harness.
- Browser/Bun worker integration works after optimizing the WASI Python build for V8 host stack.
- Android app currently has a WebView-hosted prototype, a small HTTP server, IP discovery, and a Chicory `add.wasm` smoke test.

## Milestone 1: Android WASI host proof

Purpose: determine whether Chicory can run the current optimized `python.wasm` artifact.

Steps:
1. Package current `python.wasm` plus enough runtime files as Android assets.
2. Add a native Kotlin probe that uses Chicory WASI, not WebView/Pyodide.
3. Probe sequence:
   - instantiate `python.wasm`
   - run `print('hello')`
   - run `import browser_convert`
   - convert `fixtures/generated/minimal.epub`
   - convert all generated fixtures and bundled Lewis Carroll samples
4. Record exact failure if Chicory rejects wasm exception/SJLJ opcodes or misses WASI behavior.

Success criteria:
- Native Kotlin/Chicory probe produces valid AZW3/KF8 output for the same fixtures as the CLI harness.

Fallback decision if blocked:
- Try newer Chicory/runtime options if available.
- If wasm exceptions/SJLJ are unsupported, decide between alternate wasm host, alternate CPython build, or temporary Android WebView worker fallback.

## Milestone 2: Native library model

After runtime proof, build the native app around it.

Data model:
- `BookRecord(id, title, author, tags, originalPath, originalName, ext, size, status, azw3Path, options, error, timestamps)`
- Status: imported, ready, queued, converting, converted, needs_reconversion, error.

Storage:
- `files/originals/<book-id>/...`
- `files/converted/<book-id>/...azw3`
- `files/runtime/...` for unpacked runtime if needed.

Persistence:
- Start with SQLite/Room or a small JSON/SQLite repository if faster.

## Milestone 3: Imports and auto-conversion

Inputs:
- Android document picker.
- Android `ACTION_VIEW` / send/open-into-app intents.
- Configured source folder/document tree scan.

Behavior:
- Copy every import into app-private originals storage.
- If Kindle settings are confirmed, enqueue automatically.
- On settings/profile change, mark converted books `needs_reconversion` and enqueue.
- Default conversion parallelism: 1. Add an internal setting later only after memory testing.

## Milestone 4: Local Kindle server

Server behavior:
- Runs only while app is running unless user opts in later.
- Serves plain Kindle-compatible HTML with latest converted AZW3 first.
- Routes:
  - `/` Kindle download index
  - `/catalog.json`
  - `/download/<book-id>`

Network UX:
- Show all candidate IPv4 URLs.
- Best-effort mDNS/Bonjour advertisement if Android network permits.
- Numeric IP remains primary because Kindle/hotspot name resolution is unreliable.

## Milestone 5: Native UI

Screens:
- Library list with status and actions.
- Kindle settings/profile screen.
- Imports/source-folder screen.
- Server screen with URLs and status.
- Runtime probe/debug screen until Milestone 1 is complete.

## Current next task

Milestone 1 has proven that the exnref-translated static WASI CPython/calibre artifact can run under Chicory, but Chicory is too slow for a good Android UX. Shift the runtime proof to a native NDK/JNI embedding spike, with Wasmtime as the first candidate and WasmEdge as fallback. See `NATIVE_WASM_RUNTIMES.md` for host-side runtime probe results and the recommended JNI plan.
