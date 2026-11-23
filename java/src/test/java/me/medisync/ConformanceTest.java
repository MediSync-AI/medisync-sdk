package me.medisync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.medisync.audio.LiveStream;
import me.medisync.audio.StreamEvent;
import me.medisync.audio.StreamOptions;
import me.medisync.core.ClientCore;
import me.medisync.core.Json;
import me.medisync.core.OperationMeta;
import me.medisync.errors.ApiException;
import me.medisync.errors.CapabilityException;
import me.medisync.errors.LiveStreamException;
import me.medisync.models.StreamConfig;
import me.medisync.resources.Operations;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared conformance cases of conformance/cases against the mock server: every HTTP case
 * with the sync and with the async client, and the live cases.
 */
class ConformanceTest {
  private static JsonNode load(String file) throws Exception {
    return Json.MAPPER.readTree(Files.readAllBytes(Mock.ROOT.resolve("conformance/cases").resolve(file))).get("cases");
  }

  static MediSyncClient client(Mock mock, JsonNode options, List<Long> sleeps) {
    return MediSyncClient.builder()
        .apiKey("msk_test_conformance")
        .baseUrl(mock.url)
        .maxRetries(options != null && options.has("maxRetries") ? options.get("maxRetries").asInt() : 2)
        .version(options != null && options.has("version") ? options.get("version").asText() : Operations.API_VERSION)
        .sleeper(d -> {
          synchronized (sleeps) {
            sleeps.add(d.toMillis());
          }
          return CompletableFuture.completedFuture(null);
        })
        .build();
  }

  /** The value with every {"$file": ...} replaced by null; the files are set after the conversion. */
  private static JsonNode withoutFiles(JsonNode v, Map<String, FileInput> files) {
    if (v == null || !v.isObject()) return v;
    ObjectNode copy = ((ObjectNode) v).deepCopy();
    for (Map.Entry<String, JsonNode> e : v.properties()) {
      JsonNode f = e.getValue().get("$file");
      if (f != null) {
        files.put(e.getKey(), FileInput.of(new byte[f.get("bytes").asInt()], f.get("filename").asText()));
        copy.remove(e.getKey());
      }
    }
    return copy;
  }

  private static String pascal(String s) {
    StringBuilder out = new StringBuilder();
    for (String part : s.split("_")) if (!part.isEmpty()) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    return out.toString();
  }

  private static Object model(JsonNode json, Class<?> type) throws Exception {
    Map<String, FileInput> files = new java.util.LinkedHashMap<>();
    Object value = Json.MAPPER.convertValue(withoutFiles(json, files), type);
    for (Map.Entry<String, FileInput> f : files.entrySet()) type.getMethod("set" + pascal(f.getKey()), FileInput.class).invoke(value, f.getValue());
    return value;
  }

  /** Calls the method of a dotted name on the sync client or its async twin; waits for a future. */
  static Object call(MediSyncClient client, JsonNode spec, boolean async) throws Throwable {
    String dotted = spec.get("method").asText();
    OperationMeta meta = Operations.ALL.get(dotted);
    JsonNode args = spec.has("args") ? spec.get("args") : Json.MAPPER.createObjectNode();
    Object node = async ? client.async() : client;
    String[] parts = dotted.split("\\.");
    for (int i = 0; i < parts.length - 1; i++) node = node.getClass().getMethod(parts[i]).invoke(node);
    String name = parts[parts.length - 1];
    int pathCount = args.has("path") ? args.get("path").size() : 0;
    boolean extra = meta.getBody() != null || meta.hasQuery();
    int arity = pathCount + (extra ? 1 : 0) + 1;
    Method method = null;
    for (Method m : node.getClass().getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == arity && m.getParameterTypes()[arity - 1] == RequestOptions.class) method = m;
    }
    assertNotNull(method, "no method " + dotted + " with " + arity + " parameters");
    List<Object> list = new ArrayList<>();
    for (int i = 0; i < pathCount; i++) list.add(args.get("path").get(i).asText());
    if (meta.getBody() != null) list.add(model(args.has("body") ? args.get("body") : Json.MAPPER.createObjectNode(), method.getParameterTypes()[pathCount]));
    else if (meta.hasQuery()) list.add(args.has("query") ? model(args.get("query"), method.getParameterTypes()[pathCount]) : null);
    RequestOptions.Builder options = RequestOptions.builder();
    if (args.path("options").has("idempotencyKey")) options.idempotencyKey(args.get("options").get("idempotencyKey").asText());
    list.add(options.build());
    Object result;
    try {
      result = method.invoke(node, list.toArray());
      if (result instanceof CompletableFuture) result = ClientCore.join((CompletableFuture<?>) result);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
    if ("iterate".equals(spec.path("mode").asText())) {
      List<Object> items = new ArrayList<>();
      for (Object item : ((Page<?, ?>) result).autoPager()) items.add(item);
      return items;
    }
    return result;
  }

