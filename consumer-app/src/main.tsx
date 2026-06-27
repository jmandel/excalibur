import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BookOpen, Download, Library, Loader2, WandSparkles, Plus, Search, Settings2, Sparkles, Tags, Trash2, Upload } from 'lucide-react';
import clsx from 'clsx';
import { useAppStore } from './store';
import { deviceProfiles } from './devices';
import type { LibraryBook } from './types';
import './styles.css';

function fmtBytes(n: number) { return n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`; }
function download(blob: Blob, name: string) { const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = name; document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 1500); }

function Hero() {
  const { converterStatus, loading, loadSamples, enqueueAllPending, deviceConfirmed } = useAppStore();
  const helperStatus = !deviceConfirmed ? 'Waiting for your Kindle' : (converterStatus === 'Not loaded' ? 'Ready when needed' : converterStatus);
  return <section className="hero">
    <div>
      <div className="eyebrow"><Sparkles size={16}/> private browser conversion</div>
      <h1>Your books, ready for your Kindle.</h1>
      <p>First choose your Kindle. Then add books to your library, organize them, and queue Kindle-ready AZW3 copies whenever you’re ready.</p>
      <div className="heroActions">
        <button className="primary" onClick={loadSamples}><BookOpen/> Try with Lewis Carroll</button>
        <button onClick={enqueueAllPending} disabled={loading || !deviceConfirmed}>{loading ? <Loader2 className="spin"/> : <Sparkles/>} Convert waiting books</button>
      </div>
    </div>
    <div className="statusCard">
      <span>Conversion helper</span>
      <strong>{helperStatus}</strong>
      <p>Confirm a device to unlock the queue. Every conversion is tracked here.</p>
    </div>
  </section>;
}

function DeviceSetup() {
  const { selectedDevice, setDevice, options, setOptions, confirmDevice, deviceConfirmed } = useAppStore();
  const selected = deviceProfiles.find(d => d.id === selectedDevice) ?? deviceProfiles[0];
  return <section className="card setup">
    <div className="sectionTitle"><Settings2/><div><h2>Identify your Kindle</h2><p>Conversion starts from your target device profile and can be tuned like calibre.</p></div></div>
    <div className="setupGrid">
      <label className="wide">Device
        <select value={selectedDevice} onChange={e => setDevice(e.target.value)}>{deviceProfiles.map(d => <option key={`${d.id}-${d.label}`} value={d.id}>{d.label}</option>)}</select>
        <span className="hint">{selected.description}</span>
      </label>
      <label>Base font size
        <input type="number" value={options.base_font_size} min={0} step={0.5} onChange={e => setOptions({ base_font_size: Number(e.target.value) })}/>
        <span className="hint">0 = Auto, using the selected profile’s calibre base font.</span>
      </label>
      <label>Margins
        <div className="quad"><input title="left" value={options.margin_left} type="number" onChange={e => setOptions({ margin_left: Number(e.target.value) })}/><input title="right" value={options.margin_right} type="number" onChange={e => setOptions({ margin_right: Number(e.target.value) })}/><input title="top" value={options.margin_top} type="number" onChange={e => setOptions({ margin_top: Number(e.target.value) })}/><input title="bottom" value={options.margin_bottom} type="number" onChange={e => setOptions({ margin_bottom: Number(e.target.value) })}/></div>
        <span className="hint">Left, right, top, bottom in points.</span>
      </label>
      <label className="check"><input type="checkbox" checked={options.dont_compress} onChange={e => setOptions({ dont_compress: e.target.checked })}/> Disable AZW3 compression</label>
      <label className="check"><input type="checkbox" checked={options.no_inline_toc} onChange={e => setOptions({ no_inline_toc: e.target.checked })}/> No inline TOC</label>
      <button className="primary confirmDevice" onClick={confirmDevice}>{deviceConfirmed ? 'Device confirmed' : 'Use this Kindle'}</button>
    </div>
  </section>;
}

function UploadPanel() {
  const { addFiles } = useAppStore();
  return <section className="card upload" onDragOver={e => e.preventDefault()} onDrop={e => { e.preventDefault(); addFiles(e.dataTransfer.files); }}>
    <Upload size={34}/>
    <h2>Drop books here</h2>
    <p>EPUB, MOBI, AZW, AZW3, PRC, POBI</p>
    <label className="fileButton"><Plus/> Choose files<input hidden type="file" multiple accept=".epub,.mobi,.azw,.azw3,.prc,.pobi" onChange={e => e.target.files && addFiles(e.target.files)}/></label>
  </section>;
}

function BookCard({ book }: { book: LibraryBook }) {
  const { enqueueBook, updateBook, deleteBook, deviceConfirmed, queue } = useAppStore();
  const [editingTags, setEditingTags] = useState(book.tags.join(', '));
  const queued = queue.some(j => j.bookId === book.id && (j.status === 'queued' || j.status === 'running'));
  const busy = book.status === 'converting' || queued;
  return <article className={clsx('bookCard', book.status)}>
    <div className="bookTop">
      <div className="cover"><BookOpen/></div>
      <div className="bookMeta">
        <input className="titleInput" value={book.title} onChange={e => updateBook(book.id, { title: e.target.value })}/>
        <input className="authorInput" placeholder="Author" value={book.author ?? ''} onChange={e => updateBook(book.id, { author: e.target.value })}/>
        <div className="small">{book.ext.toUpperCase()} · {fmtBytes(book.inputSize)} · {book.status}</div>
      </div>
    </div>
    <div className="tagRow"><Tags size={15}/><input value={editingTags} placeholder="tags, comma separated" onChange={e => setEditingTags(e.target.value)} onBlur={() => updateBook(book.id, { tags: editingTags.split(',').map(t => t.trim()).filter(Boolean) })}/></div>
    {book.error && <div className="error">{book.error}</div>}
    {book.sampleSource && <a className="source" href={book.sampleSource} target="_blank">Project Gutenberg source</a>}
    <div className="actions">
      <button onClick={() => enqueueBook(book.id)} disabled={busy || !deviceConfirmed}>{busy ? <Loader2 className="spin"/> : <WandSparkles/>}{queued ? 'Queued' : book.azw3Blob ? 'Queue reconvert' : 'Queue convert'}</button>
      <button onClick={() => download(book.inputBlob, book.filename)}><Download/> Input</button>
      <button disabled={!book.azw3Blob} onClick={() => book.azw3Blob && download(book.azw3Blob, book.azw3Name || `${book.title}.azw3`)}><Download/> AZW3</button>
      <button className="ghostDanger" onClick={() => deleteBook(book.id)}><Trash2/> </button>
    </div>
  </article>;
}

function LibraryView() {
  const { books, refresh, clear } = useAppStore();
  const [query, setQuery] = useState('');
  useEffect(() => { refresh(); }, [refresh]);
  const filtered = useMemo(() => books.filter(b => `${b.title} ${b.author ?? ''} ${b.tags.join(' ')}`.toLowerCase().includes(query.toLowerCase())), [books, query]);
  return <section className="card libraryPanel">
    <div className="libraryHead"><div className="sectionTitle"><Library/><div><h2>Library</h2><p>{books.length} books stored locally in IndexedDB.</p></div></div><div className="search"><Search size={17}/><input placeholder="Search title, author, tag" value={query} onChange={e => setQuery(e.target.value)}/></div><button className="ghostDanger" onClick={clear}><Trash2/> Clear</button></div>
    <div className="booksGrid">{filtered.map(b => <BookCard key={b.id} book={b}/>)}{filtered.length === 0 && <div className="empty">No books yet. Load samples or upload your own.</div>}</div>
  </section>;
}

function QueueSidebar() {
  const { queue, logs, activeJobId, converterStatus, deviceConfirmed } = useAppStore();
  const counts = {
    queued: queue.filter(j => j.status === 'queued').length,
    running: queue.filter(j => j.status === 'running').length,
    done: queue.filter(j => j.status === 'done').length,
    error: queue.filter(j => j.status === 'error').length,
  };
  const active = queue.find(j => j.id === activeJobId);
  return <aside className="queuePanel">
    <h2>Conversion queue</h2>
    <p>{deviceConfirmed ? converterStatus === 'Not loaded' ? 'Waiting for books.' : converterStatus : 'Confirm your Kindle to enable conversion.'}</p>
    <div className="queueCounts"><span>{counts.queued} queued</span><span>{counts.running} running</span><span>{counts.done} done</span><span>{counts.error} errors</span></div>
    {active && <div className="activeJob"><strong>Now converting</strong><br/>{active.bookTitle}</div>}
    <div className="jobList">{queue.slice(-8).reverse().map(j => <div key={j.id} className={`job ${j.status}`}><span>{j.status}</span><strong>{j.bookTitle}</strong>{j.startedAt && <small>{Math.round(((j.finishedAt ?? Date.now()) - j.startedAt) / 1000)}s</small>}</div>)}</div>
    <details open><summary>Logs</summary><div className="logList">{logs.slice(-30).reverse().map(l => <div key={l.id}><time>{new Date(l.time).toLocaleTimeString()}</time> {l.message}</div>)}</div></details>
  </aside>;
}

function AssistantApiNote() {
  return <section className="card apiNote"><h2>LLM-ready library API</h2><p>This app exposes <code>window.kindleLibrary</code> for future assistants: list, rename, tag, annotate, convert, and delete books with user permission.</p><pre>{`await kindleLibrary.listBooks()
await kindleLibrary.renameBook(id, "New title")
await kindleLibrary.addTags(id, ["fiction", "queued"])
await kindleLibrary.convertBook(id, { output_profile: "kindle_oasis" })`}</pre></section>;
}

function App() { return <><Hero/><div className="appShell"><main><DeviceSetup/><UploadPanel/><LibraryView/><AssistantApiNote/></main><QueueSidebar/></div></>; }

createRoot(document.getElementById('root')!).render(<App/>);
