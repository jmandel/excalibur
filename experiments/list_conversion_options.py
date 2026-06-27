#!/usr/bin/env python3
"""List calibre Plumber options exposed by the reduced registry for a conversion pair."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import calibre_bootstrap  # noqa: F401
from calibre.ebooks.conversion.plumber import Plumber
from calibre.utils.logging import Log


def recs(group):
    rows = []
    for rec in sorted(group, key=lambda r: r.option.name):
        opt = rec.option
        rows.append({
            'name': opt.name,
            'recommended_value': rec.recommended_value,
            'level': rec.level,
            'choices': getattr(opt, 'choices', None),
            'type': getattr(opt, 'type', None),
            'help': getattr(opt, 'help', None),
        })
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--input', default='dummy.epub')
    ap.add_argument('--output', default='dummy.azw3')
    ap.add_argument('--json', action='store_true')
    ns = ap.parse_args()
    p = Plumber(ns.input, ns.output, Log())
    data = {
        'input': ns.input,
        'output': ns.output,
        'input_format': p.input_fmt,
        'output_format': p.output_fmt,
        'input_options': recs(p.input_options),
        'pipeline_options': recs(p.pipeline_options),
        'output_options': recs(p.output_options),
    }
    if ns.json:
        print(json.dumps(data, indent=2, sort_keys=True, default=str))
    else:
        for section in ('input_options', 'pipeline_options', 'output_options'):
            print(f'[{section}]')
            for r in data[section]:
                print(f"{r['name']} = {r['recommended_value']!r}")
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
