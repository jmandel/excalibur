#!/usr/bin/env python3
from __future__ import annotations
import os, shutil, subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'experiments/static-wasi-python'
SRC = WORK / 'Python-3.12.0'
PYWASM = SRC / 'builddir/wasi/python.wasm'
THIRD_PARTY_SITE = WORK / 'third-party-site'
WASMTIME = ROOT / 'experiments/wasi-python-spike/wasmtime/wasmtime-v18.0.4-x86_64-linux/wasmtime'
NODE = shutil.which('node')

code = r'''
import sys, importlib.machinery, sysconfig
print('python', sys.version.replace('\n',' '), flush=True)
print('platform', sys.platform, sysconfig.get_platform(), flush=True)
print('ext_suffixes', importlib.machinery.EXTENSION_SUFFIXES, flush=True)
print('has _cmsgpack builtin', '_cmsgpack' in sys.builtin_module_names, flush=True)
for m in ['array','_struct','_pickle','_decimal','pyexpat','_elementtree','_socket','select','zlib','_bz2','_lzma','_regex','_lxml_etree','_imaging','_imagingft','_imagingcms','_webp','_avif']:
    try:
        mod = __import__(m)
        print('import ok', m, getattr(mod, '__file__', '<builtin>'), flush=True)
    except Exception as e:
        print('import fail', m, type(e).__name__, e, flush=True)
try:
    import regex
    print('regex ok', regex.compile(r'(?i)(a)(b)').findall('ab AB'), flush=True)
except Exception as e:
    print('regex fail', type(e).__name__, e, flush=True)
try:
    import msgpack
    packed = msgpack.packb({'x': [1, 2, 3]}, use_bin_type=True)
    print('msgpack ok', msgpack.unpackb(packed, raw=False), flush=True)
except Exception as e:
    print('msgpack fail', type(e).__name__, e, flush=True)
try:
    from lxml import etree
    root = etree.fromstring(b'<root><child a="1"/></root>')
    print('lxml etree ok', root.tag, root[0].get('a'), flush=True)
except Exception as e:
    print('lxml etree fail', type(e).__name__, e, flush=True)
try:
    from lxml import html
    doc = html.fromstring('<html><body><p>Hello</p></body></html>')
    print('lxml html ok', doc.xpath('string(//p)'), flush=True)
except Exception as e:
    print('lxml html fail', type(e).__name__, e, flush=True)

try:
    import gc
    from PIL import Image, ImageCms, ImageFont, features
    print('pillow import ok', Image.VERSION if hasattr(Image, 'VERSION') else getattr(Image, '__version__', '?'), flush=True)
    im = Image.new('RGB', (2, 2), (255, 0, 0))
    print('pillow new ok', im.mode, im.size, flush=True)
    import io
    for fmt in ['PNG', 'JPEG', 'TIFF', 'WEBP', 'JPEG2000', 'AVIF']:
        try:
            buf = io.BytesIO(); im.save(buf, fmt); buf.seek(0)
            im2 = Image.open(buf); im2.load()
            print('pillow', fmt, 'ok', im2.mode, im2.size, len(buf.getvalue()), flush=True)
            del im2, buf; gc.collect()
        except Exception as e:
            print('pillow', fmt, 'fail', type(e).__name__, e, flush=True)
    srgb = ImageCms.createProfile('sRGB')
    converted = ImageCms.profileToProfile(im, srgb, srgb)
    print('pillow ImageCms ok', converted.mode, converted.size, flush=True)
    del converted, srgb; gc.collect()
    font = ImageFont.load_default(size=12)
    mask = font.getmask('WASI')
    print('pillow ImageFont ok', mask.size, flush=True)
    del mask, font; gc.collect()
    for feat in ['jpg', 'jpg_2000', 'zlib', 'libtiff', 'webp', 'webp_anim', 'webp_mux', 'freetype2', 'littlecms2', 'avif']:
        try: print('feature', feat, features.check(feat)); gc.collect()
        except Exception as e: print('feature', feat, 'err', type(e).__name__, e)
except Exception as e:
    print('pillow fail', type(e).__name__, e, flush=True)
    raise
'''

def main():
    if not PYWASM.exists():
        raise SystemExit(f'missing {PYWASM}; run build_static_wasi_python.py first')
    with tempfile.TemporaryDirectory() as td:
        probe = Path(td) / 'probe.py'; probe.write_text(code)
        py_path = '/builddir/wasi/build/lib.wasi-wasm32-3.12:/Lib:/third_party_site'
        if NODE:
            runner = Path(td) / 'run_wasi_probe.js'
            runner.write_text(f'''
const fs = require('fs');
const {{ WASI }} = require('wasi');
const wasi = new WASI({{
  version: 'preview1',
  args: ['python.wasm', '/work/probe.py'],
  env: {{ PYTHONPATH: {py_path!r} }},
  preopens: {{
    '/': {str(SRC)!r},
    '/work': {td!r},
    '/third_party_site': {str(THIRD_PARTY_SITE)!r}
  }}
}});
(async () => {{
  const bytes = fs.readFileSync({str(PYWASM)!r});
  const mod = await WebAssembly.compile(bytes);
  const inst = await WebAssembly.instantiate(mod, {{ wasi_snapshot_preview1: wasi.wasiImport }});
  wasi.start(inst);
}})().catch((err) => {{ console.error(err); process.exit(1); }});
''')
            cmd = [NODE, '--experimental-wasm-exnref', '--stack-size=32768', str(runner)]
        else:
            cmd = [str(WASMTIME), 'run', '--dir', f'{SRC}::/', '--dir', f'{td}::/work', '--dir', f'{THIRD_PARTY_SITE}::/third_party_site', '--env', f'PYTHONPATH={py_path}', str(PYWASM), '/work/probe.py']
        cp = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(cp.stdout, flush=True)
        return cp.returncode
if __name__ == '__main__':
    raise SystemExit(main())
