# WASI CPython calibre progress log

Follow-up to `docs/wasi-python-calibre-spike.md`.

## Progress made

A newer prebuilt WASI CPython 3.12 runtime from `vmware-labs/webassembly-language-runtimes` includes `zlib` and `_bz2`:

```text
import ok zlib
import ok bz2
import ok zipfile
```

`experiments/wasi_python312_probe.py` mounts that runtime, extracts `web/calibre-runtime.zip`, installs pure-Python wheels, and provides experimental pure-Python stubs for unavailable binary modules (`lxml`, `PIL`, `regex`, `msgpack`) to measure how far the calibre pipeline can get without native wheels.

Current command:

```bash
python3 experiments/wasi_python312_probe.py
```

Current observed progress:

```text
bootstrap ok
KINDLE_PROFILES_OK
browser_convert ok
InputFormatPlugin: EPUB Input running
```

This is materially farther than the 3.11 probe: calibre's browser conversion entrypoint imports under standalone WASI CPython and starts the real Plumber/EPUB input path.

## Current blocker

The run stops in EPUB OPF/spine processing:

```text
ValueError: No valid entries in the spine of this EPUB
```

That failure is caused by the deliberately incomplete pure-Python `lxml` facade in the probe. It is useful for proving import and early execution progress, but it is not a real replacement for lxml. calibre relies on lxml XPath, parent links, builders, HTML parsing, serialization, and element semantics throughout OPF/OEB/MOBI code.

## Extension-module investigation

`wasmpy-build` can compile CPython C extensions to WASM modules, but the probed CPython runtime reports:

```python
importlib.machinery.EXTENSION_SUFFIXES == []
```

A tiny `hello` C extension can be compiled to a `.wasm`, but this runtime does not load extension modules via importlib/dlopen, so producing standalone extension `.wasm` files is not enough. We either need a WASI CPython build with dynamic extension loading enabled, or statically linked/built-in modules.

## Updated path to success

The most promising route is now:

1. Build CPython for WASI with `zlib` and dynamic extension support, or with required extensions statically linked.
2. Build/link lxml's Cython output + libxml2/libxslt into that same CPython/WASI build.
3. Then add Pillow, regex, and msgpack similarly or replace them only where truly optional.

A pure-Python lxml facade is not viable for full conversion; it will become a growing reimplementation of lxml/calibre XML semantics.
