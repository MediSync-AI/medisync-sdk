package me.medisync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.medisync.core.Json;
import me.medisync.errors.ApiConnectionException;
import me.medisync.errors.ApiTimeoutException;
import me.medisync.errors.MediSyncException;
import me.medisync.errors.NotFoundException;
import me.medisync.models.connect.AppUpdate;
import me.medisync.models.connect.Site;
import me.medisync.models.connect.SiteCreate;
import org.junit.jupiter.api.Test;

class ClientTest {
  @Test
  void needsAKeyFromTheArgumentOrTheEnvironment() {
    MediSyncException e = assertThrows(MediSyncException.class, () -> MediSyncClient.builder().env(k -> null).build());
    assertTrue(e.getMessage().contains("MEDISYNC_API_KEY"));
    MediSyncClient fromEnv = MediSyncClient.builder().env(k -> k.equals("MEDISYNC_API_KEY") ? "msk_test_env" : k.equals("MEDISYNC_BASE_URL") ? "http://127.0.0.1:1/" : null).build();
    assertEquals("http://127.0.0.1:1", fromEnv.getBaseUrl());
    assertEquals("https://api.medisync.me", new MediSyncClient("msk_test_x").getBaseUrl());
  }

  @Test
  void modelsSendExplicitNullsAndKeepUnknownFields() throws Exception {
    AppUpdate update = new AppUpdate().description(null).homepageUrl("https://example.com");
    assertEquals(Json.MAPPER.readTree("{\"homepage_url\":\"https://example.com\",\"description\":null}"), Json.MAPPER.valueToTree(update));
    update.setDescription("Praxissoftware");
    assertEquals(Json.MAPPER.readTree("{\"description\":\"Praxissoftware\",\"homepage_url\":\"https://example.com\"}"), Json.MAPPER.valueToTree(update));
    Site site = Json.MAPPER.readValue("{\"id\":\"site_1\",\"name\":\"Praxis Beispiel\",\"future_field\":{\"a\":1}}", Site.class);
    assertEquals("site_1", site.getId());
    assertEquals(Collections.singletonMap("a", 1), site.getAdditionalProperties().get("future_field"));
    assertEquals(Json.MAPPER.readTree("{\"id\":\"site_1\",\"name\":\"Praxis Beispiel\",\"future_field\":{\"a\":1}}"), Json.MAPPER.valueToTree(site));
    assertEquals(site, Json.MAPPER.readValue(site.toJson(), Site.class));
    assertTrue(site.toString().startsWith("Site{"));
  }

  @Test
  void callOptionsCallbackAndErrorFields() throws Exception {
    try (LocalServer server = new LocalServer()) {
      server.then((e, b) -> {
        e.getResponseHeaders().add("request-id", "req_hdr_1");
        LocalServer.send(e, 200, "{\"id\":\"site_1\"}");
      });
      server.json(404, "{\"error\":{\"type\":\"not_found_error\",\"code\":\"not_found\",\"message\":\"No site.\",\"request_id\":\"req_body_2\"}}");
      List<ResponseInfo> seen = Collections.synchronizedList(new ArrayList<>());
      MediSyncClient client = MediSyncClient.builder().apiKey("msk_test_unit").baseUrl(server.url()).defaultHeader("X-Partner", "praxis-app").onResponse(seen::add).build();
      Site site = client.connect().sites().create(new SiteCreate().name("Praxis Beispiel").kind("practice"), RequestOptions.builder().idempotencyKey("site-key-1").header("X-Trace", "t1").build());
      assertEquals("site_1", site.getId());
      LocalServer.Seen first = server.seen.get(0);
      assertEquals("site-key-1", first.headers.getFirst("idempotency-key"));
      assertEquals("praxis-app", first.headers.getFirst("x-partner"));
      assertEquals("t1", first.headers.getFirst("x-trace"));
      assertEquals("medisync-java/" + Version.VERSION, first.headers.getFirst("user-agent"));
      assertEquals(Json.MAPPER.readTree("{\"name\":\"Praxis Beispiel\",\"kind\":\"practice\"}"), Json.MAPPER.readTree(first.body));
      NotFoundException nf = assertThrows(NotFoundException.class, () -> client.connect().sites().get("site_x"));
      assertEquals("req_body_2", nf.getRequestId());
      assertEquals("No site.", nf.getMessage());
      assertEquals(2, seen.size());
      assertEquals("req_hdr_1", seen.get(0).getRequestId());
      assertEquals("sites.create", seen.get(0).getOperation());
      assertEquals(404, seen.get(1).getStatus());
    }
  }

  @Test
  void aTimeoutIsRetriedThenThrown() throws Exception {
    try (LocalServer server = new LocalServer()) {
      LocalServer.Handler slow = (e, b) -> {
        try {
          Thread.sleep(1500);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
        LocalServer.send(e, 200, "{\"data\":[]}");
      };
      server.then(slow).then(slow);
      List<Long> sleeps = new ArrayList<>();
      MediSyncClient client =
          MediSyncClient.builder().apiKey("msk_test_unit").baseUrl(server.url()).timeout(Duration.ofMillis(300)).maxRetries(1).sleeper(d -> {
            sleeps.add(d.toMillis());
            return CompletableFuture.completedFuture(null);
          }).build();
      ApiTimeoutException e = assertThrows(ApiTimeoutException.class, () -> client.models().list());
      assertTrue(e.getMessage().contains("timed out after 300 ms"));
      assertEquals(1, sleeps.size());
    }
  }

  @Test
  void aRefusedConnectionIsAnApiConnectionException() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    MediSyncClient client = MediSyncClient.builder().apiKey("msk_test_unit").baseUrl("http://127.0.0.1:" + port).maxRetries(0).build();
    ApiConnectionException e = assertThrows(ApiConnectionException.class, () -> client.models().list());
    assertTrue(e.getMessage().startsWith("GET /v1/models failed"));
    // A POST without an idempotency key is not retried; the async client fails its future.
    CompletableFuture<?> f = client.async().models().list();
    assertThrows(Exception.class, f::join);
  }

  @Test
  void anEmptyAnswerOfAVoidOperationIsNull() throws Exception {
    try (LocalServer server = new LocalServer()) {
      server.then((e, b) -> {
        e.sendResponseHeaders(204, -1);
      });
      MediSyncClient client = MediSyncClient.builder().apiKey("msk_test_unit").baseUrl(server.url()).build();
      client.connect().agents().revoke("agt_1");
      assertNull(client.async().connect().agents().revoke("agt_1").exceptionally(x -> null).join());
      JsonNode none = null;
      assertNull(none);
    }
  }
}
