#!/usr/bin/env python3
from __future__ import annotations
import json, shutil, subprocess, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PYWASM = ROOT / 'experiments/static-wasi-python/Python-3.12.0/builddir/wasi/python.wasm'
SRC = ROOT / 'experiments/static-wasi-python/Python-3.12.0'
THIRD_PARTY_SITE = ROOT / 'experiments/static-wasi-python/third-party-site'
NODE = shutil.which('node')

RUNNER = r'''
const fs = require('fs');
const { WASI } = require('wasi');
const [,, wasm, srcRoot, thirdParty, repo, work, inputName] = process.argv;
const code = `
import os, json
os.chdir('/')
import browser_convert
print('imported browser_convert', flush=True)
info = browser_convert.convert_file('/work/${inputName}', '/work/out.azw3', {'output_profile':'kindle_oasis','base_font_size':0,'margin_left':5,'margin_right':5,'margin_top':5,'margin_bottom':5,'dont_compress':False,'no_inline_toc':False})
print('converted', json.dumps(info, sort_keys=True), flush=True)
`;
fs.writeFileSync(`${work}/probe.py`, code);
const pyPath = '/builddir/wasi/build/lib.wasi-wasm32-3.12:/Lib:/third_party_site:/repo/experiments:/repo/third_party/calibre/src';
const wasi = new WASI({
  version: 'preview1',
  args: ['python.wasm', '/work/probe.py'],
  env: { PYTHONPATH: pyPath, PYTHONDONTWRITEBYTECODE: '1' },
  preopens: { '/': srcRoot, '/third_party_site': thirdParty, '/repo': repo, '/work': work }
});
(async()=>{
  const mod = await WebAssembly.compile(fs.readFileSync(wasm));
  const inst = await WebAssembly.instantiate(mod, {wasi_snapshot_preview1: wasi.wasiImport});
  wasi.start(inst);
})().catch(e=>{ console.error(e); process.exit(1); });
'''

def run_fixture(fixture: Path) -> int:
    if not NODE:
        raise SystemExit('node is required for wasm exception support in this probe')
    with tempfile.TemporaryDirectory() as td:
        work = Path(td)
        shutil.copy2(fixture, work / fixture.name)
        runner = work / 'run.js'; runner.write_text(RUNNER)
        cmd = [NODE, '--experimental-wasm-exnref', '--stack-size=32768', str(runner), str(PYWASM), str(SRC), str(THIRD_PARTY_SITE), str(ROOT), str(work), fixture.name]
        cp = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(f'=== {fixture.name} ===')
        print(cp.stdout)
        out = work / 'out.azw3'
        if cp.returncode == 0 and out.exists() and out.stat().st_size > 0:
            print(f'OK {fixture.name}: {out.stat().st_size} bytes')
            return 0
        print(f'FAIL {fixture.name}')
        return cp.returncode or 1

def main() -> int:
    if not PYWASM.exists():
        raise SystemExit(f'missing {PYWASM}')
    fixtures = sorted((ROOT / 'fixtures/generated').glob('*.epub'))
    fixtures += sorted((ROOT / 'consumer-app/src/assets/samples').glob('*.epub'))
    failures = 0
    for f in fixtures:
        failures += run_fixture(f) != 0
    return 1 if failures else 0

if __name__ == '__main__':
    raise SystemExit(main())
