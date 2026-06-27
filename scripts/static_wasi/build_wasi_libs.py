#!/usr/bin/env python3
from __future__ import annotations
import os, shutil, subprocess, tarfile, textwrap, time, urllib.error, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'experiments/static-wasi-python'
SRC = WORK / 'third-party-src'
BUILD = WORK / 'third-party-build'
PREFIX = WORK / 'third-party-wasi'
WASI_SDK = Path(os.environ.get('WASI_SDK_PATH', Path.home() / '.local/share/wasmpy-build/wasi-sdk'))
TARBALLS = {
    'zlib-1.3.1.tar.gz': 'https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz',
    'bzip2-1.0.8.tar.gz': 'https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz',
    'xz-5.4.6.tar.gz': 'https://github.com/tukaani-project/xz/releases/download/v5.4.6/xz-5.4.6.tar.gz',
    'jpeg-3.0.3.tar.gz': 'https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.0.3.tar.gz',
    'libpng-1.6.43.tar.gz': 'https://download.sourceforge.net/libpng/libpng-1.6.43.tar.gz',
    'libxml2-2.12.9.tar.xz': 'https://download.gnome.org/sources/libxml2/2.12/libxml2-2.12.9.tar.xz',
    'libxslt-1.1.39.tar.xz': 'https://download.gnome.org/sources/libxslt/1.1/libxslt-1.1.39.tar.xz',
    'lcms2-2.16.tar.gz': 'https://github.com/mm2/Little-CMS/releases/download/lcms2.16/lcms2-2.16.tar.gz',
    'freetype-2.13.2.tar.gz': 'https://download.savannah.gnu.org/releases/freetype/freetype-2.13.2.tar.gz',
    'tiff-4.6.0.tar.gz': 'https://download.osgeo.org/libtiff/tiff-4.6.0.tar.gz',
    'libwebp-1.4.0.tar.gz': 'https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.4.0.tar.gz',
    'openjpeg-2.5.2.tar.gz': 'https://github.com/uclouvain/openjpeg/archive/refs/tags/v2.5.2.tar.gz',
    'libyuv-main.tar.gz': 'https://chromium.googlesource.com/libyuv/libyuv/+archive/refs/heads/main.tar.gz',
    'aom-3.9.1.tar.gz': 'https://aomedia.googlesource.com/aom/+archive/v3.9.1.tar.gz',
    'libavif-1.1.1.tar.gz': 'https://github.com/AOMediaCodec/libavif/archive/refs/tags/v1.1.1.tar.gz',
}

def download(url: str, path: Path, attempts: int = 5) -> None:
    tmp = path.with_suffix(path.suffix + '.part')
    for attempt in range(1, attempts + 1):
        try:
            print(f'Downloading {url} -> {path.name} (attempt {attempt}/{attempts})')
            req = urllib.request.Request(url, headers={'User-Agent': 'excalibur-ci/1.0'})
            with urllib.request.urlopen(req, timeout=120) as resp, tmp.open('wb') as out:
                shutil.copyfileobj(resp, out)
            if not tarfile.is_tarfile(tmp):
                raise OSError(f'downloaded file is not a tar archive: {path.name}')
            tmp.replace(path)
            return
        except (OSError, TimeoutError, urllib.error.URLError) as e:
            tmp.unlink(missing_ok=True)
            if attempt == attempts:
                raise
            wait = min(60, 2 ** attempt)
            print(f'Download failed: {e}; retrying in {wait}s')
            time.sleep(wait)

def ensure_tarball(url: str, path: Path) -> None:
    if path.exists():
        if tarfile.is_tarfile(path):
            return
        print(f'Removing invalid cached archive: {path}')
        path.unlink()
    download(url, path)

def run(cmd, cwd=None, env=None):
    print('+', ' '.join(map(str, cmd)))
    subprocess.run(list(map(str, cmd)), cwd=cwd, env=env, check=True)

def host_build_aux(name: str) -> Path | None:
    for base in [
        Path('/usr/share/autoconf/build-aux'),
        Path('/usr/share/automake-1.18'),
        Path('/usr/share/automake-1.17'),
        Path('/usr/share/automake-1.16'),
        Path('/usr/share/libtool/build-aux'),
    ]:
        candidate = base / name
        if candidate.exists():
            return candidate
    return None

