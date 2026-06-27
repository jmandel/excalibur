#!/usr/bin/env python3
"""Translate legacy WebAssembly EH opcodes to standard exnref EH.

The WASI SDK SJLJ/setjmp path used by our static CPython build emits legacy
phase-3 exception-handling opcodes. Browser V8 accepts that artifact, but
Chicory 1.7.5 rejects it during parse. Recent Binaryen can post-process those
legacy instructions to the newer exnref/try_table form.

Usage:
  BINARYEN_WASM_OPT=/path/to/wasm-opt \
    python3 scripts/static_wasi/translate_wasm_eh_to_exnref.py \
      path/to/python.wasm path/to/python-exnref.wasm
"""
from __future__ import annotations
import os, shutil, subprocess, sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        print('usage: translate_wasm_eh_to_exnref.py INPUT.wasm OUTPUT.wasm', file=sys.stderr)
        return 2
    src, dst = map(Path, sys.argv[1:])
    wasm_opt = os.environ.get('BINARYEN_WASM_OPT') or shutil.which('wasm-opt')
    if not wasm_opt:
        raise SystemExit('wasm-opt not found; install Binaryen >= 130 or set BINARYEN_WASM_OPT')
    version = subprocess.run([wasm_opt, '--version'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout.strip()
    print(version)
    help_text = subprocess.run([wasm_opt, '--help'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout
    if '--translate-to-exnref' not in help_text:
        raise SystemExit(f'{wasm_opt} does not support --translate-to-exnref; need recent Binaryen (tested with version_130)')
    dst.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([wasm_opt, str(src), '--translate-to-exnref', '-o', str(dst)], check=True)
    print(f'{src} -> {dst}')
    print(f'input={src.stat().st_size} output={dst.stat().st_size}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
