# Journal

## 2026-06-27

- Created new git repo: `/home/exedev/kindle-wasm-converter`.
- Added calibre as submodule at `third_party/calibre` from `https://github.com/kovidgoyal/calibre.git`.
- Started `plan.md` with the Pyodide/reduced-calibre strategy.
- Started this chronological journal.
- Launched subagent `sample-fixtures` to find legally usable test EPUB/MOBI/AZW3 materials and fixture categories.

Initial calibre source observations from prior reconnaissance:

- Current calibre submodule HEAD is expected near `7f4ea78` from 2026-06-27.
- `AZW3Output.convert()` delegates to `create_kf8_book()` in `mobi/writer8/main.py`.
- The KF8 writer is compact enough to port later, but Pyodide reuse is the fastest proof-of-life.
- Likely shims needed: `calibre_extensions.icu`, `speedup`, `cPalmdoc`, `imageops`, `translator`.
- Start with EPUB input. MOBI reader adds more surface area but is mostly Python plus PalmDOC/HuffCDIC decompression.

### Native import-probe setup

- Created `scripts/make_minimal_epubs.py` and generated three synthetic EPUB fixtures:
  - `fixtures/generated/minimal.epub`
  - `fixtures/generated/css-image.epub`
  - `fixtures/generated/svg.epub`
- Created local `.venv` for native experiments. System Python lacked `lxml`, `css_parser`, `PIL`, `html5_parser`, and `polyglot`.
- Installed enough native packages for import probing except `html5-parser`, which failed because `pkg-config` is missing. This is not yet blocking the core EPUB->AZW3 path.
- Added `experiments/import_probe.py`, which prepends `third_party/calibre/src` and installs runtime stubs for `calibre_extensions`.
- First import-probe findings:
  - calibre source assumes frozen-app globals `sys.extensions_location` and `sys.resources_location`; the probe now supplies these.
  - `sys.resources_location` must point at top-level `third_party/calibre/resources`, not `src/calibre/resources`.
  - translator shim needs `gettext`, plural APIs, and `install()`.
  - ICU shim needs case constants and multiple functions; currently still failing on additional ICU API surface (`icu.chr` next).
- This reinforces the plan: Pyodide reuse is feasible-looking but will require a deliberate shim layer, not ad-hoc imports.

### Import probe reached green

- Expanded `experiments/import_probe.py` shims until the key modules import successfully under native Python 3.12 + venv dependencies.
- Green imports now include:
  - `calibre.ebooks.conversion.plugins.epub_input`
  - `calibre.ebooks.oeb.base`
  - `calibre.ebooks.oeb.reader`
  - `calibre.ebooks.conversion.plugins.mobi_output`
  - `calibre.ebooks.mobi.writer2.resources`
  - `calibre.ebooks.mobi.writer8.main`
  - `calibre.ebooks.mobi.writer8.mobi`
- Additional shim discoveries:
  - calibre's source-tree runtime expects a top-level `compression` package; aliasing stdlib `zlib`, `bz2`, `gzip`, `lzma` is enough for this import set.
  - calibre's AI plugin type hints can fail under the local Python 3.12 probe (`ChatMessage` / `ChatResponse`); stubbing `calibre.ai` and builtins placeholders avoids pulling that unrelated surface.
  - Qt image stack can be avoided at import time by stubbing `calibre.utils.img`; initial AZW3 path uses `process_images=False`, so this is acceptable for the first spike.
  - The OEB and AZW3 writer modules themselves did not require Qt once image helpers were stubbed.

This is a strong signal that a reduced-calibre Pyodide package is plausible: the first hurdle is a clean shim/runtime bootstrap, not a deep rewrite.

### End-to-end native pipeline reached

- Added reusable experiment bootstrap: `experiments/calibre_bootstrap.py`.
- Added `experiments/convert_with_plumber.py`, which invokes calibre's real `Plumber` conversion path with default option recommendations.
- To avoid importing the full plugin registry, bootstrap now provides a narrow `calibre.customize.ui` registry for:
  - EPUB/K EPUB input
  - MOBI/AZW/AZW3-family input
  - AZW3 output
  - MOBI output experimentally, though legacy MOBI output currently drags Qt rasterization
  - default input/output profiles
  - no-op preprocess/postprocess plugins
