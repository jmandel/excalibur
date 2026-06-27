#!/usr/bin/env python3
"""Convert generated fixtures through calibre Plumber and validate AZW3 structure."""
from __future__ import annotations

from pathlib import Path

from convert_with_plumber import convert
from inspect_azw3 import inspect

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "fixtures" / "generated"
OUT = ROOT / "experiments" / "out"


def main() -> int:
    epubs = sorted(FIXTURES.glob("*.epub"))
    if not epubs:
        raise SystemExit("No EPUB fixtures found")
    generated = []
    for epub in epubs:
        out = OUT / f"{epub.stem}.azw3"
        print(f"CONVERT EPUB {epub.name} -> {out.name}")
        convert(epub, out)
        info = inspect(out)
        assert info["is_kf8"], info
        assert info["records"] > 1, info
        print(f"VALID {out.name}: records={info['records']} size={info['size']}")
        generated.append(out)

    for azw3 in generated:
        out = OUT / f"{azw3.stem}-roundtrip.azw3"
        print(f"CONVERT AZW3 {azw3.name} -> {out.name}")
        convert(azw3, out)
        info = inspect(out)
        assert info["is_kf8"], info
        assert info["records"] > 1, info
        print(f"VALID {out.name}: records={info['records']} size={info['size']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
