import { create } from 'zustand';
import { bundledSamples } from './assets';
import { convertBlobToAzw3, onRuntimeLine } from './converter';
import { readEpubMeta } from './epubMeta';
import { matchStage, type Stage } from './stages';
import { defaultDevice } from './devices';

export type Phase = 'idle' | 'converting' | 'done' | 'error';

type BookMeta = { title: string; author: string; ext: string; sizeText: string };

// Inputs worth converting *into* Kindle format — i.e. not Kindle formats themselves.
const ACCEPTED = ['epub', 'mobi', 'fb2', 'prc', 'txt', 'html', 'htmlz', 'pdb', 'rtf'];

function extOf(name: string) { return name.split('.').pop()?.toLowerCase() || ''; }
function cleanTitle(name: string) { return name.replace(/\.[^.]+$/, '').replace(/[_-]+/g, ' ').trim() || 'Untitled'; }
function safeName(title: string) { return `${title}.azw3`.replace(/[\\/:*?"<>|]+/g, '-'); }
function mb(bytes: number) { return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1048576).toFixed(1)} MB`; }

type State = {
  phase: Phase;
  device: string;
  book: BookMeta | null;
  stage: Stage;
  label: string;
  fraction: number;
  lastLine: string;
  resultUrl?: string;
  resultName?: string;
  error?: string;
  setDevice(id: string): void;
  convertFile(file: File): Promise<void>;
  convertSample(index: number): Promise<void>;
  reset(): void;
};

export const useStore = create<State>((set, get) => ({
  phase: 'idle',
  device: defaultDevice,
  book: null,
  stage: 'import',
  label: '',
  fraction: 0,
  lastLine: '',

  setDevice(id) { set({ device: id }); },

  reset() {
    const url = get().resultUrl;
    if (url) URL.revokeObjectURL(url);
    onRuntimeLine(undefined);
    set({ phase: 'idle', book: null, stage: 'import', label: '', fraction: 0, lastLine: '', resultUrl: undefined, resultName: undefined, error: undefined });
  },

  async convertSample(index) {
    const sample = bundledSamples[index];
    const resp = await fetch(sample.url);
    const blob = await resp.blob();
    const file = new File([blob], sample.file, { type: 'application/epub+zip' });
    await get().convertFile(file);
  },

  async convertFile(file: File) {
    const prev = get().resultUrl;
    if (prev) URL.revokeObjectURL(prev);

    const ext = extOf(file.name);
    if (!ACCEPTED.includes(ext)) {
      set({ phase: 'error', error: `${ext ? `.${ext}` : 'That file'} isn't a book Excalibur can read. Try an EPUB or MOBI.`, book: null });
      return;
    }

    const meta = ext === 'epub' ? await readEpubMeta(file) : {};
    const book: BookMeta = {
      title: meta.title || cleanTitle(file.name),
      author: meta.author || '',
      ext,
      sizeText: mb(file.size),
    };
    set({ phase: 'converting', book, stage: 'import', label: 'Warming up the converter', fraction: 0.04, lastLine: '', error: undefined, resultUrl: undefined, resultName: undefined });

    // Forward-only progress driven by calibre's log lines. Benign sandbox noise
    // (calibre can't probe write-access in WASM, deprecations, etc.) is hidden from
    // the line shown to users so the converting screen stays calm.
    const NOISE = /no write access|using a temporary|deprecat|^\s*warning\b/i;
    let maxFraction = 0.04;
    onRuntimeLine((line) => {
      const hint = matchStage(line);
      const trimmed = line.replace(/^ERR\s+/, '').trim();
      const showable = trimmed && !NOISE.test(trimmed) ? trimmed : '';
      if (hint && hint.fraction >= maxFraction) {
        maxFraction = hint.fraction;
        set({ stage: hint.stage, label: hint.label, fraction: hint.fraction, lastLine: showable });
      } else if (showable) {
        set({ lastLine: showable });
      }
    });

    try {
      const result = await convertBlobToAzw3(file, ext, crypto.randomUUID(), { output_profile: get().device });
      onRuntimeLine(undefined);
      const url = URL.createObjectURL(result.blob);
      set({ phase: 'done', stage: 'done', label: 'Ready', fraction: 1, resultUrl: url, resultName: safeName(book.title) });
    } catch (e) {
      onRuntimeLine(undefined);
      set({ phase: 'error', error: e instanceof Error ? e.message : String(e) });
    }
  },
}));
