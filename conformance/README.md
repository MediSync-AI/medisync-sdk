# Conformance

Shared test cases that every SDK runs against a mock of the API. The mock is built from `specs/` and runs locally with bun.

```sh
bun conformance/mock/server.ts --port 0   # prints MOCK_URL=http://127.0.0.1:<port>
```

| File | Cases |
|---|---|
| `cases/operations.generated.json` | One call per operation, with arguments that fit the spec. Written by the generator. |
| `cases/http.json` | Auth and version headers, user agent, idempotency keys, retries, `retry-after`, pagination, query arrays, path encoding, multipart uploads, binary, text and empty answers. |
| `cases/errors.json` | Every error type of the platform API and the error codes of MediSync Connect, with the class each SDK throws. |
| `cases/live.json` | The live stream: handshake, stream token, message flow, `end_utterance` with `ack` and `flushed` (silent tail, two in a row, servers without `flushed`, a close before it, speaker roles after it, a non-fatal `asr_failed`), reconnect after a fatal error or a drop, refused handshake, config error. |
| `operations.json` | The method name of every operation and helper, per language (TypeScript, Python, Java, .NET). The parity tests read it. |

## Case format

A case has `call` (a method in camelCase and its `args`: `path`, `body`, `query`, `options`), optional `client` options, optional scripted `responses`, and `expect`:

- `requests`: method, path, query, headers (a value starting with `~` is a regular expression), body, multipart fields, and `problems`, which the mock fills when a request does not fit the spec;
- `requestCount`, `sameHeaderAcrossRequests`, `sleepsMs` (exact or `{min, max}`);
- `result` or `error` (`class`, `status`, `code`, `type`, `requestId`, `param`, and the MediSync Connect fields).

The error classes have their TypeScript names. Java and .NET use `Exception` for `Error` (`AlreadyTransferredError` is `AlreadyTransferredException`, `APIError` is `ApiException`). In .NET, `NotSupportedError` is `VerbNotSupportedException`, so it does not clash with `System.NotSupportedException`.

Live cases have `steps` (`open`, `setTerms`, `sendAudio`, `endUtterance`, `finish` with an optional `flushTimeoutMs`, `close`), a `ws` script for the mock (`endUtterance.replies` scripts the answers to each `end_utterance`), and expectations on the events, on how long `finish` took (`finishMs`) and on what the mock received.

## Mock control

| Endpoint | Use |
|---|---|
| `POST /__mock/reset` | `{"responses": [...], "ws": {...}}`: clear the log and script the next answers |
| `GET /__mock/requests` | The requests since the last reset, with the problems found in each |
| `GET /__mock/ws` | The live stream connections since the last reset |
