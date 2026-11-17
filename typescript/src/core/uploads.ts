// File inputs for multipart uploads: audio for transcriptions, PDFs for MediSync Connect.

/** A file with a name and a content type. Make one with `toFile()`. */
export interface FileInput {
  data: Blob | ArrayBuffer | ArrayBufferView;
  filename?: string;
  contentType?: string;
}

/**
 * Anything the SDK can upload: a Blob or File (in Node, `await fs.openAsBlob(path)`), bytes, or a
 * `FileInput` with a file name.
 */
export type Uploadable = Blob | ArrayBuffer | ArrayBufferView | FileInput;

/** Bytes with a file name, for example `toFile(await readFile('visit.wav'), 'visit.wav')`. */
export function toFile(data: Blob | ArrayBuffer | ArrayBufferView, filename?: string, contentType?: string): FileInput {
  return { data, ...(filename ? { filename } : {}), ...(contentType ? { contentType } : {}) };
}

function isFileInput(value: unknown): value is FileInput {
  return !!value && typeof value === 'object' && 'data' in value && !(value instanceof Blob) && !ArrayBuffer.isView(value) && !(value instanceof ArrayBuffer);
}

export function isUploadable(value: unknown): value is Uploadable {
  return value instanceof Blob || value instanceof ArrayBuffer || ArrayBuffer.isView(value) || isFileInput(value);
}

function toBlob(data: Blob | ArrayBuffer | ArrayBufferView, contentType?: string): Blob {
  if (data instanceof Blob) return contentType && !data.type ? new Blob([data], { type: contentType }) : data;
  const bytes = data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  return new Blob([bytes as Uint8Array<ArrayBuffer>], contentType ? { type: contentType } : {});
}

/**
 * The multipart form of a body. Files go as files; booleans as `true` or `false`; numbers as text;
 * arrays and objects as JSON text. Fields that are undefined are left out.
 */
export function multipartBody(body: object): FormData {
  const form = new FormData();
  for (const [key, value] of Object.entries(body)) {
    if (value === undefined) continue;
    if (isUploadable(value)) {
      const input: FileInput = isFileInput(value) ? value : { data: value };
      const name = input.filename ?? (typeof File !== 'undefined' && input.data instanceof File ? input.data.name : undefined) ?? key;
      form.append(key, toBlob(input.data, input.contentType), name);
    } else if (typeof value === 'string') {
      form.append(key, value);
    } else if (typeof value === 'number' || typeof value === 'boolean') {
      form.append(key, String(value));
    } else if (value === null) {
      form.append(key, 'null');
    } else {
      form.append(key, JSON.stringify(value));
    }
  }
  return form;
}
