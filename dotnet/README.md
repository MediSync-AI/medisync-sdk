# MediSync .NET SDK

The .NET SDK for the MediSync API: speech to text (batch and live), clinical notes and letters, ICD-10-GM coding, decisions, account reads and MediSync Connect.

Everything the API generates is a draft. A clinician reviews and releases it.

- `netstandard2.0` (.NET Framework 4.6.2 or later, for example in a PVS on Windows) and `net8.0`.
- Async methods with a `CancellationToken`. Nullable annotations are on.
- Uses `HttpClient`, `System.Text.Json` and `ClientWebSocket`.
- API reference: https://docs.medisync.me

## Install

```sh
dotnet add package MediSync
```

## Start

```csharp
using MediSync;
using MediSync.Models;

var client = new MediSyncClient(); // reads MEDISYNC_API_KEY

var models = await client.Models.ListAsync();
var codes = await client.Coding.Icd10gmAsync(new CodeRequest
{
    Mentions = new List<CodeRequestMention> { new() { Id = "m1", Text = "Vorhofflimmern, paroxysmal" } },
});
```

Every method is named after its API operation: `client.Connect.Transfers.CreateAsync(...)` is `connect.transfers.create` in every SDK. The full list is in `conformance/operations.json` of the repository.

Models are classes with properties. Fields this SDK version does not know are in `AdditionalProperties`. A nullable field set to `null` is sent as `null`, so a PATCH can clear it.

## Options

```csharp
var client = new MediSyncClient(new MediSyncClientOptions
{
    ApiKey = "msk_test_…",                 // default: MEDISYNC_API_KEY
    BaseUrl = "https://api.medisync.me",    // default: MEDISYNC_BASE_URL, else this
    Version = "2026-11-01",                 // the MediSync-Version header; default: the version of this SDK
    Timeout = TimeSpan.FromSeconds(120),    // per attempt
    MaxRetries = 2,
    HttpClient = httpClient,                // optional, for example with a proxy
    OnResponse = info => logger.LogInformation("{Op} {Status} {RequestId}", info.Operation, info.Status, info.RequestId),
});
```

Log the request id of each call. Support finds a call by it.

## Retries and idempotency

The client retries network errors, 408, 409 `idempotency_key_in_use`, 429 and 5xx (not 501). It waits for `retry-after` when the answer has one, else it backs off with jitter. It does not retry `spend_cap_reached` or `test_daily_limit_reached`.

It retries only GET requests and requests with an `Idempotency-Key`. Every POST that takes a key gets a UUID, and each retry sends the same key. To retry a call yourself, pass your own key:

```csharp
await client.Notes.CreateAsync(body, new RequestOptions { IdempotencyKey = "enc-2026-11-16-0042" });
```

## Errors

Every answer outside 2xx throws an `ApiException` subclass. It has `Status`, `Code`, `Type`, `Param`, `RequestId` and `Error`, the error object of the body.

```csharp
try
{
    await client.Connect.Diagnoses.PlanWriteAsync(connectionId, body);
}
catch (VerbNotSupportedException e)
{
    Console.WriteLine($"{e.Reason} {e.Alternative}");
}
```

| Class | When |
|---|---|
| `InvalidRequestException` | 400 |
| `AuthenticationException` | 401 |
| `PermissionDeniedException`, `ProductNotEnabledException` | 403 |
| `NotFoundException` | 404 |
| `ConflictException`, `AlreadyTransferredException`, `PlanStaleException`, `PlanExpiredException` | 409 |
| `RequestTooLargeException` | 413 |
| `CapabilityException`, `VerbNotSupportedException`, `UnverifiedVerbException`, `VerbNotGrantedException` | 422 or 403, MediSync Connect |
| `LicenceRequiredException` | MediSync Connect licence |
| `RateLimitException` | 429 |
| `InternalServerException`, `OperationNotImplementedException` | 500, 501 |
| `TargetException` | 502: the PVS or KIS refused or could not be reached |
| `ServiceUnavailableException`, `AgentOfflineException` | 503 |
| `GatewayTimeoutException` | 504 |
| `ApiConnectionException`, `ApiTimeoutException` | no answer |

## Batch transcription