- Added `experiments/inspect_azw3.py` for PalmDB/MOBI/KF8 sanity checks.
- Added `experiments/run_fixture_conversions.py` regression harness.

Successful conversions using calibre's default Plumber transform path:

- `fixtures/generated/minimal.epub` -> `experiments/out/minimal.azw3`
- `fixtures/generated/css-image.epub` -> `experiments/out/css-image.azw3`
- `fixtures/generated/svg.epub` -> `experiments/out/svg.azw3`
- generated AZW3 inputs round-trip through `MOBIInput` -> `AZW3Output`:
  - `minimal.azw3` -> `minimal-roundtrip.azw3`
  - `css-image.azw3` -> `css-image-roundtrip.azw3`
  - `svg.azw3` -> `svg-roundtrip.azw3`

Key shims/fallbacks added during execution:

- `calibre_extensions.fast_html_entities.replace_all_entities`
- pure-Python PalmDOC decompressor in the `cPalmdoc` shim
- no-subprocess `calibre.utils.safe_atexit` shim to avoid `calibre-parallel`
- localization fallbacks because source checkout lacks built `resources/localization/*.calibre_msgpack`
- empty metadata plugin registry for jacket/identifier transforms

Native dependency finding:

- `html5_parser` and `lxml` must be built against the same `libxml2`; the venv required rebuilding `lxml` with `--no-binary lxml` after installing system `libxml2-dev`/`libxslt1-dev`.

Current limitation:

- True legacy `.mobi` output fixture generation failed because MOBI output's MOBI6 path imports Qt SVG rasterization. This is not on the target output path, but we still need a legal legacy `.mobi` input fixture or a better MOBI-output workaround if we want old-MOBI input coverage.

### Pyodide/WASM end-to-end reached

- Installed Node LTS via documented `nodeenv` path and added `pyodide` npm dependency.
- Added `experiments/pyodide_probe.mjs` to check Pyodide runtime/package availability.
- Pyodide version: `0.28.3`, Python `3.13.2` on Emscripten.
- Pyodide package findings:
  - Available via `loadPackage`: `lxml`, `Pillow`, `python-dateutil`, `regex`, `beautifulsoup4`, `html5lib`, `webencodings`, `msgpack`, `tzdata`, `lzma`.
  - Installable via `micropip`: `css-parser`, `chardet`, `tzlocal`.
  - Not available directly: `html5-parser`; the runner currently stubs it with an `lxml.html` fallback sufficient for our fixtures.
- Added `experiments/pyodide_calibre_run.mjs`.
- The Pyodide runner mounts the repo via NODEFS, installs packages, injects the `html5_parser` fallback, imports the same `calibre_bootstrap`, and runs the same `convert_with_plumber`/`inspect_azw3` flow.

Successful WASM conversions:

- `minimal.epub` -> `minimal.azw3`
- `css-image.epub` -> `css-image.azw3`
- `svg.epub` -> `svg.azw3`
- generated AZW3 round-trips through `MOBIInput` -> `AZW3Output`:
  - `minimal.azw3` -> `minimal-roundtrip.azw3`
  - `css-image.azw3` -> `css-image-roundtrip.azw3`
  - `svg.azw3` -> `svg-roundtrip.azw3`

This completes the initial proof that technique (A) works: a reduced calibre EPUB/AZW3-to-AZW3 pipeline can run end-to-end in WebAssembly through Pyodide, with default calibre Plumber transforms, for the sample fixture corpus.

Important caveats:

- The `html5_parser` fallback is not full fidelity; for production we need either a Pyodide build of `html5-parser`, a robust replacement, or an EPUB3 nav parser that avoids that dependency.
- This has not yet tested legacy MOBI6 input from a real `.mobi` file.
- Outputs are structurally valid KF8/AZW3 by sanity checks, but not yet compared byte/structure-by-structure against a packaged `ebook-convert` binary.

### Expanded MOBI and fixture coverage

