#!/usr/bin/env python3
from __future__ import annotations
import argparse, os, shutil, stat, subprocess, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / 'experiments/static-wasi-python'
CPY = STATIC / 'Python-3.12.0'
LEGACY_WASM = CPY / 'builddir/wasi/python.wasm'
EXNREF_WASM = ROOT / 'build/runtime/python-exnref.wasm'
ANDROID_CWASM = ROOT / 'build/runtime/python-aarch64-android.cwasm'
OUTS = [
    ROOT / 'web/calibre-runtime.zip',
    ROOT / 'consumer-app/src/assets/calibre-runtime.zip',
    ROOT / 'android-app/app/src/main/assets/app/calibre-runtime.zip',
]
INCLUDE_FILES = [
    'experiments/calibre_bootstrap.py',
    'experiments/convert_with_plumber.py',
    'experiments/inspect_azw3.py',
    'experiments/check_profiles.py',
    'experiments/browser_convert.py',
    'experiments/wasi_runtime_shims.py',
]
INCLUDE_DIRS = ['third_party/calibre/src', 'third_party/calibre/resources']
EXCLUDE_PARTS = {'__pycache__', '.git', 'test', 'tests', 'idlelib', 'tkinter', 'turtledemo', 'ensurepip'}
EXCLUDE_SUFFIXES = {'.pyc', '.pyo', '.so', '.a', '.o'}
WASI_ENTRIES = [
    (CPY / 'Lib', Path('wasi/Lib')),
    (CPY / 'builddir/wasi/build/lib.wasi-wasm32-3.12', Path('wasi/build/lib.wasi-wasm32-3.12')),
    (STATIC / 'third-party-site', Path('wasi/third_party_site')),
]

def should_include(p: Path) -> bool:
    return p.is_file() and not any(part in EXCLUDE_PARTS for part in p.parts) and p.suffix not in EXCLUDE_SUFFIXES

def write_bytes(z: zipfile.ZipFile, arc: Path, data: bytes, mode: int = 0o644, compress_type: int = zipfile.ZIP_DEFLATED):
    info = zipfile.ZipInfo(str(arc), date_time=(2026, 6, 27, 0, 0, 0))
    info.compress_type = compress_type
    info.external_attr = mode << 16
    z.writestr(info, data)

def write_file(z: zipfile.ZipFile, src: Path, arc: Path, stored: bool = False):
    mode = stat.S_IMODE(src.stat().st_mode) or 0o644
    write_bytes(z, arc, src.read_bytes(), mode, zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED)

def add_tree(z: zipfile.ZipFile, src: Path, arc_root: Path):
    for p in sorted(src.rglob('*')):
        if should_include(p):
            write_file(z, p, arc_root / p.relative_to(src))

def find_wasm_opt() -> str:
    env = os.environ.get('BINARYEN_WASM_OPT')
    candidates = [env, '/tmp/binaryen130/bin/wasm-opt', shutil.which('wasm-opt')]
    for c in candidates:
        if c and Path(c).exists():
            help_text = subprocess.run([c, '--help'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout
            if '--translate-to-exnref' in help_text:
                return c
    raise SystemExit('Need Binaryen wasm-opt with --translate-to-exnref; set BINARYEN_WASM_OPT or install Binaryen >=130')

def ensure_exnref(force: bool = False):
    if not LEGACY_WASM.exists():
        raise SystemExit(f'missing {LEGACY_WASM}; run scripts/static_wasi/build_static_wasi_python.py first')
    EXNREF_WASM.parent.mkdir(parents=True, exist_ok=True)
    if force or not EXNREF_WASM.exists() or EXNREF_WASM.stat().st_mtime < LEGACY_WASM.stat().st_mtime:
        wasm_opt = find_wasm_opt()
        subprocess.run([wasm_opt, str(LEGACY_WASM), '--translate-to-exnref', '-o', str(EXNREF_WASM)], check=True)
    print(f'exnref wasm: {EXNREF_WASM} {EXNREF_WASM.stat().st_size}')

def maybe_precompile_android(force: bool = False):
    wasmtime = os.environ.get('WASMTIME') or shutil.which('wasmtime') or '/tmp/wasmtime/wasmtime'
    if not Path(wasmtime).exists():
        print('skip android precompile: wasmtime CLI not found')
        return
    if force or not ANDROID_CWASM.exists() or ANDROID_CWASM.stat().st_mtime < EXNREF_WASM.stat().st_mtime:
        subprocess.run([wasmtime, 'compile', '-W', 'exceptions=y', '--target', 'aarch64-linux-android', str(EXNREF_WASM), '-o', str(ANDROID_CWASM)], check=True)
    print(f'android cwasm: {ANDROID_CWASM} {ANDROID_CWASM.stat().st_size}')

def build_zip(out: Path, include_cwasm: bool):
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(out.suffix + '.tmp')
    with zipfile.ZipFile(tmp, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        write_bytes(z, Path('runtime-manifest.json'), ("""{\n  "python_wasm": "wasi/python.wasm",\n  "exception_handling": "exnref",\n  "generated_by": "scripts/build_runtime_artifacts.py",\n  "android_precompiled": %s\n}\n""" % ('true' if include_cwasm and ANDROID_CWASM.exists() else 'false')).encode())
        write_file(z, EXNREF_WASM, Path('wasi/python.wasm'), stored=True)
        if include_cwasm and ANDROID_CWASM.exists():
            write_file(z, ANDROID_CWASM, Path('wasi/python-aarch64-android.cwasm'), stored=True)
        for f in INCLUDE_FILES:
            rel = Path(f); write_file(z, ROOT / rel, rel)
        for d in INCLUDE_DIRS:
            base = ROOT / d
            for p in sorted(base.rglob('*')):
                if should_include(p): write_file(z, p, p.relative_to(ROOT))
        for src, arc in WASI_ENTRIES:
            if src.is_dir(): add_tree(z, src, arc)
            elif src.is_file(): write_file(z, src, arc)
    tmp.replace(out)
    print(f'{out} {out.stat().st_size}')

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--force', action='store_true')
    ap.add_argument('--android-precompile', action='store_true', help='also produce wasi/python-aarch64-android.cwasm for Android')
    args = ap.parse_args()
    ensure_exnref(args.force)
    if args.android_precompile:
        maybe_precompile_android(args.force)
    for out in OUTS:
        build_zip(out, include_cwasm=args.android_precompile and 'android-app' in str(out))
if __name__ == '__main__': main()
