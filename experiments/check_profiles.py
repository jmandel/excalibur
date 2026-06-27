#!/usr/bin/env python3
"""Verify required calibre device profiles are exposed by the reduced registry."""
from __future__ import annotations

import argparse

import calibre_bootstrap  # noqa
from calibre.customize.ui import output_profiles

REQUIRED_KINDLE_OUTPUT_PROFILES = {
    'kindle',
    'kindle_dx',
    'kindle_fire',
    'kindle_oasis',
    'kindle_pw',
    'kindle_pw3',
    'kindle_scribe',
    'kindle_voyage',
}


def main() -> int:
    profiles = {p.short_name: p for p in output_profiles()}
    missing = sorted(REQUIRED_KINDLE_OUTPUT_PROFILES - set(profiles))
    if missing:
        raise SystemExit(f"Missing Kindle output profiles: {missing}")
    print('KINDLE_PROFILES_OK')
    for name in sorted(REQUIRED_KINDLE_OUTPUT_PROFILES):
        p = profiles[name]
        print(f"{p.short_name}\t{p.name}\tscreen={p.screen_size}\tdpi={p.dpi}\tfbase={p.fbase}")
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
