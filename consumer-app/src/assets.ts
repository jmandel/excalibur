// @ts-ignore Bun file-loader asset import
import runtimeUrl from './assets/calibre-runtime.zip' with { type: 'file' };
// @ts-ignore Bun file-loader asset import
import aliceUrl from './assets/samples/alice-wonderland.epub' with { type: 'file' };
// @ts-ignore Bun file-loader asset import
import lookingGlassUrl from './assets/samples/through-the-looking-glass.epub' with { type: 'file' };
// @ts-ignore Bun file-loader asset import
import snarkUrl from './assets/samples/hunting-of-the-snark.epub' with { type: 'file' };

export { runtimeUrl };

export const bundledSamples = [
  {
    name: "Alice's Adventures in Wonderland",
    file: 'alice-wonderland.epub',
    author: 'Lewis Carroll',
    url: aliceUrl,
    source: 'https://www.gutenberg.org/ebooks/11',
    license: 'Project Gutenberg public domain/free use notice; public domain in the United States',
  },
  {
    name: 'Through the Looking-Glass',
    file: 'through-the-looking-glass.epub',
    author: 'Lewis Carroll',
    url: lookingGlassUrl,
    source: 'https://www.gutenberg.org/ebooks/12',
    license: 'Project Gutenberg public domain/free use notice; public domain in the United States',
  },
  {
    name: 'The Hunting of the Snark',
    file: 'hunting-of-the-snark.epub',
    author: 'Lewis Carroll',
    url: snarkUrl,
    source: 'https://www.gutenberg.org/ebooks/13',
    license: 'Project Gutenberg public domain/free use notice; public domain in the United States',
  },
] as const;