- Added a Qt-free `calibre.ebooks.oeb.transforms.rasterize` shim that raises `Unavailable`, matching the code path MOBI output already handles. This lets the experiment generate legacy MOBI6 fixtures without installing Qt.
- Native pipeline now generates legacy `.mobi` fixtures from every generated EPUB and converts them back to AZW3 through `MOBIInput`.
- Pyodide pipeline does the same: EPUB -> MOBI fixture -> MOBIInput -> AZW3.
- Added more generated fixtures:
  - `epub2-ncx.epub`: EPUB2/NCX-only, no EPUB3 nav.
  - `rtl.epub`: Arabic/RTL metadata and RTL page progression.
- Added structural tools:
  - `experiments/azw3_summary.py`
  - `experiments/compare_summaries.py`
- Native-vs-Pyodide summary comparison mostly matches. Some round-trip outputs differ by a blank/padding record (`''` record prefix) and therefore record count / `first_non_text_record`; core KF8 structure remains valid. This should be normalized in future comparison logic.

### Conversion option/profile input support

- User pointed out calibre's many conversion settings/device profiles must be available as WASM inputs.
- Fixed reduced `calibre.customize.ui` registry to expose all calibre input/output profiles from `calibre.customize.profiles`, not just `default`.
- Verified Kindle output profiles are available:
  - `kindle`, `kindle_dx`, `kindle_fire`, `kindle_oasis`, `kindle_pw`, `kindle_pw3`, `kindle_scribe`, `kindle_voyage`.
- Added `experiments/list_conversion_options.py` to inventory all input/pipeline/output options for a conversion pair.
- Added `experiments/list_profiles.py` and generated `experiments/profile_inventory.json` locally for inspection.
- Added `experiments/check_profiles.py` as a regression check for Kindle profiles.
- Extended `experiments/convert_with_plumber.py` with `--options-json` / `--options-file` support and an `options` dict parameter, mapped to `Plumber.merge_ui_recommendations()` with `OptionRecommendation.HIGH`.
- Added native and Pyodide option smoke tests using:
  - `output_profile=kindle_pw3`
  - `margin_left=0`
  - `margin_right=0`
  - `base_font_size=14`
  - `dont_compress=true`

### Android native app plan resumed

- Added `android-app/PLAN.md` to track the native Kotlin Android direction:
  - prove the optimized static WASI CPython/calibre runtime under Android/Chicory first;
  - then build native library/import/conversion/server UI around that proof;
  - retain numeric IP URLs as primary and mDNS/Bonjour as best-effort for Kindle/tethering cases.
- Current Android app state observed:
  - still WebView-hosted for the main app;
  - includes a simple HTTP server and IP URL discovery;
  - includes Chicory runtime/WASI dependencies;
  - includes a tiny `add.wasm` `WasmSpike` smoke test.
- Next step: replace/extend `WasmSpike` with a real `python.wasm` startup/import/conversion probe and journal the result.

### Android Chicory parse probe

- Ran a JVM-side Chicory 1.7.5 parse probe against `experiments/static-wasi-python/Python-3.12.0/builddir/wasi/python.wasm` before wiring Android UI.
- Result: Chicory parser fails before instantiation:
  - `MalformedException: illegal opcode, op value 06`
- Interpretation: opcode `0x06` is `try` in the legacy WebAssembly exception handling proposal used by the WASI SDK SJLJ/setjmp path. Chicory documentation says exception handling exists in recent releases, but Chicory 1.7.5 still rejects this artifact's exception encoding in our local probe.
- Next step: investigate whether a newer/configured Chicory parser/runtime can accept this exception encoding; if not, build/test a no-wasm-exception CPython variant or choose another Android WASM host/fallback path.

### Official EH flag attempt

- Tried rebuilding static WASI CPython with `-mllvm -wasm-use-legacy-eh=false` in addition to `-mllvm -wasm-enable-sjlj`.
- Build completed after a second explicit `make -j1 libpython3.12.a python.wasm` due to the same custom static MODOBJS first-link ordering issue.
- Chicory parse result was unchanged: `illegal opcode, op value 06`.
- Conclusion: the WASI SDK SJLJ path still emits the legacy exception `try` opcode that Chicory 1.7.5 rejects, even with the official-EH flag.
- Next experiment: attempt a no-SJLJ/no-wasm-EH CPython build and see whether it compiles/runs enough of our conversion path.

