import { unzipSync } from 'fflate';
import { WASI, File, OpenFile, ConsoleStdout, PreopenDirectory, Directory } from '@bjorn3/browser_wasi_shim';

interface RuntimeFiles { wasm: Uint8Array; rootEntries: [string, File | Directory][]; }
let runtime: RuntimeFiles | undefined;
let root: any;
let rootDir: any;
let counter = 0;

function post(type: string, payload: any = {}) { self.postMessage({ type, ...payload }); }
function addPath(entries: Map<string, any>, parts: string[], data: Uint8Array) {
  let cur = entries;
  for (const part of parts.slice(0, -1)) {
    let child = cur.get(part);
    if (!(child instanceof Map)) { child = new Map<string, any>(); cur.set(part, child); }
    cur = child;
  }
  cur.set(parts[parts.length - 1], data);
}
function materialize(tree: Map<string, any>): [string, File | Directory][] {
  return [...tree.entries()].map(([name, value]) => value instanceof Map
    ? [name, new Directory(new Map(materialize(value)))] as [string, Directory]
    : [name, new File(value)] as [string, File]);
}
async function loadRuntime(runtimeUrl: string): Promise<RuntimeFiles> {
  const resp = await fetch(runtimeUrl);
  if (!resp.ok) throw new Error(`Failed to fetch runtime: ${resp.status}`);
  const bytes = new Uint8Array(await resp.arrayBuffer());
  const zip = unzipSync(bytes);
  const tree = new Map<string, any>();
  let wasm: Uint8Array | undefined;
  for (const [name, data] of Object.entries(zip)) {
    if (name.endsWith('/')) continue;
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    if (name === 'wasi/python.wasm') { wasm = bytes; continue; }
    if (name.startsWith('wasi/')) addPath(tree, name.slice('wasi/'.length).split('/'), bytes);
    else addPath(tree, name.split('/'), bytes);
  }
  if (!wasm) throw new Error('runtime zip missing wasi/python.wasm');
  return { wasm, rootEntries: materialize(tree) };
}
function findEntry(dir: any, parts: string[]): any {
  let cur = dir;
  for (const part of parts) { cur = cur.contents.get(part); if (!cur) return undefined; }
  return cur;
}
function ensureDir(dir: any, parts: string[]): any {
  let cur = dir;
  for (const part of parts) {
    let child = cur.contents.get(part);
    if (!child) { child = new Directory(new Map()); cur.contents.set(part, child); child.parent = cur; }
    cur = child;
  }
  return cur;
}
function writeFile(path: string, data: Uint8Array) {
  const parts = path.replace(/^\/+/, '').split('/').filter(Boolean);
  const parent = ensureDir(rootDir, parts.slice(0, -1));
  parent.contents.set(parts[parts.length - 1], new File(data));
}
function readFile(path: string): Uint8Array {
  const entry = findEntry(rootDir, path.replace(/^\/+/, '').split('/').filter(Boolean));
  if (!entry || !(entry instanceof File)) throw new Error(`No such file: ${path}`);
  return entry.data;
}
async function runPython(code: string) {
  if (!runtime || !root) throw new Error('runtime not initialized');
  const scriptPath = `/tmp/web-run-${counter++}.py`;
  writeFile(scriptPath, new TextEncoder().encode(code));
  const pyPath = '/build/lib.wasi-wasm32-3.12:/Lib:/third_party_site:/experiments:/third_party/calibre/src';
  const wasi = new WASI(
    ['python.wasm', '-S', scriptPath],
    ['PYTHONDONTWRITEBYTECODE=1', `PYTHONPATH=${pyPath}`, 'PYTHONHOME=/'],
    [
      new OpenFile(new File([])),
      ConsoleStdout.lineBuffered((msg: string) => post('stdout', { message: msg })),
      ConsoleStdout.lineBuffered((msg: string) => post('stdout', { message: `ERR ${msg}` })),
      root,
    ],
  );
  const mod = await WebAssembly.compile(runtime.wasm);
  const inst = await WebAssembly.instantiate(mod, { wasi_snapshot_preview1: wasi.wasiImport });
  wasi.start(inst as any);
}

self.onmessage = async (ev: MessageEvent) => {
  const { id, op } = ev.data;
  try {
    if (op === 'init') {
      runtime = await loadRuntime(ev.data.runtimeUrl);
      root = new PreopenDirectory('/', new Map(runtime.rootEntries)) as any;
      rootDir = root.dir;
      ensureDir(rootDir, ['tmp']); ensureDir(rootDir, ['work']);
      post('result', { id });
    } else if (op === 'run') {
      await runPython(ev.data.code);
      post('result', { id });
    } else if (op === 'write') {
      writeFile(ev.data.path, ev.data.data);
      post('result', { id });
    } else if (op === 'read') {
      const data = readFile(ev.data.path);
      post('result', { id, data },);
    }
  } catch (e) {
    const err = e instanceof Error ? e : new Error(String(e));
    post('error', { id, error: err.message, stack: err.stack });
  }
};