  private static boolean matches(JsonNode expected, JsonNode actual) {
    if (expected.isTextual() && expected.asText().startsWith("~")) return actual != null && actual.isTextual() && Pattern.compile(expected.asText().substring(1)).matcher(actual.asText()).find();
    if (expected.isNumber() && actual != null && actual.isNumber()) return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
    return expected.equals(actual);
  }

  static void partial(JsonNode expected, JsonNode actual, String where) {
    if (expected.isObject()) {
      assertTrue(actual != null && actual.isObject(), where + ": expected an object, got " + actual);
      for (Map.Entry<String, JsonNode> e : expected.properties()) partial(e.getValue(), actual.get(e.getKey()), where + "." + e.getKey());
      return;
    }
    assertTrue(matches(expected, actual == null ? Json.MAPPER.missingNode() : actual), where + ": expected " + expected + ", got " + actual);
  }

  private static void checkRequest(JsonNode exp, JsonNode req, int i) {
    String at = "request " + i;
    assertNotNull(req, at + ": missing");
    if (exp.has("method")) assertEquals(exp.get("method").asText(), req.get("method").asText(), at + ": method");
    if (exp.has("path")) assertEquals(exp.get("path").asText(), req.get("path").asText(), at + ": path");
    if (exp.has("query")) partial(exp.get("query"), req.get("query"), at + ".query");
    for (JsonNode k : exp.path("absentQuery")) assertTrue(!req.get("query").has(k.asText()), at + ": query " + k + " must be absent");
    if (exp.has("rawQuery")) assertTrue(matches(exp.get("rawQuery"), req.get("rawQuery")), at + ": raw query " + req.get("rawQuery"));
    if (exp.has("headers")) partial(exp.get("headers"), req.get("headers"), at + ".headers");
    for (JsonNode h : exp.path("absentHeaders")) assertTrue(!req.get("headers").has(h.asText()), at + ": header " + h + " must be absent");
    if (exp.has("body")) assertEquals(exp.get("body"), req.get("body"), at + ": body");
    if (exp.has("multipart")) partial(exp.get("multipart"), req.get("multipart"), at + ".multipart");
    if (exp.has("problems")) assertEquals(exp.get("problems"), req.get("problems"), at + ": the mock found problems");
  }

  private static JsonNode ids(List<?> items) {
    ArrayNode out = Json.MAPPER.createArrayNode();
    for (Object item : items) out.add(Json.MAPPER.valueToTree(item).get("id"));
    return out;
  }

