export type DeviceProfile = {
  id: string;
  label: string;
  description: string;
  screen: string;
  recommended?: boolean;
};

export type BookStatus = 'new' | 'ready' | 'converting' | 'converted' | 'needs-reconversion' | 'error';

export type LibraryBook = {
  id: string;
  title: string;
  filename: string;
  ext: string;
  createdAt: number;
  updatedAt: number;
  inputBlob: Blob;
  inputSize: number;
  azw3Blob?: Blob;
  azw3Name?: string;
  azw3Info?: Record<string, unknown>;
  status: BookStatus;
  error?: string;
  author?: string;
  tags: string[];
  notes?: string;
  sampleSource?: string;
  sampleLicense?: string;
  conversionOptions?: ConvertOptions;
};

export type ConvertOptions = {
  output_profile: string;
  base_font_size: number;
  margin_left: number;
  margin_right: number;
  margin_top: number;
  margin_bottom: number;
  dont_compress: boolean;
  no_inline_toc: boolean;
};

export type LibraryAPI = {
  listBooks(): Promise<LibraryBook[]>;
  getBook(id: string): Promise<LibraryBook | undefined>;
  renameBook(id: string, title: string): Promise<void>;
  setAuthor(id: string, author: string): Promise<void>;
  setTags(id: string, tags: string[]): Promise<void>;
  addTags(id: string, tags: string[]): Promise<void>;
  removeTags(id: string, tags: string[]): Promise<void>;
  setNotes(id: string, notes: string): Promise<void>;
  convertBook(id: string, options?: Partial<ConvertOptions>): Promise<LibraryBook>;
  deleteBook(id: string): Promise<void>;
};

export type QueueJobStatus = 'queued' | 'running' | 'done' | 'error';
export type QueueJob = {
  id: string;
  bookId: string;
  bookTitle: string;
  status: QueueJobStatus;
  createdAt: number;
  startedAt?: number;
  finishedAt?: number;
  error?: string;
};
export type QueueLog = {
  id: string;
  time: number;
  message: string;
  jobId?: string;
  bookId?: string;
};
