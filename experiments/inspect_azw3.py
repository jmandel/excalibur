#!/usr/bin/env python3
"""Small structural sanity checker for PalmDB/MOBI/KF8/AZW3 files."""
from __future__ import annotations

import argparse
import struct
from pathlib import Path


def inspect(path: Path) -> dict[str, int | str | bool]:
    data = path.read_bytes()
    if len(data) < 78:
        raise ValueError("too small")
    title = data[:32].rstrip(b"\0").decode("ascii", "replace")
    dbtype = data[60:68]
    nrecords = struct.unpack_from(">H", data, 76)[0]
    record0_offset = struct.unpack_from(">I", data, 78)[0]
    rec0 = data[record0_offset:]
    mobi = rec0.find(b"MOBI")
    exth = rec0.find(b"EXTH")
    if dbtype != b"BOOKMOBI":
        raise ValueError(f"not BOOKMOBI: {dbtype!r}")
    if mobi < 0:
        raise ValueError("missing MOBI header")
    if exth < 0:
        raise ValueError("missing EXTH header")
    # Offsets in calibre's MOBIHeader.DEFINITION are from the start of record 0;
    # the MOBI magic itself is at offset 16 within record 0.
    version = struct.unpack_from(">L", rec0, 36)[0]
    first_non_text = struct.unpack_from(">L", rec0, 80)[0]
    return {
        "file": str(path),
        "size": len(data),
        "title": title,
        "records": nrecords,
        "record0_offset": record0_offset,
        "mobi_header_offset_in_record0": mobi,
        "exth_offset_in_record0": exth,
        "mobi_version": version,
        "first_non_text_record": first_non_text,
        "is_kf8": version == 8,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ns = ap.parse_args()
    for f in ns.files:
        info = inspect(Path(f))
        print("OK", info)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