### WebAssembly EH research for Android

- Researched WASI SDK setjmp/longjmp support. WASI SDK documents `-mllvm -wasm-enable-sjlj -lsetjmp` and says current/default output is legacy "phase3" exception handling.
- Confirmed locally that our clang accepts `-mllvm -wasm-use-legacy-eh=false`, but the resulting CPython artifact still contains legacy opcode `0x06` and Chicory rejects it.
- Installed Ubuntu `binaryen` package; version 108 is too old and lacks `--translate-to-exnref`.
- Downloaded upstream Binaryen version_130; it supports `wasm-opt --translate-to-exnref`, the documented post-link translation from old phase-3 EH to new exnref EH. Exact CPython artifact translation still needs to be run after restoring a normal legacy `python.wasm` build.
- Tried experimental `--no-sjlj` build. It is not a drop-in option: WASI SDK's `setjmp.h` fails compilation without `-mllvm -wasm-enable-sjlj` when Pillow/JPEG code includes setjmp.
- Current interpretation: nonlegacy EH is possible in toolchains, but not yet proven for this CPython/calibre artifact. Practical routes are (1) Binaryen v130 translate-to-exnref after legacy build, (2) rebuild full WASI SDK/sysroot/libs with official wasm EH flags, or (3) use a runtime that supports the legacy artifact.

### Binaryen exnref translation success

- Restored a normal legacy-EH `python.wasm` build.
- Installed/downloaded upstream Binaryen version_130 in `/tmp/binaryen130` because Ubuntu `binaryen` 108 lacks `--translate-to-exnref`.
- Ran Binaryen v130 translation:
  - `/tmp/binaryen130/bin/wasm-opt python.wasm --translate-to-exnref -o /tmp/python-exnref.wasm`
- Resulting translated artifact:
  - legacy input: about 27 MiB
  - exnref output: about 25 MiB
- JVM-side Chicory 1.7.5 parse probe now succeeds on `/tmp/python-exnref.wasm`:
  - `PARSE_OK`
  - imports: 43
  - exports: 2
  - tags: 1
- This proves the nonlegacy EH path is actionable as a post-link Binaryen transform. Next step: instantiate/start the translated artifact under Chicory+WASI.

### Chicory exnref startup and minimal conversion

- Translated exnref artifact also works under Node/V8 for `fixtures/generated/minimal.epub` conversion, including without the previous `--experimental-wasm-exnref` flag. This suggests a single exnref artifact may be viable across Node/browser/Android.
- Added a JVM-side Chicory probe shape and ran it against `/tmp/python-exnref.wasm`.
- Chicory 1.7.5 results:
  - `print()` startup: pass.
  - `import browser_convert`: initially failed because Chicory's WASI `path_rename/os.replace` returned ENOTSUP for calibre atomic config writes.
  - Added WASI shim fallback for `os.replace`: if replace/rename return errno 58, copy destination contents and unlink source.
  - `import browser_convert`: pass after shim fallback, but slow on JVM interpreter (~101s).
  - `fixtures/generated/minimal.epub` conversion: pass, generated valid-looking `/work/out.azw3` (~12 KiB), slow on JVM interpreter (~216s).
- Next: make the exnref translation part of the reproducible build/runtime packaging and test in Chrome/browser plus fuller Node fixture set before switching Android assets.

### Chicory conversion probe and WasmEdge fallback assessment

