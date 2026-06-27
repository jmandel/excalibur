import { runtimeUrl } from './assets';
import { createWasiPython, type WasiPythonRuntime } from './wasiPython';
import type { ConvertOptions } from './types';

let pythonPromise: Promise<WasiPythonRuntime> | undefined;
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
  if (ready && pythonPromise) return pythonPromise;
  if (pythonPromise) return pythonPromise;
  pythonPromise = (async () => {
    emit('Loading static WASI CPython runtime');
    const python = await createWasiPython(runtimeUrl, emit);
    emit('Initializing calibre conversion API');
    await python.runPython(`
import sys, os
os.chdir('/')
import browser_convert
from PIL import Image, features
print('native pillow', Image.__version__, features.check('webp'), features.check('avif'))
`);
    ready = true;
    emit('Converter ready');
    return python;
  })();
  return pythonPromise;
}

export async function convertBlobToAzw3(input: Blob, ext: string, id: string, options: Partial<ConvertOptions> = {}) {
  const python = await initConverter();
  const opts = { ...defaultOptions, ...options };
  const safe = id.replace(/[^a-zA-Z0-9_-]/g, '_');
  const inPath = `/work/${safe}.${ext}`;
  const outPath = `/work/${safe}.azw3`;
  await python.writeFile(inPath, new Uint8Array(await input.arrayBuffer()));
  await python.writeFile('/tmp/options.json', new TextEncoder().encode(JSON.stringify(opts)));
  await python.writeFile('/tmp/convert-result.json', new Uint8Array());
  await python.runPython(`
import browser_convert, json
with open('/tmp/options.json', 'r', encoding='utf-8') as f:
    opts = json.load(f)
info = browser_convert.convert_file(${JSON.stringify(inPath)}, ${JSON.stringify(outPath)}, opts)
with open('/tmp/convert-result.json', 'w', encoding='utf-8') as f:
    json.dump(info, f)
`);
  const outBytes = await python.readFile(outPath);
  const infoText = new TextDecoder().decode(await python.readFile('/tmp/convert-result.json'));
  const info = infoText ? JSON.parse(infoText) : null;
  return {
    blob: new Blob([outBytes.slice()], { type: 'application/octet-stream' }),
    info,
    options: opts,
  };
}
