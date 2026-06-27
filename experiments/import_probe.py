#!/usr/bin/env python3
"""Probe selected calibre imports with runtime stubs for native extensions."""
from __future__ import annotations

import importlib
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CALIBRE_SRC = ROOT / "third_party" / "calibre" / "src"
sys.path.insert(0, str(CALIBRE_SRC))
# calibre assumes its frozen app layout exposes this.
sys.extensions_location = str(ROOT / "build" / "fake-extensions")
sys.resources_location = str(ROOT / "third_party" / "calibre" / "resources")

# Minimal translation globals expected by many calibre modules.
import builtins
builtins._ = lambda x, *a, **k: x
builtins.__ = lambda x, *a, **k: x

# Stub package: calibre_extensions
ce = types.ModuleType("calibre_extensions")
ce.__path__ = []
sys.modules["calibre_extensions"] = ce

# cPalmdoc: delegate to calibre's pure Python implementation after import.
cPalmdoc = types.ModuleType("calibre_extensions.cPalmdoc")
def _late_py_compress(data):
    from calibre.ebooks.compression.palmdoc import py_compress_doc
    return py_compress_doc(data)
cPalmdoc.compress = _late_py_compress
cPalmdoc.decompress = lambda data: (_ for _ in ()).throw(NotImplementedError("PalmDOC decompress stub"))
sys.modules[cPalmdoc.__name__] = cPalmdoc
setattr(ce, "cPalmdoc", cPalmdoc)

# speedup helpers used in OEB parsing.
speedup = types.ModuleType("calibre_extensions.speedup")
def barename(tag):
    return tag.rpartition('}')[-1] if tag.startswith('{') else tag.rpartition(':')[-1]
def namespace(tag):
    return tag[1:].partition('}')[0] if tag.startswith('{') else ''
speedup.barename = barename
speedup.namespace = namespace
speedup.clean_xml_chars = lambda s: s
speedup.pread_all = lambda fd, size, offset: b''
sys.modules[speedup.__name__] = speedup
setattr(ce, "speedup", speedup)

# ICU coarse fallbacks.
icu = types.ModuleType("calibre_extensions.icu")
icu.unicode_version = "stub"
icu.NFC = "NFC"; icu.NFD = "NFD"; icu.NFKC = "NFKC"; icu.NFKD = "NFKD"
icu.UPPER_CASE = "upper"; icu.LOWER_CASE = "lower"; icu.TITLE_CASE = "title"
icu.set_default_encoding = lambda enc: None
icu.set_filesystem_encoding = lambda enc: None
icu.change_case = lambda s, which: str(s).lower() if which == 'lower' else str(s).upper() if which == 'upper' else str(s).title()
icu.capitalize = lambda s: str(s).capitalize()
icu.lower = lambda s: str(s).lower()
icu.upper = lambda s: str(s).upper()
icu.title_case = lambda s: str(s).title()
icu.sort_key = lambda s, *a, **k: str(s)
icu.numeric_sort_key = lambda s, *a, **k: str(s)
icu.strcmp = lambda a,b: (str(a)>str(b))-(str(a)<str(b))
icu.case_sensitive_strcmp = icu.strcmp
icu.primary_find = lambda a,b: str(a).find(str(b))
icu.primary_sort_key = icu.sort_key
icu.ord_string = lambda s: [ord(c) for c in str(s)]
icu.safe_chr = chr
icu.swap_case = lambda s: str(s).swapcase()
icu.character_name = lambda c: ''
icu.normalize = lambda mode, s: str(s)
icu.contractions = lambda *a, **k: []
icu.partition_by_first_letter = lambda items, key=lambda x: x: {'#': list(items)}
sys.modules[icu.__name__] = icu
setattr(ce, "icu", icu)

translator = types.ModuleType("calibre_extensions.translator")
class Translator:
    def __init__(self, *a, **k): pass
    def translate(self, s): return s
    def gettext(self, s): return s
    def ngettext(self, singular, plural, n): return singular if n == 1 else plural
    def pgettext(self, context, s): return s
    def npgettext(self, context, singular, plural, n): return singular if n == 1 else plural
    def install(self, names=()):
        import builtins
        builtins._ = self.gettext
        for name in names:
            setattr(builtins, name, getattr(self, name, self.gettext))
translator.Translator = Translator
sys.modules[translator.__name__] = translator
setattr(ce, "translator", translator)

imageops = types.ModuleType("calibre_extensions.imageops")
imageops.set_image_allocation_limit = lambda *a, **k: None
sys.modules[imageops.__name__] = imageops
setattr(ce, "imageops", imageops)

modules = [
    "calibre.ebooks.conversion.plugins.epub_input",
    "calibre.ebooks.oeb.base",
    "calibre.ebooks.oeb.reader",
    "calibre.ebooks.conversion.plugins.mobi_output",
    "calibre.ebooks.mobi.writer2.resources",
    "calibre.ebooks.mobi.writer8.main",
    "calibre.ebooks.mobi.writer8.mobi",
]

ok = True
for name in modules:
    try:
        importlib.import_module(name)
        print("OK", name)
    except Exception as e:
        ok = False
        print("FAIL", name, type(e).__name__, e)
        import traceback; traceback.print_exc(limit=4)

raise SystemExit(0 if ok else 1)
