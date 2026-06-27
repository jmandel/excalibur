#!/usr/bin/env python3
from __future__ import annotations
import argparse, shutil, subprocess, tempfile, textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_WASM = Path('/tmp/python-exnref.wasm')
SRC = ROOT / 'experiments/static-wasi-python/Python-3.12.0'
THIRD = ROOT / 'experiments/static-wasi-python/third-party-site'
FIXTURE = ROOT / 'fixtures/generated/minimal.epub'

JAVA = r'''
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasi.*;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

public class ChicoryWasiPythonProbe {
  public static void main(String[] args) throws Exception {
    Path wasm = Path.of(args[0]), srcRoot = Path.of(args[1]), third = Path.of(args[2]), repo = Path.of(args[3]), work = Path.of(args[4]);
    String mode = args[5];
    java.nio.file.Files.createDirectories(work); java.nio.file.Files.createDirectories(work.resolve(".config"));
    String code;
    if (mode.equals("print")) code = "print('hello from chicory wasi python', flush=True)\n";
    else if (mode.equals("import")) code = "import os\nos.chdir('/')\nimport browser_convert\nprint('imported browser_convert', flush=True)\n";
    else if (mode.equals("convert")) {
      String input = args[6];
      code = "import os, json\nos.chdir('/')\nimport browser_convert\nprint('imported browser_convert', flush=True)\ninfo = browser_convert.convert_file('/work/" + input + "', '/work/out.azw3', {'output_profile':'kindle_oasis','base_font_size':0,'margin_left':5,'margin_right':5,'margin_top':5,'margin_bottom':5,'dont_compress':False,'no_inline_toc':False})\nprint('converted', json.dumps(info, sort_keys=True), flush=True)\n";
    } else throw new IllegalArgumentException("unknown mode " + mode);
    java.nio.file.Files.writeString(work.resolve("probe.py"), code);
    ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
    String pyPath = "/builddir/wasi/build/lib.wasi-wasm32-3.12:/Lib:/third_party_site:/repo/experiments:/repo/third_party/calibre/src";
    WasiOptions opts = WasiOptions.builder()
      .withArguments(List.of("python.wasm", "-S", "/work/probe.py"))
      .withEnvironment("PYTHONPATH", pyPath).withEnvironment("PYTHONDONTWRITEBYTECODE", "1")
      .withEnvironment("HOME", "/work").withEnvironment("XDG_CONFIG_HOME", "/work/.config")
      .withDirectory("/", srcRoot).withDirectory("/third_party_site", third).withDirectory("/repo", repo).withDirectory("/work", work)
      .withStdout(stdout).withStderr(stderr).withThrowOnExit0(false).build();
    try (WasiPreview1 wasi = WasiPreview1.builder().withOptions(opts).build()) {
      WasmModule module = Parser.parse(wasm.toFile());
      ImportValues imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
      long start = System.nanoTime();
      Instance.builder(module).withImportValues(imports).build();
      System.out.println("START_OK ms=" + String.format("%.2f", (System.nanoTime() - start) / 1_000_000.0));
    } catch (Throwable t) {
      System.out.println("START_FAIL " + t.getClass().getName() + ": " + t.getMessage()); t.printStackTrace(System.out);
    }
    System.out.println("--- stdout ---"); System.out.print(stdout.toString());
    System.out.println("--- stderr ---"); System.out.print(stderr.toString());
  }
}
'''

def chicory_classpath() -> str:
    jars = sorted(Path.home().glob('.gradle/caches/modules-2/files-2.1/com.dylibso.chicory/*/1.7.5/**/*.jar'))
    if not jars:
        raise SystemExit('Chicory jars not found; build android-app once to populate Gradle cache')
    return ':'.join(str(p) for p in jars)

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--wasm', type=Path, default=DEFAULT_WASM)
    ap.add_argument('--mode', choices=['print', 'import', 'convert'], default='convert')
    args = ap.parse_args()
    if not args.wasm.exists():
        raise SystemExit(f'missing {args.wasm}; run translate_wasm_eh_to_exnref.py first')
    cp = chicory_classpath()
    with tempfile.TemporaryDirectory() as td:
        td = Path(td); work = td / 'work'; work.mkdir()
        src = td / 'ChicoryWasiPythonProbe.java'; src.write_text(JAVA)
        subprocess.run(['javac', '-cp', cp, str(src)], check=True)
        cmd = ['java', '-Xmx3g', '-cp', f'{td}:{cp}', 'ChicoryWasiPythonProbe', str(args.wasm), str(SRC), str(THIRD), str(ROOT), str(work), args.mode]
        if args.mode == 'convert':
            shutil.copy2(FIXTURE, work / FIXTURE.name); cmd.append(FIXTURE.name)
        run = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(run.stdout)
        if args.mode == 'convert':
            out = work / 'out.azw3'
            print(f'out.azw3 exists={out.exists()} size={out.stat().st_size if out.exists() else 0}')
            if not out.exists() or out.stat().st_size == 0: return 1
        return run.returncode

if __name__ == '__main__':
    raise SystemExit(main())