  private static void checkResult(JsonNode exp, Object result) {
    if (exp.has("notNull")) assertNotNull(result, "result: expected a value");
    if (exp.has("null")) assertNull(result, "result: expected nothing");
    if (exp.has("bytes")) {
      assertTrue(result instanceof byte[], "result: expected bytes");
      assertEquals(exp.get("bytes").asInt(), ((byte[]) result).length);
    }
    if (exp.has("text")) assertTrue(result instanceof String, "result: expected text");
    if (exp.has("textStarts")) assertTrue(String.valueOf(result).startsWith(exp.get("textStarts").asText()));
    if (exp.has("fields")) partial(exp.get("fields"), Json.MAPPER.valueToTree(result), "result");
    if (exp.has("items")) assertEquals(exp.get("items"), ids((List<?>) result));
    if (exp.has("pageIds")) assertEquals(exp.get("pageIds"), ids(((Page<?, ?>) result).getData()));
    if (exp.has("nextCursor")) assertEquals(exp.get("nextCursor").isNull() ? null : exp.get("nextCursor").asText(), ((Page<?, ?>) result).getNextCursor());
  }

  /** The Java name of an error class of the cases: AlreadyTransferredError is AlreadyTransferredException. */
  static String javaClass(String name) {
    return name.replaceFirst("^API", "Api").replaceFirst("Error$", "Exception");
  }

  static void checkError(JsonNode exp, Throwable err) {
    assertNotNull(err, "expected " + exp.get("class").asText() + ", got no error");
    assertEquals(javaClass(exp.get("class").asText()), err.getClass().getSimpleName(), "error class: " + err);
    if (err instanceof ApiException) {
      ApiException e = (ApiException) err;
      if (exp.has("status")) assertEquals(exp.get("status").asInt(), e.getStatus());
      if (exp.has("code")) assertEquals(exp.get("code").asText(), e.getCode());
      if (exp.has("type")) assertEquals(exp.get("type").asText(), e.getType());
      if (exp.has("requestId")) assertEquals(exp.get("requestId").asText(), e.getRequestId());
      if (exp.has("param")) assertEquals(exp.get("param").isNull() ? null : exp.get("param").asText(), e.getParam());
      if (exp.has("verb")) assertEquals(exp.get("verb").asText(), ((CapabilityException) e).getVerb());
      if (exp.has("reason")) assertEquals(exp.get("reason").asText(), e.getError() == null ? null : e.getError().getReason());
      if (exp.has("alternative")) assertEquals(exp.get("alternative").asText(), e.getError() == null ? null : e.getError().getAlternative());
    } else if (err instanceof LiveStreamException) {
      LiveStreamException e = (LiveStreamException) err;
      if (exp.has("code")) assertEquals(exp.get("code").asText(), e.getCode());
      if (exp.has("codeOneOf")) {
        Set<String> codes = new HashSet<>();
        exp.get("codeOneOf").forEach(c -> codes.add(c.asText()));
        assertTrue(codes.contains(e.getCode()), "error.code " + e.getCode());
      }
    }
  }

  private static void checkSleeps(JsonNode exp, List<Long> sleeps) {
    assertEquals(exp.size(), sleeps.size(), "sleeps: " + sleeps);
    for (int i = 0; i < exp.size(); i++) {
      JsonNode e = exp.get(i);
      long s = sleeps.get(i);
      if (e.isNumber()) assertEquals(e.asLong(), s, "sleep " + i);
      else assertTrue(s >= e.get("min").asLong() && s <= e.get("max").asLong(), "sleep " + i + ": " + s);
    }
  }

  private static void runHttp(JsonNode c, boolean async) throws Throwable {
    Mock mock = Mock.get();
    mock.reset(c.get("responses"), null);
    List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
    Object result = null;
    Throwable error = null;
    try {
      result = call(client(mock, c.get("client"), sleeps), c.get("call"), async);
    } catch (Throwable e) {
      error = e;
    }
    JsonNode exp = c.get("expect");
    if (!exp.has("error") && error != null) throw error;
    JsonNode requests = mock.requests();
    if (exp.has("requestCount")) assertEquals(exp.get("requestCount").asInt(), requests.size(), "request count");
    JsonNode reqs = exp.path("requests");
    for (int i = 0; i < reqs.size(); i++) checkRequest(reqs.get(i), requests.get(i), i);
    if (exp.has("sameHeaderAcrossRequests")) {
      Set<String> values = new HashSet<>();
      for (JsonNode r : requests) values.add(r.get("headers").path(exp.get("sameHeaderAcrossRequests").asText()).asText());
      assertEquals(1, values.size(), "the header differs between attempts");
    }
    if (exp.has("sleepsMs")) checkSleeps(exp.get("sleepsMs"), sleeps);
    if (exp.has("result")) checkResult(exp.get("result"), result);
    if (exp.has("error")) checkError(exp.get("error"), error);
  }

