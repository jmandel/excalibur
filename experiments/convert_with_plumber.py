#!/usr/bin/env python3
"""Run calibre's Plumber conversion path with default option recommendations."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Install source-tree shims before importing calibre.
import calibre_bootstrap  # noqa: F401

from calibre.ebooks.conversion.plumber import Plumber
from calibre.utils.logging import Log
from calibre.customize.conversion import OptionRecommendation


def option_recommendations_from_mapping(options: dict) -> list[tuple[str, object, int]]:
    return [(name, value, OptionRecommendation.HIGH) for name, value in options.items()]


def convert(input_path: Path, output_path: Path, options: dict | None = None, extra_recs=()) -> int:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    log = Log()
    plumber = Plumber(str(input_path), str(output_path), log)
    recs = []
    if options:
        recs.extend(option_recommendations_from_mapping(options))
    recs.extend(extra_recs or ())
    if recs:
        plumber.merge_ui_recommendations(recs)
    plumber.run()
    print(output_path)
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--options-json", help="JSON object of calibre option_name -> value overrides")
    ap.add_argument("--options-file", help="Path to JSON object of calibre option_name -> value overrides")
    ns = ap.parse_args(argv)
    options = {}
    if ns.options_json:
        options.update(json.loads(ns.options_json))
    if ns.options_file:
        options.update(json.loads(Path(ns.options_file).read_text()))
    return convert(Path(ns.input).resolve(), Path(ns.output).resolve(), options=options)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
