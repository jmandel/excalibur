# Standalone WASI CPython with native calibre deps: actionable build path

## Bottom line

For this repo, the most realistic path is:

1. **Stop depending on prebuilt WASI runtimes** and build CPython from source with the official WASI helper.
2. **Statically link stdlib compression modules first** (`zlib`, `_bz2`, `_lzma`) so EPUB/ZIP handling is native and no longer shimmed.
3. For calibre deps, prioritize in this order:
   - **lxml**: hard requirement for real EPUB/OEB/MOBI paths.
   - **Pillow**: required for non-trivial image resources/covers.
   - **regex**: relatively easy native win, but not the main current blocker.
   - **msgpack**: lowest priority; calibre’s conversion path can continue with pure Python for now.
4. For third-party native modules, use one of two tracks:
   - **Track A: WASI dynamic extensions** via `--enable-wasm-dynamic-linking` so package names like `lxml.etree` and `PIL._imaging` work unmodified.
   - **Track B: built-in/top-level native modules** if dynamic loading is still unusable, but then you must patch package imports because `Modules/Setup.local` cannot define dotted module names.

Given this repo’s current probe results, **Track B is safer for stdlib compression**, while **Track A is preferable for lxml/Pillow** because of package layout.

---

## What the repo already proved

From the repo’s current docs and probes:

- `experiments/wasi_python312_probe.py` already produced a **standalone WASI CPython EPUB->AZW3 conversion** using shims.
- The blocker is not “can WASI run calibre at all?”; the blocker is **native dependency replacement**.
- The current standalone runtime lacks the expected native modules (`zlib`, `_bz2`, `_lzma`, `lxml`, `PIL`, `regex`, `msgpack`).
- The Android/Web assets only contain **Pyodide/Emscripten wheels** like:
  - `lxml-...-pyodide_2025_0_wasm32.whl`
  - `pillow-...-pyodide_2025_0_wasm32.whl`
  - `regex-...-pyodide_2025_0_wasm32.whl`
  - `msgpack-...-pyodide_2025_0_wasm32.whl`

Those wheels are ABI-incompatible with standalone WASI CPython.

---

## Dependency triage from this repo

Quick scan of `third_party/calibre/src`:

- **lxml**: imported in many calibre paths, including the current EPUB/OEB/MOBI conversion path (`oeb.base`, `oeb.reader`, `mobi.writer8.main`, `mobi.writer8.skeleton`, etc.). This is the main dependency.
- **PIL/Pillow**: only a handful of files, but they are exactly the image-heavy output paths (`mobi/writer2/resources.py`, image transforms, etc.).
- **regex**: imported in ~14 files, mostly search/metadata/hyphenation/editor paths. Important, but less central than lxml.
- **msgpack**: calibre mostly reaches it through `calibre.utils.serialize`, not by direct imports in the conversion core. Lowest urgency.

So the correct build order is **lxml -> Pillow -> regex -> msgpack**.

---

## Primary-source constraints that matter

### CPython / WASI

Official CPython docs/devguide say:

- WASI is a supported CPython target and should be built with the official helper (`Tools/wasm/wasi.py` for 3.13, `Tools/wasm/wasi` for 3.14, `Platforms/WASI` for 3.15+).
- The helper performs the required **two-stage cross build**.
- `Modules/Setup` / `Modules/Setup.local` controls whether C extensions are built **built-in** or **shared**.
- `--enable-wasm-dynamic-linking` exists, but increases binary size and is still a special WebAssembly build mode.

Relevant official docs:

- CPython WASI build README: `Platforms/WASI/README.md`
- Python devguide WASI section: `devguide.python.org/getting-started/setup-building/`
- Python configure/build docs: `docs.python.org/.../using/configure.html`

### Important CPython build-system gotcha

`Modules/Setup` entries require the module name to be a valid Python identifier. That means **no dotted names** like:

- `lxml.etree`
- `msgpack._cmsgpack`
- `PIL._imaging`
- `regex._regex`

