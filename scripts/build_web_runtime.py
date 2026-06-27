#!/usr/bin/env python3
from __future__ import annotations

import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'web' / 'calibre-runtime.zip'
INCLUDE_FILES = [
    'experiments/calibre_bootstrap.py',
    'experiments/convert_with_plumber.py',
    'experiments/inspect_azw3.py',
    'experiments/check_profiles.py',
    'experiments/browser_convert.py',
]
INCLUDE_DIRS = [
    'third_party/calibre/src',
    'third_party/calibre/resources',
]
EXCLUDE_PARTS = {'__pycache__', '.git'}
EXCLUDE_SUFFIXES = {'.pyc', '.pyo'}


def should_include(p: Path) -> bool:
    if any(part in EXCLUDE_PARTS for part in p.parts):
        return False
    if p.suffix in EXCLUDE_SUFFIXES:
        return False
    return p.is_file()


def write(z: zipfile.ZipFile, rel: Path):
    info = zipfile.ZipInfo(str(rel), date_time=(2026, 6, 27, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    z.writestr(info, (ROOT / rel).read_bytes())


def main() -> int:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in INCLUDE_FILES:
            write(z, Path(f))
        for d in INCLUDE_DIRS:
            for p in sorted((ROOT / d).rglob('*')):
                if should_include(p):
                    write(z, p.relative_to(ROOT))
    print(OUT, OUT.stat().st_size)
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
