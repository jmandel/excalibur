#!/usr/bin/env python3
from __future__ import annotations

import stat
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'web' / 'calibre-runtime.zip'
CONSUMER_OUT = ROOT / 'consumer-app/src/assets/calibre-runtime.zip'
STATIC = ROOT / 'experiments/static-wasi-python'
CPY = STATIC / 'Python-3.12.0'
INCLUDE_FILES = [
    'experiments/calibre_bootstrap.py',
    'experiments/convert_with_plumber.py',
    'experiments/inspect_azw3.py',
    'experiments/check_profiles.py',
    'experiments/browser_convert.py',
    'experiments/wasi_runtime_shims.py',
]
INCLUDE_DIRS = [
    'third_party/calibre/src',
    'third_party/calibre/resources',
]
EXCLUDE_PARTS = {'__pycache__', '.git', 'test', 'tests', 'idlelib', 'tkinter', 'turtledemo', 'ensurepip'}
EXCLUDE_SUFFIXES = {'.pyc', '.pyo', '.so', '.a', '.o'}

WASI_ENTRIES = [
    (CPY / 'builddir/wasi/python.wasm', Path('wasi/python.wasm')),
    (CPY / 'Lib', Path('wasi/Lib')),
    (CPY / 'builddir/wasi/build/lib.wasi-wasm32-3.12', Path('wasi/build/lib.wasi-wasm32-3.12')),
    (STATIC / 'third-party-site', Path('wasi/third_party_site')),
]


def should_include(p: Path) -> bool:
    if any(part in EXCLUDE_PARTS for part in p.parts):
        return False
    if p.suffix in EXCLUDE_SUFFIXES:
        return False
    return p.is_file()


def write_bytes(z: zipfile.ZipFile, arc: Path, data: bytes, mode: int = 0o644):
    info = zipfile.ZipInfo(str(arc), date_time=(2026, 6, 27, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = mode << 16
    z.writestr(info, data)


def write_file(z: zipfile.ZipFile, src: Path, arc: Path):
    mode = stat.S_IMODE(src.stat().st_mode) or 0o644
    write_bytes(z, arc, src.read_bytes(), mode)


def add_tree(z: zipfile.ZipFile, src: Path, arc_root: Path):
    for p in sorted(src.rglob('*')):
        if should_include(p):
            write_file(z, p, arc_root / p.relative_to(src))


def main() -> int:
    if not (CPY / 'builddir/wasi/python.wasm').exists():
        raise SystemExit('missing static WASI python; run scripts/static_wasi/build_static_wasi_python.py first')
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in INCLUDE_FILES:
            rel = Path(f)
            write_file(z, ROOT / rel, rel)
        for d in INCLUDE_DIRS:
            base = ROOT / d
            for p in sorted(base.rglob('*')):
                if should_include(p):
                    write_file(z, p, p.relative_to(ROOT))
        for src, arc in WASI_ENTRIES:
            if src.is_dir():
                add_tree(z, src, arc)
            elif src.is_file():
                write_file(z, src, arc)
    CONSUMER_OUT.parent.mkdir(parents=True, exist_ok=True)
    CONSUMER_OUT.write_bytes(OUT.read_bytes())
    print(OUT, OUT.stat().st_size)
    print(CONSUMER_OUT, CONSUMER_OUT.stat().st_size)
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
