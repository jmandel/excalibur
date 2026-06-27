#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, shutil, subprocess, tempfile, time, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUNTIME_ZIP = ROOT / 'web/calibre-runtime.zip'
NODE = shutil.which('node')

RUNNER = r'''
const fs = require('fs');
const { WASI } = require('wasi');
const [,, wasm, runtimeRoot, work, inputName] = process.argv;
const code = `
import os, json
os.chdir('/')
import browser_convert
print('imported browser_convert', flush=True)
info = browser_convert.convert_file('/work/${inputName}', '/work/out.azw3', {'output_profile':'kindle_oasis','base_font_size':0,'margin_left':5,'margin_right':5,'margin_top':5,'margin_bottom':5,'dont_compress':False,'no_inline_toc':False})
print('converted', json.dumps(info, sort_keys=True), flush=True)
`;
fs.writeFileSync(`${work}/probe.py`, code);
const pyPath = '/build/lib.wasi-wasm32-3.12:/Lib:/third_party_site:/experiments:/third_party/calibre/src';
const wasi = new WASI({
  version: 'preview1',
  args: ['python.wasm', '/work/probe.py'],
  env: {
    PYTHONPATH: pyPath,
    PYTHONDONTWRITEBYTECODE: '1',
    PYTHONHOME: '/',
    PYTHONTZPATH: '/usr/share/zoneinfo',
    HOME: '/tmp',
    XDG_CONFIG_HOME: '/tmp/.config'
  },
  preopens: { '/': runtimeRoot, '/work': work }
});
(async()=>{
  const mod = await WebAssembly.compile(fs.readFileSync(wasm));
  const inst = await WebAssembly.instantiate(mod, {wasi_snapshot_preview1: wasi.wasiImport});
  wasi.start(inst);
})().catch(e=>{ console.error(e); process.exit(1); });
'''

def extract_runtime(dest: Path) -> Path:
    if not RUNTIME_ZIP.exists():
        raise SystemExit(f'missing {RUNTIME_ZIP}; run scripts/build_runtime_artifacts.py first')
    with zipfile.ZipFile(RUNTIME_ZIP) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            name = info.filename
            rel = Path(name.removeprefix('wasi/')) if name.startswith('wasi/') else Path(name)
            out = dest / rel
            out.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, out.open('wb') as target:
                shutil.copyfileobj(src, target)
    wasm = dest / 'python.wasm'
    if not wasm.exists():
        raise SystemExit(f'{RUNTIME_ZIP} is missing wasi/python.wasm')
    return wasm

def run_fixture(fixture: Path) -> int:
    if not NODE:
        raise SystemExit('node is required for this probe')
    with tempfile.TemporaryDirectory() as td:
        work = Path(td)
        runtime_root = work / 'runtime'
        runtime_root.mkdir()
        wasm = extract_runtime(runtime_root)
        (runtime_root / 'tmp/.config').mkdir(parents=True)
        shutil.copy2(fixture, work / fixture.name)
        runner = work / 'run.js'; runner.write_text(RUNNER)
        cmd = [NODE, '--stack-size=32768', str(runner), str(wasm), str(runtime_root), str(work), fixture.name]
        start = time.monotonic()
        cp = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        elapsed = time.monotonic() - start
        print(f'=== {fixture.name} ===')
        print(cp.stdout)
        out = work / 'out.azw3'
        if cp.returncode == 0 and out.exists() and out.stat().st_size > 0:
            print(f'OK {fixture.name}: {out.stat().st_size} bytes in {elapsed:.2f}s; node return code {cp.returncode}')
            return 0
        print(f'FAIL {fixture.name}: {elapsed:.2f}s; node return code {cp.returncode}; out exists={out.exists()} size={out.stat().st_size if out.exists() else 0}')
        return cp.returncode or 1

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        '--generated-only',
        action='store_true',
        help='only probe generated smoke fixtures; default also probes bundled sample EPUBs',
    )
    args = ap.parse_args()
    fixtures = sorted((ROOT / 'fixtures/generated').glob('*.epub'))
    if not args.generated_only:
        fixtures += sorted((ROOT / 'consumer-app/src/assets/samples').glob('*.epub'))
    failures = 0
    for f in fixtures:
        failures += run_fixture(f) != 0
    return 1 if failures else 0

if __name__ == '__main__':
    raise SystemExit(main())
