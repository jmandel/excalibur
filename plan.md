# Plan: calibre-derived EPUB/MOBI/AZW3 -> AZW3 in WebAssembly

## Objective

Explore technique (A): run a **reduced calibre conversion subset** inside WASM via Pyodide, targeting:

```text
EPUB input -> calibre OEB intermediate -> calibre KF8/AZW3 writer -> .azw3 bytes
MOBI/AZW/AZW3 input -> calibre MOBI reader -> OEB intermediate -> calibre KF8/AZW3 writer -> .azw3 bytes
```

This repo is for source reconnaissance, experiments, fixture collection, and notes. It is not yet an Android app.

## Repository layout

```text
third_party/calibre/       calibre source as git submodule
fixtures/                  legally usable sample books and generated fixtures
experiments/               Pyodide / extraction / conversion spikes
docs/                      deeper notes as they accumulate
scripts/                   helper scripts
plan.md                    current plan, updated as findings change
journal.md                 chronological progress log
```

## Starting hypothesis

The fastest proof-of-life is **Pyodide + selected calibre modules**, not a native Android port and not a clean Rust port yet.

Why:

- calibre's AZW3 writer is mostly Python byte assembly and DOM/CSS rewriting.
- Pyodide targets CPython-in-WASM and can carry native Python packages such as `lxml`/`Pillow` when available.
- This gives an executable compatibility oracle before committing to a Rust/TS rewrite.

## Relevant calibre source map

### EPUB input

- `src/calibre/ebooks/conversion/plugins/epub_input.py`
- `src/calibre/ebooks/metadata/opf2.py`
- `src/calibre/utils/zipfile.py`
- fallback: `src/calibre/utils/localunzip.py`

### MOBI/AZW/AZW3 input

- `src/calibre/ebooks/conversion/plugins/mobi_input.py`
- `src/calibre/ebooks/mobi/reader/mobi6.py`
- `src/calibre/ebooks/mobi/reader/mobi8.py`
- `src/calibre/ebooks/mobi/reader/headers.py`
- `src/calibre/ebooks/mobi/reader/index.py`
- `src/calibre/ebooks/mobi/reader/markup.py`
- `src/calibre/ebooks/mobi/reader/ncx.py`
- `src/calibre/ebooks/mobi/huffcdic.py`

### OEB intermediate

- `src/calibre/ebooks/conversion/plumber.py:create_oebbook`
- `src/calibre/ebooks/oeb/base.py`
- `src/calibre/ebooks/oeb/reader.py`
- `src/calibre/ebooks/oeb/parse_utils.py`
- `src/calibre/ebooks/oeb/normalize_css.py`
- selected `src/calibre/ebooks/oeb/transforms/*`

### AZW3 output / KF8 writer

- `src/calibre/ebooks/conversion/plugins/mobi_output.py:AZW3Output`
- `src/calibre/ebooks/mobi/writer2/resources.py`
- `src/calibre/ebooks/mobi/writer8/main.py`
- `src/calibre/ebooks/mobi/writer8/mobi.py`
- `src/calibre/ebooks/mobi/writer8/index.py`
- `src/calibre/ebooks/mobi/writer8/skeleton.py`
- `src/calibre/ebooks/mobi/writer8/exth.py`
- `src/calibre/ebooks/mobi/writer8/header.py`
- `src/calibre/ebooks/mobi/writer8/toc.py`
- `src/calibre/ebooks/mobi/writer8/tbs.py`

## Workstreams

### 1. Fixture corpus

Need small, legally redistributable fixtures covering:

- minimal EPUB2/EPUB3
- CSS + images
- NCX and EPUB3 nav
- internal links / anchors
- SVG content
- embedded fonts if license permits
- RTL / non-Latin metadata if possible
- MOBI/AZW/AZW3 input samples if legally safe

Fixture policy:

- Prefer self-generated minimal EPUB fixtures for exact assertions.
- Use public-domain/open-license books for real-world smoke tests.
- Record source URL and license for every third-party file.
- Do not commit generated AZW3 outputs unless small and license-safe; prefer scripts to regenerate.

### 2. Pyodide feasibility matrix

Questions to answer:

- Which calibre dependencies are available as Pyodide packages?
- Can selected calibre modules import under Pyodide with stubs?
- Is calibre's current Python syntax compatible with Pyodide's Python version?
- Can lxml/css-parser/Pillow function inside the sandbox well enough?
- How much filesystem emulation is required?

Expected shims:

- `calibre_extensions.icu`
- `calibre_extensions.speedup`
- `calibre_extensions.cPalmdoc`
- `calibre_extensions.imageops`
- `calibre_extensions.translator`

Initial simplifications:

- EPUB input first, MOBI later.
- No DRM.
- No WebP conversion initially.
- Use pure-Python PalmDOC compression first.
- Use default/fallback collation and case handling instead of ICU.

### 3. Minimal conversion harness

Target API for spike:

```python
def convert_to_azw3(input_bytes: bytes, ext: str) -> bytes:
    ...
```

Initial path:

```text
input bytes -> MEMFS temp dir -> input plugin -> OPF path -> OEBBook -> minimal transforms -> AZW3Output.convert -> output bytes
```

### 4. Validation

For each fixture:

- desktop calibre conversion succeeds
- Pyodide conversion succeeds
- generated AZW3 can be parsed/opened by desktop calibre
- generated AZW3 can be downloaded/opened on target Kindle later
- structural comparison against desktop calibre output where feasible

