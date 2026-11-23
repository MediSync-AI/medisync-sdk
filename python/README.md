# medisync

The Python SDK for the MediSync API: speech to text (batch and live), clinical notes and letters, ICD-10-GM coding, decisions, account reads and MediSync Connect.

Everything the API generates is a draft. A clinician reviews and releases it.

- Python 3.9 or later. A sync client `MediSync` and an asyncio client `AsyncMediSync`.
- Typed: pydantic v2 models, `py.typed`, checked with mypy (strict) and pyright (strict).
- API reference: https://docs.medisync.me

## Install

```sh
pip install medisync
# MediSync Connect apps that sign with a key pair:
pip install "medisync[app]"
```

## Start

```python
from medisync import MediSync

client = MediSync()  # reads MEDISYNC_API_KEY

models = client.models.list()
codes = client.coding.icd10gm(mentions=[{"id": "m1", "text": "Vorhofflimmern, paroxysmal"}])
```

```python
import asyncio
from medisync import AsyncMediSync


async def main() -> None:
    async with AsyncMediSync() as client:
        models = await client.models.list()


asyncio.run(main())
```

Every method is named after its API operation, in snake_case: `client.connect.transfers.create(...)`, `client.decisions.create_batch(...)`. Body and query fields are keyword arguments. A field you leave out is not sent; `None` sends `null`.

## Options

```python
client = MediSync(
    api_key="msk_test_…",                 # default: MEDISYNC_API_KEY
    base_url="https://api.medisync.me",   # default: MEDISYNC_BASE_URL, else this
    version="2026-11-01",                 # the MediSync-Version header; default: the version of this SDK
    timeout=120.0,                        # seconds per attempt
    max_retries=2,
    on_response=lambda info: print(info.operation, info.status, info.request_id),
)
```

Every method also takes `extra_headers`, `extra_query`, `timeout`, and, where the operation takes one, `idempotency_key` and `extra_body`.

## Retries and idempotency

The client retries network errors, 408, 409 `idempotency_key_in_use`, 429 and 5xx (not 501). It waits for `retry-after` when the answer has one, else it backs off with jitter. It does not retry `spend_cap_reached` or `test_daily_limit_reached`.

It retries only GET requests and requests with an `Idempotency-Key`. Every POST that takes a key gets a UUID, and each retry sends the same key. A file upload is read again from its start for each attempt.

## Errors

Every answer outside 2xx raises an `APIError` subclass with `status`, `code`, `type`, `param`, `request_id` and the `error` object of the body. The classes are the same as in the TypeScript SDK: `InvalidRequestError`, `AuthenticationError`, `PermissionDeniedError`, `NotFoundError`, `ConflictError`, `RateLimitError`, `TargetError` (the PVS or KIS refused), `ServiceUnavailableError`, `GatewayTimeoutError`, the MediSync Connect classes (`NotSupportedError`, `VerbNotGrantedError`, `LicenceRequiredError`, `AlreadyTransferredError`, and others), and `APIConnectionError` and `APITimeoutError` when no answer came.

```python
from medisync import NotSupportedError

try:
    client.connect.diagnoses.plan_write(connection_id, patient=patient, diagnoses=diagnoses, source={"app": "beispiel-scribe"})
except NotSupportedError as err:
    print(err.reason, err.alternative)
```

## Batch transcription

```python
from pathlib import Path

created = client.audio.transcriptions.create(file=Path("visit.wav"), language="de", diarization=True)
done = client.audio.transcriptions.wait(created.id)
if done.status == "completed" and done.result:
    print(done.result.text)
```

`file` takes a path, bytes, an open binary file, or a tuple `(filename, content, content_type)`.

## Live transcription

```python
async with client.audio.stream.open(config={"language": "de"}) as stream:
    async def read() -> None:
        async for event in stream:
            if event.type == "transcript":
                print(event.segment.start, event.text)

    reader = asyncio.create_task(read())
    async for frame in microphone():  # 16 kHz mono pcm_s16le
        await stream.send(frame)
    await stream.finish()  # end_utterance, the last transcripts up to flushed, then close
    await reader
```

The sync client has the same stream; a thread reads the socket. On a drop, or a fatal error a new stream can continue (`worker_lost`, `max_duration_reached` and others), the stream connects again from the last `segment.end` and sends the audio after that point again. You see a `reconnecting` event. This works for `pcm_s16le`.

`finish()` sends `end_utterance` and waits for the `flushed` event that answers it. The server sends `flushed` when it has finished its work on the audio it had, so the end of the recording is not cut off. Audio the server dropped under load (`backpressure`) has no transcript. A segment the server could not transcribe arrives as a `StreamError` with code `asr_failed` and `fatal` false, and the stream goes on. With `speaker_roles` on, `finish()` then waits up to 3 s for the final `speaker_roles` event. Under load the final roles can come before `flushed`, or not at all for a later `end_utterance`. Servers before 23.11.2026 send no `flushed`. After `flush_timeout` seconds (default 10, `finish(flush_timeout=5)`) `finish()` ends when a transcript reaches the ack's `processed_until`, or after 3 s without a message. Each `end_utterance()` call gets one `ack` and one `flushed`, in the order of the calls.

## MediSync Connect

Lists answer a page. Iterate the page to read every page:

```python
for site in client.connect.sites.list():
    print(site.id)
```

A write is planned, shown to the doctor, released, then transferred:

```python
from medisync.connect import build_release

plan = client.connect.records.plan_write(connection_id, document=document, mapping_id=mapping_id, mapping_version=1)
# Show plan.preview to the doctor. Continue only after the doctor released it.
release = build_release(plan, member_id=member_id, role="doctor", practitioner_link="ARZT-03", patient_confirmed=document["patient"])
transfer = client.connect.transfers.create(plan_id=plan.id, release=release)
done = client.connect.transfers.wait(transfer.id)
```

Helpers that are no API operation: `connect.transfers.wait`, `connect.events.poll` (pass a `threading.Event` or `asyncio.Event` as `stop`), `connect.capabilities.for_connection` and `audio.transcriptions.wait`.

Apps with a key pair (`private_key_jwt`):

```python
from medisync import AppCredentials, MediSync

client = MediSync(connect_app=AppCredentials(client_id="app_6Tn3Cw9KhZ", key_id="k1", private_key=pem, installation_id="ins_3Rv8Qa2MxK"))
```

Models: `medisync.types` for the platform API and the live stream, `medisync.types.connect` for MediSync Connect.

## Licence

Apache-2.0