def refresh_config_scripts(src: Path) -> None:
    for name in ['config.sub', 'config.guess']:
        replacement = host_build_aux(name)
        if not replacement:
            continue
        for target in src.rglob(name):
            shutil.copy2(replacement, target)

def archive_stem(name: str) -> str:
    for suffix in ['.tar.gz', '.tar.xz', '.tar.bz2', '.tgz']:
        if name.endswith(suffix):
            return name.removesuffix(suffix)
    return name

def unpack(name, dest_name=None):
    stem = dest_name or archive_stem(name)
    dest = BUILD / stem
    if dest.exists(): shutil.rmtree(dest)
    with tarfile.open(SRC / name) as t:
        members = t.getmembers()
        flat = name in {'libyuv-main.tar.gz', 'aom-3.9.1.tar.gz'}
        if flat:
            dest.mkdir(parents=True)
            t.extractall(dest)
        else:
            t.extractall(BUILD)
    if dest.exists(): return dest
    # GitHub auto-tarballs may unpack to project-version names.
    cands = sorted(BUILD.glob(stem.split('-')[0] + '*'), key=lambda p: p.stat().st_mtime, reverse=True)
    if cands: return cands[0]
    raise FileNotFoundError(dest)

def toolchain():
    p = BUILD / 'wasi-toolchain.cmake'
    p.write_text(textwrap.dedent(f'''
        set(CMAKE_SYSTEM_NAME WASI)
        set(CMAKE_SYSTEM_PROCESSOR wasm32)
        set(CMAKE_C_COMPILER {WASI_SDK}/bin/clang)
        set(CMAKE_CXX_COMPILER {WASI_SDK}/bin/clang++)
        set(CMAKE_AR {WASI_SDK}/bin/llvm-ar)
        set(CMAKE_RANLIB {WASI_SDK}/bin/llvm-ranlib)
        set(CMAKE_SYSROOT {WASI_SDK}/share/wasi-sysroot)
        set(CMAKE_FIND_ROOT_PATH {PREFIX};{WASI_SDK}/share/wasi-sysroot)
        set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
        set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
        set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
        set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
    '''))
    return p

def cmake_build(src, bname, args, env):
    b = BUILD / bname
    if b.exists(): shutil.rmtree(b)
    run(['cmake','-S',src,'-B',b,f'-DCMAKE_TOOLCHAIN_FILE={toolchain()}',f'-DCMAKE_INSTALL_PREFIX={PREFIX}','-DBUILD_SHARED_LIBS=OFF',*args], env=env)
    run(['cmake','--build',b,'-j',str(os.cpu_count() or 2)], env=env)
    run(['cmake','--install',b], env=env)
    return b

def build_libjpeg_turbo(src, env, sjlj):
    # libjpeg-turbo 3.0.3 does not have a WITH_TOOLS option. Its default
    # static "all" target links example/tool executables, which is unnecessary
    # for our static Pillow build and fails under WASI without extra setjmp
    # linker flags. Build just the static library and install the needed files.
    b = BUILD / 'libjpeg-turbo-build'
    if b.exists(): shutil.rmtree(b)
    run([
        'cmake', '-S', src, '-B', b,
        f'-DCMAKE_TOOLCHAIN_FILE={toolchain()}',
        f'-DCMAKE_INSTALL_PREFIX={PREFIX}',
        '-DBUILD_SHARED_LIBS=OFF',
        '-DENABLE_SHARED=OFF',
        '-DENABLE_STATIC=ON',
        '-DWITH_JPEG8=ON',
        '-DWITH_SIMD=OFF',
        '-DWITH_TURBOJPEG=OFF',
        f'-DCMAKE_C_FLAGS={sjlj}',
        f'-DCMAKE_EXE_LINKER_FLAGS={sjlj}',
    ], env=env)
    run(['cmake','--build',b,'--target','jpeg-static','-j',str(os.cpu_count() or 2)], env=env)
    (PREFIX/'include').mkdir(exist_ok=True)
    (PREFIX/'lib').mkdir(exist_ok=True)
    (PREFIX/'lib/pkgconfig').mkdir(parents=True, exist_ok=True)
    shutil.copy2(b/'libjpeg.a', PREFIX/'lib/libjpeg.a')
    for header in ['jerror.h', 'jmorecfg.h', 'jpeglib.h']:
        shutil.copy2(src/header, PREFIX/f'include/{header}')
    shutil.copy2(b/'jconfig.h', PREFIX/'include/jconfig.h')
    pc = b/'pkgscripts/libjpeg.pc'
    if pc.exists():
        shutil.copy2(pc, PREFIX/'lib/pkgconfig/libjpeg.pc')
    return b