Structural comparison checklist:

- PalmDB record count
- MOBI header fields
- EXTH records
- text record count
- FDST entries
- SKEL/CHUNK/NCX/GUIDE records
- resource count and MIME types

### 5. Later production direction

If Pyodide proves behavior, consider clean port:

```text
EPUB parser / libmobi-WASM -> neutral book model -> Rust/WASM AZW3 writer
```

The Rust writer should use calibre's `writer8` as a reference but expose a small neutral input model rather than recreating OEBBook.

## Immediate next steps

1. Add calibre submodule. **Done.**
2. Create `plan.md` and `journal.md`. **Done.**
3. Inventory imports for the minimal EPUB->AZW3 path.
4. Create/download initial fixtures with license notes.
5. Try running desktop calibre source modules enough to produce a baseline AZW3 from a tiny fixture. **Started via `experiments/import_probe.py`.**
6. Build a Pyodide import spike.

## Current native import-probe status

`experiments/import_probe.py` is the working harness for discovering the minimum shim surface. It currently:

- Adds `third_party/calibre/src` to `sys.path`.
- Defines `sys.extensions_location` and `sys.resources_location` expected by calibre's app layout.
- Stubs `calibre_extensions.cPalmdoc`, `speedup`, `icu`, `translator`, and `imageops`.
- Attempts imports of the core EPUB/OEB/AZW3 modules.

Status: green for the target import set under native Python 3.12 with venv packages and runtime shims.

Next probe should move from importability to execution:

1. Run `EPUBInput.convert()` on `fixtures/generated/minimal.epub` into a temp dir.
2. Run `create_oebbook()` on the resulting OPF.
3. Instantiate `AZW3Output` or directly call the writer path with minimal opts.
4. Determine which conversion transforms are truly required versus optional polish.

## Native end-to-end status

Reached a native end-to-end pipeline using calibre's real `Plumber` default transform path:

```text
EPUB -> EPUBInput -> OEBReader/OEBBook -> default Plumber transforms -> AZW3Output/writer8 -> AZW3
AZW3 -> MOBIInput/Mobi8Reader -> OEBReader/OEBBook -> default Plumber transforms -> AZW3Output/writer8 -> AZW3
```

Repro command:

```bash
.venv/bin/python experiments/run_fixture_conversions.py
```

The command converts all generated EPUB fixtures to AZW3, then round-trips those AZW3 files through the MOBI-family input path back to AZW3, and validates basic KF8 structure.

Important: this is not yet Pyodide. It is the native source-tree compatibility oracle that the Pyodide package should replicate.

## Pyodide translation implications from native run

Required Python packages likely include:

- `lxml`
- `html5-parser` or a workaround for EPUB3 nav parsing
- `css-parser`
- `Pillow` for image metadata/JPEG handling
- `python-dateutil`
- `tzlocal`/`tzdata`
- `regex`, `chardet`, `beautifulsoup4`, `html5lib`, `webencodings`, `msgpack` depending on imported surfaces

Required WASM/runtime shims now known:

- `calibre_extensions.icu`
- `calibre_extensions.speedup`
- `calibre_extensions.cPalmdoc` with compress + decompress
- `calibre_extensions.fast_html_entities`
- `calibre_extensions.translator`
- `calibre_extensions.imageops`
- `calibre.utils.safe_atexit` no-subprocess behavior
- source-checkout localization fallbacks or packaged localization resources
- narrow `calibre.customize.ui` plugin registry

Next big task:

- Package this exact native bootstrap into a browser/Pyodide experiment and run `experiments/run_fixture_conversions.py` equivalent inside WASM.

## Pyodide/WASM status

Reached end-to-end Pyodide execution for the fixture corpus:

```bash
node experiments/pyodide_calibre_run.mjs
```

This runs inside Pyodide/WASM and performs:

```text
EPUB fixtures -> calibre Plumber defaults -> AZW3
AZW3 outputs -> MOBIInput -> calibre Plumber defaults -> AZW3
```

The same structural `inspect_azw3.py` checks pass inside Pyodide.

### Pyodide package plan

Use `pyodide.loadPackage()` for:

- `lxml`
- `Pillow`
- `python-dateutil`
- `regex`
- `beautifulsoup4`
- `html5lib`
- `webencodings`
- `msgpack`
- `tzdata`
- `lzma`
- `micropip`

Use `micropip.install()` for:

- `css-parser`
- `chardet`
- `tzlocal`

Currently shim/replace:

- `html5_parser`
- `calibre_extensions.*`
- narrow `calibre.customize.ui`
- `calibre.utils.safe_atexit`
- source-checkout localization helpers

### Next correctness work

1. Compare Pyodide outputs to native-source outputs structurally, not byte-for-byte.
2. Add more fixtures: EPUB2 NCX-only, malformed-ish XHTML, embedded fonts, RTL, real public-domain EPUB. **EPUB2 NCX-only and RTL added.**
3. Find or generate a legal legacy MOBI6 input fixture. **Generated MOBI6 fixtures now work via calibre MOBI output with SVG rasterizer unavailable.**
4. Replace the temporary `html5_parser` fallback with a more faithful parser story.
5. Package only the needed calibre subset instead of mounting the whole repo.
6. Normalize structural comparisons to ignore harmless padding-record variation.
