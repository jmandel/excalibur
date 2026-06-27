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
 import zipfile as _zf
 with _zf.ZipFile(SANDBOX/'work/flat.epub', 'w') as z:
  z.writestr('mimetype','application/epub+zip', compress_type=_zf.ZIP_STORED)
  z.writestr('META-INF/container.xml','''<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>''')
  z.writestr('content.opf','''<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:flat</dc:identifier><dc:title>Flat Minimal</dc:title><dc:creator>Test</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/><item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch1"/><itemref idref="ch2"/></spine></package>''')
  z.writestr('chapter1.xhtml','''<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><h1>One</h1><p>Hello.</p></body></html>''')
  z.writestr('chapter2.xhtml','''<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Two</title></head><body><h1>Two</h1><p>World.</p></body></html>''')
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

 # multiprocessing is absent in WASI; enough shell for modules that import but do not fork in this path.
 if 'multiprocessing' not in sys.modules:
  mp=types.ModuleType('multiprocessing'); mp.Pipe=lambda *a,**k: (_ for _ in ()).throw(NotImplementedError('multiprocessing.Pipe unavailable under WASI'))
  mpc=types.ModuleType('multiprocessing.connection'); mpc.Connection=type('Connection',(),{}); mpc.PipeConnection=mpc.Connection
  sys.modules['multiprocessing']=mp; sys.modules['multiprocessing.connection']=mpc

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
    def __init__(self, e, parent=None): self._e=e; self._parent=parent
    def __getattr__(self, n): return getattr(self._e, n)
    def __iter__(self): return iter([_wrap(x, self) for x in list(self._e)])
    def __len__(self): return len(self._e)
    def __getitem__(self, i): return _wrap(self._e[i], self)
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
    def iterdescendants(self,*a,**k): return (_wrap(x) for x in list(self._e.iter(*a,**k))[1:])
    def getparent(self): return self._parent
    def getroottree(self): return _TreeProxy(ET.ElementTree(self._e))
    def findall(self,*a,**k): return [_wrap(x) for x in self._e.findall(*a,**k)]
    def find(self,*a,**k):
     r=self._e.find(*a,**k); return _wrap(r) if r is not None else None
    def xpath(self, expr, *a, **k):
     import re
     nodes=list(self._e.iter())
     direct=list(self._e) if expr.startswith('./') else nodes
     if expr.startswith('//*[@') and '/@' not in expr:
      attrs=[]
      inside=expr.split('[@',1)[1].split(']',1)[0]
      for part in inside.replace(' or ', '|').split('|'):
       attrs.append(part.split('=')[0].strip().lstrip('@'))
      return [_wrap(x) for x in nodes if any(x.get(a) is not None for a in attrs)]
     if expr.startswith('//') and '[' not in expr and '/@' not in expr:
      target=expr[2:].split(':')[-1].lower(); return [_wrap(x) for x in nodes if str(x.tag).split('}')[-1].lower()==target]
     if expr in ('o2:manifest','o2:spine','o2:tours','o2:guide'):
      target=expr.split(':')[-1]; return [_wrap(x) for x in nodes if str(x.tag).split('}')[-1].lower()==target]
     if expr in ('o2:metadata//*','o2:metadata//o2:meta'):
      metas=[x for x in nodes if str(x.tag).split('}')[-1].lower()=='metadata']
      out=[]
      for m in metas:
       for c in m.iter():
        if c is not m and (expr.endswith('*') or str(c.tag).split('}')[-1].lower()=='meta'): out.append(_wrap(c))
      return out
     if expr.startswith('/o2:package/o2:manifest/o2:item'):
      return [_wrap(c) for m in nodes if str(m.tag).split('}')[-1].lower()=='manifest' for c in list(m) if str(c.tag).split('}')[-1].lower()=='item']
     if expr.startswith('/o2:package/o2:spine/o2:itemref'):
      return [_wrap(c) for m in nodes if str(m.tag).split('}')[-1].lower()=='spine' for c in list(m) if str(c.tag).split('}')[-1].lower()=='itemref']
     if expr.startswith('/o2:package/o2:guide/o2:reference'):
      return [_wrap(c) for m in nodes if str(m.tag).split('}')[-1].lower()=='guide' for c in list(m) if str(c.tag).split('}')[-1].lower()=='reference']
     if 're:match(name' in expr:
      low=expr.lower()
      def lname(x): return str(x.tag).split('}')[-1].lower()
      if 'metadata' in low and 'item' not in low and 'itemref' not in low: return [_wrap(x) for x in nodes if lname(x)=='metadata']
      if 'manifest' in low and 'item' in low:
       return [_wrap(c) for x in nodes if lname(x)=='manifest' for c in list(x) if lname(c)=='item']
      if 'spine' in low and 'itemref' in low:
       return [_wrap(c) for x in nodes if lname(x)=='spine' for c in list(x) if lname(c)=='itemref']
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
   class _TreeProxy:
    def __init__(self, t): self._t=t
    def getroot(self): return _wrap(self._t.getroot())
    def write(self, *a, **k): return self._t.write(*a, **k)
    def xpath(self, expr, *a, **k): return self.getroot().xpath(expr, *a, **k)
    def __getattr__(self, n): return getattr(self._t, n)
   def _wrap(x, parent=None): return x if x is None or hasattr(x, '_e') else _ElementProxy(x, parent)
   etree.Entity=type('Entity',(),{})
   etree._wrap=_wrap
   etree.Element=lambda tag,*a,**k: _wrap(ET.Element(tag, k.get('attrib') or (a[0] if a and isinstance(a[0], dict) else {}))); etree.SubElement=lambda parent,tag,*a,**k: _wrap(ET.SubElement(_unwrap(parent), tag, k.get('attrib') or (a[0] if a and isinstance(a[0], dict) else {}))); etree.Comment=ET.Comment; etree.ProcessingInstruction=ET.ProcessingInstruction; etree.QName=ET.QName
   etree.XMLParser=_Parser; etree.HTMLParser=_Parser; etree.XMLSyntaxError=XMLSyntaxError; etree.ParserError=ParserError; etree.XPathEvalError=XPathEvalError; etree.Resolver=type('Resolver',(),{})
   etree.FunctionNamespace=lambda *a,**k: type('FunctionNamespace',(dict,),{})()
   etree.iselement=lambda x: hasattr(x, '_e') or hasattr(x, 'tag')
   etree.fromstring=lambda data,*a,**k: _wrap(ET.fromstring(_bytes(data)))
   etree.XML=etree.fromstring
   etree.tostring=lambda el,*a,**k: ET.tostring(_unwrap(el), encoding=('unicode' if k.get('encoding') is str else k.get('encoding','unicode' if k.get('method')=='text' else 'utf-8')))
   etree.parse=lambda f,*a,**k: _TreeProxy(ET.parse(f))
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
   pil=types.ModuleType('PIL'); image=types.ModuleType('PIL.Image'); image.open=lambda *a,**k: (_ for _ in ()).throw(NotImplementedError('PIL.Image.open')); imageops=types.ModuleType('PIL.ImageOps'); imageops.expand=lambda img,*a,**k: img; imageops.fit=lambda img,*a,**k: img; imageops.contain=lambda img,*a,**k: img; imageops.pad=lambda img,*a,**k: img; pil.Image=image; pil.ImageOps=imageops; sys.modules['PIL']=pil; sys.modules['PIL.Image']=image; sys.modules['PIL.ImageOps']=imageops
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
  import calibre.ebooks.metadata.opf2 as _opf2
  def _lname(x): return str(getattr(x,'tag','')).split('}')[-1].lower()
  def _desc(root, name): return [x for x in root.iter() if _lname(x)==name]
  _opf2.OPF.metadata_path = staticmethod(lambda root: _desc(root,'metadata'))
  _opf2.OPF.manifest_path = staticmethod(lambda root: [c for m in _desc(root,'manifest') for c in list(m) if _lname(c)=='item'])
  _opf2.OPF.manifest_ppath = staticmethod(lambda root: _desc(root,'manifest'))
  _opf2.OPF.spine_path = staticmethod(lambda root: [c for m in _desc(root,'spine') for c in list(m) if _lname(c)=='itemref'])
  _opf2.OPF.guide_path = staticmethod(lambda root: [c for m in _desc(root,'guide') for c in list(m) if _lname(c)=='reference'])
  _opf2.OPF.epub3_nav = property(lambda self: None)
  import calibre.ebooks.oeb.base as _oeb_base
  import lxml.etree as _etree
  def _simple_parse_xhtml(self, data):
   if not isinstance(data, str): data=self.oeb.decode(data)
   return _etree.fromstring(data.encode('utf-8') if isinstance(data,str) else data)
  _oeb_base.Manifest.Item._parse_xhtml = _simple_parse_xhtml
  import calibre.ebooks.mobi.writer8.main as _w8main
  def _simple_aid(self):
   for i,item in enumerate(self.oeb.spine):
    root=self.data(item)
    if not hasattr(root,'iterdescendants') and hasattr(_etree,'_wrap'): root=_etree._wrap(root); self._data_cache[item.href]=root
    j=0
    for tag in root.iterdescendants():
     j+=1; tag.set('aid', f'{i}-{j}')
  _w8main.KF8Writer.insert_aid_attributes = _simple_aid
  import calibre.ebooks.mobi.writer8.skeleton as _skel
  def _patched_remove_namespaces(self, root):
   raw = _etree._unwrap(root) if hasattr(_etree, '_unwrap') else getattr(root, '_e', root)
   def clone(src):
    tag = str(src.tag).rpartition('}')[-1] if src.tag else 'div'
    if tag in ('html','body','head','title','h1','h2','h3','p','div','span','a','br','img','ol','ul','li'):
     pass
    e = _etree.Element(tag, attrib={str(k).rpartition('}')[-1]:str(v) for k,v in getattr(src,'attrib',{}).items()})
    e.text = getattr(src,'text',None); e.tail = getattr(src,'tail',None)
    for c in list(src): e.append(clone(c))
    return e
   return clone(raw)
  _skel.Chunker.remove_namespaces = _patched_remove_namespaces
  import calibre.ebooks.oeb.reader as _reader
  def _patched_spine_from_opf(self, opf):
   for elem in _opf2.OPF.spine_path(opf):
    idref=elem.get('idref')
    item=self.oeb.manifest.ids.get(idref)
    if item is not None:
     self.oeb.spine.add(item, elem.get('linear'))
   if len(self.oeb.spine) == 0:
    raise Exception('patched spine still empty')
  _reader.OEBReader._spine_from_opf = _patched_spine_from_opf
  print(browser_convert.convert_file('/work/flat.epub','/work/flat.azw3', {'output_profile':'kindle_pw3'}))
 except Exception: print('convert/import failed'); traceback.print_exc()
 '''))

def main():
 ensure(); prep()
 cp=run([WASMTIME,'run','--dir',f'{SANDBOX}::/','--dir',f'{PY_ROOT}::/opt/python','--env','PYTHONHOME=/opt/python','--env','PYTHONPATH=/opt/python/usr/local/lib/python3.12:/opt/python/usr/local/lib/python312.zip:/opt/python/usr/local/lib/python3.12/site-packages:/app/experiments',PY_ROOT/'bin/python-3.12.0.wasm','/probe.py'])
 (WORK/'last-output.txt').write_text(cp.stdout)
 return cp.returncode
if __name__=='__main__': raise SystemExit(main())
