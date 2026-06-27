#!/usr/bin/env python3
from __future__ import annotations
import os, shutil, subprocess, sys, tarfile, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'experiments/static-wasi-python'
SRC = WORK / 'third-party-src'
BUILD = WORK / 'third-party-build'
PREFIX = WORK / 'third-party-wasi'
WASI_SDK = Path.home() / '.local/share/wasmpy-build/wasi-sdk'
TARBALLS = {
    'zlib-1.3.1.tar.gz': 'https://zlib.net/fossils/zlib-1.3.1.tar.gz',
    'bzip2-1.0.8.tar.gz': 'https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz',
    'xz-5.4.6.tar.gz': 'https://github.com/tukaani-project/xz/releases/download/v5.4.6/xz-5.4.6.tar.gz',
}

def run(cmd, cwd=None, env=None):
    print('+', ' '.join(map(str, cmd)))
    subprocess.run(list(map(str, cmd)), cwd=cwd, env=env, check=True)

def unpack(name):
    stem = name.removesuffix('.tar.gz')
    dest = BUILD / stem
    if dest.exists():
        shutil.rmtree(dest)
    with tarfile.open(SRC / name) as t:
        t.extractall(BUILD)
    return dest

def main():
    if not (WASI_SDK / 'bin/clang').exists():
        raise SystemExit(f'WASI SDK not found at {WASI_SDK}; install wasmpy-build first')
    SRC.mkdir(parents=True, exist_ok=True); BUILD.mkdir(parents=True, exist_ok=True); PREFIX.mkdir(parents=True, exist_ok=True)
    for name, url in TARBALLS.items():
        path = SRC / name
        if not path.exists():
            urllib.request.urlretrieve(url, path)
    cc = f'{WASI_SDK}/bin/clang --sysroot={WASI_SDK}/share/wasi-sysroot'
    env = os.environ.copy() | {'CC': cc, 'AR': f'{WASI_SDK}/bin/llvm-ar', 'RANLIB': f'{WASI_SDK}/bin/llvm-ranlib'}

    z = unpack('zlib-1.3.1.tar.gz')
    run(['./configure', '--static', f'--prefix={PREFIX}'], cwd=z, env=env | {'CHOST': 'wasm32-wasi'})
    run(['make', f'-j{os.cpu_count() or 2}'], cwd=z, env=env)
    run(['make', 'install'], cwd=z, env=env)

    b = unpack('bzip2-1.0.8.tar.gz')
    run(['make', f'-j{os.cpu_count() or 2}', f'CC={cc}', f'AR={env["AR"]}', f'RANLIB={env["RANLIB"]}', 'libbz2.a'], cwd=b)
    (PREFIX / 'include').mkdir(parents=True, exist_ok=True); (PREFIX / 'lib').mkdir(parents=True, exist_ok=True)
    shutil.copy2(b / 'bzlib.h', PREFIX / 'include/bzlib.h')
    shutil.copy2(b / 'libbz2.a', PREFIX / 'lib/libbz2.a')

    x = unpack('xz-5.4.6.tar.gz')
    run(['./configure', '--host=wasm32-wasi', f'--prefix={PREFIX}', '--disable-shared', '--enable-static', '--disable-threads', '--disable-xz', '--disable-xzdec', '--disable-lzmadec', '--disable-lzmainfo', '--disable-scripts', '--disable-doc', '--disable-nls'], cwd=x, env=env)
    run(['make', f'-j{os.cpu_count() or 2}'], cwd=x, env=env)
    run(['make', 'install'], cwd=x, env=env)
    print(PREFIX)

if __name__ == '__main__':
    main()
