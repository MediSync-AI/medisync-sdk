# Changelog

All four packages (npm and JSR `medisync`, PyPI `medisync`, Maven `me.medisync:medisync`, NuGet `MediSync`) share one version.

## 0.2.0

The live stream reads the new `flushed` message of the speech service.

- New event `flushed` with `processed_until`: TypeScript `StreamFlushed`, Python `StreamFlushed`, Java and .NET `StreamFlushed`. The server sends one after each `end_utterance`, once every transcript of the audio it had is out, with the same `processed_until` as the `ack`.
- `finish()` waits for the `flushed` that answers its own `end_utterance`, so the last words of a recording are not cut off when the speech service is slow. With speaker roles on, it then waits up to 3 s for the final `speaker_roles`. A closed socket still ends it at once.
- Servers before 23.11.2026 send no `flushed`. After a timeout (default 10 s) `finish()` uses the old rule: a transcript reaches the ack's `processed_until`, or 3 s pass without a message. Set the timeout with `finish({ flushTimeoutMs })` (TypeScript), `finish(flush_timeout=...)` (Python), `finish(Duration)` (Java) or `FinishAsync(TimeSpan)` (.NET).
- `finish()` no longer waits forever when the server sends nothing at all after `end_utterance`.
- A non-fatal `error` with code `asr_failed` (one segment has no transcript) stays an event; the stream goes on. The speech service now also sends it when a segment fails or takes longer than 120 s.
- Message types the SDK does not know behave as before: TypeScript passes them through, Java and .NET give an `UnknownStreamEvent`, Python skips them.

## 0.1.0

First release on all five registries (17.11.2026).