This is the single biggest built-in-module gotcha for third-party packages.

Implication:

- Building `zlib`, `_bz2`, `_lzma` as built-ins is easy.
- Building package-private compiled modules as built-ins requires **patching package imports** to point at top-level built-ins (e.g. `_lxml_etree`, `_imaging`, `_regex`, `_cmsgpack`) unless dynamic extension loading works.

### lxml / libxml2 / libxslt

Official lxml docs say:

- lxml requires **libxml2** and **libxslt**.
- Release sdists ship with generated C, so you do **not** need Cython for a release build.
- `STATIC_DEPS=true pip install lxml` is supported, but for WASI cross-compilation it is likely the wrong default because you need strict control over target compiler/sysroot and the libxml2/libxslt build flags.

Official libxml2 docs say:

- it supports Autotools/CMake/Meson;
- `--with-modules` is on by default;
- `--with-iconv` is on by default;
- `--with-zlib` is off by default.

For WASI, you should expect to turn **modules off** and probably manage **iconv** explicitly.

### Pillow

Official Pillow docs say:

- **zlib** and **libjpeg** are required by default.
- build configuration can be controlled explicitly; it supports source builds with environment/compiler flags.
- recent Pillow build config strongly suggests disabling platform auto-detection in automated/cross builds when you already know include/lib paths.

### msgpack

Official msgpack docs/source say:

- there is a C extension (`msgpack._cmsgpack`),
- but the package has a built-in pure-Python fallback, including an env-controlled `MSGPACK_PUREPYTHON` path.

That makes msgpack a poor early target for native build effort.

### regex

The regex project is a CPython-targeted C extension with package Python files plus a compiled core. It is simpler than lxml/Pillow because it has no external C library dependency, but it still has the **dotted-name built-in problem**.

---

## Recommended implementation path

## Phase 1: own the CPython WASI build

Pick one CPython line and stay on it.

**Recommendation: CPython 3.13.x or 3.14.x**, not 3.12 prebuilt runtime.

Why:

- 3.13 already has the official WASI helper used in upstream docs.
- 3.14/3.15 have slightly cleaner helper naming, but 3.13 is already good enough and closer to your current experiments.
- Building from source removes the current “missing zlib in the prebuilt runtime” problem.

### Concrete commands

For CPython 3.13:

```bash
python3 Tools/wasm/wasi.py configure-build-python --quiet -- --config-cache
python3 Tools/wasm/wasi.py make-build-python --quiet
python3 Tools/wasm/wasi.py configure-host --quiet -- --config-cache
python3 Tools/wasm/wasi.py make-host --quiet
```

Or the one-shot form from the devguide:

```bash
python3 Tools/wasm/wasi.py build --quiet -- --config-cache
```

For 3.14 the helper path changes to `Tools/wasm/wasi`; for 3.15+ it becomes `Platforms/WASI`.

### Repo action

Add a source-based build script instead of downloading a VMware runtime, e.g.:

- `scripts/build_wasi_cpython.sh`
- `scripts/build_wasi_deps.sh`

Do **not** keep betting the architecture on a runtime you cannot rebuild.

---

## Phase 2: restore native stdlib compression first

Before third-party wheels, build these for WASI and link them into CPython:

- **zlib** -> Python `zlib`
- **bzip2** -> Python `_bz2`
- **xz/liblzma** -> Python `_lzma`

This removes the most painful stdlib shims immediately and is straightforward with `Modules/Setup.local`.

### Why first

- EPUB handling needs `zipfile`, which wants `zlib`.
- Current repo probes explicitly show this is missing in stock prebuilt runtimes.
- These are ordinary top-level stdlib extension names, so they fit `Modules/Setup.local` naturally.

### Example `Modules/Setup.local`

Something like:

