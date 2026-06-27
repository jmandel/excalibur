import { loadPyodide } from 'https://cdn.jsdelivr.net/pyodide/v0.28.3/full/pyodide.mjs';

const DB_NAME = 'kindle-wasm-library';
const DB_VERSION = 1;
let db;
let pyodide;
let converterReady = false;

const $ = (id) => document.getElementById(id);
const logEl = $('log');
function log(...args) {
  const line = args.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ');
  logEl.textContent += line + '\n';
  logEl.scrollTop = logEl.scrollHeight;
  console.log(...args);
}
function status(s) { $('runtimeStatus').textContent = s; }
function setProgress(v) { $('runtimeProgress').value = v; }

function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const d = req.result;
      const store = d.createObjectStore('books', { keyPath: 'id' });
      store.createIndex('createdAt', 'createdAt');
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
function txStore(mode='readonly') { return db.transaction('books', mode).objectStore('books'); }
function putBook(book) { return new Promise((res, rej) => { const r = txStore('readwrite').put(book); r.onsuccess=()=>res(); r.onerror=()=>rej(r.error); }); }
function getBook(id) { return new Promise((res, rej) => { const r = txStore().get(id); r.onsuccess=()=>res(r.result); r.onerror=()=>rej(r.error); }); }
function getAllBooks() { return new Promise((res, rej) => { const r = txStore().getAll(); r.onsuccess=()=>res(r.result.sort((a,b)=>b.createdAt-a.createdAt)); r.onerror=()=>rej(r.error); }); }
function deleteBook(id) { return new Promise((res, rej) => { const r = txStore('readwrite').delete(id); r.onsuccess=()=>res(); r.onerror=()=>rej(r.error); }); }
function clearBooks() { return new Promise((res, rej) => { const r = txStore('readwrite').clear(); r.onsuccess=()=>res(); r.onerror=()=>rej(r.error); }); }

function extOf(name) { return name.split('.').pop().toLowerCase(); }
function baseName(name) { return name.replace(/\.[^.]+$/, ''); }
function fmtBytes(n) { return n < 1024 ? `${n} B` : n < 1024*1024 ? `${(n/1024).toFixed(1)} KB` : `${(n/1024/1024).toFixed(1)} MB`; }
function bytesFromArrayBuffer(ab) { return new Uint8Array(ab); }

function currentOptions() {
  const num = id => Number($(id).value);
  return {
    output_profile: $('outputProfile').value,
    base_font_size: num('baseFontSize'),
    margin_left: num('marginLeft'),
    margin_right: num('marginRight'),
    margin_top: num('marginTop'),
    margin_bottom: num('marginBottom'),
    dont_compress: $('dontCompress').checked,
    no_inline_toc: $('noInlineToc').checked,
  };
}

async function initConverter() {
  if (converterReady) return;
  $('initBtn').disabled = true;
  try {
    status('Loading Pyodide...'); setProgress(5);
    pyodide = await loadPyodide({ stdout: (s) => log(s), stderr: (s) => log('ERR', s) });
    setProgress(15);
    status('Loading packages...');
    await pyodide.loadPackage(['micropip', 'lxml', 'Pillow', 'python-dateutil', 'regex', 'beautifulsoup4', 'html5lib', 'webencodings', 'msgpack', 'tzdata', 'lzma']);
    setProgress(35);
    await pyodide.runPythonAsync(`
import micropip
for pkg in ['css-parser', 'chardet', 'tzlocal']:
    await micropip.install(pkg)
`);
    setProgress(50);
    status('Loading calibre runtime...');
    const resp = await fetch('calibre-runtime.zip');
    if (!resp.ok) throw new Error(`Failed to fetch runtime: ${resp.status}`);
    const zipBytes = new Uint8Array(await resp.arrayBuffer());
    pyodide.FS.mkdirTree('/app');
    pyodide.FS.writeFile('/tmp/calibre-runtime.zip', zipBytes);
    await pyodide.runPythonAsync(`
import zipfile, os
with zipfile.ZipFile('/tmp/calibre-runtime.zip') as z:
    z.extractall('/app')
os.chdir('/app')
`);
    setProgress(70);
    status('Initializing converter...');
    await pyodide.runPythonAsync(`
import sys
sys.path.insert(0, '/app/experiments')
import browser_convert
`);
    setProgress(100);
    converterReady = true;
    status('Ready');
    log('Converter ready');
  } catch (e) {
    status('Failed');
    log('INIT FAILED', e.message || String(e));
    $('initBtn').disabled = false;
    throw e;
  }
}