- Checked the in-flight JVM Chicory conversion probe using `/tmp/python-exnref.wasm` against `fixtures/generated/minimal.epub`.
- Result: conversion completed and produced `/tmp/chicory-work/out.azw3` (12 KiB). The output timestamp indicates roughly 216 seconds from probe setup to AZW3 creation for the tiny fixture.
- This confirms the exnref-translated static WASI CPython/calibre runtime can parse, instantiate, import `browser_convert`, and complete an EPUB→AZW3 conversion under Chicory 1.7.5 with the WASI `os.replace`/`os.rename` copy fallback shim.
- Performance is the blocker: import alone was about 101 seconds and the minimal conversion took roughly 3.5 minutes on the JVM Chicory interpreter. That is likely too slow for a pleasant native Android app unless Chicory AOT/JIT/runtime behavior is substantially faster on-device, which should not be assumed.
- WasmEdge is now a credible fallback/investigation path: it would mean embedding a native runtime through JNI/NDK rather than pure Kotlin/Java. The upside is likely much better execution speed and mature WASI support; the cost is Android native packaging complexity, ABI-specific `.so` files, CMake/JNI glue, APK size, and needing to validate WebAssembly EH/exnref support for this CPython artifact.
- Next recommendation: keep Chicory as the pure-JVM proof and integration scaffold, but immediately run a native WasmEdge Android feasibility spike before investing deeply in Chicory UI integration. First test should be host-side WasmEdge CLI/library with `/tmp/python-exnref.wasm` and the same preopens/args; if that works and is significantly faster, create an Android NDK JNI spike modeled after WasmEdge's APK embedding docs.

### Chicory Android AOT/compiler backend note

- User pointed out `chicory-compiler-android` / Android AOT mode.
- Checked current docs:
  - General Chicory docs distinguish interpreter, runtime compilation, and build-time compilation to Java bytecode.
  - `dylibso/chicory-compiler-android` is an experimental Android backend packaged as `com.dylibso.chicory:android-aot:0.0.1` through GitHub Package Registry, not plain Maven Central.
  - The Android backend is used by adding `AotAndroidMachine::new` as Chicory's `MachineFactory`.
  - Docs advise running Chicory/AOT on a dedicated thread with generous stack (example: 8 MiB), matching our stack-sensitive CPython workload.
- Important distinction: Android AOT/compiler backend can improve execution speed, but it still starts from a wasm module parsed by Chicory. Therefore our Binaryen exnref translation remains necessary before AOT can help with this artifact.
- Next Android direction after exnref packaging: try `android-aot` if credentials/access are available, or keep JVM/Android interpreter only for correctness probes.

### Native Android WebAssembly runtime spike

- Starting direct investigation of embedding a native WebAssembly runtime in the Android APK via NDK/JNI instead of relying on WebView or pure-JVM Chicory.
- Candidate runtimes to assess: WasmEdge, Wasmer, Wasm3, Wasmtime/WAMR if useful.
- First-pass criteria: Android embeddability, WASI support, WebAssembly EH/exnref compatibility for the static CPython artifact, expected performance versus Chicory, APK/ABI packaging complexity, and maintenance risk.

### Native runtime host probes

- Directly tested the user's suggested NDK/JNI-native runtime direction by running the exnref-translated static WASI CPython/calibre artifact on several host runtimes before touching Android packaging.
- WasmEdge 0.17.0: passed `print`, `import browser_convert`, and `minimal.epub` conversion. Minimal conversion took about 73s and peak RSS was about 549 MiB. `--enable-jit`/JIT attempted compilation but warned that Exception Handling is not supported in WasmEdge AOT/JIT, then fell back to interpreter mode.
- Wasmtime 46.0.1: passed with `-W exceptions=y`. First trivial run paid compile/cache cost (~16.6s), then `import browser_convert` was ~1.13s and `minimal.epub` conversion was ~1.19s with about 167 MiB RSS. This is dramatically better than Chicory and is the leading Android runtime candidate.
- Wasmer 7.1.0: failed before execution: no backend supports the module's required feature set, even with exceptions/reference/all proposal flags.
- WAMR/iwasm 2.4.4 gc-eh: failed loading `/tmp/python-exnref.wasm` with unsupported opcode `0x1f`; the release advertises legacy EH but not current EH.
- Wasm3 0.5.0: failed parsing the module (`out of order Wasm section`), confirming it is not suitable for this modern CPython/WASI artifact.
- Downloaded Android aarch64 runtime packages for the two viable options. Wasmtime's Android C API package includes a ~24.8 MiB `libwasmtime.so` plus headers; WasmEdge's Android package includes a ~3.1 MiB `libwasmedge.so` plus headers.
- Conclusion: move production Android runtime exploration from Chicory to NDK/JNI embedding. Try Wasmtime first because speed is the blocker and it supports the exnref/EH artifact very well on the host. Keep WasmEdge as the smaller/slower fallback.
