// The live speech stream (specs/asyncapi.yaml): one WebSocket per conversation. The client sends
// a config, then audio frames; the server sends session_ready, then one final transcript per
// segment. After a drop, or a fatal error that a new stream can continue, the stream connects
// again with `timeline_offset_seconds` set to the last `segment.end` and sends the audio after
// that point again, as the "Errors and reconnect" page of the docs describes.
import type { MediSyncCore } from '../core/client.ts';
import { MediSyncError } from '../core/errors.ts';
import type { StreamConfig, StreamServerMessage } from '../generated/types/live.ts';

/** Fatal codes after which a new stream can continue the conversation. */
export const CONTINUE_CODES: ReadonlySet<string> = new Set(['overloaded', 'unavailable', 'service_unavailable', 'worker_lost', 'max_duration_reached', 'session_conflict']);
/** Close codes after which a new stream can continue: abnormal drop, going away, service failure, restart. */
const CONTINUE_CLOSE = new Set([1001, 1006, 1011, 1012]);
/** After `end_utterance`, the stream ends when nothing has arrived for this long. */
const SILENCE_MS = 3_000;

/** A fatal stream error, a refused handshake or a close the stream cannot continue after. */
export class LiveStreamError extends MediSyncError {
  /** The error code, for example `config_invalid`, or `handshake_failed` and `closed`. */
  readonly code: string;
  readonly param: string | null;
  readonly requestId: string | null;
  /** The WebSocket close code, when the socket closed. */
  readonly closeCode: number | null;

  constructor(code: string, message: string, extra: { param?: string | null; requestId?: string | null; closeCode?: number | null } = {}) {
    super(message);
    this.code = code;
    this.param = extra.param ?? null;
    this.requestId = extra.requestId ?? null;
    this.closeCode = extra.closeCode ?? null;
  }
}

/** Sent by the SDK, not the server: the stream lost its socket and connects again. */
export interface StreamReconnecting {
  type: 'reconnecting';
  /** 1 for the first attempt after a drop. */
  attempt: number;
  /** The `timeline_offset_seconds` of the new stream: the last `segment.end`. */
  offset_seconds: number;
  /** The error code or close code that ended the old stream. */
  reason: string;
}

export type StreamEvent = StreamServerMessage | StreamReconnecting;

/** A WebSocket constructor: the global one, or one from a package such as `ws` on Node 20. */
export type WebSocketLike = new (url: string, init?: unknown) => WebSocket;

export interface StreamOptions {
  /** The config, without `type`. Default: 16 kHz mono `pcm_s16le`, language detected. */
  config?: Omit<StreamConfig, 'type'>;
  /**
   * A single-use stream token (`mst_…`) instead of the API key, for browsers and apps. A function
   * gets a new token for each connection, so the stream can connect again after a drop.
   */
  token?: string | (() => Promise<string>);
  /** Connect again after a drop or a fatal error a new stream can continue. Default true for `pcm_s16le`. */
  reconnect?: boolean;
  /** Attempts in a row without a `session_ready` before the stream gives up. Default 5. */
  maxReconnects?: number;
  /** Seconds of sent audio kept for a reconnect. Older audio is dropped. Default 300. */
  bufferSeconds?: number;
  /** Default: the global WebSocket (Node 22+, Bun, Deno, browsers). */
  WebSocket?: WebSocketLike;
}

type Bytes = Uint8Array<ArrayBuffer>;
type Frame = { start: number; end: number; data: Bytes };

function toBytes(audio: ArrayBuffer | ArrayBufferView): Bytes {
  return audio instanceof ArrayBuffer ? new Uint8Array(audio) : new Uint8Array(audio.buffer as ArrayBuffer, audio.byteOffset, audio.byteLength);
}

/**
 * An open live stream. Send audio with `send()`, read messages with `for await`, and end with
 * `finish()` to keep the last transcripts, or `close()` to drop them.
 */
export class LiveStream implements AsyncIterable<StreamEvent> {
  /** The session of the current socket, from `session_ready`. */
  sessionId: string | null = null;

