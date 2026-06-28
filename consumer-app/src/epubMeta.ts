import { unzipSync, strFromU8 } from 'fflate';

const decode = (s: string) =>
  s
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&apos;/g, "'")
    .trim();

/** Best-effort EPUB title/author from the OPF package document. */
export async function readEpubMeta(file: File): Promise<{ title?: string; author?: string }> {
  try {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const zip = unzipSync(bytes);
    const container = zip['META-INF/container.xml'];
    if (!container) return {};
    const opfPath = strFromU8(container).match(/full-path="([^"]+)"/)?.[1];
    if (!opfPath || !zip[opfPath]) return {};
    const opf = strFromU8(zip[opfPath]);
    const title = opf.match(/<dc:title[^>]*>([^<]+)<\/dc:title>/i)?.[1];
    const author = opf.match(/<dc:creator[^>]*>([^<]+)<\/dc:creator>/i)?.[1];
    return { title: title ? decode(title) : undefined, author: author ? decode(author) : undefined };
  } catch {
    return {};
  }
}