```make
*static*
zlib zlibmodule.c -I$(WASI_DEPS)/include $(WASI_DEPS)/lib/libz.a
_bz2 _bz2module.c -I$(WASI_DEPS)/include $(WASI_DEPS)/lib/libbz2.a
_lzma _lzmamodule.c -I$(WASI_DEPS)/include $(WASI_DEPS)/lib/liblzma.a
```

Use absolute paths or a make variable you inject consistently.

### Expected success check

Inside the built runtime:

```python
import importlib.machinery
print(importlib.machinery.EXTENSION_SUFFIXES)
import zlib, bz2, lzma, zipfile
```

Even if extension suffixes are still empty, built-ins should import.

---

## Phase 3: build WASI static C libs needed by lxml/Pillow

Install all target libraries into one target prefix, e.g.:

```text
$PWD/out/wasi-deps/
  include/
  lib/
```

Set a cross environment once and reuse it:

```bash
export WASI_SDK=/opt/wasi-sdk
export CC="$WASI_SDK/bin/clang --target=wasm32-wasip1"
export CXX="$WASI_SDK/bin/clang++ --target=wasm32-wasip1"
export AR="$WASI_SDK/bin/llvm-ar"
export RANLIB="$WASI_SDK/bin/llvm-ranlib"
export PKG_CONFIG_PATH="$PWD/out/wasi-deps/lib/pkgconfig"
export CPPFLAGS="-I$PWD/out/wasi-deps/include"
export LDFLAGS="-L$PWD/out/wasi-deps/lib"
```

### 3A. zlib / bzip2 / xz

Build these first because CPython and Pillow rely on them.

### 3B. libxml2

For WASI, start conservative:

- `--with-python=no`
- `--with-modules=no`  ← important, no runtime `dlopen` assumption
- `--with-zlib=yes` if you want compressed XML support
- keep `--with-html`, `--with-xpath`, `--with-reader`, `--with-output` on
- keep it **static-only** if possible

### 3C. libxslt

Build it against the same prefix as libxml2.

### 3D. likely iconv decision point

This is the highest-risk C-library issue for lxml.

libxml2 expects `iconv` by default. On WASI you should assume one of these will happen:

1. **wasi-libc provides enough iconv for your build** -> best case.
2. It does not -> build and install **libiconv** for WASI.
3. You disable iconv -> build may succeed, but real-world HTML/XML encoding coverage will be worse.

For EPUB conversion, option 2 is the safe plan.

---

## Phase 4: native package strategy

You now choose between two tracks.

## Track A (preferred): WASI dynamic extension modules

Use this if `--enable-wasm-dynamic-linking` works in your chosen runtime stack.

### Why it is preferable

It preserves normal package structure:

- `lxml.etree`
- `PIL._imaging`
- `regex._regex`
- `msgpack._cmsgpack`

No package source patching required.

### Build shape

1. Reconfigure CPython host build with:

```bash
... configure-host ... --enable-wasm-dynamic-linking
```

2. Use the resulting target Python / sysconfig to build extension wheels from **sdists**, not host wheels.
3. Mount/install those wheels into the WASI filesystem just like the pure-Python wheels today.

### Risks

- dynamic linking on WASM is explicitly special-case/experimental upstream;
- larger binary and weaker dead-code elimination;
- your current prebuilt runtime showed `EXTENSION_SUFFIXES == []`, so you must verify this from a self-built CPython before investing further.

### Gate

Do not proceed with lxml/Pillow wheel work until this check passes in your custom runtime:

```python
import importlib.machinery
print(importlib.machinery.EXTENSION_SUFFIXES)
```

and you can import a trivial out-of-tree WASI extension.

If that gate fails, switch to Track B.

## Track B (fallback): built-in top-level native modules + package patches

Use this if dynamic loading is still a dead end.

### Core idea

Compile native modules into `libpython` / `python.wasm` as top-level built-ins, then patch package Python files to import those built-ins.

Examples:

