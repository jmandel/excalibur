import { runtimeUrl } from './assets';
import type { ConvertOptions } from './types';

let pyodidePromise: Promise<any> | undefined;
let ready = false;
let statusCallback: ((message: string) => void) | undefined;

export function onConverterStatus(cb: (message: string) => void) {
  statusCallback = cb;
}
function emit(message: string) {
  statusCallback?.(message);
  console.log('[converter]', message);
}

export const defaultOptions: ConvertOptions = {
  output_profile: 'kindle_oasis',
  base_font_size: 0,
  margin_left: 5,
  margin_right: 5,
  margin_top: 5,
  margin_bottom: 5,
  dont_compress: false,
  no_inline_toc: false,
};

export async function initConverter() {
  if (ready && pyodidePromise) return pyodidePromise;
  if (pyodidePromise) return pyodidePromise;
  pyodidePromise = (async () => {
    emit('Loading Pyodide runtime');
    // Bun serves/transpiles the app, but Pyodide itself is loaded from the official browser bundle.
    // @ts-ignore remote ESM import
    const { loadPyodide } = await import('https://cdn.jsdelivr.net/pyodide/v0.28.3/full/pyodide.mjs');
    const pyodide = await loadPyodide({
      indexURL: 'https://cdn.jsdelivr.net/pyodide/v0.28.3/full/',
      stdout: emit,
      stderr: (s: string) => emit(`ERR ${s}`),
    });
    emit('Loading Python packages');
    await pyodide.loadPackage(['micropip', 'lxml', 'Pillow', 'python-dateutil', 'regex', 'beautifulsoup4', 'html5lib', 'webencodings', 'msgpack', 'tzdata', 'lzma']);
    await pyodide.runPythonAsync(`
import micropip
for pkg in ['css-parser', 'chardet', 'tzlocal']:
    await micropip.install(pkg)
`);
    emit('Loading calibre runtime');
    const resp = await fetch(runtimeUrl);
    if (!resp.ok) throw new Error(`Failed to fetch calibre runtime: ${resp.status}`);
    const bytes = new Uint8Array(await resp.arrayBuffer());
    pyodide.FS.mkdirTree('/app');
    pyodide.FS.writeFile('/tmp/calibre-runtime.zip', bytes);
    await pyodide.runPythonAsync(`
import zipfile, os
with zipfile.ZipFile('/tmp/calibre-runtime.zip') as z:
    z.extractall('/app')
os.chdir('/app')
`);
    emit('Initializing calibre conversion API');
    await pyodide.runPythonAsync(`
import sys
sys.path.insert(0, '/app/experiments')
import browser_convert
`);
    ready = true;
    emit('Converter ready');
    return pyodide;
  })();
  return pyodidePromise;
}

export async function convertBlobToAzw3(input: Blob, ext: string, id: string, options: Partial<ConvertOptions> = {}) {
  const pyodide = await initConverter();
  const opts = { ...defaultOptions, ...options };
  const safe = id.replace(/[^a-zA-Z0-9_-]/g, '_');
  const inPath = `/work/${safe}.${ext}`;
  const outPath = `/work/${safe}.azw3`;
  pyodide.FS.mkdirTree('/work');
  pyodide.FS.writeFile(inPath, new Uint8Array(await input.arrayBuffer()));
  pyodide.globals.set('IN_PATH', inPath);
  pyodide.globals.set('OUT_PATH', outPath);
  pyodide.globals.set('OPTIONS', opts);
  const info = await pyodide.runPythonAsync(`
import browser_convert
browser_convert.convert_file(IN_PATH, OUT_PATH, dict(OPTIONS.to_py()))
`);
  const outBytes = pyodide.FS.readFile(outPath);
  return {
    blob: new Blob([outBytes], { type: 'application/octet-stream' }),
    info: info?.toJs ? info.toJs({ dict_converter: Object.fromEntries }) : info,
    options: opts,
  };
}
