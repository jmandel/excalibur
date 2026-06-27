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
 if not WASMTIME.exists():
  subprocess.run(['python3', str(ROOT/'experiments/wasi_python_probe.py')], check=False)

def prep():
 if SANDBOX.exists(): shutil.rmtree(SANDBOX)
 (SANDBOX/'app').mkdir(parents=True)
 (SANDBOX/'work').mkdir()
 with zipfile.ZipFile(ROOT/'web/calibre-runtime.zip') as z: z.extractall(SANDBOX/'app')
 shutil.copy(ROOT/'fixtures/generated/minimal.epub', SANDBOX/'work/minimal.epub')
 sp=PY_ROOT/'usr/local/lib/python3.12/site-packages'
 sp.mkdir(parents=True, exist_ok=True)
 for whl in PURE_WHEELS:
  if whl.exists():
   with zipfile.ZipFile(whl) as z: z.extractall(sp)
 (SANDBOX/'probe.py').write_text(textwrap.dedent('''
 import sys, types, traceback
 sys.path.insert(0,'/app/experiments')
 sys.path.append('/opt/python/usr/local/lib/python3.12/site-packages')
 print('python', sys.version, sys.platform)
 if not hasattr(__import__('os'), 'umask'):
  import os; os.umask=lambda mask: 0o22

 # WASI runtime has no threads module in this build; provide synchronous executor for imports/basic zip use.
 import concurrent.futures as _cf
 try:
  _cf.ThreadPoolExecutor
  _need_tpe=False
 except Exception:
  _need_tpe=True
 if _need_tpe:
  class _Future:
   def __init__(self, value=None, exc=None): self._value=value; self._exc=exc
   def result(self, timeout=None):
    if self._exc: raise self._exc
    return self._value
  class ThreadPoolExecutor:
   def __init__(self, *a, **k): pass
   def __enter__(self): return self
   def __exit__(self, *a): self.shutdown(); return False
   def submit(self, fn, *a, **k):
    try: return _Future(fn(*a, **k))
    except BaseException as e: return _Future(exc=e)
   def map(self, fn, *iterables, timeout=None, chunksize=1):
    return map(fn, *iterables)
   def shutdown(self, wait=True, cancel_futures=False): pass
  _cf.ThreadPoolExecutor=ThreadPoolExecutor
  _thread=types.ModuleType('concurrent.futures.thread'); _thread.ThreadPoolExecutor=ThreadPoolExecutor; sys.modules['concurrent.futures.thread']=_thread

 def install_experiment_stubs():
  import re as _re, xml.etree.ElementTree as ET
  # regex fallback: enough for import probing, not full regex semantics.
  sys.modules.setdefault('regex', _re)
  # msgpack fallback: JSON bytes, enough to see if imports progress.
  if 'msgpack' not in sys.modules:
   import json
   mp=types.ModuleType('msgpack'); mp.dumps=lambda o,*a,**k: json.dumps(o).encode(); mp.loads=lambda b,*a,**k: json.loads(bytes(b).decode()); mp.packb=mp.dumps; mp.unpackb=mp.loads; sys.modules['msgpack']=mp
  # Tiny lxml facade backed by ElementTree. This is intentionally incomplete; it measures how far calibre gets without native lxml.
  if 'lxml' not in sys.modules:
   lxml=types.ModuleType('lxml'); lxml.__path__=[]
   etree=types.ModuleType('lxml.etree')
   class XMLSyntaxError(Exception): pass
   class ParserError(Exception): pass
   class XPathEvalError(Exception): pass
   class _Resolvers:
    def add(self, *a, **k): pass
   class _Parser:
    def __init__(self,*a,**k): self.resolvers=_Resolvers()
   def _bytes(x, encoding='utf-8'):
    return x if isinstance(x,(bytes,bytearray)) else str(x).encode(encoding if encoding != 'unicode' else 'utf-8')
   def _unwrap(x): return x._e if hasattr(x, '_e') else x
   class _ElementProxy:
    def __init__(self, e): self._e=e
    def __getattr__(self, n): return getattr(self._e, n)
    def __iter__(self): return iter([_wrap(x) for x in list(self._e)])
    def __len__(self): return len(self._e)
    def __getitem__(self, i): return _wrap(self._e[i])
    @property
    def tag(self): return self._e.tag
    @tag.setter
    def tag(self, v): self._e.tag=v
    @property
    def text(self): return self._e.text
    @text.setter
    def text(self, v): self._e.text=v
    @property
    def tail(self): return self._e.tail
    @tail.setter
    def tail(self, v): self._e.tail=v
    @property
    def attrib(self): return self._e.attrib
    def get(self,*a,**k): return self._e.get(*a,**k)
    def set(self,*a,**k): return self._e.set(*a,**k)
    def append(self, x): return self._e.append(_unwrap(x))
    def insert(self, i, x): return self._e.insert(i, _unwrap(x))
    def remove(self, x): return self._e.remove(_unwrap(x))
    def makeelement(self, tag, attrib=None): return _wrap(ET.Element(tag, attrib or {}))
    def iter(self,*a,**k): return (_wrap(x) for x in self._e.iter(*a,**k))
    def findall(self,*a,**k): return [_wrap(x) for x in self._e.findall(*a,**k)]
    def find(self,*a,**k):
     r=self._e.find(*a,**k); return _wrap(r) if r is not None else None
    def xpath(self, expr, *a, **k):
     import re
     nodes=list(self._e.iter())
     direct=list(self._e) if expr.startswith('./') else nodes
     if 're:match(name()' in expr:
      low=expr.lower()
      def lname(x): return str(x.tag).split('}')[-1].lower()
      if 'metadata' in low and 'item' not in low and 'itemref' not in low: return [_wrap(x) for x in nodes if lname(x)=='metadata']
      if 'manifest' in low and 'item' in low: return [_wrap(c) for x in nodes if lname(x)=='manifest' for c in list(x) if lname(c)=='item']
      if 'spine' in low and 'itemref' in low: return [_wrap(c) for x in nodes if lname(x)=='spine' for c in list(x) if lname(c)=='itemref']
      if 'guide' in low: return [_wrap(x) for x in nodes if lname(x)=='guide']
      if 'spine' in low: return [_wrap(x) for x in nodes if lname(x)=='spine']
     if '/@' in expr or expr.endswith('@href]'):
      attr=expr.split('/@')[-1].split(']')[0]
      if attr == 'src' and 'content' in expr:
       return [x.get('src') for x in nodes if str(x.tag).split('}')[-1]=='content' and x.get('src')]
      return [x.get(attr) for x in nodes if x.get(attr) is not None]
     if 'local-name()=' in expr:
      names=re.findall(r'local-name\(\)="([^"]+)"', expr)
      pool=direct if expr.startswith('./') else nodes
      return [_wrap(x) for x in pool if str(x.tag).split('}')[-1] in names]
     if 'name()' in expr and 'meta' in expr:
      return [_wrap(x) for x in nodes if str(x.tag).split('}')[-1].lower()=='meta' and (('@name' not in expr) or x.get('name'))]
     if expr.startswith('//*[@id='):
      wanted=expr.split('=',1)[1].strip().strip('[]').strip(chr(34)).strip(chr(39))
      return [_wrap(x) for x in nodes if x.get('id')==wanted]
     if expr == './*': return [_wrap(x) for x in list(self._e)]
     try: return [_wrap(x) for x in self._e.findall(expr.replace('xhtml:',''))]
     except Exception: return []
   def _wrap(x): return x if x is None or hasattr(x, '_e') else _ElementProxy(x)
   etree.Element=ET.Element; etree.SubElement=ET.SubElement; etree.Comment=ET.Comment; etree.ProcessingInstruction=ET.ProcessingInstruction; etree.QName=ET.QName
   etree.XMLParser=_Parser; etree.HTMLParser=_Parser; etree.XMLSyntaxError=XMLSyntaxError; etree.ParserError=ParserError; etree.XPathEvalError=XPathEvalError; etree.Resolver=type('Resolver',(),{})
   etree.fromstring=lambda data,*a,**k: _wrap(ET.fromstring(_bytes(data)))
   etree.XML=etree.fromstring
   etree.tostring=lambda el,*a,**k: ET.tostring(_unwrap(el), encoding=k.get('encoding','unicode' if k.get('method')=='text' else 'utf-8'))
   etree.parse=lambda f,*a,**k: ET.parse(f)
   etree.iterparse=lambda *a,**k: ET.iterparse(*a,**k)
   etree.register_namespace=ET.register_namespace
   etree.XPath=lambda expr,*a,**k: (lambda node,*aa,**kw: node.xpath(expr,*aa,**kw) if hasattr(node,'xpath') else [])
   html=types.ModuleType('lxml.html')
   html.document_fromstring=lambda data,*a,**k: _wrap(ET.fromstring(_bytes(data)))
   html.fromstring=html.document_fromstring
   html.tostring=etree.tostring
   html.defs=types.SimpleNamespace(link_attrs={'href','src','action','longdesc','usemap','cite','background'}, empty_tags={'br','img','hr','meta','link','input'})
   builder=types.ModuleType('lxml.builder')
   class ElementMaker:
    def __init__(self, *a, **k): self.namespace=k.get('namespace')
    def __getattr__(self, tag):
     return lambda *children, **attrs: _make(tag, children, attrs)
    def __call__(self, tag, *children, **attrs): return _make(tag, children, attrs)
   def _make(tag, children, attrs):
    e=ET.Element(tag, {k.rstrip('_'):str(v) for k,v in attrs.items() if k not in ('nsmap',)})
    for c in children:
     if isinstance(c, str): e.text=(e.text or '')+c
     else: e.append(_unwrap(c))
    return e
   builder.ElementMaker=ElementMaker; builder.E=ElementMaker()
   html_builder=types.ModuleType('lxml.html.builder')
   for tag in 'A BODY BR DD DIV DL DT H1 H2 H3 HEAD HR HTML IMG LI LINK META OL P SPAN STRONG STYLE TABLE TD TITLE TR UL'.split(): setattr(html_builder, tag, getattr(ElementMaker(), tag))
   sys.modules.update({'lxml':lxml,'lxml.etree':etree,'lxml.html':html,'lxml.builder':builder,'lxml.html.builder':html_builder})
   lxml.etree=etree; lxml.html=html; lxml.builder=builder
  # Pillow is optional for the current image stubs; provide importable shell.
  if 'PIL' not in sys.modules:
   pil=types.ModuleType('PIL'); image=types.ModuleType('PIL.Image'); image.open=lambda *a,**k: (_ for _ in ()).throw(NotImplementedError('PIL.Image.open')); sys.modules['PIL']=pil; sys.modules['PIL.Image']=image
 install_experiment_stubs()

 for m in ['zlib','bz2','zipfile','lxml','PIL','regex','bs4','html5lib','msgpack','dateutil']:
  try: __import__(m); print('import ok',m)
  except Exception as e: print('import fail',m,type(e).__name__,e)
 if 'lzma' not in sys.modules:
  mod=types.ModuleType('lzma'); mod.open=lambda *a,**k: (_ for _ in ()).throw(NotImplementedError('lzma')) ; sys.modules['lzma']=mod
 try:
  import calibre_bootstrap
  print('bootstrap ok')
 except Exception: print('bootstrap failed'); traceback.print_exc()
 try:
  import browser_convert
  print('browser_convert ok')
  print(browser_convert.convert_file('/work/minimal.epub','/work/minimal.azw3', {'output_profile':'kindle_pw3'}))
 except Exception: print('convert/import failed'); traceback.print_exc()
 '''))

def main():
 ensure(); prep()
 cp=run([WASMTIME,'run','--dir',f'{SANDBOX}::/','--dir',f'{PY_ROOT}::/opt/python','--env','PYTHONHOME=/opt/python','--env','PYTHONPATH=/opt/python/usr/local/lib/python3.12:/opt/python/usr/local/lib/python312.zip:/opt/python/usr/local/lib/python3.12/site-packages:/app/experiments',PY_ROOT/'bin/python-3.12.0.wasm','/probe.py'])
 (WORK/'last-output.txt').write_text(cp.stdout)
 return cp.returncode
if __name__=='__main__': raise SystemExit(main())