  readonly #client: MediSyncCore;
  readonly #options: StreamOptions;
  readonly #config: Record<string, unknown>;
  readonly #bytesPerSecond: number | null;
  /** Bytes of one sample of every channel: the step a resent frame may start at. */
  readonly #sampleBytes: number;
  readonly #reconnect: boolean;
  #ws: WebSocket | null = null;
  #live = false;
  #frames: Frame[] = [];
  #sent: number;
  #lastEnd: number;
  #queue: StreamEvent[] = [];
  #wake: (() => void) | null = null;
  #done = false;
  #failure: LiveStreamError | null = null;
  #closing = false;
  #finishing: { ack: number | null; timer: ReturnType<typeof setTimeout> | null; resolve: () => void; reject: (e: unknown) => void } | null = null;
  #attempts = 0;
  #continueReason: string | null = null;
  #opened: { resolve: () => void; reject: (e: unknown) => void } | null = null;
  /** The wordbook set with setTerms(), sent again on a new socket. */
  #terms: string[] | null = null;

  constructor(client: MediSyncCore, options: StreamOptions) {
    this.#client = client;
    this.#options = options;
    const config = (options.config ?? {}) as Record<string, unknown>;
    const audio = { encoding: 'pcm_s16le', sample_rate: 16000, channels: 1, ...((config.audio as object | undefined) ?? {}) } as { encoding: string; sample_rate: number; channels: number };
    this.#config = { ...config, audio };
    this.#bytesPerSecond = audio.encoding === 'pcm_s16le' ? audio.sample_rate * 2 * audio.channels : null;
    this.#sampleBytes = 2 * audio.channels;
    this.#reconnect = (options.reconnect ?? true) && this.#bytesPerSecond !== null && typeof options.token !== 'string';
    this.#sent = Number(config.timeline_offset_seconds ?? 0);
    this.#lastEnd = this.#sent;
  }

