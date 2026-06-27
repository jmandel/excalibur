#!/usr/bin/env python3
"""Convert generated fixtures through calibre Plumber and validate AZW3 structure."""
from __future__ import annotations

from pathlib import Path
import shutil

from convert_with_plumber import convert
from inspect_azw3 import inspect
from check_profiles import main as check_profiles_main

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "fixtures" / "generated"
OUT = ROOT / "experiments" / "out"


def main() -> int:
    check_profiles_main()
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True, exist_ok=True)
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

        mobi = OUT / f"{epub.stem}.mobi"
        print(f"CONVERT EPUB {epub.name} -> {mobi.name} (legacy MOBI fixture)")
        convert(epub, mobi)
        mobi_to_azw3 = OUT / f"{epub.stem}-mobi-input.azw3"
        print(f"CONVERT MOBI {mobi.name} -> {mobi_to_azw3.name}")
        convert(mobi, mobi_to_azw3)
        info = inspect(mobi_to_azw3)
        assert info["is_kf8"], info
        assert info["records"] > 1, info
        print(f"VALID {mobi_to_azw3.name}: records={info['records']} size={info['size']}")

    profile_out = OUT / "minimal-kindle-pw3-options.azw3"
    print(f"CONVERT OPTIONS minimal.epub -> {profile_out.name}")
    convert(FIXTURES / "minimal.epub", profile_out, options={
        'output_profile': 'kindle_pw3',
        'margin_left': 0,
        'margin_right': 0,
        'base_font_size': 14,
        'dont_compress': True,
    })
    info = inspect(profile_out)
    assert info["is_kf8"], info
    print(f"VALID {profile_out.name}: records={info['records']} size={info['size']}")

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
