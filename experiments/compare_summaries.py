#!/usr/bin/env python3
"""Compare structural AZW3 summaries between two output directories."""
from __future__ import annotations

import argparse
from pathlib import Path
from azw3_summary import summary

KEYS = [
    'title', 'mobi_version', 'compression', 'records', 'last_text_record',
    'first_non_text_record', 'fdst_count', 'has_exth', 'record_prefix_counts'
]


def normalize(row: dict) -> dict:
    row = dict(row)
    # calibre sometimes inserts/removes a zero padding record depending on host
    # details. It shifts first_non_text_record and record count but does not
    # change semantic KF8 records. Normalize that for native-vs-Pyodide checks.
    prefixes = dict(row['record_prefix_counts'])
    pad = prefixes.pop('', 0)
    if pad:
        row['records'] -= pad
        row['first_non_text_record'] -= pad
    row['record_prefix_counts'] = prefixes
    return row


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('left_dir')
    ap.add_argument('right_dir')
    ns = ap.parse_args()
    left = Path(ns.left_dir)
    right = Path(ns.right_dir)
    failures = 0
    for lf in sorted(left.glob('*.azw3')):
        rf = right / lf.name
        if not rf.exists():
            print('MISS', rf)
            failures += 1
            continue
        a, b = normalize(summary(lf)), normalize(summary(rf))
        diffs = {k: (a[k], b[k]) for k in KEYS if a[k] != b[k]}
        if diffs:
            print('DIFF', lf.name, diffs)
        else:
            print('MATCH', lf.name)
    return 1 if failures else 0

if __name__ == '__main__':
    raise SystemExit(main())