  /** @internal Opens the first socket and waits for `session_ready`. */
  open(): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.#opened = { resolve, reject };
      void this.#connect();
    });
  }

  /** The largest `segment.end` so far: everything before it is transcribed. */
  get transcribedUntil(): number {
    return this.#lastEnd;
  }

  /**
   * Sends one audio frame (at most 1 MiB), in the format of the config. Frames are kept for a
   * reconnect until a transcript covers them. Send at real-time speed.
   */
  send(audio: ArrayBuffer | ArrayBufferView): void {
    if (this.#done || this.#closing) throw new LiveStreamError('closed', 'The stream is closed.');
    const data = toBytes(audio);
    if (this.#bytesPerSecond) {
      const frame = { start: this.#sent, end: this.#sent + data.byteLength / this.#bytesPerSecond, data };
      this.#sent = frame.end;
      this.#frames.push(frame);
      const keep = this.#sent - (this.#options.bufferSeconds ?? 300);
      while (this.#frames.length && (this.#frames[0] as Frame).end < keep) this.#frames.shift();
    }
    if (this.#live) this.#ws?.send(data);
  }

  /** Replaces the wordbook of this stream. An empty list removes it. */
  setTerms(terms: string[]): void {
    this.#terms = [...terms];
    this.#sendJson({ type: 'terms', terms: this.#terms });
  }

  /** Flushes the audio the server holds. The server answers with `ack`. */
  endUtterance(): void {
    this.#sendJson({ type: 'end_utterance' });
  }

  /**
   * Ends the stream and keeps its tail: sends `end_utterance`, waits for the transcripts up to the
   * ack's `processed_until` (or 3 s without a message), then closes. Iteration ends after that.
   */
  finish(): Promise<void> {
    if (this.#done) return this.#failure ? Promise.reject(this.#failure) : Promise.resolve();
    return new Promise<void>((resolve, reject) => {
      this.#finishing = { ack: null, timer: null, resolve, reject };
      if (this.#live) this.endUtterance();
    });
  }

  /** Ends the stream at once. Audio not yet transcribed is lost. */
  close(): void {
    if (this.#done) return;
    this.#closing = true;
    this.#sendJson({ type: 'close' });
    this.#ws?.close(1000);
    this.#end(null);
  }

  async *[Symbol.asyncIterator](): AsyncGenerator<StreamEvent, void, undefined> {
    for (;;) {
      const next = this.#queue.shift();
      if (next) {
        yield next;
        continue;
      }
      if (this.#done) {
        if (this.#failure) throw this.#failure;
        return;
      }
      await new Promise<void>((resolve) => {
        this.#wake = resolve;
      });
    }
  }

  #push(event: StreamEvent): void {
    this.#queue.push(event);
    this.#wake?.();
    this.#wake = null;
  }

  #sendJson(message: object): void {
    if (this.#live && this.#ws?.readyState === 1) this.#ws.send(JSON.stringify(message));
  }

  #end(failure: LiveStreamError | null): void {
    if (this.#done) return;
    this.#done = true;
    this.#failure = failure;
    this.#live = false;
    if (this.#finishing) {
      if (this.#finishing.timer) clearTimeout(this.#finishing.timer);
      if (failure) this.#finishing.reject(failure);
      else this.#finishing.resolve();
      this.#finishing = null;
    }
    if (this.#opened) {
      if (failure) this.#opened.reject(failure);
      else this.#opened.resolve();
      this.#opened = null;
    }
    this.#wake?.();
    this.#wake = null;
  }

  async #url(): Promise<{ url: string; headers: Record<string, string> | null }> {
    const base = this.#client.baseUrl.replace(/^http/, 'ws');
    const token = this.#options.token;
    if (token) {
      const value = typeof token === 'string' ? token : await token();
      return { url: `${base}/v1/audio/stream?token=${encodeURIComponent(value)}`, headers: null };
    }
    if (typeof (globalThis as { document?: unknown }).document !== 'undefined') {
      throw new LiveStreamError('token_required', 'A browser cannot send the API key on a WebSocket. Pass a stream token from your server.');
    }
    const headers = { ...this.#client.baseHeaders(), authorization: await this.#client.authorization('platform') };
    return { url: `${base}/v1/audio/stream`, headers };
  }

  async #connect(): Promise<void> {
    let target: { url: string; headers: Record<string, string> | null };
    try {
      target = await this.#url();
    } catch (err) {
      this.#end(err instanceof LiveStreamError ? err : new LiveStreamError('handshake_failed', `Could not open the stream: ${err instanceof Error ? err.message : String(err)}`));
      return;
    }
    const WS = this.#options.WebSocket ?? (globalThis as { WebSocket?: WebSocketLike }).WebSocket;
    if (!WS) {
      this.#end(new LiveStreamError('no_websocket', 'This runtime has no WebSocket. On Node 20, pass `WebSocket` from the `ws` package.'));
      return;
    }
    const ws = target.headers ? new WS(target.url, { headers: target.headers }) : new WS(target.url);
    ws.binaryType = 'arraybuffer';
    this.#ws = ws;
    this.#live = false;
    this.#continueReason = null;
    const offset = this.#lastEnd;
    let opened = false;
    ws.onopen = () => {
      opened = true;
      ws.send(JSON.stringify({ ...this.#config, type: 'config', timeline_offset_seconds: offset }));
    };
    ws.onmessage = (event: MessageEvent) => {
      if (this.#ws !== ws || typeof event.data !== 'string') return;
      let message: StreamServerMessage;
      try {
        message = JSON.parse(event.data) as StreamServerMessage;
      } catch {
        return;
      }
      this.#onMessage(ws, message, offset);
    };
    ws.onclose = (event: CloseEvent) => {
      if (this.#ws !== ws) return;
      this.#onClose(event.code, event.reason);
    };
    ws.onerror = () => {
      // After the open a close event follows and decides. A refused handshake fires only this
      // event on some runtimes (Node): treat it as an abnormal close.
      if (!opened && this.#ws === ws) {
        this.#ws = null;
        this.#onClose(1006, '');
      }
    };
  }

  #onMessage(ws: WebSocket, message: StreamServerMessage, offset: number): void {
    switch (message.type) {
      case 'session_ready': {
        this.sessionId = message.session_id;
        this.#attempts = 0;
        if (this.#terms) ws.send(JSON.stringify({ type: 'terms', terms: this.#terms }));
        // The audio that has no transcript yet, from the offset on, then live audio.
        if (this.#bytesPerSecond) {
          for (const f of this.#frames) {
            if (f.end <= offset) continue;
            const skip = Math.max(0, Math.round(((offset - f.start) * this.#bytesPerSecond) / this.#sampleBytes)) * this.#sampleBytes;
            ws.send(skip > 0 ? f.data.subarray(Math.min(skip, f.data.byteLength)) : f.data);
          }
        }
        this.#live = true;
        if (this.#finishing) {
          this.#finishing.ack = null;
          this.endUtterance();
        }
        this.#opened?.resolve();
        this.#opened = null;
        break;
      }
      case 'transcript': {
        const end = message.segment?.end ?? 0;
        if (end > this.#lastEnd) this.#lastEnd = end;
        while (this.#frames.length && (this.#frames[0] as Frame).end <= this.#lastEnd) this.#frames.shift();
        if (this.#finishing) {
          if (this.#finishing.ack !== null && end >= this.#finishing.ack) {
            this.#push(message);
            this.#completeFinish();
            return;
          }
          this.#armSilence();
        }
        break;
      }
      case 'ack':
        if (this.#finishing) {
          this.#finishing.ack = message.processed_until;
          if (this.#lastEnd >= message.processed_until) {
            this.#push(message);
            this.#completeFinish();
            return;
          }
          this.#armSilence();
        }
        break;
      case 'error':
        if (message.fatal) {
          if (this.#reconnect && CONTINUE_CODES.has(message.code)) {
            this.#continueReason = message.code;
          } else {
            this.#push(message);
            this.#end(new LiveStreamError(message.code, message.message, { param: message.param ?? null, requestId: message.request_id ?? null }));
            this.#ws?.close(1000);
            return;
          }
        }
        break;
      default:
        break;
    }
    this.#push(message);
  }

  #armSilence(): void {
    const f = this.#finishing;
    if (!f) return;
    if (f.timer) clearTimeout(f.timer);
    f.timer = setTimeout(() => this.#completeFinish(), SILENCE_MS);
  }

  #completeFinish(): void {
    this.#sendJson({ type: 'close' });
    this.#closing = true;
    this.#ws?.close(1000);
    this.#end(null);
  }

  #onClose(code: number, reason: string): void {
    if (this.#done) return;
    this.#live = false;
    const why = this.#continueReason ?? (reason || String(code));
    // Before the first session_ready a failure ends open(): a key, scope or stream limit problem
    // shows at once instead of after every reconnect attempt.
    const canContinue = this.#reconnect && !this.#closing && this.sessionId !== null && (this.#continueReason !== null || CONTINUE_CLOSE.has(code) || (code === 1008 && CONTINUE_CODES.has(reason)));
    if (!canContinue) {
      if (code === 1000 || this.#closing) this.#end(null);
      else if (this.sessionId === null && !reason) this.#end(new LiveStreamError('handshake_failed', `The stream did not open (close ${code}). Check the API key or stream token, the speech scope and the stream limit of the project.`, { closeCode: code }));
      else this.#end(new LiveStreamError(reason || 'closed', `The stream closed with ${code}${reason ? ` ${reason}` : ''}.`, { closeCode: code }));
      return;
    }
    this.#attempts++;
    if (this.#attempts > (this.#options.maxReconnects ?? 5)) {
      this.#end(new LiveStreamError('reconnect_failed', `The stream could not connect again after ${this.#attempts - 1} attempts (${why}).`, { closeCode: code }));
      return;
    }
    this.#push({ type: 'reconnecting', attempt: this.#attempts, offset_seconds: this.#lastEnd, reason: why });
    const delay = Math.min(1000 * 2 ** (this.#attempts - 1), 30_000) * (0.5 + Math.random() / 2);
    setTimeout(() => void this.#connect(), delay);
  }
}

/** Opens a live stream and waits for `session_ready`. */
export async function openStream(client: MediSyncCore, options: StreamOptions = {}): Promise<LiveStream> {
  const stream = new LiveStream(client, options);
  await stream.open();
  return stream;
}
