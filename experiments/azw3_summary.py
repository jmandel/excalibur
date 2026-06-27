#!/usr/bin/env python3
"""Emit stable-ish structural summaries for AZW3/MOBI files."""
from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path


def palm_records(data: bytes):
    n = struct.unpack_from('>H', data, 76)[0]
    offsets = [struct.unpack_from('>I', data, 78 + i * 8)[0] for i in range(n)]
    offsets.append(len(data))
    return [data[offsets[i]:offsets[i+1]] for i in range(n)]


def summary(path: Path):
    data = path.read_bytes()
    recs = palm_records(data)
    rec0 = recs[0]
    mobi = rec0.find(b'MOBI')
    if mobi < 0:
        raise ValueError('missing MOBI')
    exth = rec0.find(b'EXTH')
    version = struct.unpack_from('>L', rec0, 36)[0]
    first_non_text = struct.unpack_from('>L', rec0, 80)[0]
    last_text = struct.unpack_from('>H', rec0, 8)[0]
    text_length = struct.unpack_from('>L', rec0, 4)[0]
    compression = struct.unpack_from('>H', rec0, 0)[0]
    first_resource = struct.unpack_from('>L', rec0, 108)[0]
    fdst_record = struct.unpack_from('>L', rec0, 192)[0]
    fdst_count = struct.unpack_from('>L', rec0, 196)[0]
    ncx_index = struct.unpack_from('>L', rec0, 244)[0]
    chunk_index = struct.unpack_from('>L', rec0, 248)[0]
    skel_index = struct.unpack_from('>L', rec0, 252)[0]
    guide_index = struct.unpack_from('>L', rec0, 260)[0]
    rec_prefix_counts = {}
    for rec in recs[1:]:
        key = rec[:4].decode('ascii', 'replace') if len(rec) >= 4 else ''
        rec_prefix_counts[key] = rec_prefix_counts.get(key, 0) + 1
    return {
        'file': str(path),
        'size': len(data),
        'title': data[:32].rstrip(b'\0').decode('ascii', 'replace'),
        'records': len(recs),
        'mobi_version': version,
        'compression': compression,
        'text_length': text_length,
        'last_text_record': last_text,
        'first_non_text_record': first_non_text,
        'first_resource_record': first_resource,
        'fdst_record': fdst_record,
        'fdst_count': fdst_count,
        'ncx_index': ncx_index,
        'chunk_index': chunk_index,
        'skel_index': skel_index,
        'guide_index': guide_index,
        'has_exth': exth >= 0,
        'record_prefix_counts': rec_prefix_counts,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+')
    ap.add_argument('--json', action='store_true')
    ns = ap.parse_args()
    rows = [summary(Path(f)) for f in ns.files]
    if ns.json:
        print(json.dumps(rows, indent=2, sort_keys=True))
    else:
        for r in rows:
            print(json.dumps(r, sort_keys=True))
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
