#!/usr/bin/env python3
"""Run calibre's Plumber conversion path with default option recommendations."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Install source-tree shims before importing calibre.
import calibre_bootstrap  # noqa: F401

from calibre.ebooks.conversion.plumber import Plumber
from calibre.utils.logging import Log
from calibre.customize.conversion import OptionRecommendation


def convert(input_path: Path, output_path: Path, extra_recs=()) -> int:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    log = Log()
    plumber = Plumber(str(input_path), str(output_path), log)
    if extra_recs:
        plumber.merge_ui_recommendations(extra_recs)
    plumber.run()
    print(output_path)
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ns = ap.parse_args(argv)
    return convert(Path(ns.input).resolve(), Path(ns.output).resolve())


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
