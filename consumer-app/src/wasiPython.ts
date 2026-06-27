// Bun's HTML dev server treats .js under src as a module if imported normally/as file.
// Import the pre-bundled worker as text and create a module Blob URL so Worker
// never receives Bun's HTML fallback or HMR module wrapper.
// @ts-ignore Bun text-loader asset import
import workerSource from './assets/wasiPythonWorker.bundle.txt' with { type: 'text' };
export interface WasiPythonRuntime {
  runPython(code: string): Promise<void>;
  writeFile(path: string, data: Uint8Array): Promise<void>;
  readFile(path: string): Promise<Uint8Array>;
}

type Pending = { resolve(value?: any): void; reject(error: Error): void };

export async function createWasiPython(runtimeUrl: string, emit: (message: string) => void): Promise<WasiPythonRuntime> {
  const workerUrl = URL.createObjectURL(new Blob([workerSource], { type: 'text/javascript' }));
  const worker = new Worker(workerUrl, { type: 'module' });
  const pending = new Map<number, Pending>();
  let nextId = 1;
  worker.onmessage = (ev) => {
    const msg = ev.data;
    if (msg.type === 'stdout') { emit(msg.message); return; }
    const p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    if (msg.type === 'error') {
      if (msg.stack) emit(`ERR stack ${msg.stack}`);
      const err = new Error(msg.error);
      if (msg.stack) err.stack = msg.stack;
      p.reject(err);
    } else {
      p.resolve(msg.data);
    }
  };
  worker.onerror = (ev) => {
    const detail = ev.message || `Worker error at ${ev.filename || 'unknown'}:${ev.lineno || 0}:${ev.colno || 0}`;
    emit(`ERR ${detail}`);
    for (const p of pending.values()) p.reject(new Error(detail));
    pending.clear();
  };
  worker.onmessageerror = () => {
    const detail = 'Worker message deserialization failed';
    emit(`ERR ${detail}`);
    for (const p of pending.values()) p.reject(new Error(detail));
    pending.clear();
  };
  function call(op: string, payload: Record<string, any> = {}, transfer: Transferable[] = []): Promise<any> {
    const id = nextId++;
    const promise = new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
    worker.postMessage({ id, op, ...payload }, transfer);
    return promise;
  }
  await call('init', { runtimeUrl: new URL(runtimeUrl, window.location.href).href });
  return {
    runPython: (code) => call('run', { code }),
    writeFile: (path, data) => call('write', { path, data }, [data.buffer as ArrayBuffer]),
    readFile: (path) => call('read', { path }),
  };
}
