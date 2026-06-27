#!/usr/bin/env python3
"""Probe a standalone WASI CPython runtime with the reduced calibre runtime.

This script downloads a prebuilt CPython/wasi SDK zip if needed, runs smoke
imports under wasmtime, then attempts to import the browser conversion entrypoint
against web/calibre-runtime.zip extracted into a WASI preopened root.
"""
from __future__ import annotations

import os
import json
import shutil
import subprocess
import sys
import textwrap
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "experiments" / "wasi-python-spike"
RUNTIME_URL = "https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/python%2F3.11.1%2B20230127-c8036b4/python-aio-3.11.1.zip"
ZIP = WORK / "python-aio-3.11.1.zip"
RUNTIME = WORK / "python-aio-3.11.1"
SANDBOX = WORK / "sandbox"
PURE_WHEELS = [
    ROOT / 'node_modules/pyodide/beautifulsoup4-4.13.3-py3-none-any.whl',
    ROOT / 'node_modules/pyodide/html5lib-1.1-py2.py3-none-any.whl',
    ROOT / 'node_modules/pyodide/python_dateutil-2.9.0.post0-py2.py3-none-any.whl',
    ROOT / 'node_modules/pyodide/six-1.17.0-py2.py3-none-any.whl',
    ROOT / 'node_modules/pyodide/soupsieve-2.6-py3-none-any.whl',
    ROOT / 'node_modules/pyodide/typing_extensions-4.14.1-py3-none-any.whl',
    ROOT / 'node_modules/pyodide/webencodings-0.5.1-py2.py3-none-any.whl',
    ROOT / 'android-app/app/src/main/assets/app/wheels/css_parser-1.1.1-py3-none-any.whl',
    ROOT / 'android-app/app/src/main/assets/app/wheels/chardet-5.2.0-py3-none-any.whl',
    ROOT / 'android-app/app/src/main/assets/app/wheels/tzlocal-5.4.3-py3-none-any.whl',
]
WASMTIME = shutil.which("wasmtime")


def run(cmd: list[str], *, cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(cmd))
    return subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=check)


def ensure_wasmtime() -> str:
    global WASMTIME
    if WASMTIME:
        return WASMTIME
    install = WORK / "wasmtime"
    bin_path = install / "wasmtime-v18.0.4-x86_64-linux" / "wasmtime"
    if not bin_path.exists():
        tar = WORK / "wasmtime-v18.0.4-x86_64-linux.tar.xz"
        if not tar.exists():
            url = "https://github.com/bytecodealliance/wasmtime/releases/download/v18.0.4/wasmtime-v18.0.4-x86_64-linux.tar.xz"
            print("download", url)
            urllib.request.urlretrieve(url, tar)
        install.mkdir(parents=True, exist_ok=True)
        run(["tar", "-xJf", str(tar), "-C", str(install)])
    return str(bin_path)


def ensure_runtime() -> Path:
    WORK.mkdir(parents=True, exist_ok=True)
    if not ZIP.exists():
        print("download", RUNTIME_URL)
        urllib.request.urlretrieve(RUNTIME_URL, ZIP)
    if not (WORK / "bin" / "python-3.11.1.wasm").exists():
        with zipfile.ZipFile(ZIP) as z:
            z.extractall(WORK)
    return WORK


def extract_pure_wheels(site_packages: Path) -> None:
    site_packages.mkdir(parents=True, exist_ok=True)
    for wheel in PURE_WHEELS:
        if wheel.exists():
            with zipfile.ZipFile(wheel) as z:
                z.extractall(site_packages)

def prepare_sandbox(runtime: Path) -> None:
    if SANDBOX.exists():
        shutil.rmtree(SANDBOX)
    SANDBOX.mkdir(parents=True)
    # Runtime zip contains lib/python3.11 and python.wasm. Keep lib at /lib.
    pyver = 'python3.11'
    if (runtime / "lib").exists():
        shutil.copytree(runtime / "lib", SANDBOX / "lib")
    elif (runtime / "usr" / "local" / "lib").exists():
        shutil.copytree(runtime / "usr" / "local" / "lib", SANDBOX / "lib")
    elif (runtime / "usr" / "local" / "lib" / pyver).exists():
        shutil.copytree(runtime / "usr" / "local" / "lib", SANDBOX / "lib")
    else:
        print("runtime contents:")
        for p in runtime.iterdir(): print(" ", p)
    extract_pure_wheels(SANDBOX / 'lib' / pyver / 'site-packages')
    # Extract our reduced calibre runtime into /app.
    (SANDBOX / "app").mkdir()
    with zipfile.ZipFile(ROOT / "web" / "calibre-runtime.zip") as z:
        z.extractall(SANDBOX / "app")
    (SANDBOX / "work").mkdir()
    shutil.copy(ROOT / "fixtures" / "generated" / "minimal.epub", SANDBOX / "work" / "minimal.epub")

    (SANDBOX / "probe.py").write_text(textwrap.dedent('''
        import os, sys, json, traceback
        print('python', sys.version)
        print('platform', sys.platform)
        sys.path.insert(0, '/app/experiments')
        sys.path.append('/lib/python3.11/site-packages')
        try:
            import zipfile, xml.etree.ElementTree, sqlite3
            print('stdlib ok')
        except Exception:
            print('stdlib failed'); traceback.print_exc()
        mods = ['lxml', 'PIL', 'regex', 'bs4', 'html5lib', 'msgpack', 'dateutil']
        for m in mods:
            try:
                __import__(m); print('import ok', m)
            except Exception as e:
                print('import fail', m, type(e).__name__, e)
        # Some WASI CPython builds lack optional compression extension modules.
        # The real converter needs zlib for EPUB/ZIP; bz2/lzma can be stubbed to measure import progress.
        import types
        for missing in ['bz2', 'lzma']:
            if missing not in sys.modules:
                mod = types.ModuleType(missing)
                mod.open = lambda *a, **k: (_ for _ in ()).throw(NotImplementedError(missing))
                sys.modules[missing] = mod
        try:
            import calibre_bootstrap
            print('bootstrap ok')
        except Exception:
            print('bootstrap failed'); traceback.print_exc()
        try:
            import browser_convert
            print('browser_convert ok')
        except Exception:
            print('browser_convert failed'); traceback.print_exc()
    '''))


def python_wasm(runtime: Path) -> Path:
    for name in ["python.wasm", "bin/python.wasm", "bin/python-3.11.1.wasm", "usr/local/bin/python.wasm"]:
        p = runtime / name
        if p.exists(): return p
    raise FileNotFoundError("python.wasm not found")


def main() -> int:
    wasmtime = ensure_wasmtime()
    runtime = ensure_runtime()
    prepare_sandbox(runtime)
    wasm = python_wasm(runtime)
    env = ["PYTHONHOME=/", "PYTHONPATH=/lib/python3.11:/app/experiments"]
    cmd = [wasmtime, "run", "--dir", f"{SANDBOX}::/", *sum((["--env", e] for e in env), []), str(wasm), "/probe.py"]
    cp = run(cmd, check=False)
    print(cp.stdout)
    (WORK / "last-output.txt").write_text(cp.stdout)
    return cp.returncode


if __name__ == "__main__":
    raise SystemExit(main())
