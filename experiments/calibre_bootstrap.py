#!/usr/bin/env python3
"""Runtime bootstrap/shims for source-tree calibre conversion experiments."""
from __future__ import annotations

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
from collections.abc import Iterable
builtins._ = lambda x, *a, **k: x
builtins.__ = lambda x, *a, **k: x
builtins.Iterable = Iterable

# calibre's bundled runtime exposes a top-level `compression` package. Alias
# stdlib modules for source-tree probing.
compression = types.ModuleType("compression")
import zlib, bz2, gzip, lzma
compression.zlib = zlib; compression.bz2 = bz2; compression.gzip = gzip; compression.lzma = lzma
sys.modules["compression"] = compression
for _name, _mod in {"zlib": zlib, "bz2": bz2, "gzip": gzip, "lzma": lzma}.items():
    sys.modules[f"compression.{_name}"] = _mod

# Avoid importing calibre's AI plugin type-hint module through customize.
ai = types.ModuleType("calibre.ai")
class ChatMessage: pass
class ChatResponse: pass
ai.ChatMessage = ChatMessage; ai.ChatResponse = ChatResponse
sys.modules["calibre.ai"] = ai
builtins.ChatMessage = ChatMessage
builtins.ChatResponse = ChatResponse
builtins.Iterator = __import__('collections.abc').abc.Iterator

# Avoid Qt image stack during import probing. The AZW3 spike starts with
# process_images=False, so these are not exercised for initial imports.
img_mod = types.ModuleType("calibre.utils.img")
img_mod.optimize_png = lambda path: None
img_mod.image_and_format_from_data = lambda data: (None, None)
img_mod.image_from_data = lambda data: data
img_mod.image_to_data = lambda img, fmt='PNG', **kw: bytes(img) if isinstance(img, (bytes, bytearray)) else b''
img_mod.png_data_to_gif_data = lambda data: data
img_mod.gif_data_to_png_data = lambda data: data
img_mod.resize_image = lambda img, *a, **k: img
img_mod.scale_image = lambda data, *a, **k: data
img_mod.save_cover_data_to = lambda data, path, *a, **k: open(path, 'wb').write(data)
class AnimatedGIF: pass
img_mod.AnimatedGIF = AnimatedGIF
sys.modules["calibre.utils.img"] = img_mod

# Stub package: calibre_extensions
ce = types.ModuleType("calibre_extensions")
ce.__path__ = []
sys.modules["calibre_extensions"] = ce

# cPalmdoc: pure-Python PalmDOC compression/decompression fallback.
cPalmdoc = types.ModuleType("calibre_extensions.cPalmdoc")
def _late_py_compress(data):
    from calibre.ebooks.compression.palmdoc import py_compress_doc
    return py_compress_doc(data)
def _py_decompress_doc(data: bytes) -> bytes:
    out = bytearray()
    i = 0
    data = bytes(data)
    while i < len(data):
        c = data[i]
        i += 1
        if 1 <= c <= 8:
            out.extend(data[i:i+c]); i += c
        elif c <= 0x7f:
            out.append(c)
        elif c >= 0xc0:
            out.append(0x20); out.append(c ^ 0x80)
        elif i < len(data):
            c = (c << 8) | data[i]
            i += 1
            dist = (c & 0x3fff) >> 3
            n = (c & 7) + 3
            if dist <= len(out):
                for _ in range(n):
                    out.append(out[-dist])
    return bytes(out)
cPalmdoc.compress = _late_py_compress
cPalmdoc.decompress = _py_decompress_doc
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
icu.UCOL_PRIMARY = 0; icu.UCOL_SECONDARY = 1; icu.UCOL_ALTERNATE_HANDLING = 2
icu.UCOL_SHIFTED = 3; icu.UCOL_NON_IGNORABLE = 4
icu.set_default_encoding = lambda enc: None
icu.set_filesystem_encoding = lambda enc: None
icu.change_case = lambda s, which, *a: str(s).lower() if which == 'lower' else str(s).upper() if which == 'upper' else str(s).title()
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
icu.chr = chr
icu.swap_case = lambda s: str(s).swapcase()
icu.character_name = lambda c: ''
icu.character_name_from_code = lambda c: ''
icu.normalize = lambda mode, s: str(s)
icu.utf16_length = lambda s: len(str(s).encode('utf-16-le')) // 2
icu.word_prefix_find = lambda query, text: str(text).lower().startswith(str(query).lower())
class _Collator:
    def __init__(self, *a, **k): pass
    def sort_key(self, s): return str(s)
    def strcmp(self, a, b): return (str(a)>str(b))-(str(a)<str(b))
    def find(self, a, b): return str(a).find(str(b))
    def clone(self): return self
    def set_attribute(self, *a, **k): pass
class _Transliterator:
    def __init__(self, *a, **k): pass
    def transliterate(self, s): return str(s)
