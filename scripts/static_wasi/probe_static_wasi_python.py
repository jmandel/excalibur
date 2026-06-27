#!/usr/bin/env python3
from __future__ import annotations
import subprocess, textwrap, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'experiments/static-wasi-python/Python-3.12.0'
PYWASM = SRC / 'builddir/wasi/python.wasm'
WASMTIME = ROOT / 'experiments/wasi-python-spike/wasmtime/wasmtime-v18.0.4-x86_64-linux/wasmtime'

code = r'''
import sys, importlib.machinery, sysconfig
print('python', sys.version.replace('\n',' '))
print('platform', sys.platform, sysconfig.get_platform())
print('ext_suffixes', importlib.machinery.EXTENSION_SUFFIXES)
for m in ['array','_struct','_pickle','_decimal','pyexpat','_elementtree','_socket','select','zlib','_bz2','_lzma']:
    try:
        mod = __import__(m)
        print('import ok', m, getattr(mod, '__file__', '<builtin>'))
    except Exception as e:
        print('import fail', m, type(e).__name__, e)
'''

def main():
    if not PYWASM.exists():
        raise SystemExit(f'missing {PYWASM}; run build_static_wasi_python.py first')
    with tempfile.TemporaryDirectory() as td:
        probe = Path(td) / 'probe.py'
        probe.write_text(code)
        cmd = [str(WASMTIME), 'run', '--dir', f'{SRC}::/', '--dir', f'{td}::/work', '--env', 'PYTHONPATH=/builddir/wasi/build/lib.wasi-wasm32-3.12:/Lib', str(PYWASM), '/work/probe.py']
        cp = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(cp.stdout)
        return cp.returncode
if __name__ == '__main__':
    raise SystemExit(main())