  private static void runLive(JsonNode c) throws Throwable {
    Mock mock = Mock.get();
    mock.reset(null, c.get("ws"));
    MediSyncClient client = client(mock, null, new ArrayList<>());
    List<StreamEvent> events = Collections.synchronizedList(new ArrayList<>());
    Throwable error = null;
    Thread reader = null;
    Throwable[] readError = new Throwable[1];
    long finishMs = -1;
    try {
      LiveStream stream = null;
      for (JsonNode step : c.get("steps")) {
        if (step.has("open")) {
          StreamOptions.Builder options = StreamOptions.builder();
          if (step.get("open").has("config")) options.config(Json.MAPPER.convertValue(step.get("open").get("config"), StreamConfig.class));
          if (step.get("open").has("token")) options.token(step.get("open").get("token").asText());
          stream = client.audio().stream().open(options.build());
          LiveStream s = stream;
          reader = new Thread(() -> {
            try {
              for (StreamEvent e : s) events.add(e);
            } catch (Throwable t) {
              readError[0] = t;
            }
          });
          reader.start();
        } else if (step.has("setTerms")) {
          List<String> terms = new ArrayList<>();
          step.get("setTerms").forEach(t -> terms.add(t.asText()));
          stream.setTerms(terms);
        } else if (step.has("sendAudio")) {
          double frameSeconds = step.get("sendAudio").get("frameSeconds").asDouble();
          byte[] frame = new byte[(int) Math.round(frameSeconds * 32000)];
          long n = Math.round(step.get("sendAudio").get("seconds").asDouble() / frameSeconds);
          for (long i = 0; i < n; i++) stream.send(frame);
        } else if (step.has("endUtterance")) {
          stream.endUtterance();
        } else if (step.has("finish")) {
          long started = System.nanoTime();
          JsonNode f = step.get("finish");
          if (f.has("flushTimeoutMs")) stream.finish(Duration.ofMillis(f.get("flushTimeoutMs").asLong()));
          else stream.finish();
          finishMs = (System.nanoTime() - started) / 1_000_000;
        } else if (step.has("close")) {
          stream.close();
        }
      }
      if (reader != null) reader.join(Duration.ofSeconds(30).toMillis());
    } catch (Throwable e) {
      error = e;
    }
    JsonNode exp = c.get("expect");
    if (!exp.has("error") && error != null) throw error;
    if (!exp.has("error") && readError[0] != null) throw readError[0];
    if (exp.has("error")) checkError(exp.get("error"), error);
    JsonNode log = mock.ws();
    if (exp.has("connectionCount")) assertEquals(exp.get("connectionCount").asInt(), log.size(), "connections");
    JsonNode conns = exp.path("connections");
    for (int i = 0; i < conns.size(); i++) {
      JsonNode e = conns.get(i);
      JsonNode conn = log.get(i);
      assertNotNull(conn, "connection " + i + " missing");
      if (e.has("headers")) partial(e.get("headers"), conn.get("headers"), "connection " + i + ".headers");
      for (JsonNode h : e.path("absentHeaders")) assertTrue(!conn.get("headers").has(h.asText()), "header " + h + " must be absent");
      if (e.has("query")) partial(e.get("query"), conn.get("query"), "connection " + i + ".query");
      for (JsonNode q : e.path("absentQuery")) assertTrue(!conn.get("query").has(q.asText()), "query " + q + " must be absent");
      if (e.has("config")) partial(e.get("config"), conn.get("config"), "connection " + i + ".config");
      if (e.has("audioBytes")) assertEquals(e.get("audioBytes").asLong(), conn.get("audioBytes").asLong(), "connection " + i + ": audio bytes");
      if (e.has("firstAudioAt")) assertTrue(Math.abs(conn.get("firstAudioAt").asDouble() - e.get("firstAudioAt").asDouble()) < 1e-6, "first audio at " + conn.get("firstAudioAt"));
      if (e.has("messageTypes")) {
        ArrayNode types = Json.MAPPER.createArrayNode();
        conn.get("messages").forEach(m -> types.add(m.get("type")));
        assertEquals(e.get("messageTypes"), types, "connection " + i + ": message types");
      }
      if (e.has("terms")) {
        JsonNode terms = null;
        for (JsonNode m : conn.get("messages")) if ("terms".equals(m.path("type").asText()) && terms == null) terms = m.get("terms");
        assertEquals(e.get("terms"), terms);
      }
    }
    List<String> types;
    synchronized (events) {
      types = events.stream().map(StreamEvent::getType).collect(Collectors.toList());
    }
    if (exp.has("eventTypes")) {
      List<String> expected = new ArrayList<>();
      exp.get("eventTypes").forEach(t -> expected.add(t.asText()));
      assertEquals(expected, types, "event types");
    }
    for (JsonNode t : exp.path("eventTypesInclude")) assertTrue(types.contains(t.asText()), "event " + t + " missing in " + types);
    if (exp.has("finishMs")) {
      assertTrue(finishMs >= 0, "finish did not return");
      JsonNode fm = exp.get("finishMs");
      if (fm.has("min")) assertTrue(finishMs >= fm.get("min").asLong(), "finish took " + finishMs + " ms");
      if (fm.has("max")) assertTrue(finishMs <= fm.get("max").asLong(), "finish took " + finishMs + " ms");
    }
    if (exp.has("lastSegmentEnd")) {
      double max = Double.NEGATIVE_INFINITY;
      synchronized (events) {
        for (StreamEvent e : events) if ("transcript".equals(e.getType())) max = Math.max(max, Json.MAPPER.valueToTree(e).path("segment").path("end").asDouble());
      }
      assertEquals(exp.get("lastSegmentEnd").asDouble(), max, 1e-9);
    }
  }

