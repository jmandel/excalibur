"""Browser/Pyodide entrypoints for in-browser conversion."""
from __future__ import annotations

import os
import sys
import types
from pathlib import Path

if sys.platform == 'wasi':
    import wasi_runtime_shims
    wasi_runtime_shims.install_pre_import()

# html5_parser is not currently a Pyodide package. Fallback used by the demo;
# production should replace this with a full html5-parser build/replacement.
try:
    import html5_parser  # noqa: F401
except Exception:
    import lxml.html
    import lxml.etree as ET
    hp = types.ModuleType('html5_parser')
    def parse(raw, maybe_xhtml=True, line_number_attr=None, keep_doctype=False, sanitize_names=True, **kw):
        if isinstance(raw, bytes):
            raw = raw.decode('utf-8', 'replace')
        parser = ET.HTMLParser(recover=True)
        root = lxml.html.document_fromstring(raw, parser=parser)
        for el in root.iter():
            if isinstance(el.tag, str) and not el.tag.startswith('{'):
                el.tag = '{http://www.w3.org/1999/xhtml}' + el.tag.lower()
        return root
    hp.parse = parse
    soup = types.ModuleType('html5_parser.soup')
    soup.parse = parse
    sys.modules['html5_parser'] = hp
    sys.modules['html5_parser.soup'] = soup

# Source tree layout is extracted at /app.
os.chdir('/app')
sys.path.insert(0, '/app/experiments')
import calibre_bootstrap  # noqa: F401,E402

# Ensure modules that import localization helpers by name see fallbacks.
import calibre.utils.localization as _loc  # noqa: E402
_loc.get_lang = lambda: 'en'
_loc.canonicalize_lang = lambda x: (x or 'en').lower().replace('_', '-')
_loc.lang_as_iso639_1 = lambda x: (x or 'en').split('-')[0].lower()
try:
    import calibre.ebooks.metadata.opf3 as _opf3
    _opf3.canonicalize_lang = _loc.canonicalize_lang
except Exception:
    pass

from convert_with_plumber import convert  # noqa: E402
from inspect_azw3 import inspect  # noqa: E402
from check_profiles import main as check_profiles_main  # noqa: E402

if sys.platform == 'wasi':
    wasi_runtime_shims.install_conversion_patches()

check_profiles_main()


def convert_file(input_path: str, output_path: str, options: dict | None = None) -> dict:
    """Convert a file already present in MEMFS to AZW3 and return metadata."""
    inp = Path(input_path)
    out = Path(output_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    convert(inp, out, options=options or {})
    info = inspect(out)
    return info
