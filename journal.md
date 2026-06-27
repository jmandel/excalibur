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