- build `_lxml_etree` instead of `lxml.etree`
- build `_imaging` instead of `PIL._imaging`
- build `_regex` instead of `regex._regex`
- build `_cmsgpack` instead of `msgpack._cmsgpack`

Then patch package imports accordingly.

### Why this is workable

- No reliance on WASI `dlopen`.
- Consistent with current repo reality, where built-ins work but shared extensions do not.

### Why this is annoying

- Package patching is mandatory.
- Upstream package updates become harder.
- lxml in particular has more than one compiled entrypoint in some configurations.

---

## Package-by-package recommendation

## 1) lxml: build after stdlib compression, before everything else

### Recommendation

Try **Track A first**, because lxml’s normal package shape wants `lxml.etree`.

If Track A fails, vendor lxml source and patch imports for Track B.

### Build notes

- Use the **lxml sdist**, not a wheel.
- Prefer the release sdist with generated C; avoid introducing Cython into the first build.
- Point the build at your WASI-built `libxml2` + `libxslt` prefix via `CPPFLAGS`, `LDFLAGS`, and `PKG_CONFIG_PATH`.
- Do **not** rely on `STATIC_DEPS=true` as the main plan; for WASI you want deterministic target libs, not an auto-download build step.

### Gotchas

- **iconv** is the likely pain point.
- **libxml2 modules must be off** for WASI.
- If dynamic linking fails and you switch to built-ins, you must patch `lxml/__init__.py` and related imports to use your top-level built-in name(s).

### Success gate

Your conversion path is not really de-risked until this works under WASI:

```python
from lxml import etree, html
```

and calibre can parse a normal EPUB with nested `OEBPS/...` paths without your ElementTree shim.

## 2) Pillow: build after lxml

### Recommendation

Build a **minimal codec set first**:

- zlib
- libjpeg

Potentially skip TIFF, WebP, JPEG2000, RAQM, Freetype, LCMS at first.

### Why

For calibre AZW3 generation, the urgent need is image decoding/resizing for covers and inline images, not the full Pillow feature matrix.

### Build notes

- Use source build from sdist.
- Explicitly disable platform guessing / host autodetection.
- Keep build inputs pinned to your WASI prefix.

### Gotchas

- Pillow’s compiled module is package-private (`PIL._imaging`), so built-in-only mode again implies package patching.
- JPEG and PNG support depend on external libs being correctly found for the **target**, not the host.

### Success gate

At minimum under WASI:

```python
from PIL import Image
```

and a JPEG + PNG smoke test should pass.

## 3) regex: easiest true third-party native module

### Recommendation

Build this **after** lxml/Pillow, or even before Pillow if you want a quick native-extension proof.

### Why

- no external C library dependency,
- much smaller surface area than lxml/Pillow,
- good candidate for validating your third-party extension pipeline.

### Gotcha

Still has the dotted-name package layout problem (`regex._regex`) if you do built-ins instead of dynamic loading.

### Success gate

```python
import regex
regex.compile(r"(?i)(a)(b)").findall("ab AB")
```

## 4) msgpack: keep pure Python until late

### Recommendation

Defer native msgpack.

### Why

- calibre’s core conversion path does not need native msgpack first;
- the package already ships a pure-Python fallback;
- current repo shims can likely be replaced by the real pure-Python package immediately.

### Concrete improvement now

Instead of the JSON shell shim, vendor the actual msgpack Python package and force fallback mode:

```bash
export MSGPACK_PUREPYTHON=1
```

or patch the runtime environment so `msgpack` imports its fallback implementation.

That gives you correctness faster, even if performance is lower.

---

## Strongly recommended build order for this repo

1. **Own CPython source build** (3.13/3.14).
2. **Build zlib/bzip2/xz** and link `zlib`, `_bz2`, `_lzma` as built-ins.
3. **Replace the msgpack shim with real pure-Python msgpack fallback**.
4. **Attempt Track A dynamic extension proof** with a trivial third-party extension.
5. If Track A works, build **regex** first as a pipeline smoke test.
6. Then build **lxml** against WASI libxml2/libxslt/libiconv.
7. Then build **Pillow** with minimal codecs.
8. Only if Track A fails permanently, switch to **Track B** and patch package imports for built-in top-level modules.