icu.Collator = _Collator
icu.Transliterator = _Transliterator
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

fast_html_entities = types.ModuleType("calibre_extensions.fast_html_entities")
fast_html_entities.replace_all_entities = lambda raw, *a, **k: raw
sys.modules[fast_html_entities.__name__] = fast_html_entities
setattr(ce, "fast_html_entities", fast_html_entities)



def bootstrap() -> None:
    """Importing this module installs shims; this function is for readability."""
    return None

# Narrow plugin registry for Plumber experiments. This avoids importing the full
# calibre.customize.ui registry, which pulls unrelated GUI/AI/device plugins.
ui = types.ModuleType("calibre.customize.ui")
def _epub_input_plugin():
    from calibre.ebooks.conversion.plugins.epub_input import EPUBInput
    return EPUBInput(None)
def _mobi_input_plugin():
    from calibre.ebooks.conversion.plugins.mobi_input import MOBIInput
    return MOBIInput(None)
def _azw3_output_plugin():
    from calibre.ebooks.conversion.plugins.mobi_output import AZW3Output
    return AZW3Output(None)
def _mobi_output_plugin():
    from calibre.ebooks.conversion.plugins.mobi_output import MOBIOutput
    return MOBIOutput(None)
def plugin_for_input_format(fmt):
    fmt = fmt.lower()
    if fmt in {'epub', 'kepub'}:
        return _epub_input_plugin()
    if fmt in {'mobi', 'prc', 'azw', 'azw3', 'pobi'}:
        return _mobi_input_plugin()
    return None
def plugin_for_output_format(fmt):
    fmt = fmt.lower()
    if fmt == 'azw3':
        return _azw3_output_plugin()
    if fmt == 'mobi':
        return _mobi_output_plugin()
    return None
def available_input_formats():
    return {'epub', 'kepub', 'mobi', 'prc', 'azw', 'azw3', 'pobi'}
def available_output_formats():
    return {'azw3', 'mobi'}
def input_profiles():
    from calibre.customize import profiles
    for cls in profiles.input_profiles:
        yield cls(None)
def output_profiles():
    from calibre.customize import profiles
    for cls in profiles.output_profiles:
        yield cls(None)
def run_plugins_on_preprocess(path, *a, **k):
    return path
def run_plugins_on_postprocess(path, fmt, *a, **k):
    return None
ui.plugin_for_input_format = plugin_for_input_format
ui.plugin_for_output_format = plugin_for_output_format
ui.available_input_formats = available_input_formats
ui.available_output_formats = available_output_formats
ui.input_profiles = input_profiles
ui.output_profiles = output_profiles
ui.run_plugins_on_preprocess = run_plugins_on_preprocess
ui.run_plugins_on_postprocess = run_plugins_on_postprocess
ui.metadata_plugins = lambda *a, **k: ()
ui.all_metadata_plugins = lambda *a, **k: ()
ui.config = {'plugin_customization': {}}
sys.modules['calibre.customize.ui'] = ui

# Avoid source-tree experiments spawning calibre-parallel for temp cleanup.
safe_atexit = types.ModuleType('calibre.utils.safe_atexit')
safe_atexit.remove_folder_atexit = lambda path: None
safe_atexit.remove_file_atexit = lambda path: None
safe_atexit.remove_dir = lambda path: None
safe_atexit.unlink = lambda path: None
safe_atexit.run_program_now = lambda cmdline: None
safe_atexit.reset_after_fork = lambda: None
safe_atexit.register = lambda *a, **k: None
safe_atexit.unregister = lambda *a, **k: None
sys.modules['calibre.utils.safe_atexit'] = safe_atexit

# Source checkout lacks built localization msgpack resources. Patch common language
# helpers to sane defaults for conversion experiments.
try:
    import calibre.utils.localization as _loc
    _loc.get_lang = lambda: 'en'
    _loc.canonicalize_lang = lambda x: (x or 'en').lower().replace('_', '-')
    _loc.lang_as_iso639_1 = lambda x: (x or 'en').split('-')[0].lower()
    _loc.lang_as_iso639_2 = lambda x: (x or 'eng').split('-')[0].lower()
    _loc.calibre_langcode_to_name = lambda x, localize=True: x or 'en'
except Exception:
    pass

# Avoid Qt dependency for legacy MOBI6 output fixture generation. calibre's
# MOBI output already handles Unavailable by warning and continuing.
rasterize_mod = types.ModuleType('calibre.ebooks.oeb.transforms.rasterize')
class Unavailable(Exception):
    pass
class SVGRasterizer:
    def __init__(self, *a, **k):
        raise Unavailable('Qt SVG rasterizer unavailable in experiment runtime')
rasterize_mod.Unavailable = Unavailable
rasterize_mod.SVGRasterizer = SVGRasterizer
sys.modules['calibre.ebooks.oeb.transforms.rasterize'] = rasterize_mod
