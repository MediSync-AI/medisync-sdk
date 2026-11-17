import { MediSyncError } from '../core/errors.ts';
import { pause } from '../connect/follow.ts';
import type { Transcription } from '../generated/types/platform.ts';

/** A transcription in one of these states changes no more. */
export const FINAL_TRANSCRIPTION_STATUSES: readonly string[] = ['completed', 'failed', 'expired'];

/** `audio.transcriptions.wait()` ran out of time. `transcription` is the last state it read. */
export class TranscriptionWaitTimeoutError extends MediSyncError {
  readonly transcription: Transcription;

  constructor(transcription: Transcription) {
    super(`Transcription ${transcription.id} is still ${transcription.status}.`);
    this.transcription = transcription;
  }
}

export type TranscriptionWaitOptions = {
  /** Give up after this long and throw `TranscriptionWaitTimeoutError`. Default 600 000 ms. */
  timeoutMs?: number;
  /** First pause between reads. Grows by half up to 10 s. Default 2000 ms. */
  intervalMs?: number;
  /** Called after each read whose status differs from the read before. */
  onUpdate?: (transcription: Transcription) => void;
  signal?: AbortSignal;
};

/**
 * Reads a transcription until it is `completed`, `failed` or `expired`. A failed transcription is
 * answered, not thrown: check `status` and `error`.
 */
export async function waitForTranscription(
  get: (id: string, o: { signal?: AbortSignal }) => Promise<Transcription>,
  id: string,
  options: TranscriptionWaitOptions = {},
  sleep: (ms: number, signal?: AbortSignal) => Promise<void> = pause,
): Promise<Transcription> {
  const deadline = Date.now() + (options.timeoutMs ?? 600_000);
  let interval = options.intervalMs ?? 2_000;
  let last: string | null = null;
  for (;;) {
    const t = await get(id, options.signal ? { signal: options.signal } : {});
    if (t.status !== last) {
      last = t.status;
      options.onUpdate?.(t);
    }
    if (FINAL_TRANSCRIPTION_STATUSES.includes(t.status)) return t;
    const left = deadline - Date.now();
    if (left <= 0) throw new TranscriptionWaitTimeoutError(t);
    await sleep(Math.min(interval, left), options.signal);
    interval = Math.min(interval * 1.5, 10_000);
  }
}
