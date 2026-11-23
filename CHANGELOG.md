# Changelog

All four packages (npm and JSR `medisync`, PyPI `medisync`, Maven `me.medisync:medisync`, NuGet `MediSync`) share one version.

## 0.3.0

The SDKs follow the public API of 23.11.2026.

- MediSync Connect, six new methods: `connect.appointments.busy` and `connect.appointments.busySite` (busy times of a connection or of a site), `connect.connections.getInbound`, `connect.connections.oauthStart`, `connect.connections.setSecrets` and `connect.connections.deleteSecrets`.
- Speech: the `language` list has all 100 languages the speech service takes.
- Live stream: `flushed` is now part of the AsyncAPI spec. TypeScript and Python use the type from the spec under the same name, `StreamFlushed`. Java and .NET keep the class of 0.2.0.
- `segment.start` and `segment.end` of a transcript are required numbers in the spec. The server always sends both. TypeScript: `number`, no longer optional. Python: `float`, no longer `Optional`. .NET: `double`, no longer `double?`. Java keeps `Double` and drops `@Nullable`.
- Python: `StreamReconnecting.type` is `Literal["reconnecting"]`. A check `event.type == "transcript"` now narrows a stream event to `StreamTranscript` in strict type checkers.

Code that reads the stream needs no change. Code that builds these objects itself, for example in tests, can need one:

- .NET: `segment.Start ?? 0`, `.HasValue` or `.Value` on `Start` and `End` no longer compile. Use the value directly.
- Python: `StreamTranscriptSegment()` without `start` and `end` fails validation. `StreamFlushed(processed_until=...)` now needs `type="flushed"` as well.
- TypeScript: an object literal of type `StreamTranscriptSegment` needs `start` and `end`.

## 0.2.0

The live stream reads the new `flushed` message of the speech service.

- New event `flushed` with `processed_until`: TypeScript `StreamFlushed`, Python `StreamFlushed`, Java and .NET `StreamFlushed`. The server sends one after each `end_utterance`, once it has finished its work on the audio it had (audio dropped under load or not transcribed has no transcript), with the same `processed_until` as the `ack`.
- `finish()` waits for the `flushed` that answers its own `end_utterance`, so the last words of a recording are not cut off when the speech service is slow. With speaker roles on, it then waits up to 3 s for the final `speaker_roles`. A closed socket still ends it at once.
- Servers before 23.11.2026 send no `flushed`. After a timeout (default 10 s) `finish()` uses the old rule: a transcript reaches the ack's `processed_until`, or 3 s pass without a message. Set the timeout with `finish({ flushTimeoutMs })` (TypeScript), `finish(flush_timeout=...)` (Python), `finish(Duration)` (Java) or `FinishAsync(TimeSpan)` (.NET).
- `finish()` no longer waits forever when the server sends nothing at all after `end_utterance`.
- A non-fatal `error` with code `asr_failed` (one segment has no transcript) stays an event; the stream goes on. The speech service now also sends it when a segment fails or takes longer than 120 s.
- Message types the SDK does not know behave as before: TypeScript passes them through, Java and .NET give an `UnknownStreamEvent`, Python skips them.
- Apps on 0.1.x keep working after the speech service sends `flushed`: TypeScript passes it on as an event of an unknown type, Java and .NET give an `UnknownStreamEvent`, and Python drops it. `finish()` on 0.1.x still ends by its old rule and does not wait for `flushed`.

## 0.1.0

First release on all five registries (17.11.2026).
