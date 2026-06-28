// Five coarse conversion stages, shared visual language with the Android app.
export type Stage = 'import' | 'parse' | 'layout' | 'write' | 'done';
export const STAGES: { id: Stage; label: string }[] = [
  { id: 'import', label: 'Import' },
  { id: 'parse', label: 'Parse' },
  { id: 'layout', label: 'Layout' },
  { id: 'write', label: 'Write' },
  { id: 'done', label: 'Ready' },
];

export type StageHint = { stage: Stage; label: string; fraction: number };

// calibre emits log lines, not percentages. Map the lines we recognize to a
// stage + coarse fraction. Order is forward-only; the store never moves back.
const MARKERS: [string, StageHint][] = [
  ['imported browser_convert', { stage: 'import', label: 'Starting up', fraction: 0.06 }],
  ['Input running', { stage: 'parse', label: 'Reading the book', fraction: 0.18 }],
  ['Parsing all content', { stage: 'parse', label: 'Reading the book', fraction: 0.24 }],
  ['Merging user specified metadata', { stage: 'parse', label: 'Reading the book', fraction: 0.32 }],
  ['Detecting structure', { stage: 'layout', label: 'Laying out pages', fraction: 0.46 }],
  ['Detected chapters', { stage: 'layout', label: 'Laying out pages', fraction: 0.52 }],
  ['Flattening CSS', { stage: 'layout', label: 'Laying out pages', fraction: 0.58 }],
  ['Rasterizing', { stage: 'layout', label: 'Laying out pages', fraction: 0.62 }],
  ['inline TOC', { stage: 'layout', label: 'Laying out pages', fraction: 0.66 }],
  ['Creating', { stage: 'write', label: 'Building the Kindle file', fraction: 0.8 }],
  ['Serializing', { stage: 'write', label: 'Building the Kindle file', fraction: 0.86 }],
  ['Compressing', { stage: 'write', label: 'Building the Kindle file', fraction: 0.9 }],
  ['converted', { stage: 'done', label: 'Finishing', fraction: 0.98 }],
];

export function matchStage(line: string): StageHint | undefined {
  const lower = line.toLowerCase();
  for (const [needle, hint] of MARKERS) if (lower.includes(needle.toLowerCase())) return hint;
  return undefined;
}

export function stageIndex(stage: Stage): number {
  return Math.max(0, STAGES.findIndex((s) => s.id === stage));
}
