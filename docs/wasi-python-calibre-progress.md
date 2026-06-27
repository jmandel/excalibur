# WASI CPython calibre progress log

Follow-up to `docs/wasi-python-calibre-spike.md`.

## Current status: first standalone WASI AZW3 produced

`experiments/wasi_python312_probe.py` now demonstrates a complete EPUB→AZW3 conversion under standalone WASI CPython 3.12, outside WebView/V8/Pyodide.

Command:

```bash
python3 experiments/wasi_python312_probe.py
```

The probe downloads/runs a prebuilt WASI CPython 3.12 runtime from `vmware-labs/webassembly-language-runtimes`, mounts `web/calibre-runtime.zip`, installs pure-Python wheels, and runs the real calibre `Plumber` path against a generated flat EPUB fixture.

Latest successful output includes:

```text
browser_convert ok
InputFormatPlugin: EPUB Input running
Creating AZW3 Output...
AZW3 output written to /work/flat.azw3
{'file': '/work/flat.azw3', 'size': 10403, ... 'mobi_version': 8, 'is_kf8': True}
```

This is the first successful no-WebView/no-Pyodide-browser-host conversion proof in the repo.

## How it works today

The runtime has useful WASI stdlib modules:

```text
import ok zlib
import ok bz2
import ok zipfile
```

The probe currently uses pragmatic compatibility shims for unavailable binary modules/APIs:

- `lxml`: a small ElementTree-backed facade plus targeted XPath/OPF/OEB patches.
- `PIL`: minimal image module shell; flat text fixture has no real images.
- `regex`: falls back to stdlib `re`.
- `msgpack`: JSON-backed shell sufficient for this path.
- `multiprocessing`/thread executor: synchronous/no-op compatibility shell.
- OEB/KF8 writer patches to bypass or simplify lxml-heavy operations where safe for the flat fixture.

## Limitations

This is a proof of feasibility, not yet production-quality calibre-on-WASI:

- The successful fixture is intentionally flat and text-only.
- The existing generated EPUB fixtures with nested `OEBPS/...` paths and EPUB3 nav need more complete XML/path handling.
- Real books need real `lxml` semantics or a much more robust compatibility layer.
- Image-heavy books still need real Pillow or deeper image stubs/handling.
- `regex` and `msgpack` are still fallback shims.
- Dynamic CPython extension loading remains unresolved for this prebuilt runtime (`EXTENSION_SUFFIXES == []`).

## Next direction

There are now two viable tracks:

1. **Pragmatic pure-Python compatibility layer**: keep expanding the ElementTree-backed lxml facade and path handling until normal EPUB/MOBI fixtures pass. This may be enough for many text-first books and keeps the runtime pure WASI.
2. **Real extension toolchain**: build/customize WASI CPython with dynamic or statically-linked extension support, then build lxml/libxml2/libxslt, Pillow, regex, and msgpack for that ABI.

The successful flat EPUB conversion shows Option D can work end-to-end. The remaining question is whether to harden the shim path or invest in real WASI extension builds.
