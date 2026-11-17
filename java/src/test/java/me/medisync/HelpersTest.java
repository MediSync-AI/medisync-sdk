package me.medisync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import me.medisync.audio.TranscriptionWaitOptions;
import me.medisync.audio.Transcriptions;
import me.medisync.connect.Capabilities;
import me.medisync.connect.CapabilitiesAsync;
import me.medisync.connect.EventPoller;
import me.medisync.connect.PollOptions;
import me.medisync.connect.ReleaseBy;
import me.medisync.connect.Releases;
import me.medisync.connect.TransferWaitOptions;
import me.medisync.connect.Transfers;
import me.medisync.core.ClientCore;
import me.medisync.core.Json;
import me.medisync.core.Sleeper;
import me.medisync.errors.LicenceRequiredException;
import me.medisync.errors.NotSupportedException;
import me.medisync.errors.TransferWaitTimeoutException;
import me.medisync.errors.UnverifiedVerbException;
import me.medisync.errors.VerbNotGrantedException;
import me.medisync.models.Transcription;
import me.medisync.models.connect.CapabilitySet;
import me.medisync.models.connect.Event;
import me.medisync.models.connect.EventList;
import me.medisync.models.connect.EventsListQuery;
import me.medisync.models.connect.PatientRef;
import me.medisync.models.connect.Plan;
import me.medisync.models.connect.ReleaseInput;
import me.medisync.models.connect.Transfer;
import org.junit.jupiter.api.Test;

class HelpersTest {
  private static Sleeper recording(List<Long> sleeps) {
    return d -> {
      sleeps.add(d.toMillis());
      return CompletableFuture.completedFuture(null);
    };
  }

  private static CompletableFuture<Transfer> transfer(String status) {
    return CompletableFuture.completedFuture(new Transfer().id("trf_1").status(status));
  }

  @Test
  void transfersWaitStopsAtAFinalStatusOrInDoubtAndDoublesThePause() {
    List<String> states = new ArrayList<>(Arrays.asList("queued", "delivered", "delivered", "in_doubt"));
    List<Long> sleeps = new ArrayList<>();
    List<String> updates = new ArrayList<>();
    Transfer t = ClientCore.join(Transfers.waitFor(id -> transfer(states.remove(0)), "trf_1", TransferWaitOptions.builder().onUpdate(x -> updates.add(x.getStatus())).build(), recording(sleeps)));
    assertEquals("in_doubt", t.getStatus());
    assertEquals(Arrays.asList(1000L, 2000L, 4000L), sleeps);
    assertEquals(Arrays.asList("queued", "delivered", "in_doubt"), updates);
  }

  @Test
  void transfersWaitTimesOutWithTheLastState() {
    TransferWaitTimeoutException e =
        assertThrows(TransferWaitTimeoutException.class, () -> ClientCore.join(Transfers.waitFor(id -> transfer("queued"), "trf_1", TransferWaitOptions.builder().timeout(Duration.ZERO).build(), recording(new ArrayList<>()))));
    assertEquals("queued", e.getTransfer().getStatus());
    assertTrue(Transfers.isFinalStatus("partially_completed"));
    assertFalse(Transfers.isFinalStatus("in_doubt"));
  }

  @Test
  void transfersWaitWithACustomUntil() {
    List<String> states = new ArrayList<>(Arrays.asList("queued", "delivered"));
    Transfer t = ClientCore.join(Transfers.waitFor(id -> transfer(states.remove(0)), "trf_1", TransferWaitOptions.builder().until(x -> !"queued".equals(x.getStatus())).build(), recording(new ArrayList<>())));
    assertEquals("delivered", t.getStatus());
  }

  @Test
  void transcriptionsWaitGrowsThePauseByHalf() {
    List<String> states = new ArrayList<>(Arrays.asList("queued", "processing", "processing", "completed"));
    List<Long> sleeps = new ArrayList<>();
    Transcription t =
        ClientCore.join(Transcriptions.waitFor(id -> CompletableFuture.completedFuture(new Transcription().id("tr_1").status(states.remove(0))), "tr_1", TranscriptionWaitOptions.defaults(), recording(sleeps)));
    assertEquals("completed", t.getStatus());
    assertEquals(Arrays.asList(2000L, 3000L, 4500L), sleeps);
  }

  private static CapabilitiesAsync caps(String json, AtomicInteger loads) {
    return new CapabilitiesAsync("con_1", () -> {
      loads.incrementAndGet();
      return CompletableFuture.completedFuture(Json.MAPPER.convertValue(Json.MAPPER.valueToTree(parse(json)), CapabilitySet.class));
    });
  }