async function convertBook(id) {
  await initConverter();
  const book = await getBook(id);
  if (!book) return;
  book.status = 'converting'; await putBook(book); await renderLibrary();
  try {
    const safe = id.replace(/[^a-zA-Z0-9_-]/g, '_');
    const inPath = `/work/${safe}.${book.ext}`;
    const outPath = `/work/${safe}.azw3`;
    pyodide.FS.mkdirTree('/work');
    pyodide.FS.writeFile(inPath, new Uint8Array(await book.inputBlob.arrayBuffer()));
    const options = currentOptions();
    pyodide.globals.set('IN_PATH', inPath);
    pyodide.globals.set('OUT_PATH', outPath);
    pyodide.globals.set('OPTIONS', options);
    const info = await pyodide.runPythonAsync(`
import browser_convert
browser_convert.convert_file(IN_PATH, OUT_PATH, dict(OPTIONS.to_py()))
`);
    const outBytes = pyodide.FS.readFile(outPath);
    book.azw3Blob = new Blob([outBytes], { type: 'application/octet-stream' });
    book.azw3Name = `${baseName(book.name)}.azw3`;
    book.azw3Info = info.toJs ? info.toJs({dict_converter: Object.fromEntries}) : info;
    book.options = options;
    book.status = 'converted';
    log('Converted', book.name, book.azw3Info);
  } catch (e) {
    book.status = 'error';
    book.error = e.message || String(e);
    log('CONVERT FAILED', book.name, book.error);
  }
  await putBook(book);
  await renderLibrary();
}

async function addFiles(files) {
  for (const file of files) {
    const ext = extOf(file.name);
    if (!['epub','mobi','azw','azw3','prc','pobi'].includes(ext)) {
      log('Skipping unsupported file', file.name);
      continue;
    }
    const book = {
      id: crypto.randomUUID(),
      name: file.name,
      ext,
      createdAt: Date.now(),
      inputBlob: file,
      inputSize: file.size,
      status: 'new',
    };
    await putBook(book);
  }
  await renderLibrary();
}

async function loadSamples() {
  const manifest = await (await fetch('samples/manifest.json')).json();
  for (const sample of manifest) {
    const existing = (await getAllBooks()).find(b => b.sampleSource === sample.source);
    if (existing) continue;
    log('Loading sample', sample.name);
    const resp = await fetch(`samples/${sample.file}`);
    if (!resp.ok) throw new Error(`Failed to load sample ${sample.file}: ${resp.status}`);
    const blob = new Blob([await resp.arrayBuffer()], { type: 'application/epub+zip' });
    const book = {
      id: crypto.randomUUID(),
      name: `${sample.name} - ${sample.author}.epub`,
      ext: 'epub',
      createdAt: Date.now(),
      inputBlob: blob,
      inputSize: blob.size,
      status: 'new',
      sampleSource: sample.source,
      sampleLicense: sample.license,
    };
    await putBook(book);
  }
  await renderLibrary();
}

function downloadBlob(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

async function renderLibrary() {
  const rows = await getAllBooks();
  const body = $('libraryBody');
  body.textContent = '';
  for (const b of rows) {
    const tr = document.createElement('tr');
    const inputInfo = `${b.ext.toUpperCase()} · ${fmtBytes(b.inputSize)} · ${b.status || 'new'}`;
    const azwInfo = b.azw3Blob ? `${b.azw3Name} · ${fmtBytes(b.azw3Blob.size)}` : (b.error ? `Error: ${b.error}` : '—');
    tr.innerHTML = `<td><strong></strong><div class="small"></div></td><td></td><td></td><td class="actions"></td>`;
    tr.children[0].querySelector('strong').textContent = b.name;
    tr.children[0].querySelector('.small').textContent = b.sampleSource ? `Sample · ${b.sampleSource}` : (b.options ? `profile=${b.options.output_profile}` : '');
    tr.children[1].textContent = inputInfo;
    tr.children[2].textContent = azwInfo;
    const actions = tr.children[3];
    const convertBtn = document.createElement('button'); convertBtn.textContent = b.azw3Blob ? 'Reconvert' : 'Convert to AZW3'; convertBtn.onclick = () => convertBook(b.id); actions.append(convertBtn);
    const dlInput = document.createElement('button'); dlInput.textContent = 'Download input'; dlInput.onclick = () => downloadBlob(b.inputBlob, b.name); actions.append(dlInput);
    if (b.azw3Blob) { const dl = document.createElement('button'); dl.textContent = 'Download AZW3'; dl.onclick = () => downloadBlob(b.azw3Blob, b.azw3Name); actions.append(dl); }
    const del = document.createElement('button'); del.textContent = 'Delete'; del.className = 'danger'; del.onclick = async () => { await deleteBook(b.id); await renderLibrary(); }; actions.append(del);
    body.append(tr);
  }
}

$('initBtn').onclick = initConverter;
$('fileInput').onchange = (e) => addFiles(e.target.files);
$('loadSamplesBtn').onclick = loadSamples;
$('refreshBtn').onclick = renderLibrary;
$('clearBtn').onclick = async () => { if (confirm('Clear all stored books?')) { await clearBooks(); await renderLibrary(); } };

db = await openDB();
await renderLibrary();
log('Library ready. Initialize converter, then upload books.');
