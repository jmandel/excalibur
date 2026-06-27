#!/usr/bin/env python3
from __future__ import annotations

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

if __name__ == '__main__':
    raise SystemExit(subprocess.call([
        'python3', str(ROOT / 'scripts/build_runtime_artifacts.py'), '--android-precompile',
    ]))
