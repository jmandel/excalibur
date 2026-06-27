import { openDB, type DBSchema } from 'idb';
import type { LibraryBook } from './types';

interface LibrarySchema extends DBSchema {
  books: {
    key: string;
    value: LibraryBook;
    indexes: { createdAt: number; title: string };
  };
}

const dbPromise = openDB<LibrarySchema>('kindle-library-studio', 1, {
  upgrade(db) {
    const store = db.createObjectStore('books', { keyPath: 'id' });
    store.createIndex('createdAt', 'createdAt');
    store.createIndex('title', 'title');
  },
});

export async function putBook(book: LibraryBook) { return (await dbPromise).put('books', book); }
export async function getBook(id: string) { return (await dbPromise).get('books', id); }
export async function deleteBook(id: string) { return (await dbPromise).delete('books', id); }
export async function clearBooks() { return (await dbPromise).clear('books'); }
export async function listBooks() {
  const books = await (await dbPromise).getAll('books');
  return books.sort((a, b) => b.updatedAt - a.updatedAt);
}
