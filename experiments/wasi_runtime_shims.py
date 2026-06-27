"""Compatibility shims for running the reduced calibre converter on WASI CPython.

This is intentionally pure Python. It gives WASI builds without dynamic extension
loading enough of the browser/Pyodide dependency surface to exercise the calibre
EPUB -> OEB -> AZW3 path for text-first books.
"""
from __future__ import annotations

import os
import sys
import types


def install_pre_import() -> None:
    """Install modules/APIs needed before importing browser_convert/calibre."""
    if not hasattr(os, 'umask'):
        os.umask = lambda mask: 0o22  # type: ignore[attr-defined]
    _install_multiprocessing_stub()
    _install_threadpool_stub()
    _install_regex_stub()
    _install_msgpack_stub()
    _install_lxml_facade()
    _install_pil_stub()
    if 'lzma' not in sys.modules:
        mod = types.ModuleType('lzma')
        mod.open = lambda *a, **k: (_ for _ in ()).throw(NotImplementedError('lzma unavailable under this WASI runtime'))
        sys.modules['lzma'] = mod


def install_conversion_patches() -> None:
    """Patch lxml-heavy calibre call sites after browser_convert imports."""
    import calibre.ebooks.metadata.opf2 as opf2
    import calibre.ebooks.oeb.base as oeb_base
    import calibre.ebooks.oeb.reader as reader
    import calibre.ebooks.mobi.writer8.main as w8main
    import calibre.ebooks.mobi.writer8.skeleton as skeleton
    import lxml.etree as etree

    def lname(x):
        return str(getattr(x, 'tag', '')).split('}')[-1].lower()

    def desc(root, name):
        return [x for x in root.iter() if lname(x) == name]

    opf2.OPF.metadata_path = staticmethod(lambda root: desc(root, 'metadata'))
    opf2.OPF.manifest_path = staticmethod(lambda root: [c for m in desc(root, 'manifest') for c in list(m) if lname(c) == 'item'])
    opf2.OPF.manifest_ppath = staticmethod(lambda root: desc(root, 'manifest'))
    opf2.OPF.spine_path = staticmethod(lambda root: [c for m in desc(root, 'spine') for c in list(m) if lname(c) == 'itemref'])
    opf2.OPF.guide_path = staticmethod(lambda root: [c for m in desc(root, 'guide') for c in list(m) if lname(c) == 'reference'])
    # Avoid the EPUB3 nav polish path, which pulls multiprocessing/container code.
    opf2.OPF.epub3_nav = property(lambda self: None)

    def simple_parse_xhtml(self, data):
        if not isinstance(data, str):
            data = self.oeb.decode(data)
        return etree.fromstring(data.encode('utf-8') if isinstance(data, str) else data)
    oeb_base.Manifest.Item._parse_xhtml = simple_parse_xhtml

    def simple_aid(self):
        for i, item in enumerate(self.oeb.spine):
            root = self.data(item)
            if not hasattr(root, 'iterdescendants') and hasattr(etree, '_wrap'):
                root = etree._wrap(root)  # type: ignore[attr-defined]
                self._data_cache[item.href] = root
            for j, tag in enumerate(root.iterdescendants(), start=1):
                tag.set('aid', f'{i}-{j}')
    w8main.KF8Writer.insert_aid_attributes = simple_aid

    def patched_remove_namespaces(self, root):
        raw = etree._unwrap(root) if hasattr(etree, '_unwrap') else getattr(root, '_e', root)  # type: ignore[attr-defined]
        def clone(src):
            tag = str(src.tag).rpartition('}')[-1] if getattr(src, 'tag', None) else 'div'
            e = etree.Element(tag, attrib={str(k).rpartition('}')[-1]: str(v) for k, v in getattr(src, 'attrib', {}).items()})
            e.text = getattr(src, 'text', None)
            e.tail = getattr(src, 'tail', None)
            for c in list(src):
                e.append(clone(c))
            return e
        return clone(raw)
    skeleton.Chunker.remove_namespaces = patched_remove_namespaces

    def patched_spine_from_opf(self, opf):
        for elem in opf2.OPF.spine_path(opf):
            item = self.oeb.manifest.ids.get(elem.get('idref'))
            if item is not None:
                self.oeb.spine.add(item, elem.get('linear'))
        if len(self.oeb.spine) == 0:
            raise Exception('WASI shim: spine still empty')
    reader.OEBReader._spine_from_opf = patched_spine_from_opf


