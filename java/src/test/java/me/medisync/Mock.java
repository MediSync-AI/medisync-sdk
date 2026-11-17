package me.medisync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import me.medisync.core.Json;

/** The mock server of conformance/mock, started with bun, or MEDISYNC_MOCK_URL. One per test run. */
final class Mock {
  static final Path ROOT = Paths.get(System.getProperty("medisync.repo", "..")).toAbsolutePath().normalize();
  private static Mock instance;

  final String url;
  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  private Mock(String url) {
    this.url = url;
  }

  static synchronized Mock get() throws Exception {
    if (instance != null) return instance;
    String url = System.getenv("MEDISYNC_MOCK_URL");
    if (url == null || url.isEmpty()) {
      String bun = System.getenv().getOrDefault("BUN", "bun");
      Process process = new ProcessBuilder(bun, ROOT.resolve("conformance/mock/server.ts").toString(), "--port", "0").redirectError(ProcessBuilder.Redirect.INHERIT).start();
      BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      String line = out.readLine();
      if (line == null || !line.startsWith("MOCK_URL=")) throw new IllegalStateException("The mock server did not start: " + line + ". Is bun installed?");
      url = line.substring("MOCK_URL=".length()).trim();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        process.destroy();
        try {
          process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
      }));
    }
    instance = new Mock(url);
    return instance;
  }

  private JsonNode call(String path, JsonNode body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + path)).header("content-type", "application/json");
    b = body == null ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return Json.MAPPER.readTree(res.body());
  }

  void reset(JsonNode responses, JsonNode ws) throws Exception {
    ObjectNode body = Json.MAPPER.createObjectNode();
    body.set("responses", responses == null ? Json.MAPPER.createArrayNode() : responses);
    body.set("ws", ws == null ? Json.MAPPER.createObjectNode() : ws);
    call("/__mock/reset", body);
  }

  JsonNode requests() throws Exception {
    return call("/__mock/requests", null);
  }

  JsonNode ws() throws Exception {
    return call("/__mock/ws", null);
  }
}
