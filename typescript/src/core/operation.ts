/** What the client needs to know about one API operation. The generator writes one per operation. */
export interface OperationMeta {
  readonly api: 'platform' | 'connect';
  readonly operationId: string;
  readonly method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  readonly path: string;
  readonly body: 'json' | 'multipart' | null;
  /** The operation takes query parameters. */
  readonly query: boolean;
  readonly response: 'json' | 'binary' | 'text' | 'empty';
  /** `optional` and `required`: the operation takes an Idempotency-Key, and the client sends one. */
  readonly idempotency: 'none' | 'optional' | 'required';
  readonly paginated: boolean;
  /** Query arrays sent as one comma-separated value. */
  readonly commaQuery?: readonly string[];
  /** The x-medisync fields of the spec. */
  readonly scope?: string;
  readonly verb?: string;
  readonly phi?: boolean;
  readonly metered?: string;
  readonly usage?: string;
  readonly public?: boolean;
}

/** Options of one call. Every generated method takes them as its last argument. */
export interface RequestOptions {
  /**
   * The Idempotency-Key of a POST that takes one. The client makes a UUID when you give none,
   * and sends the same key with every retry of the call. Give your own to retry a call yourself.
   */
  idempotencyKey?: string;
  signal?: AbortSignal;
  /** Per attempt, in milliseconds. Default: the client's `timeoutMs`. */
  timeoutMs?: number;
  /** Default: the client's `maxRetries`. */
  maxRetries?: number;
  /** Extra headers for this call. */
  headers?: Record<string, string>;
}

export interface RequestArgs {
  path?: Record<string, string | number>;
  query?: object;
  body?: object;
}