def _install_multiprocessing_stub() -> None:
    if 'multiprocessing' in sys.modules:
        return
    mp = types.ModuleType('multiprocessing')
    mp.Pipe = lambda *a, **k: (_ for _ in ()).throw(NotImplementedError('multiprocessing.Pipe unavailable under WASI'))
    mpc = types.ModuleType('multiprocessing.connection')
    mpc.Connection = type('Connection', (), {})
    mpc.PipeConnection = mpc.Connection
    sys.modules['multiprocessing'] = mp
    sys.modules['multiprocessing.connection'] = mpc


def _install_threadpool_stub() -> None:
    import concurrent.futures as cf
    try:
        cf.ThreadPoolExecutor
        return
    except Exception:
        pass
    class Future:
        def __init__(self, value=None, exc=None):
            self._value = value; self._exc = exc
        def result(self, timeout=None):
            if self._exc: raise self._exc
            return self._value
    class ThreadPoolExecutor:
        def __init__(self, *a, **k): pass
        def __enter__(self): return self
        def __exit__(self, *a): self.shutdown(); return False
        def submit(self, fn, *a, **k):
            try: return Future(fn(*a, **k))
            except BaseException as e: return Future(exc=e)
        def map(self, fn, *iterables, timeout=None, chunksize=1): return map(fn, *iterables)
        def shutdown(self, wait=True, cancel_futures=False): pass
    cf.ThreadPoolExecutor = ThreadPoolExecutor
    thread = types.ModuleType('concurrent.futures.thread')
    thread.ThreadPoolExecutor = ThreadPoolExecutor
    sys.modules['concurrent.futures.thread'] = thread


def _install_regex_stub() -> None:
    if 'regex' not in sys.modules:
        import re
        sys.modules['regex'] = re


def _install_msgpack_stub() -> None:
    if 'msgpack' in sys.modules:
        return
    import json
    mp = types.ModuleType('msgpack')
    mp.dumps = lambda o, *a, **k: json.dumps(o).encode()
    mp.loads = lambda b, *a, **k: json.loads(bytes(b).decode())
    mp.packb = mp.dumps; mp.unpackb = mp.loads
    sys.modules['msgpack'] = mp


def _install_pil_stub() -> None:
    if 'PIL' in sys.modules:
        return
    pil = types.ModuleType('PIL')
    image = types.ModuleType('PIL.Image')
    image.open = lambda *a, **k: (_ for _ in ()).throw(NotImplementedError('PIL.Image.open unavailable in WASI shim'))
    imageops = types.ModuleType('PIL.ImageOps')
    for name in ('expand', 'fit', 'contain', 'pad'):
        setattr(imageops, name, lambda img, *a, **k: img)
    pil.Image = image; pil.ImageOps = imageops
    sys.modules['PIL'] = pil; sys.modules['PIL.Image'] = image; sys.modules['PIL.ImageOps'] = imageops


