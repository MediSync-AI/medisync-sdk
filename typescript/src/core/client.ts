// The HTTP core of the client: auth, headers, retries, idempotency keys, errors and pages. The
// generated resource classes call `request()` and `requestPage()` with the operation's meta.
import { AppTokenSource, type AppCredentials } from '../connect/app-auth.ts';
import { API_VERSION } from '../generated/operations.ts';
import { VERSION } from '../version.ts';
import { APIConnectionError, APITimeoutError, MediSyncError, errorFromResponse, type APIError } from './errors.ts';
import type { OperationMeta, RequestArgs, RequestOptions } from './operation.ts';
import { CursorPage, PagePromise, type Page } from './pagination.ts';
import { multipartBody } from './uploads.ts';

export const DEFAULT_BASE_URL = 'https://api.medisync.me';
const MAX_RETRY_WAIT_MS = 60_000;

export type Fetch = (input: Request) => Promise<Response>;

/** What `onResponse` gets after each answer, also for retries and errors. */
export interface ResponseInfo {
  operation: string;
  status: number;
  /** Quote it to support. */
  requestId: string | null;
  attempt: number;
  headers: Headers;
}

export interface ClientOptions {
  /** A secret API key, `msk_live_…` or `msk_test_…`. Default: the `MEDISYNC_API_KEY` environment variable. */
  apiKey?: string;
  /**
   * MediSync Connect apps with a key pair (`private_key_jwt`): Connect calls use the app's access
   * token instead of the API key. Pass an `AppTokenSource` to share one token between clients.
   */
  app?: AppCredentials | AppTokenSource;
  /** Default: `MEDISYNC_BASE_URL`, else `https://api.medisync.me`. */
  baseUrl?: string;
  /** The `MediSync-Version` header. Default: the version this SDK was generated for. */
  version?: string;
  /** Per attempt, in milliseconds. Default 120 000. */
  timeoutMs?: number;
  /** Retries after a network error, 408, 409 `idempotency_key_in_use`, 429 or 5xx. Default 2. */
  maxRetries?: number;
  /** Headers sent with every call. */
  defaultHeaders?: Record<string, string>;
  /** A fetch to use instead of the global one, for example for a proxy or a test. */
  fetch?: Fetch;
  /** Called after every answer. Log `requestId`: support looks a call up by it. */
  onResponse?: (info: ResponseInfo) => void;
  /** Makes Idempotency-Keys when a call gives none. Default `crypto.randomUUID`. */
  idempotencyKey?: () => string;
  /** Swapped in tests. */
  sleep?: (ms: number) => Promise<void>;
}

function env(name: string): string | undefined {
  try {
    const g = globalThis as { process?: { env?: Record<string, string | undefined> }; Deno?: { env: { get(n: string): string | undefined } } };
    return g.process?.env?.[name] ?? g.Deno?.env.get(name) ?? undefined;
  } catch {
    // Deno without --allow-env.
    return undefined;
  }
}

const isBrowser = () => typeof (globalThis as { document?: unknown }).document !== 'undefined';
const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

function retryAfterMs(headers: Headers, error: { retry_after?: number } | null): number | null {
  const header = headers.get('retry-after');
  if (header) {
    const seconds = Number(header);
    if (Number.isFinite(seconds) && seconds >= 0) return seconds * 1000;
    const date = Date.parse(header);
    if (!Number.isNaN(date)) return Math.max(date - Date.now(), 0);
  }
  if (typeof error?.retry_after === 'number') return error.retry_after * 1000;
  return null;
}

/** Exponential backoff with jitter: about 0.5 s, 1 s, 2 s, ... up to 8 s. */
export function backoffMs(attempt: number): number {
  const base = Math.min(500 * 2 ** attempt, 8_000);
  return base * (1 - Math.random() * 0.25);
}

