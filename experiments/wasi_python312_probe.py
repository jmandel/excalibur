#!/usr/bin/env python3
from __future__ import annotations
import shutil, subprocess, textwrap, urllib.request, tarfile, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'experiments/wasi-python312-spike'
SANDBOX=WORK/'sandbox'
PY_TGZ=WORK/'python-3.12.0-wasi-sdk-20.0.tar.gz'
PY_ROOT=WORK/'python-3.12.0'
PY_URL='https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/python/3.12.0%2B20231211-040d5a6/python-3.12.0-wasi-sdk-20.0.tar.gz'
WASMTIME=ROOT/'experiments/wasi-python-spike/wasmtime/wasmtime-v18.0.4-x86_64-linux/wasmtime'
PURE_WHEELS=[
 ROOT/'node_modules/pyodide/beautifulsoup4-4.13.3-py3-none-any.whl',
 ROOT/'node_modules/pyodide/html5lib-1.1-py2.py3-none-any.whl',
 ROOT/'node_modules/pyodide/python_dateutil-2.9.0.post0-py2.py3-none-any.whl',
 ROOT/'node_modules/pyodide/six-1.17.0-py2.py3-none-any.whl',
 ROOT/'node_modules/pyodide/soupsieve-2.6-py3-none-any.whl',
 ROOT/'node_modules/pyodide/typing_extensions-4.14.1-py3-none-any.whl',
 ROOT/'node_modules/pyodide/tzdata-2025.2-py2.py3-none-any.whl',
 ROOT/'node_modules/pyodide/webencodings-0.5.1-py2.py3-none-any.whl',
 ROOT/'android-app/app/src/main/assets/app/wheels/css_parser-1.1.1-py3-none-any.whl',
 ROOT/'android-app/app/src/main/assets/app/wheels/chardet-5.2.0-py3-none-any.whl',
 ROOT/'android-app/app/src/main/assets/app/wheels/tzlocal-5.4.3-py3-none-any.whl',
]

def run(cmd, check=False):
 print('+',' '.join(map(str,cmd)))
 cp=subprocess.run(list(map(str,cmd)), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=check)
 print(cp.stdout)
 return cp

def ensure():
 WORK.mkdir(parents=True,exist_ok=True)
 if not PY_TGZ.exists(): urllib.request.urlretrieve(PY_URL,PY_TGZ)
 if not (PY_ROOT/'bin/python-3.12.0.wasm').exists():
  PY_ROOT.mkdir(parents=True,exist_ok=True)
  with tarfile.open(PY_TGZ) as t: t.extractall(PY_ROOT)
 if not WASMTIME.exists(): subprocess.run(['python3', str(ROOT/'experiments/wasi_python_probe.py')], check=False)

def prep():
 if SANDBOX.exists(): shutil.rmtree(SANDBOX)
 (SANDBOX/'app').mkdir(parents=True); (SANDBOX/'work').mkdir()
 with zipfile.ZipFile(ROOT/'web/calibre-runtime.zip') as z: z.extractall(SANDBOX/'app')
 # Use the working tree's latest shim/browser entrypoint while iterating.
 shutil.copy(ROOT/'experiments/wasi_runtime_shims.py', SANDBOX/'app/experiments/wasi_runtime_shims.py')
 shutil.copy(ROOT/'experiments/browser_convert.py', SANDBOX/'app/experiments/browser_convert.py')
 with zipfile.ZipFile(SANDBOX/'work/flat.epub', 'w') as z:
  z.writestr('mimetype','application/epub+zip', compress_type=zipfile.ZIP_STORED)
  z.writestr('META-INF/container.xml','''<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>''')
  z.writestr('content.opf','''<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:flat</dc:identifier><dc:title>Flat Minimal</dc:title><dc:creator>Test</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/><item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch1"/><itemref idref="ch2"/></spine></package>''')
  z.writestr('chapter1.xhtml','''<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><h1>One</h1><p>Hello.</p></body></html>''')
  z.writestr('chapter2.xhtml','''<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Two</title></head><body><h1>Two</h1><p>World.</p></body></html>''')
 sp=PY_ROOT/'usr/local/lib/python3.12/site-packages'; sp.mkdir(parents=True, exist_ok=True)
 for whl in PURE_WHEELS:
  if whl.exists():
   with zipfile.ZipFile(whl) as z: z.extractall(sp)
 (SANDBOX/'probe.py').write_text(textwrap.dedent('''
 import sys, traceback
 sys.path.insert(0,'/app/experiments')
 sys.path.append('/opt/python/usr/local/lib/python3.12/site-packages')
 print('python', sys.version, sys.platform)
 for m in ['zlib','bz2','zipfile','lxml','PIL','regex','bs4','html5lib','msgpack','dateutil']:
  try: __import__(m); print('import ok',m)
  except Exception as e: print('import fail',m,type(e).__name__,e)
 try:
  import browser_convert
  print('browser_convert ok')
  print(browser_convert.convert_file('/work/flat.epub','/work/flat.azw3', {'output_profile':'kindle_pw3'}))
 except Exception:
  print('convert/import failed'); traceback.print_exc()
 '''))

def main():
 ensure(); prep()
 cp=run([WASMTIME,'run','--dir',f'{SANDBOX}::/','--dir',f'{PY_ROOT}::/opt/python','--env','PYTHONHOME=/opt/python','--env','PYTHONPATH=/opt/python/usr/local/lib/python3.12:/opt/python/usr/local/lib/python312.zip:/opt/python/usr/local/lib/python3.12/site-packages:/app/experiments',PY_ROOT/'bin/python-3.12.0.wasm','/probe.py'])
 (WORK/'last-output.txt').write_text(cp.stdout)
 return cp.returncode
if __name__=='__main__': raise SystemExit(main())