def _install_lxml_facade() -> None:
    if 'lxml' in sys.modules:
        return
    import re
    import xml.etree.ElementTree as ET
    lxml = types.ModuleType('lxml'); lxml.__path__ = []
    etree = types.ModuleType('lxml.etree')

    class XMLSyntaxError(Exception): pass
    class ParserError(Exception): pass
    class XPathEvalError(Exception): pass
    class Resolvers:
        def add(self, *a, **k): pass
    class Parser:
        def __init__(self, *a, **k): self.resolvers = Resolvers()
    def unwrap(x): return x._e if hasattr(x, '_e') else x
    def wrap(x, parent=None): return x if x is None or hasattr(x, '_e') else ElementProxy(x, parent)

    class ElementProxy:
        def __init__(self, e, parent=None): self._e = e; self._parent = parent
        def __getattr__(self, n): return getattr(self._e, n)
        def __iter__(self): return iter([wrap(x, self) for x in list(self._e)])
        def __len__(self): return len(self._e)
        def __getitem__(self, i): return wrap(self._e[i], self)
        @property
        def tag(self): return self._e.tag
        @tag.setter
        def tag(self, v): self._e.tag = v
        @property
        def text(self): return self._e.text
        @text.setter
        def text(self, v): self._e.text = v
        @property
        def tail(self): return self._e.tail
        @tail.setter
        def tail(self, v): self._e.tail = v
        @property
        def attrib(self): return self._e.attrib
        def get(self, *a, **k): return self._e.get(*a, **k)
        def set(self, *a, **k): return self._e.set(*a, **k)
        def append(self, x): return self._e.append(unwrap(x))
        def insert(self, i, x): return self._e.insert(i, unwrap(x))
        def remove(self, x): return self._e.remove(unwrap(x))
        def makeelement(self, tag, attrib=None): return wrap(ET.Element(tag, attrib or {}))
        def iter(self, *a, **k): return (wrap(x) for x in self._e.iter(*a, **k))
        def iterdescendants(self, *a, **k): return (wrap(x) for x in list(self._e.iter(*a, **k))[1:])
        def getparent(self): return self._parent
        def getroottree(self): return TreeProxy(ET.ElementTree(self._e))
        def findall(self, *a, **k): return [wrap(x) for x in self._e.findall(*a, **k)]
        def find(self, *a, **k):
            r = self._e.find(*a, **k); return wrap(r) if r is not None else None
        def xpath(self, expr, *a, **k):
            nodes = list(self._e.iter()); direct = list(self._e) if expr.startswith('./') else nodes
            lname = lambda x: str(x.tag).split('}')[-1].lower()
            if expr.startswith('//*[@') and '/@' not in expr:
                inside = expr.split('[@', 1)[1].split(']', 1)[0]
                attrs = [part.split('=')[0].strip().lstrip('@') for part in inside.replace(' or ', '|').split('|')]
                return [wrap(x) for x in nodes if any(x.get(attr) is not None for attr in attrs)]
            if expr.startswith('//') and '[' not in expr and '/@' not in expr:
                target = expr[2:].split(':')[-1].lower(); return [wrap(x) for x in nodes if lname(x) == target]
            if expr in ('o2:manifest','o2:spine','o2:tours','o2:guide'):
                target = expr.split(':')[-1]; return [wrap(x) for x in nodes if lname(x) == target]
            if expr in ('o2:metadata//*','o2:metadata//o2:meta'):
                out = []
                for m in [x for x in nodes if lname(x) == 'metadata']:
                    for c in m.iter():
                        if c is not m and (expr.endswith('*') or lname(c) == 'meta'): out.append(wrap(c))
                return out
            if expr.startswith('/o2:package/o2:manifest/o2:item'):
                return [wrap(c) for m in nodes if lname(m) == 'manifest' for c in list(m) if lname(c) == 'item']
            if expr.startswith('/o2:package/o2:spine/o2:itemref'):
                return [wrap(c) for m in nodes if lname(m) == 'spine' for c in list(m) if lname(c) == 'itemref']
            if expr.startswith('/o2:package/o2:guide/o2:reference'):
                return [wrap(c) for m in nodes if lname(m) == 'guide' for c in list(m) if lname(c) == 'reference']
            if 're:match(name' in expr:
                low = expr.lower()
                if 'metadata' in low and 'item' not in low and 'itemref' not in low: return [wrap(x) for x in nodes if lname(x) == 'metadata']
                if 'manifest' in low and 'item' in low: return [wrap(c) for x in nodes if lname(x) == 'manifest' for c in list(x) if lname(c) == 'item']
                if 'spine' in low and 'itemref' in low: return [wrap(c) for x in nodes if lname(x) == 'spine' for c in list(x) if lname(c) == 'itemref']
                if 'guide' in low: return [wrap(x) for x in nodes if lname(x) == 'guide']
                if 'spine' in low: return [wrap(x) for x in nodes if lname(x) == 'spine']
            if '/@' in expr or expr.endswith('@href]'):
                attr = expr.split('/@')[-1].split(']')[0]
                return [x.get(attr) for x in nodes if x.get(attr) is not None]
            if 'local-name()=' in expr:
                names = re.findall(r'local-name\(\)="([^"]+)"', expr); pool = direct if expr.startswith('./') else nodes
                return [wrap(x) for x in pool if str(x.tag).split('}')[-1] in names]
            if 'name()' in expr and 'meta' in expr:
                return [wrap(x) for x in nodes if lname(x) == 'meta' and (('@name' not in expr) or x.get('name'))]
            if expr.startswith('//*[@id='):
                wanted = expr.split('=',1)[1].strip().strip('[]').strip(chr(34)).strip(chr(39))
                return [wrap(x) for x in nodes if x.get('id') == wanted]
            if expr == './*': return [wrap(x) for x in list(self._e)]
            try: return [wrap(x) for x in self._e.findall(expr.replace('xhtml:', ''))]
            except Exception: return []

    class TreeProxy:
        def __init__(self, t): self._t = t
        def getroot(self): return wrap(self._t.getroot())
        def write(self, *a, **k): return self._t.write(*a, **k)
        def xpath(self, expr, *a, **k): return self.getroot().xpath(expr, *a, **k)
        def __getattr__(self, n): return getattr(self._t, n)

    etree.Entity = type('Entity', (), {})
    etree._wrap = wrap; etree._unwrap = unwrap
    etree.Element = lambda tag, *a, **k: wrap(ET.Element(tag, k.get('attrib') or (a[0] if a and isinstance(a[0], dict) else {})))
    etree.SubElement = lambda parent, tag, *a, **k: wrap(ET.SubElement(unwrap(parent), tag, k.get('attrib') or (a[0] if a and isinstance(a[0], dict) else {})))
    etree.Comment = ET.Comment; etree.ProcessingInstruction = ET.ProcessingInstruction; etree.QName = ET.QName
    etree.XMLParser = Parser; etree.HTMLParser = Parser; etree.XMLSyntaxError = XMLSyntaxError; etree.ParserError = ParserError; etree.XPathEvalError = XPathEvalError; etree.Resolver = type('Resolver', (), {})
    etree.FunctionNamespace = lambda *a, **k: type('FunctionNamespace', (dict,), {})()
    etree.iselement = lambda x: hasattr(x, '_e') or hasattr(x, 'tag')
    etree.fromstring = lambda data, *a, **k: wrap(ET.fromstring(data if isinstance(data, (bytes, bytearray)) else str(data).encode()))
    etree.XML = etree.fromstring
    etree.tostring = lambda el, *a, **k: ET.tostring(unwrap(el), encoding=('unicode' if k.get('encoding') is str else k.get('encoding', 'unicode' if k.get('method') == 'text' else 'utf-8')))
    etree.parse = lambda f, *a, **k: TreeProxy(ET.parse(f))
    etree.iterparse = lambda *a, **k: ET.iterparse(*a, **k)
    etree.register_namespace = ET.register_namespace
    etree.XPath = lambda expr, *a, **k: (lambda node, *aa, **kw: node.xpath(expr, *aa, **kw) if hasattr(node, 'xpath') else [])

    html = types.ModuleType('lxml.html')
    html.document_fromstring = lambda data, *a, **k: etree.fromstring(data)
    html.fromstring = html.document_fromstring; html.tostring = etree.tostring
    html.defs = types.SimpleNamespace(link_attrs={'href','src','action','longdesc','usemap','cite','background'}, empty_tags={'br','img','hr','meta','link','input'})
    builder = types.ModuleType('lxml.builder')
    class ElementMaker:
        def __init__(self, *a, **k): self.namespace = k.get('namespace')
        def __getattr__(self, tag): return lambda *children, **attrs: self(tag, *children, **attrs)
        def __call__(self, tag, *children, **attrs):
            e = ET.Element(tag, {k.rstrip('_'): str(v) for k, v in attrs.items() if k != 'nsmap'})
            for c in children:
                if isinstance(c, str): e.text = (e.text or '') + c
                else: e.append(unwrap(c))
            return wrap(e)
    builder.ElementMaker = ElementMaker; builder.E = ElementMaker()
    html_builder = types.ModuleType('lxml.html.builder')
    for tag in 'A BODY BR DD DIV DL DT H1 H2 H3 HEAD HR HTML IMG LI LINK META OL P SPAN STRONG STYLE TABLE TD TITLE TR UL'.split():
        setattr(html_builder, tag, getattr(ElementMaker(), tag))
    sys.modules.update({'lxml': lxml, 'lxml.etree': etree, 'lxml.html': html, 'lxml.builder': builder, 'lxml.html.builder': html_builder})
    lxml.etree = etree; lxml.html = html; lxml.builder = builder
