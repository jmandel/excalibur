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