---

## Repo-specific concrete next steps

### 1. Add scripts

Create:

- `scripts/build_wasi_cpython.sh`
- `scripts/build_wasi_deps.sh`
- `scripts/build_wasi_extensions.sh`

### 2. Add a target prefix convention

Use one prefix, for example:

```text
experiments/wasi-toolchain/out/
```

with:

- `.../wasi-deps/`
- `.../cpython/`
- `.../site-packages/`

### 3. Add import smoke tests

Create a new probe that checks, in order:

```python
import zlib, bz2, lzma
import regex
from lxml import etree, html
from PIL import Image
import msgpack
```

and then runs the existing `browser_convert.convert_file()` on:

- the current flat fixture,
- one nested-OEBPS EPUB,
- one image-bearing EPUB.

### 4. Remove shim scope progressively

As native modules land:

- drop `regex` shim first,
- drop `msgpack` shim once real pure-python msgpack is used,
- drop `lxml` facade once real lxml imports,
- drop `PIL` shim once Pillow imports.

---

## Main gotchas to expect

1. **Dotted module names cannot be declared directly in `Modules/Setup.local`.**
   - This is the biggest reason third-party packages are harder than stdlib modules.

2. **Dynamic linking may still be a dead end in your chosen runtime.**
   - Verify with a trivial extension before doing real package work.

3. **Cross-build host contamination is likely.**
   - Disable package auto-detection where possible.
   - Always set target `CC/CXX/AR/RANLIB/CPPFLAGS/LDFLAGS/PKG_CONFIG_PATH`.

4. **libxml2 iconv support is likely to be the hardest native-lib problem.**
   - Plan for a WASI `libiconv` build.

5. **Do not use Pyodide wheels as evidence a package is “available for wasm”.**
   - They target Emscripten/Pyodide, not standalone WASI CPython.

6. **CPython version drift matters.**
   - Pick a Python minor version and keep every native extension on that ABI.

7. **WASI filesystem capability rules still apply.**
   - Tests must use in-sandbox paths, not host paths.

---

## Decision

If I had to choose one path for the next implementation spike in this repo:

- **Build CPython 3.13/3.14 from source**.
- **Statically restore `zlib/_bz2/_lzma` first**.
- **Replace msgpack shim with real pure-Python msgpack immediately**.
- **Attempt dynamic-extension support once**.
- If dynamic extensions are still not viable, **accept a patched-package built-in strategy** for `regex`, then `lxml`, then `Pillow`.

That is the shortest path from the repo’s current “successful shimmed proof” to a maintainable standalone WASI runtime with progressively fewer shims.

---

## Primary sources used

- Python devguide, WASI build section: https://devguide.python.org/getting-started/setup-building/
- CPython WASI README: https://github.com/python/cpython/blob/main/Platforms/WASI/README.md
- CPython configure/build docs (`Modules/Setup`, built-in vs shared, `--enable-wasm-dynamic-linking`): https://docs.python.org/3/using/configure.html
- CPython `Modules/Setup` comments: https://github.com/python/cpython/blob/main/Modules/Setup
- lxml build/installation docs: https://lxml.de/6.0/build.html and https://lxml.de/4.8/installation.html
- libxml2 README/build options: https://github.com/GNOME/libxml2/blob/master/README.md
- libxslt README: https://github.com/GNOME/libxslt/blob/master/README.md
- Pillow build-from-source docs: https://pillow.readthedocs.io/en/latest/installation/building-from-source.html
- msgpack README/source fallback behavior: https://github.com/msgpack/msgpack-python and https://github.com/msgpack/msgpack-python/blob/main/msgpack/__init__.py
- regex project README: https://github.com/mrabarnett/mrab-regex/blob/hg/README.rst
