# MediSync Java SDK

The Java and Kotlin SDK for the MediSync API: speech to text (batch and live), clinical notes and letters, ICD-10-GM coding, decisions, account reads and MediSync Connect.

Everything the API generates is a draft. A clinician reviews and releases it.

- Java 11 or later. Kotlin sees which values may be null (JSpecify annotations).
- Runtime dependencies: Jackson (`jackson-databind`) and the JSpecify annotations. HTTP and WebSocket come from `java.net.http`.
- API reference: https://docs.medisync.me

## Install

Maven:

```xml
<dependency>
  <groupId>me.medisync</groupId>
  <artifactId>medisync</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("me.medisync:medisync:0.1.0")
```

## Start

```java
import me.medisync.MediSyncClient;
import me.medisync.models.CodeRequest;
import me.medisync.models.CodeRequestMention;
import java.util.List;

MediSyncClient client = new MediSyncClient(); // reads MEDISYNC_API_KEY

var models = client.models().list();
var codes = client.coding().icd10gm(new CodeRequest().mentions(List.of(
    new CodeRequestMention().id("m1").text("Vorhofflimmern, paroxysmal"))));
```

Kotlin:

```kotlin
val client = MediSyncClient()
val site = client.connect().sites().get(siteId)
val bsnr: String? = site.bsnr // nullable in the spec, nullable in Kotlin
```

Every method is named after its API operation: `client.connect().transfers().create(...)` is `connect.transfers.create` in every SDK. The full list is in `conformance/operations.json` of the repository.

The methods wait for their answer. `client.async()` has the same methods, and each answers a `CompletableFuture`:

```java
client.async().models().list().thenAccept(models -> System.out.println(models));
```

Models have getters, setters and fluent setters (`new SiteCreate().name("Praxis Beispiel").kind("practice")`). Fields this SDK version does not know are in `getAdditionalProperties()`. A nullable field set to `null` is sent as `null`, so a PATCH can clear it.

## Options

```java
MediSyncClient client = MediSyncClient.builder()
    .apiKey("msk_test_…")                 // default: MEDISYNC_API_KEY
    .baseUrl("https://api.medisync.me")    // default: MEDISYNC_BASE_URL, else this
    .version("2026-11-01")                 // the MediSync-Version header; default: the version of this SDK
    .timeout(Duration.ofSeconds(120))      // per attempt
    .maxRetries(2)
    .onResponse(info -> log.info("{} {} {}", info.getOperation(), info.getStatus(), info.getRequestId()))
    .build();
```

Log the request id of each call. Support finds a call by it.

## Retries and idempotency

The client retries network errors, 408, 409 `idempotency_key_in_use`, 429 and 5xx (not 501). It waits for `retry-after` when the answer has one, else it backs off with jitter. It does not retry `spend_cap_reached` or `test_daily_limit_reached`.

It retries only GET requests and requests with an `Idempotency-Key`. Every POST that takes a key gets a UUID, and each retry sends the same key. To retry a call yourself, pass your own key:

```java
client.notes().create(body, RequestOptions.builder().idempotencyKey("enc-2026-11-16-0042").build());
```

## Errors

Every answer outside 2xx throws an `ApiException` subclass from `me.medisync.errors`. It has `getStatus()`, `getCode()`, `getType()`, `getParam()`, `getRequestId()` and `getError()`, the error object of the body.

```java
try {
  client.connect().diagnoses().planWrite(connectionId, body);
} catch (NotSupportedException e) {
  System.out.println(e.getReason() + " " + e.getAlternative());
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
| `CapabilityException`, `NotSupportedException`, `UnverifiedVerbException`, `VerbNotGrantedException` | 422 or 403, MediSync Connect |
| `LicenceRequiredException` | MediSync Connect licence |
| `RateLimitException` | 429 |
| `InternalServerException`, `OperationNotImplementedException` | 500, 501 |
| `TargetException` | 502: the PVS or KIS refused or could not be reached |
| `ServiceUnavailableException`, `AgentOfflineException` | 503 |
| `GatewayTimeoutException` | 504 |
| `ApiConnectionException`, `ApiTimeoutException` | no answer |

## Batch transcription

```java
var created = client.audio().transcriptions().create(new CreateTranscriptionRequest()
    .file(FileInput.of(Path.of("visit.wav")))
    .language("de")
    .diarization(true));
var done = client.audio().transcriptions().wait(created.getId());
if ("completed".equals(done.getStatus())) System.out.println(done.getResult().getText());
```

## Live transcription

```java
try (LiveStream stream = client.audio().stream().open(StreamOptions.builder().config(new StreamConfig().language("de")).build())) {
  Thread reader = new Thread(() -> {
    for (StreamEvent event : stream) {
      if (event instanceof StreamTranscript) System.out.println(((StreamTranscript) event).getText());
    }
  });
  reader.start();
  for (byte[] frame : microphone()) stream.send(frame); // 16 kHz mono pcm_s16le
  stream.finish(); // end_utterance, the last transcripts, then close
  reader.join();
}
```

On a drop, or a fatal error a new stream can continue (`worker_lost`, `max_duration_reached` and others), the stream connects again. The new stream starts at the last `segment.end` and gets the audio after that point again. You see a `StreamReconnecting` event. This works for `pcm_s16le`.

## MediSync Connect

Lists answer a page. `autoPager()` reads every page:

```java
for (Site site : client.connect().sites().list().autoPager()) System.out.println(site.getId());
```

A write is planned, shown to the doctor, released, then transferred:

```java
Plan plan = client.connect().records().planWrite(connectionId, new RecordWritePlanRequest().document(document).mappingId(mappingId).mappingVersion(1L));
// Show plan.getPreview() to the doctor. Continue only after the doctor released it.
ReleaseInput release = Releases.buildRelease(plan, ReleaseBy.builder(memberId, "doctor", "ARZT-03", document.getPatient()).build());
Transfer transfer = client.connect().transfers().create(new TransferCreate().planId(plan.getId()).release(release));
Transfer done = client.connect().transfers().wait(transfer.getId());
```

Helpers that are no API operation:

- `connect().transfers().wait(id)` reads a transfer until it is final or `in_doubt`.
- `connect().events().poll(query)` gives each page with events; `close()` ends it. Save `getNextCursor()` after you handled a page.
- `connect().capabilities().forConnection(id)` loads the capabilities once and answers `supports(verb)`, `callable()`, `limits(verb)` and `require(verb)`.

Apps with a key pair (`private_key_jwt`) pass `app` instead of an API key for Connect calls. EC P-256 keys sign with ES256 on every Java version. Ed25519 keys sign with EdDSA on Java 15 or later, with the JDK's own code:

```java
MediSyncClient client = MediSyncClient.builder()
    .app(AppCredentials.builder("app_6Tn3Cw9KhZaB", "k1", System.getenv("APP_KEY_PEM")).installationId("ins_3Rv8Qa2MxK").build())
    .build();
```

## Work on this package

```sh
cd java
./mvnw verify          # unit, parity and conformance tests (needs bun for the mock), jars
```

Code in `models/` and `resources/` is generated by `bun run generate` at the repository root. Do not edit it.

## Licence

Apache-2.0