```csharp
var created = await client.Audio.Transcriptions.CreateAsync(new CreateTranscriptionRequest
{
    File = FileInput.FromPath("visit.wav"),
    Language = "de",
    Diarization = true,
});
var done = await client.Audio.Transcriptions.WaitAsync(created.Id);
if (done.Status == "completed") Console.WriteLine(done.Result?.Text);
```

## Live transcription

```csharp
await using var stream = await client.Audio.Stream.OpenAsync(new StreamOptions { Config = new StreamConfig { Language = "de" } });

var reader = Task.Run(async () =>
{
    await foreach (var e in stream)
    {
        if (e is StreamTranscript t) Console.WriteLine(t.Text);
    }
});

await foreach (var frame in Microphone()) await stream.SendAsync(frame); // 16 kHz mono pcm_s16le
await stream.FinishAsync(); // end_utterance, the last transcripts up to flushed, then close
await reader;
```

On a drop, or a fatal error a new stream can continue (`worker_lost`, `max_duration_reached` and others), the stream connects again. The new stream starts at the last `segment.end` and gets the audio after that point again. You see a `StreamReconnecting` event. This works for `pcm_s16le`.

`FinishAsync()` sends `end_utterance` and waits for the `StreamFlushed` event that answers it. The server sends `flushed` after the last transcript of the audio it had, so the end of the recording is not cut off. A segment the server could not transcribe arrives as a `StreamError` with code `asr_failed` and `Fatal` false, and the stream goes on. With `speaker_roles` on, `FinishAsync()` then waits up to 3 s for the final `StreamSpeakerRoles` event. Servers before 23.11.2026 send no `flushed`. After the flush timeout (default `LiveStream.FlushTimeout`, 10 s, `FinishAsync(TimeSpan.FromSeconds(5))`) `FinishAsync()` ends when a transcript reaches the ack's `processed_until`, or after 3 s without a message. Each `EndUtteranceAsync()` call gets one `ack` and one `flushed`, in the order of the calls.

## MediSync Connect

A list method answers a `PageTask`: `await` it for the first page, or `await foreach` it for every item of every page:

```csharp
await foreach (var site in client.Connect.Sites.ListAsync()) Console.WriteLine(site.Id);
var page = await client.Connect.Sites.ListAsync(new SitesListQuery { Limit = 50 });
```

A write is planned, shown to the doctor, released, then transferred:

```csharp
var plan = await client.Connect.Records.PlanWriteAsync(connectionId, new RecordWritePlanRequest { Document = document, MappingId = mappingId, MappingVersion = 1 });
// Show plan.Preview to the doctor. Continue only after the doctor released it.
var release = Releases.BuildRelease(plan, new ReleaseBy(memberId, "doctor", "ARZT-03", document.Patient));
var transfer = await client.Connect.Transfers.CreateAsync(new TransferCreate { PlanId = plan.Id, Release = release });
var done = await client.Connect.Transfers.WaitAsync(transfer.Id);
```

Helpers that are no API operation:

- `Connect.Transfers.WaitAsync(id)` reads a transfer until it is final or `in_doubt`.
- `Connect.Events.PollAsync(query, options, cancellationToken)` gives each page with events until the token is cancelled. Save `NextCursor` after you handled a page.
- `Connect.Capabilities.ForConnection(id)` loads the capabilities once and answers `SupportsAsync(verb)`, `CallableAsync()`, `LimitsAsync(verb)` and `RequireAsync(verb)`.

Apps with a key pair (`private_key_jwt`) pass `App` instead of an API key for Connect calls. The key must be an EC P-256 key; it signs with ES256. Ed25519 keys are not supported in .NET, because .NET has no built-in Ed25519:

```csharp
var client = new MediSyncClient(new MediSyncClientOptions
{
    App = new AppCredentials("app_6Tn3Cw9KhZaB", "k1", Environment.GetEnvironmentVariable("APP_KEY_PEM")!) { InstallationId = "ins_3Rv8Qa2MxK" },
});
```

## Work on this package

```sh
cd dotnet
dotnet test            # unit, parity and conformance tests (needs bun for the mock)
dotnet pack src/MediSync -c Release -o ../dist/dotnet
```

Code in `src/MediSync/Generated` is generated by `bun run generate` at the repository root. Do not edit it.

## Licence

Apache-2.0
