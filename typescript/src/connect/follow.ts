import { MediSyncError } from '../core/errors.ts';
import type { Event, Transfer } from '../generated/types/connect.ts';

/** One page of events: the list answer or a page of `events.list`. */
export type EventPage = { data: Event[]; next_cursor?: string | null };

/** A transfer in one of these states changes no more. */
export const FINAL_TRANSFER_STATUSES: readonly string[] = ['completed', 'partially_completed', 'failed', 'expired'];

/** True for `completed`, `partially_completed`, `failed` and `expired`. An unknown status is not final. */
export function isFinalTransferStatus(status: string): boolean {
  return FINAL_TRANSFER_STATUSES.includes(status);
}

/** `transfers.wait()` ran out of time. `transfer` is the last state it read. */
export class TransferWaitTimeoutError extends MediSyncError {
  readonly transfer: Transfer;

  constructor(transfer: Transfer) {
    super(`Transfer ${transfer.id} is still ${transfer.status}.`);
    this.transfer = transfer;
  }
}

export type TransferWaitOptions = {
  /** Stop when this returns true. Default: a final status, or `in_doubt` (a person must check it). */
  until?: (transfer: Transfer) => boolean;
  /** Give up after this long and throw `TransferWaitTimeoutError`. Default 120 000 ms. */
  timeoutMs?: number;
  /** First pause between reads. Doubles up to 10 s. Default 1000 ms. */
  intervalMs?: number;
  /** Called after each read whose status differs from the read before. */
  onUpdate?: (transfer: Transfer) => void;
  signal?: AbortSignal;
};

const MAX_INTERVAL_MS = 10_000;

export function pause(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const timer = setTimeout(done, ms);
    function done() {
      signal?.removeEventListener('abort', abort);
      resolve();
    }
    function abort() {
      clearTimeout(timer);
      reject(signal?.reason);
    }
    signal?.addEventListener('abort', abort, { once: true });
  });
}

export const defaultUntil = (t: Transfer) => isFinalTransferStatus(t.status) || t.status === 'in_doubt';

/**
 * Reads a transfer until `until` holds. A transfer waits in the queue while the site's hub is
 * offline, up to 24 hours: for long waits, follow `events.poll()` instead of holding a request.
 */
export async function waitForTransfer(
  get: (id: string, o: { signal?: AbortSignal }) => Promise<Transfer>,
  transferId: string,
  options: TransferWaitOptions = {},
  sleep: (ms: number, signal?: AbortSignal) => Promise<void> = pause,
): Promise<Transfer> {
  const until = options.until ?? defaultUntil;
  const deadline = Date.now() + (options.timeoutMs ?? 120_000);
  let interval = options.intervalMs ?? 1_000;
  let lastStatus: string | null = null;
  for (;;) {
    const transfer = await get(transferId, options.signal ? { signal: options.signal } : {});
    if (transfer.status !== lastStatus) {
      lastStatus = transfer.status;
      options.onUpdate?.(transfer);
    }
    if (until(transfer)) return transfer;
    const left = deadline - Date.now();
    if (left <= 0) throw new TransferWaitTimeoutError(transfer);
    await sleep(Math.min(interval, left), options.signal);
    interval = Math.min(interval * 2, MAX_INTERVAL_MS);
  }
}

export type PollOptions = {
  /** Pause after a page with no events. Default 5000 ms. */
  intervalMs?: number;
  /** Stops the loop. The generator returns without an error. */
  signal?: AbortSignal;
};

/**
 * Polls `events.list` and yields every page that has events. Save `page.next_cursor` after you
 * handled a page and pass it as `cursor` on the next start, so no event is lost or read twice.
 */
export async function* pollEvents<P extends EventPage>(
  list: (cursor: string | undefined, o: { signal?: AbortSignal }) => PromiseLike<P>,
  cursor: string | undefined,
  options: PollOptions = {},
  sleep: (ms: number, signal?: AbortSignal) => Promise<void> = pause,
): AsyncGenerator<P, void, undefined> {
  const signal = options.signal;
  while (!signal?.aborted) {
    let page: P;
    try {
      page = await list(cursor, signal ? { signal } : {});
    } catch (err) {
      if (signal?.aborted) return;
      throw err;
    }
    if (page.next_cursor) cursor = page.next_cursor;
    if (page.data.length > 0) {
      yield page;
      continue;
    }
    try {
      await sleep(options.intervalMs ?? 5_000, signal);
    } catch {
      return;
    }
  }
}
