import { loadPyodide } from 'pyodide';
import path from 'node:path';
import process from 'node:process';

const root = process.cwd();
const pyodide = await loadPyodide();
console.log('Pyodide', pyodide.version);

// Mount repo into Pyodide. NODEFS is available in node-hosted Pyodide.
pyodide.FS.mkdir('/repo');
pyodide.FS.mount(pyodide.FS.filesystems.NODEFS, { root }, '/repo');
await pyodide.runPythonAsync("import os; os.chdir('/repo')");

await pyodide.loadPackage(['micropip', 'lxml', 'Pillow', 'python-dateutil', 'regex', 'beautifulsoup4', 'html5lib', 'webencodings', 'msgpack', 'tzdata', 'lzma']);
await pyodide.runPythonAsync(`
import micropip
for pkg in ['css-parser', 'chardet', 'tzlocal']:
    try:
        await micropip.install(pkg)
        print('MICROPIP OK', pkg)
    except Exception as e:
        print('MICROPIP MISS', pkg, e)
`);

const code = String.raw`
import sys, types, os
from pathlib import Path

# html5_parser is not currently a Pyodide package. Provide a conservative
# fallback using lxml.html for the conversion surfaces our fixtures exercise.
try:
    import html5_parser  # noqa
except Exception:
    import lxml.html
    import lxml.etree as ET
    hp = types.ModuleType('html5_parser')
    def parse(raw, maybe_xhtml=True, line_number_attr=None, keep_doctype=False, sanitize_names=True, **kw):
        if isinstance(raw, bytes):
            raw = raw.decode('utf-8', 'replace')
        parser = ET.HTMLParser(recover=True)
        root = lxml.html.document_fromstring(raw, parser=parser)
        # calibre expects XHTML namespace for EPUB nav processing.
        for el in root.iter():
            if isinstance(el.tag, str) and not el.tag.startswith('{'):
                el.tag = '{http://www.w3.org/1999/xhtml}' + el.tag.lower()
        return root
    hp.parse = parse
    soup = types.ModuleType('html5_parser.soup')
    soup.parse = parse
    sys.modules['html5_parser'] = hp
    sys.modules['html5_parser.soup'] = soup

sys.path.insert(0, '/repo/experiments')
import calibre_bootstrap  # noqa
# Ensure modules that import localization helpers by name see the source-checkout fallback.
import calibre.utils.localization as _loc
_loc.get_lang = lambda: 'en'
_loc.canonicalize_lang = lambda x: (x or 'en').lower().replace('_', '-')
_loc.lang_as_iso639_1 = lambda x: (x or 'en').split('-')[0].lower()
try:
    import calibre.ebooks.metadata.opf3 as _opf3
    _opf3.canonicalize_lang = _loc.canonicalize_lang
except Exception:
    pass
from convert_with_plumber import convert
from inspect_azw3 import inspect

fixtures = sorted(Path('/repo/fixtures/generated').glob('*.epub'))
outdir = Path('/repo/experiments/pyodide-out')
if outdir.exists():
    import shutil
    shutil.rmtree(outdir)
outdir.mkdir(parents=True, exist_ok=True)
for epub in fixtures:
    out = outdir / (epub.stem + '.azw3')
    print('PYODIDE CONVERT EPUB', epub.name, '->', out.name)
    convert(epub, out)
    info = inspect(out)
    print('PYODIDE VALID', info)
    assert info['is_kf8']
    mobi = outdir / (epub.stem + '.mobi')
    print('PYODIDE CONVERT EPUB', epub.name, '->', mobi.name, '(legacy MOBI fixture)')
    convert(epub, mobi)
    mobi_out = outdir / (epub.stem + '-mobi-input.azw3')
    print('PYODIDE CONVERT MOBI', mobi.name, '->', mobi_out.name)
    convert(mobi, mobi_out)
    info = inspect(mobi_out)
    print('PYODIDE VALID', info)
    assert info['is_kf8']

for azw3 in sorted(outdir.glob('*.azw3')):
    if azw3.stem.endswith('-roundtrip'):
        continue
    out = outdir / (azw3.stem + '-roundtrip.azw3')
    print('PYODIDE CONVERT AZW3', azw3.name, '->', out.name)
    convert(azw3, out)
    info = inspect(out)
    print('PYODIDE VALID', info)
    assert info['is_kf8']
`;

try {
  await pyodide.runPythonAsync(code);
} catch (e) {
  console.error('PYODIDE_RUN_FAILED');
  console.error(e.message || String(e));
  if (e.stack) console.error(e.stack.split('\n').slice(0, 20).join('\n'));
  process.exit(1);
}
