# medisync

The TypeScript and JavaScript SDK for the MediSync API: speech to text (batch and live), clinical notes and letters, ICD-10-GM coding, decisions, account reads and MediSync Connect.

Everything the API generates is a draft. A clinician reviews and releases it.

- Node 20 or later, Bun 1.1 or later, Deno 2, and browsers (with a stream token for live speech).
- ESM and CommonJS, with types. No runtime dependencies.
- API reference: https://docs.medisync.me

## Install

```sh
npm install medisync
# or
bun add medisync
deno add jsr:@medisync/medisync
```

## Start

```ts
import MediSync from 'medisync';

const client = new MediSync(); // reads MEDISYNC_API_KEY

const models = await client.models.list();
const codes = await client.coding.icd10gm({
  mentions: [{ id: 'm1', text: 'Vorhofflimmern, paroxysmal' }],
});
```

Every method is named after its API operation. The full list is in `conformance/operations.json` of the repository.

| Area | Methods |
|---|---|
| Speech, batch | `audio.transcriptions.create`, `get`, `delete`, `wait` |
| Speech, live | `audio.stream.tokens.create`, `audio.stream.open` |
| Notes and letters | `notes.create`, `letters.create` |
| Coding | `coding.icd10gm`, `coding.search`, `coding.catalogue`, `coding.codes.get`, `coding.codes.refine` |
| Decisions | `decisions.create`, `decisions.createBatch` |
| Account | `models.list`, `usage.get`, `costs.get`, `specs.openapi`, `specs.asyncapi` |
| MediSync Connect | `connect.*`, 94 operations, for example `connect.transfers.create` |

## Options

```ts
const client = new MediSync({
  apiKey: 'msk_test_…',            // default: MEDISYNC_API_KEY
  baseUrl: 'https://api.medisync.me', // default: MEDISYNC_BASE_URL, else this
  version: '2026-11-01',           // the MediSync-Version header; default: the version of this SDK
  timeoutMs: 120_000,              // per attempt
  maxRetries: 2,
  onResponse: ({ operation, status, requestId }) => console.log(operation, status, requestId),
});
```

Log the `requestId` of each call. Support finds a call by it.

## Retries and idempotency

The client retries network errors, 408, 409 `idempotency_key_in_use`, 429 and 5xx (not 501). It waits for `retry-after` when the answer has one, else it backs off with jitter. It does not retry `spend_cap_reached` or `test_daily_limit_reached`.

It retries only GET requests and requests with an `Idempotency-Key`. Every POST that takes a key gets a UUID, and each retry sends the same key. To retry a call yourself, pass your own key:

```ts
await client.notes.create(body, { idempotencyKey: 'enc-2026-11-16-0042' });
```

## Errors

Every answer outside 2xx throws an `APIError` subclass. It has `status`, `code`, `type`, `param`, `requestId` and the `error` object of the body.

```ts
import { RateLimitError, NotSupportedError } from 'medisync';

try {
  await client.connect.diagnoses.planWrite(connectionId, body);
} catch (err) {
  if (err instanceof NotSupportedError) console.log(err.reason, err.alternative);
  else throw err;
}
```

| Class | When |
|---|---|
| `InvalidRequestError` | 400 |
| `AuthenticationError` | 401 |
| `PermissionDeniedError`, `ProductNotEnabledError` | 403 |
| `NotFoundError` | 404 |
| `ConflictError`, `AlreadyTransferredError`, `PlanStaleError`, `PlanExpiredError` | 409 |
| `RequestTooLargeError` | 413 |
| `CapabilityError`, `NotSupportedError`, `UnverifiedVerbError`, `VerbNotGrantedError` | 422 or 403, MediSync Connect |
| `LicenceRequiredError` | MediSync Connect licence |
| `RateLimitError` | 429 |
| `InternalServerError`, `OperationNotImplementedError` | 500, 501 |
| `TargetError` | 502: the PVS or KIS refused or could not be reached |
| `ServiceUnavailableError`, `AgentOfflineError` | 503 |
| `GatewayTimeoutError` | 504 |
| `APIConnectionError`, `APITimeoutError` | no answer |

## Batch transcription

```ts
import { readFile } from 'node:fs/promises';
import { toFile } from 'medisync';

const created = await client.audio.transcriptions.create({
  file: toFile(await readFile('visit.wav'), 'visit.wav'),
  language: 'de',
  diarization: true,
});
const done = await client.audio.transcriptions.wait(created.id);
if (done.status === 'completed') console.log(done.result?.text);
```

## Live transcription

```ts
const stream = await client.audio.stream.open({ config: { language: 'de' } });

(async () => {
  for await (const event of stream) {
    if (event.type === 'transcript') console.log(event.segment.start, event.text);
  }
})();

for await (const frame of microphone()) stream.send(frame); // 16 kHz mono pcm_s16le
await stream.finish(); // end_utterance, the last transcripts, then close
```

On a drop, or a fatal error a new stream can continue (`worker_lost`, `max_duration_reached` and others), the stream connects again. The new stream starts at the last `segment.end` and gets the audio after that point again. You see a `reconnecting` event. This works for `pcm_s16le`.

A browser cannot send the API key on a WebSocket. Make a stream token on your server with `client.audio.stream.tokens.create()` and pass it as `token`. On Node 20, pass `WebSocket` from the `ws` package.

## MediSync Connect

Lists answer a page. Iterate the call to read every page:

```ts
for await (const site of client.connect.sites.list()) console.log(site.id);
```

A write is planned, shown to the doctor, released, then transferred:

```ts
import { buildRelease } from 'medisync';

const plan = await client.connect.records.planWrite(connectionId, { document, mapping_id: mappingId, mapping_version: 1 });
// Show plan.preview to the doctor. Continue only after the doctor released it.
const release = buildRelease(plan, { memberId, role: 'doctor', practitionerLink: 'ARZT-03', patientConfirmed: document.patient });
const transfer = await client.connect.transfers.create({ plan_id: plan.id, release });
const done = await client.connect.transfers.wait(transfer.id);
```

Helpers that are no API operation:

- `connect.transfers.wait(id)` reads a transfer until it is final or `in_doubt`.
- `connect.events.poll(query, { signal })` yields each page with events. Save `next_cursor` after you handled a page.
- `connect.capabilities.forConnection(id)` loads the capabilities once and answers `supports(verb)`, `callable()`, `limits(verb)` and `require(verb)`.

Apps with a key pair (`private_key_jwt`) pass `app` instead of an API key for Connect calls:

```ts
const client = new MediSync({ app: { clientId: 'app_6Tn3Cw9KhZ', keyId: 'k1', privateKey: process.env.APP_KEY_PEM!, installationId: 'ins_3Rv8Qa2MxK' } });
```

Types of Connect are under `Connect`: `import type { Connect } from 'medisync'`, then `Connect.Transfer`.

## Licence

Apache-2.0