def main():
    if not (WASI_SDK / 'bin/clang').exists():
        raise SystemExit(f'WASI SDK not found at {WASI_SDK}; install wasmpy-build first')
    SRC.mkdir(parents=True, exist_ok=True); BUILD.mkdir(parents=True, exist_ok=True); PREFIX.mkdir(parents=True, exist_ok=True)
    for name, url in TARBALLS.items():
        path = SRC / name
        ensure_tarball(url, path)
    cc = f'{WASI_SDK}/bin/clang --sysroot={WASI_SDK}/share/wasi-sysroot'
    sjlj = '-mllvm -wasm-enable-sjlj'
    env = os.environ.copy() | {'CC': cc, 'AR': f'{WASI_SDK}/bin/llvm-ar', 'RANLIB': f'{WASI_SDK}/bin/llvm-ranlib', 'PKG_CONFIG_PATH': str(PREFIX/'lib/pkgconfig')}
    env_sjlj = env | {'CFLAGS': sjlj, 'CXXFLAGS': sjlj, 'LDFLAGS': sjlj, 'CPPFLAGS': f'-I{PREFIX}/include', 'LIBS': f'-L{PREFIX}/lib'}

    z = unpack('zlib-1.3.1.tar.gz'); run(['./configure','--static',f'--prefix={PREFIX}'], z, env | {'CHOST':'wasm32-wasi'}); run(['make','-j2'], z, env); run(['make','install'], z, env)
    b = unpack('bzip2-1.0.8.tar.gz'); run(['make','-j2',f'CC={cc}',f'AR={env["AR"]}',f'RANLIB={env["RANLIB"]}','libbz2.a'], b); (PREFIX/'include').mkdir(exist_ok=True); (PREFIX/'lib').mkdir(exist_ok=True); shutil.copy2(b/'bzlib.h', PREFIX/'include/bzlib.h'); shutil.copy2(b/'libbz2.a', PREFIX/'lib/libbz2.a')
    x = unpack('xz-5.4.6.tar.gz'); refresh_config_scripts(x); run(['./configure','--host=wasm32-wasi',f'--prefix={PREFIX}','--disable-shared','--enable-static','--disable-threads','--disable-xz','--disable-xzdec','--disable-lzmadec','--disable-lzmainfo','--disable-scripts','--disable-doc','--disable-nls'], x, env); run(['make','-j2'], x, env); run(['make','install'], x, env)

    jpeg = unpack('jpeg-3.0.3.tar.gz', 'libjpeg-turbo-3.0.3'); build_libjpeg_turbo(jpeg, env_sjlj, sjlj)
    png = unpack('libpng-1.6.43.tar.gz'); refresh_config_scripts(png); run(['./configure','--host=wasm32-wasi',f'--prefix={PREFIX}','--disable-shared','--enable-static'], png, env_sjlj); run(['make','-j2','libpng16.la'], png, env_sjlj); run(['make','install-libLTLIBRARIES','install-pkgincludeHEADERS','install-binSCRIPTS','install-pkgconfigDATA'], png, env_sjlj)
    xml = unpack('libxml2-2.12.9.tar.xz'); refresh_config_scripts(xml); run(['./configure','--host=wasm32-wasi',f'--prefix={PREFIX}','--disable-shared','--enable-static','--without-python','--without-iconv','--without-icu','--without-threads','--without-zlib','--without-lzma','--without-http','--without-ftp','--without-modules'], xml, env_sjlj); run(['make','-j2'], xml, env_sjlj); run(['make','install'], xml, env_sjlj)
    xml_env = env_sjlj | {'PATH': f'{PREFIX}/bin:{env_sjlj["PATH"]}', 'CPPFLAGS': f'-I{PREFIX}/include -I{PREFIX}/include/libxml2', 'LIBS': f'-L{PREFIX}/lib'}
    xslt = unpack('libxslt-1.1.39.tar.xz'); refresh_config_scripts(xslt); run(['./configure','--host=wasm32-wasi',f'--prefix={PREFIX}','--disable-shared','--enable-static','--without-python','--without-crypto',f'--with-libxml-prefix={PREFIX}'], xslt, xml_env); run(['make','-j2'], xslt, xml_env); run(['make','install'], xslt, xml_env)
    lcms = unpack('lcms2-2.16.tar.gz', 'lcms2-2.16'); refresh_config_scripts(lcms); run(['./configure','--host=wasm32-wasi',f'--prefix={PREFIX}','--disable-shared','--enable-static'], lcms, env); run(['make','-j2'], lcms, env); run(['make','install'], lcms, env)
    ft = unpack('freetype-2.13.2.tar.gz'); refresh_config_scripts(ft); run(['./configure','--host=wasm32-wasi',f'--prefix={PREFIX}','--disable-shared','--enable-static','--without-harfbuzz','--without-brotli'], ft, env_sjlj); run(['make','-j2'], ft, env_sjlj); run(['make','install'], ft, env_sjlj)
    cmake_build(unpack('tiff-4.6.0.tar.gz'), 'tiff-build', ['-Dtiff-tools=OFF','-Dtiff-tests=OFF','-Dtiff-contrib=OFF','-Dtiff-docs=OFF','-Djbig=OFF','-Dwebp=OFF','-Dzstd=OFF','-Djpeg=ON','-Dzlib=ON','-Dlzma=ON'], env_sjlj)
    webp = unpack('libwebp-1.4.0.tar.gz'); refresh_config_scripts(webp); run(['./configure','--host=wasm32-wasi',f'--prefix={PREFIX}','--disable-shared','--enable-static','--disable-threading','--disable-neon','--disable-sse4.1','--disable-sse2','--disable-mips32','--disable-mipsdsp','--disable-mipsdspr2'], webp, env); run(['make','-j2'], webp, env); run(['make','install'], webp, env)
    cmake_build(unpack('openjpeg-2.5.2.tar.gz','openjpeg-2.5.2'), 'openjpeg-build', ['-DBUILD_CODEC=OFF','-DBUILD_TESTING=OFF',f'-DCMAKE_C_FLAGS={sjlj} -D_WASI_EMULATED_PROCESS_CLOCKS',f'-DCMAKE_EXE_LINKER_FLAGS={sjlj} -lwasi-emulated-process-clocks'], env_sjlj)
    cmake_build(unpack('libyuv-main.tar.gz','libyuv-main'), 'libyuv-build', ['-DBUILD_TESTING=OFF'], env)
    cmake_build(unpack('aom-3.9.1.tar.gz','aom-3.9.1'), 'aom-build', ['-DENABLE_DOCS=OFF','-DENABLE_EXAMPLES=OFF','-DENABLE_TESTS=OFF','-DENABLE_TOOLS=OFF','-DENABLE_NASM=OFF','-DCONFIG_MULTITHREAD=0',f'-DCMAKE_C_FLAGS={sjlj}',f'-DCMAKE_CXX_FLAGS={sjlj}',f'-DCMAKE_EXE_LINKER_FLAGS={sjlj}'], env_sjlj)
    cmake_build(unpack('libavif-1.1.1.tar.gz','libavif-1.1.1'), 'libavif-build', ['-DAVIF_CODEC_AOM=SYSTEM','-DAVIF_LIBYUV=SYSTEM','-DAVIF_BUILD_APPS=OFF','-DAVIF_BUILD_TESTS=OFF',f'-DLIBYUV_INCLUDE_DIR={PREFIX}/include',f'-DLIBYUV_LIBRARY={PREFIX}/lib/libyuv.a',f'-DLIBYUV_LIBRARIES={PREFIX}/lib/libyuv.a',f'-DLIBSHARPYUV_INCLUDE_DIR={PREFIX}/include',f'-DLIBSHARPYUV_LIBRARY={PREFIX}/lib/libsharpyuv.a',f'-DCMAKE_C_FLAGS={sjlj}',f'-DCMAKE_CXX_FLAGS={sjlj}',f'-DCMAKE_EXE_LINKER_FLAGS={sjlj}'], env_sjlj)
    print(PREFIX)
if __name__ == '__main__': main()
