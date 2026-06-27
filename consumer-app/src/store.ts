import { create } from 'zustand';
import { bundledSamples } from './assets';
import { clearBooks, deleteBook as dbDelete, getBook, listBooks, putBook } from './db';
import { convertBlobToAzw3, defaultOptions, initConverter, onConverterStatus } from './converter';
import type { ConvertOptions, LibraryAPI, LibraryBook, QueueJob, QueueLog } from './types';

function extOf(name: string) { return name.split('.').pop()?.toLowerCase() || ''; }
function cleanTitle(name: string) { return name.replace(/\.[^.]+$/, '').replace(/[_-]+/g, ' '); }
function azw3Name(book: LibraryBook) { return `${book.title || cleanTitle(book.filename)}.azw3`.replace(/[\\/:*?"<>|]+/g, '-'); }
function now() { return Date.now(); }

export type AppState = {
  books: LibraryBook[];
  selectedDevice: string;
  options: ConvertOptions;
  converterStatus: string;
  loading: boolean;
  deviceConfirmed: boolean;
  queue: QueueJob[];
  logs: QueueLog[];
  activeJobId?: string;
  refresh(): Promise<void>;
  addLog(message: string, job?: Partial<QueueLog>): void;
  initializeConverter(): Promise<void>;
  confirmDevice(): Promise<void>;
  addFiles(files: FileList | File[]): Promise<void>;
  loadSamples(): Promise<void>;
  enqueueBook(id: string): Promise<void>;
  enqueueAllPending(): Promise<void>;
  processQueue(): Promise<void>;
  convertBookNow(id: string, options?: Partial<ConvertOptions>, jobId?: string): Promise<LibraryBook>;
  updateBook(id: string, patch: Partial<LibraryBook>): Promise<void>;
  deleteBook(id: string): Promise<void>;
  clear(): Promise<void>;
  setDevice(profile: string): Promise<void>;
  setOptions(patch: Partial<ConvertOptions>): Promise<void>;
};

export const useAppStore = create<AppState>((set, get) => {
  onConverterStatus((converterStatus) => set({ converterStatus }));
  return {
    books: [], queue: [], logs: [], activeJobId: undefined,
    selectedDevice: defaultOptions.output_profile,
    options: defaultOptions,
    converterStatus: 'Not loaded', loading: false, deviceConfirmed: false,
    async refresh() { set({ books: await listBooks() }); },
    addLog(message, job = {}) { set(s => ({ logs: [...s.logs.slice(-199), { id: crypto.randomUUID(), time: now(), message, ...job }] })); },
    async initializeConverter() { set({ loading: true }); try { await initConverter(); } finally { set({ loading: false }); } },
    async confirmDevice() {
      set({ deviceConfirmed: true });
      get().addLog(`Kindle profile confirmed: ${get().options.output_profile}`);
      for (const book of await listBooks()) if (book.status === 'new') await putBook({ ...book, status: 'ready', updatedAt: now() });
      await get().refresh();
      await get().enqueueAllPending();
    },
    async addFiles(files) {
      const createdAt = now();
      for (const file of Array.from(files)) {
        const ext = extOf(file.name); if (!['epub', 'mobi', 'azw', 'azw3', 'prc', 'pobi'].includes(ext)) continue;
        await putBook({ id: crypto.randomUUID(), title: cleanTitle(file.name), filename: file.name, ext, createdAt, updatedAt: now(), inputBlob: file, inputSize: file.size, status: get().deviceConfirmed ? 'ready' : 'new', tags: [] });
      }
      await get().refresh();
      if (get().deviceConfirmed) await get().enqueueAllPending();
    },
    async loadSamples() {
      const existing = await listBooks();
      for (const sample of bundledSamples) {
        if (existing.some(b => b.sampleSource === sample.source)) continue;
        const resp = await fetch(sample.url);
        const blob = new Blob([await resp.arrayBuffer()], { type: 'application/epub+zip' });
        await putBook({ id: crypto.randomUUID(), title: sample.name, author: sample.author, filename: `${sample.name}.epub`, ext: 'epub', createdAt: now(), updatedAt: now(), inputBlob: blob, inputSize: blob.size, status: get().deviceConfirmed ? 'ready' : 'new', tags: ['sample', 'Lewis Carroll'], sampleSource: sample.source, sampleLicense: sample.license });
      }
      await get().refresh();
      if (get().deviceConfirmed) await get().enqueueAllPending();
    },
    async enqueueBook(id) {
      const book = await getBook(id); if (!book) return;
      const exists = get().queue.some(j => j.bookId === id && (j.status === 'queued' || j.status === 'running'));
      if (exists) return;
      const job: QueueJob = { id: crypto.randomUUID(), bookId: id, bookTitle: book.title, status: 'queued', createdAt: now() };
      set(s => ({ queue: [...s.queue, job] }));
      await putBook({ ...book, status: 'ready', updatedAt: now() });
      get().addLog(`Queued “${book.title}”`, { jobId: job.id, bookId: id });
      void get().processQueue();
      await get().refresh();
    },
    async enqueueAllPending() {
      for (const b of await listBooks()) if (['new', 'ready', 'needs-reconversion', 'error'].includes(b.status)) await get().enqueueBook(b.id);
    },
    async processQueue() {
      if (get().activeJobId) return;
      const job = get().queue.find(j => j.status === 'queued'); if (!job) return;
      set(s => ({ activeJobId: job.id, queue: s.queue.map(j => j.id === job.id ? { ...j, status: 'running', startedAt: now() } : j) }));
      try {
        await get().convertBookNow(job.bookId, undefined, job.id);
        set(s => ({ queue: s.queue.map(j => j.id === job.id ? { ...j, status: 'done', finishedAt: now() } : j), activeJobId: undefined }));
        get().addLog(`Finished “${job.bookTitle}”`, { jobId: job.id, bookId: job.bookId });
      } catch (e) {
        const error = e instanceof Error ? e.message : String(e);
        set(s => ({ queue: s.queue.map(j => j.id === job.id ? { ...j, status: 'error', error, finishedAt: now() } : j), activeJobId: undefined }));
        get().addLog(`Failed “${job.bookTitle}”: ${error}`, { jobId: job.id, bookId: job.bookId });
      }
      void get().processQueue();
    },
    async convertBookNow(id, optionPatch = {}, jobId) {
      const book = await getBook(id); if (!book) throw new Error('Book not found');
      const options = { ...get().options, ...optionPatch };
      await putBook({ ...book, status: 'converting', error: undefined, updatedAt: now() }); await get().refresh();
      get().addLog(`Converting “${book.title}” for ${options.output_profile}`, { jobId, bookId: id });
      try {
        const result = await convertBlobToAzw3(book.inputBlob, book.ext, book.id, options);
        const updated: LibraryBook = { ...book, azw3Blob: result.blob, azw3Name: azw3Name(book), azw3Info: result.info, conversionOptions: result.options, status: 'converted', error: undefined, updatedAt: now() };
        await putBook(updated); await get().refresh(); return updated;
      } catch (e) {
        const updated: LibraryBook = { ...book, status: 'error', error: e instanceof Error ? e.message : String(e), updatedAt: now() };
        await putBook(updated); await get().refresh(); throw e;
      }
    },
    async updateBook(id, patch) { const b = await getBook(id); if (!b) return; await putBook({ ...b, ...patch, updatedAt: now() }); await get().refresh(); },
    async deleteBook(id) { await dbDelete(id); await get().refresh(); },
    async clear() { await clearBooks(); set({ queue: [], logs: [], activeJobId: undefined }); await get().refresh(); },
    async setDevice(profile) { set(s => ({ selectedDevice: profile, options: { ...s.options, output_profile: profile }, deviceConfirmed: false })); for (const b of await listBooks()) if (b.azw3Blob) await putBook({ ...b, status: 'needs-reconversion', updatedAt: now() }); await get().refresh(); },
    async setOptions(patch) { set(s => ({ options: { ...s.options, ...patch } })); for (const b of await listBooks()) if (b.azw3Blob) await putBook({ ...b, status: 'needs-reconversion', updatedAt: now() }); await get().refresh(); },
  };
});

export const libraryAPI: LibraryAPI = {
  listBooks, getBook,
  async renameBook(id, title) { const b = await getBook(id); if (b) await putBook({ ...b, title, updatedAt: now() }); },
  async setAuthor(id, author) { const b = await getBook(id); if (b) await putBook({ ...b, author, updatedAt: now() }); },
  async setTags(id, tags) { const b = await getBook(id); if (b) await putBook({ ...b, tags, updatedAt: now() }); },
  async addTags(id, tags) { const b = await getBook(id); if (b) await putBook({ ...b, tags: Array.from(new Set([...b.tags, ...tags])), updatedAt: now() }); },
  async removeTags(id, tags) { const b = await getBook(id); if (b) await putBook({ ...b, tags: b.tags.filter(t => !tags.includes(t)), updatedAt: now() }); },
  async setNotes(id, notes) { const b = await getBook(id); if (b) await putBook({ ...b, notes, updatedAt: now() }); },
  async convertBook(id, options) { await useAppStore.getState().enqueueBook(id); const b = await getBook(id); if (!b) throw new Error('Book not found'); return b; },
  deleteBook: dbDelete,
};

Object.assign(window, { kindleLibrary: libraryAPI });
