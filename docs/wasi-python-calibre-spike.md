# WASI CPython + reduced calibre spike

Goal: explore a no-WebView Option D path where Android/Kotlin runs a standalone WASI Python runtime, mounts the reduced calibre runtime, and eventually converts EPUB/MOBI/AZW3 to AZW3.

## What now works

`experiments/wasi_python_probe.py` downloads:

- Wasmtime for local desktop probing.
- A prebuilt WASI CPython 3.11.1 runtime from `vmware-labs/webassembly-language-runtimes`.

It then creates a WASI sandbox, extracts `web/calibre-runtime.zip` to `/app`, adds pure-Python wheels to `site-packages`, and runs `/probe.py` inside `python.wasm`.

Current command:

```bash
python3 experiments/wasi_python_probe.py
```

Current observed progress:

```text
platform wasi
stdlib ok
import ok bs4
import ok html5lib
import ok dateutil
bootstrap ok
browser_convert failed: No module named 'lxml'
```

So the reduced calibre bootstrap itself can import under WASI CPython once missing optional compression modules are stubbed for import probing.

## Blocking gaps

The existing browser converter depends on Pyodide/Emscripten binary wheels:

- `lxml` (`*.cpython-313-wasm32-emscripten.so`)
- `Pillow`
- `regex`
- `msgpack`
- Pyodide `lzma`

Those wheels are not usable by the WASI CPython runtime. WASI CPython needs WASI-targeted extension modules/wheels, not Pyodide's Emscripten ABI.

The probed WASI CPython runtimes also lack important stdlib extension modules:

- `zlib` is required for EPUB/ZIP handling.
- `_bz2` / `_lzma` are missing in the tested builds.

A newer CPython 3.13 WASI build was also checked and still lacks `zlib`, `_bz2`, `_lzma`, `_sqlite3`, `_ssl`.

## Pyodide outside WebView is a different problem

`pyodide.asm.wasm` is not a standalone WASI Python. It has hundreds of Emscripten/Pyodide JS imports such as:

```text
env.emscripten_asm_const_int
env.raw_call_js
env.js2python_js
env.JsProxy_GetAttr_js
...
```

That makes it a poor target for Chicory/WASI directly unless we reimplement a large Pyodide JS host.

## Feasible next technical path

A true Option D converter needs one of:

1. Build CPython for WASI with the needed stdlib extensions, at minimum `zlib`.
2. Build or vendor WASI wheels for `lxml`, `Pillow`, `regex`, and `msgpack`.
3. Or reduce/replace calibre dependencies enough to avoid those binary extensions, which is likely a large fork from the proven Plumber path.

Recommended next spike if continuing:

- Build CPython WASI from source with `zlib` enabled via wasi-sdk.
- Attempt a WASI build of `lxml` against libxml2/libxslt for the same Python ABI.
- Only after `import lxml`, `import zlib`, and `import PIL` work under WASI should we try `browser_convert.convert_file()`.

## Current conclusion

Standalone WASM on Android is viable, and WASI CPython can import some of our Python bootstrap. But it is not yet close to replacing WebView/Pyodide for calibre conversion because the required binary Python extension stack is not available for the tested WASI runtimes.