/** True when an answer with this status and error may succeed on a retry. */
export function retryableStatus(status: number, error: APIError): boolean {
  if (error.error?.retryable === false) return false;
  if (status === 408) return true;
  if (status === 409) return error.code === 'idempotency_key_in_use';
  // A spend cap or the daily test allowance does not reset within a retry.
  if (status === 429) return error.code !== 'spend_cap_reached' && error.code !== 'test_daily_limit_reached';
  return status >= 500 && status !== 501;
}

const SAFE = new Set(['GET', 'HEAD', 'OPTIONS']);

export class MediSyncCore {
  readonly baseUrl: string;
  readonly version: string;
  readonly timeoutMs: number;
  readonly maxRetries: number;
  readonly #apiKey: string | undefined;
  readonly #tokens: AppTokenSource | null;
  readonly #fetch: Fetch;
  readonly #defaultHeaders: Record<string, string>;
  readonly #onResponse: ((info: ResponseInfo) => void) | undefined;
  readonly #newKey: () => string;
  readonly #sleep: (ms: number) => Promise<void>;

  constructor(options: ClientOptions = {}) {
    this.#apiKey = options.apiKey ?? env('MEDISYNC_API_KEY');
    this.#fetch = options.fetch ?? ((input) => globalThis.fetch(input));
    this.#tokens = options.app ? (options.app instanceof AppTokenSource ? options.app : new AppTokenSource(options.app, this.#fetch)) : null;
    if (!this.#apiKey && !this.#tokens) throw new MediSyncError('No API key. Pass apiKey, or set the MEDISYNC_API_KEY environment variable.');
    this.baseUrl = (options.baseUrl ?? env('MEDISYNC_BASE_URL') ?? DEFAULT_BASE_URL).replace(/\/+$/, '');
    this.version = options.version ?? API_VERSION;
    this.timeoutMs = options.timeoutMs ?? 120_000;
    this.maxRetries = options.maxRetries ?? 2;
    this.#defaultHeaders = options.defaultHeaders ?? {};
    this.#onResponse = options.onResponse;
    this.#newKey = options.idempotencyKey ?? (() => globalThis.crypto.randomUUID());
    this.#sleep = options.sleep ?? defaultSleep;
  }

  /** The API key, for the live stream handshake. */
  protected get apiKey(): string | undefined {
    return this.#apiKey;
  }

  /** @internal The bearer secret of an operation of `api`. */
  async authorization(api: 'platform' | 'connect'): Promise<string> {
    if (api === 'connect' && this.#tokens) return `Bearer ${await this.#tokens.get()}`;
    if (!this.#apiKey) throw new MediSyncError('This call needs an API key: the app credentials work for MediSync Connect only.');
    return `Bearer ${this.#apiKey}`;
  }

  /** @internal Headers of every call: version, user agent and the default headers. */
  baseHeaders(): Record<string, string> {
    return {
      'medisync-version': this.version,
      ...(isBrowser() ? {} : { 'user-agent': `medisync-typescript/${VERSION}` }),
      ...this.#defaultHeaders,
    };
  }

  /** @internal The URL of an operation with its path and query parameters. */
  url(op: Pick<OperationMeta, 'path' | 'commaQuery'>, args: RequestArgs): string {
    const path = op.path.replace(/\{([^}]+)\}/g, (_m, name: string) => {
      const value = args.path?.[name];
      if (value === undefined || value === '') throw new MediSyncError(`Missing the path parameter ${name}.`);
      return encodeURIComponent(String(value));
    });
    const url = new URL(this.baseUrl + path);
    for (const [key, value] of Object.entries(args.query ?? {})) {
      if (value === undefined || value === null) continue;
      if (Array.isArray(value)) {
        if (op.commaQuery?.includes(key)) url.searchParams.set(key, value.join(','));
        else for (const v of value) url.searchParams.append(key, String(v));
      } else {
        url.searchParams.set(key, String(value));
      }
    }
    return url.toString();
  }

  /** @internal Sends one operation and answers its parsed body. */
  async request<T>(op: OperationMeta, args: RequestArgs, options: RequestOptions = {}): Promise<T> {
    const url = this.url(op, args);
    const headers: Record<string, string> = { ...this.baseHeaders() };
    headers.accept = op.response === 'json' ? 'application/json' : '*/*';
    let body: BodyInit | undefined;
    if (op.body === 'json') {
      headers['content-type'] = 'application/json';
      body = JSON.stringify(args.body ?? {});
    } else if (op.body === 'multipart') {
      body = multipartBody(args.body ?? {});
    }
    if (op.idempotency !== 'none') headers['idempotency-key'] = options.idempotencyKey ?? this.#newKey();
    Object.assign(headers, options.headers);
    const canRetry = SAFE.has(op.method) || headers['idempotency-key'] !== undefined;
    const maxRetries = canRetry ? (options.maxRetries ?? this.maxRetries) : 0;
    const timeoutMs = options.timeoutMs ?? this.timeoutMs;
    let tokenRetried = false;

    for (let attempt = 0; ; attempt++) {
      options.signal?.throwIfAborted();
      const timeout = AbortSignal.timeout(timeoutMs);
      const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout;
      let response: Response;
      try {
        const authorization = await this.authorization(op.api);
        response = await this.#fetch(new Request(url, { method: op.method, headers: { ...headers, authorization }, ...(body !== undefined ? { body } : {}), signal }));
      } catch (err) {
        if (options.signal?.aborted) throw err;
        // A refused app token is an answer of the authorization server, not a network fault.
        if (err instanceof MediSyncError && !(err instanceof APIConnectionError)) throw err;
        if (attempt < maxRetries) {
          await this.#sleep(backoffMs(attempt));
          continue;
        }
        const what = `${op.method} ${new URL(url).pathname}`;
        if (timeout.aborted) throw new APITimeoutError(`${what} timed out after ${timeoutMs} ms.`, { cause: err });
        throw new APIConnectionError(`${what} failed: ${err instanceof Error ? err.message : String(err)}`, { cause: err });
      }
      const requestId = response.headers.get('request-id') ?? response.headers.get('medisync-request-id');
      this.#onResponse?.({ operation: op.operationId, status: response.status, requestId, attempt, headers: response.headers });
      if (response.ok) return (await this.#parse(op, response)) as T;

      const text = await response.text();
      let payload: unknown = text;
      try {
        payload = text ? JSON.parse(text) : null;
      } catch {
        // Not JSON: keep the text.
      }
      const error = errorFromResponse(response.status, payload, response.headers);
      if (response.status === 401 && this.#tokens && op.api === 'connect' && error.code === 'invalid_token' && !tokenRetried) {
        // The access token ended early: get a new one and send again, once.
        this.#tokens.clear();
        tokenRetried = true;
        attempt--;
        continue;
      }
      if (attempt < maxRetries && retryableStatus(response.status, error)) {
        const wait = retryAfterMs(response.headers, error.error);
        await this.#sleep(Math.min(wait ?? backoffMs(attempt), MAX_RETRY_WAIT_MS));
        continue;
      }
      throw error;
    }
  }

  async #parse(op: OperationMeta, response: Response): Promise<unknown> {
    switch (op.response) {
      case 'empty':
        await response.body?.cancel();
        return undefined;
      case 'binary':
        return response.blob();
      case 'text':
        return response.text();
      case 'json': {
        const text = await response.text();
        return text ? JSON.parse(text) : undefined;
      }
    }
  }

  /** @internal A list operation: a promise of the first page that also iterates every item. */
  requestPage<Item, Raw>(op: OperationMeta, args: RequestArgs, options?: RequestOptions): PagePromise<Item, Raw> {
    const load = async (query: object | undefined): Promise<Page<Item, Raw>> => {
      const body = await this.request<{ data?: Item[]; next_cursor?: string | null }>(op, { ...args, ...(query ? { query } : {}) }, options);
      return new CursorPage<Item>(body ?? {}, (cursor) => load({ ...(query ?? {}), cursor })) as Page<Item, Raw>;
    };
    return new PagePromise<Item, Raw>(() => load(args.query));
  }
}
