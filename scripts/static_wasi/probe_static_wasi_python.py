#!/usr/bin/env python3
from __future__ import annotations
import subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'experiments/static-wasi-python'
SRC = WORK / 'Python-3.12.0'
PYWASM = SRC / 'builddir/wasi/python.wasm'
THIRD_PARTY_SITE = WORK / 'third-party-site'
WASMTIME = ROOT / 'experiments/wasi-python-spike/wasmtime/wasmtime-v18.0.4-x86_64-linux/wasmtime'

code = r'''
import sys, importlib.machinery, sysconfig
print('python', sys.version.replace('\n',' '))
print('platform', sys.platform, sysconfig.get_platform())
print('ext_suffixes', importlib.machinery.EXTENSION_SUFFIXES)
for m in ['array','_struct','_pickle','_decimal','pyexpat','_elementtree','_socket','select','zlib','_bz2','_lzma','_regex']:
    try:
        mod = __import__(m)
        print('import ok', m, getattr(mod, '__file__', '<builtin>'))
    except Exception as e:
        print('import fail', m, type(e).__name__, e)
try:
    import regex
    print('regex ok', regex.compile(r'(?i)(a)(b)').findall('ab AB'))
except Exception as e:
    print('regex fail', type(e).__name__, e)
'''

def main():
    if not PYWASM.exists():
        raise SystemExit(f'missing {PYWASM}; run build_static_wasi_python.py first')
    with tempfile.TemporaryDirectory() as td:
        probe = Path(td) / 'probe.py'; probe.write_text(code)
        py_path = '/builddir/wasi/build/lib.wasi-wasm32-3.12:/Lib:/third_party_site'
        cmd = [str(WASMTIME), 'run', '--dir', f'{SRC}::/', '--dir', f'{td}::/work', '--dir', f'{THIRD_PARTY_SITE}::/third_party_site', '--env', f'PYTHONPATH={py_path}', str(PYWASM), '/work/probe.py']
        cp = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(cp.stdout)
        return cp.returncode
if __name__ == '__main__':
    raise SystemExit(main())