  private static Object parse(String json) {
    try {
      return Json.MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void capabilitiesAnswerFromOneLoad() {
    AtomicInteger loads = new AtomicInteger();
    Capabilities c =
        new Capabilities(
            caps(
                "{\"connection_id\":\"con_1\",\"verbs\":{"
                    + "\"record.write\":{\"status\":\"supported\"},"
                    + "\"diagnosis.write\":{\"status\":\"partial\",\"limits\":[\"no_side\"]},"
                    + "\"medication.order\":{\"status\":\"unverified\",\"reason\":\"not_tested\"},"
                    + "\"document.attach\":{\"status\":\"supported\",\"granted\":false},"
                    + "\"billing.read\":{\"status\":\"supported\",\"licensed\":false},"
                    + "\"patient.search\":{\"status\":\"future_status\"}}}",
                loads));
    assertTrue(c.supports("record.write"));
    assertTrue(c.supports("diagnosis.write"));
    assertFalse(c.supports("medication.order"));
    assertTrue(c.supports("medication.order", true));
    assertFalse(c.supports("document.attach"));
    assertFalse(c.supports("patient.search"));
    assertFalse(c.supports("appointment.book"));
    assertEquals(Arrays.asList("diagnosis.write", "record.write"), c.callable());
    assertEquals(Collections.singletonList("no_side"), c.limits("diagnosis.write"));
    assertEquals(Collections.emptyList(), c.limits("record.write"));
    NotSupportedException ns = assertThrows(NotSupportedException.class, () -> c.require("appointment.book"));
    assertEquals(422, ns.getStatus());
    assertEquals("verb_unsupported", ns.getCode());
    assertEquals("local", ns.getRequestId());
    assertThrows(UnverifiedVerbException.class, () -> c.require("medication.order"));
    c.require("medication.order", true);
    assertThrows(VerbNotGrantedException.class, () -> c.require("document.attach"));
    assertThrows(LicenceRequiredException.class, () -> c.require("billing.read"));
    assertEquals("capability_status_unknown", assertThrows(me.medisync.errors.CapabilityException.class, () -> c.require("patient.search")).getCode());
    assertEquals(1, loads.get());
    c.refresh();
    assertEquals(2, loads.get());
  }

  @Test
  void buildReleaseTakesTheHashesOfThePlan() {
    Plan plan = new Plan().id("pln_1").documentHash("dh").planHash("ph");
    PatientRef patient = new PatientRef().id("10482").family("Beispiel").given("Erika");
    ReleaseInput r = Releases.buildRelease(plan, ReleaseBy.builder("mem_1", "doctor", "ARZT-03", patient).displayName("Dr. Lea Brandt").releasedAt(Instant.parse("2026-11-16T09:00:00.123Z")).build());
    assertEquals(
        Json.MAPPER.valueToTree(parse("{\"released_by\":{\"member_id\":\"mem_1\",\"display_name\":\"Dr. Lea Brandt\"},\"role\":\"doctor\",\"practitioner_link\":\"ARZT-03\",\"patient_confirmed\":{\"id\":\"10482\",\"family\":\"Beispiel\",\"given\":\"Erika\"},\"document_hash\":\"dh\",\"released_at\":\"2026-11-16T09:00:00Z\",\"plan_hash\":\"ph\"}")),
        Json.MAPPER.valueToTree(r));
  }

  @Test
  void eventsPollGivesPagesWithEventsAndKeepsTheCursor() throws Exception {
    Mock mock = Mock.get();
    mock.reset(
        Json.MAPPER.readTree(
            "[{\"status\":200,\"body\":{\"data\":[{\"id\":\"evt_1\",\"type\":\"entry.filed\"}],\"next_cursor\":\"c1\"}},"
                + "{\"status\":200,\"body\":{\"data\":[],\"next_cursor\":\"c1\"}},"
                + "{\"status\":200,\"body\":{\"data\":[{\"id\":\"evt_2\",\"type\":\"entry.filed\"}],\"next_cursor\":\"c2\"}}]"),
        null);
    MediSyncClient client = MediSyncClient.builder().apiKey("msk_test_unit").baseUrl(mock.url).build();
    List<String> ids = new ArrayList<>();
    try (EventPoller poller = client.connect().events().poll(new EventsListQuery().siteId("ste_1"), PollOptions.interval(Duration.ofMillis(10)))) {
      for (me.medisync.Page<Event, EventList> page : poller) {
        page.getData().forEach(e -> ids.add(e.getId()));
        if (ids.size() == 2) poller.close();
      }
    }
    assertEquals(Arrays.asList("evt_1", "evt_2"), ids);
    List<String> cursors = new ArrayList<>();
    mock.requests().forEach(r -> cursors.add(r.get("query").path("cursor").asText("-")));
    assertEquals(Arrays.asList("-", "c1", "c1"), cursors);
  }

  @Test
  void eventsPollAsyncEndsWhenCancelled() throws Exception {
    Mock mock = Mock.get();
    mock.reset(Json.MAPPER.readTree("[{\"status\":200,\"body\":{\"data\":[{\"id\":\"evt_1\",\"type\":\"entry.filed\"}],\"next_cursor\":\"c1\"}}]"), null);
    MediSyncClient client = MediSyncClient.builder().apiKey("msk_test_unit").baseUrl(mock.url).build();
    CompletableFuture<Void>[] holder = new CompletableFuture[1];
    CompletableFuture<String> first = new CompletableFuture<>();
    holder[0] = client.async().connect().events().poll(null, PollOptions.interval(Duration.ofMillis(10)), page -> first.complete(page.getData().get(0).getId()));
    assertEquals("evt_1", first.get());
    holder[0].cancel(false);
    assertTrue(holder[0].isCancelled());
  }
}