  private static Stream<String> files() throws Exception {
    List<String> out = new ArrayList<>();
    try (Stream<java.nio.file.Path> list = Files.list(Mock.ROOT.resolve("conformance/cases"))) {
      list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json") && !n.equals("live.json")).sorted().forEach(out::add);
    }
    return out.stream();
  }

  @TestFactory
  Stream<DynamicNode> httpCases() throws Exception {
    List<DynamicNode> out = new ArrayList<>();
    for (String file : files().collect(Collectors.toList())) {
      JsonNode cases = load(file);
      for (boolean async : new boolean[] {false, true}) {
        List<DynamicNode> tests = new ArrayList<>();
        for (Iterator<JsonNode> it = cases.elements(); it.hasNext(); ) {
          JsonNode c = it.next();
          tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            try {
              runHttp(c, async);
            } catch (Error | Exception e) {
              throw e;
            } catch (Throwable t) {
              fail(t);
            }
          }));
        }
        out.add(DynamicContainer.dynamicContainer(file + (async ? " async" : " sync"), tests));
      }
    }
    return out.stream();
  }

  @TestFactory
  Stream<DynamicNode> liveCases() throws Exception {
    List<DynamicNode> tests = new ArrayList<>();
    for (Iterator<JsonNode> it = load("live.json").elements(); it.hasNext(); ) {
      JsonNode c = it.next();
      tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
        try {
          runLive(c);
        } catch (Error | Exception e) {
          throw e;
        } catch (Throwable t) {
          fail(t);
        }
      }));
    }
    return tests.stream().map(t -> (DynamicNode) t);
  }
}
