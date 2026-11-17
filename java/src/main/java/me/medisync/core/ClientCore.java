package me.medisync.core;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.medisync.Page;
import me.medisync.RequestOptions;
import me.medisync.ResponseInfo;
import me.medisync.Version;
import me.medisync.connect.AppTokenSource;
import me.medisync.errors.ApiConnectionException;
import me.medisync.errors.ApiErrors;
import me.medisync.errors.ApiException;
import me.medisync.errors.ApiTimeoutException;
import me.medisync.errors.ErrorBody;
import me.medisync.errors.MediSyncException;
import me.medisync.resources.Operations;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP core of the client: auth, headers, retries, idempotency keys, errors and pages. The
 * generated resource classes call {@link #request} and {@link #requestPage} with the operation's
 * meta. Public for the generated code; not a stable API.
 */
public final class ClientCore {
  /** The base URL of the API. */
  public static final String DEFAULT_BASE_URL = "https://api.medisync.me";

  private static final long MAX_RETRY_WAIT_MS = 60_000;
  private static final Set<String> SAFE = new HashSet<>(Arrays.asList("GET", "HEAD", "OPTIONS"));

  private final @Nullable String apiKey;
  private final @Nullable AppTokenSource tokens;
  private final String baseUrl;
  private final String version;
  private final Duration timeout;
  private final int maxRetries;
  private final Map<String, String> defaultHeaders;
  private final HttpClient http;
  private final @Nullable Consumer<ResponseInfo> onResponse;
  private final Supplier<String> newKey;
  private final Sleeper sleeper;

  /** A core with these settings. Throws when there is neither an API key nor an app. */
  public ClientCore(ClientSettings s) {
    String key = s.apiKey != null ? s.apiKey : s.env.apply("MEDISYNC_API_KEY");
    this.apiKey = key == null || key.isEmpty() ? null : key;
    this.tokens = s.app;
    if (this.apiKey == null && this.tokens == null) throw new MediSyncException("No API key. Pass apiKey, or set the MEDISYNC_API_KEY environment variable.");
    String base = s.baseUrl != null ? s.baseUrl : s.env.apply("MEDISYNC_BASE_URL");
    if (base == null || base.isEmpty()) base = DEFAULT_BASE_URL;
    this.baseUrl = base.replaceAll("/+$", "");
    this.version = s.version != null ? s.version : Operations.API_VERSION;
    this.timeout = s.timeout;
    this.maxRetries = s.maxRetries;
    this.defaultHeaders = Collections.unmodifiableMap(lower(s.defaultHeaders));
    // HTTP/2 over TLS; plain http (a local mock) stays on HTTP/1.1 without an upgrade.
    this.http =
        s.httpClient != null
            ? s.httpClient
            : HttpClient.newBuilder().version(baseUrl.startsWith("https:") ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(30)).build();
    this.onResponse = s.onResponse;
    this.newKey = s.idempotencyKey;
    this.sleeper = s.sleeper;
  }

  private static Map<String, String> lower(Map<String, String> headers) {
    Map<String, String> out = new LinkedHashMap<>();
    headers.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
    return out;
  }

  /** The base URL without a trailing slash. */
  public String baseUrl() {
    return baseUrl;
  }

  /** The MediSync-Version header. */
  public String version() {
    return version;
  }

  /** The HTTP client, also for the live stream. */
  public HttpClient http() {
    return http;
  }

  /** The sleeper of retries and helpers. */
  public Sleeper sleeper() {
    return sleeper;
  }

  /** The default timeout per attempt. */
  public Duration timeout() {
    return timeout;
  }

  /** Waits for a future and throws its exception as it is, not wrapped. */
  public static <T> T join(CompletableFuture<T> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new MediSyncException("Interrupted.", e);
    } catch (ExecutionException e) {
      throw unwrap(e.getCause());
    }
  }

  /** The exception inside a CompletionException, as a RuntimeException. */
  public static RuntimeException unwrap(@Nullable Throwable error) {
    Throwable t = error;
    while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
    if (t instanceof RuntimeException) return (RuntimeException) t;
    if (t instanceof Error) throw (Error) t;
    return new MediSyncException(t == null ? "Unknown error." : String.valueOf(t.getMessage()), t);
  }

  /** The bearer secret of an operation of the API. */
  public CompletableFuture<String> authorization(String api) {
    AppTokenSource t = tokens;
    if ("connect".equals(api) && t != null) return t.get().thenApply(token -> "Bearer " + token);
    if (apiKey == null) return failed(new MediSyncException("This call needs an API key: the app credentials work for MediSync Connect only."));
    return CompletableFuture.completedFuture("Bearer " + apiKey);
  }

  /** Headers of every call: version, user agent and the default headers. */
  public Map<String, String> baseHeaders() {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("medisync-version", version);
    h.put("user-agent", "medisync-java/" + Version.VERSION);
    h.putAll(defaultHeaders);
    return h;
  }

  static <T> CompletableFuture<T> failed(Throwable e) {
    CompletableFuture<T> f = new CompletableFuture<>();
    f.completeExceptionally(e);
    return f;
  }

  /** encodeURIComponent of JavaScript. */
  public static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~");
  }

  private static Map<String, JsonNode> queryNodes(@Nullable Object query) {
    Map<String, JsonNode> out = new LinkedHashMap<>();
    if (query == null) return out;
    JsonNode node = Json.MAPPER.valueToTree(query);
    if (!(node instanceof ObjectNode)) throw new MediSyncException("The query must be an object.");
    for (Map.Entry<String, JsonNode> e : node.properties()) {
      if (!e.getValue().isNull()) out.put(e.getKey(), e.getValue());
    }
    return out;
  }

  /** The URL of an operation with its path and query parameters. */
  URI url(OperationMeta op, RequestArgs args, Map<String, JsonNode> query) {
    StringBuilder path = new StringBuilder();
    String template = op.getPath();
    int i = 0;
    while (i < template.length()) {
      int open = template.indexOf('{', i);
      if (open < 0) {
        path.append(template, i, template.length());
        break;
      }
      int close = template.indexOf('}', open);
      path.append(template, i, open);
      String name = template.substring(open + 1, close);
      String value = args.getPath().get(name);
      if (value == null || value.isEmpty()) throw new MediSyncException("Missing the path parameter " + name + ".");
      path.append(encode(value));
      i = close + 1;
    }
    List<String> pairs = new ArrayList<>();
    for (Map.Entry<String, JsonNode> e : query.entrySet()) {
      JsonNode v = e.getValue();
      if (v.isArray()) {
        List<String> items = new ArrayList<>();
        for (JsonNode item : v) items.add(item.isValueNode() ? item.asText() : item.toString());
        if (op.getCommaQuery().contains(e.getKey())) pairs.add(encode(e.getKey()) + "=" + encode(String.join(",", items)));
        else for (String item : items) pairs.add(encode(e.getKey()) + "=" + encode(item));
      } else {
        pairs.add(encode(e.getKey()) + "=" + encode(v.isValueNode() ? v.asText() : v.toString()));
      }
    }
    return URI.create(baseUrl + path + (pairs.isEmpty() ? "" : "?" + String.join("&", pairs)));
  }

  /** Exponential backoff with jitter: about 0.5 s, 1 s, 2 s, ... up to 8 s. */
  public static long backoffMs(int attempt) {
    double base = Math.min(500 * Math.pow(2, attempt), 8_000);
    return Math.round(base * (1 - ThreadLocalRandom.current().nextDouble() * 0.25));
  }

  /** True when an answer with this status and error may succeed on a retry. */
  public static boolean retryableStatus(int status, ApiException error) {
    ErrorBody body = error.getError();
    if (body != null && Boolean.FALSE.equals(body.getRetryable())) return false;
    if (status == 408) return true;
    if (status == 409) return "idempotency_key_in_use".equals(error.getCode());
    // A spend cap or the daily test allowance does not reset within a retry.
    if (status == 429) return !"spend_cap_reached".equals(error.getCode()) && !"test_daily_limit_reached".equals(error.getCode());
    return status >= 500 && status != 501;
  }

  private static @Nullable Long retryAfterMs(HttpHeaders headers, ApiException error) {
    String header = headers.firstValue("retry-after").orElse(null);
    if (header != null) {
      try {
        double seconds = Double.parseDouble(header.trim());
        if (seconds >= 0) return Math.round(seconds * 1000);
      } catch (NumberFormatException notSeconds) {
        try {
          long at = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
          return Math.max(at - System.currentTimeMillis(), 0);
        } catch (RuntimeException notDate) {
          // Neither: use the body or the backoff.
        }
      }
    }
    ErrorBody body = error.getError();
    if (body != null && body.getRetryAfter() != null) return Math.round(body.getRetryAfter() * 1000);
    return null;
  }

  private static final class Call {
    final OperationMeta op;
    final URI url;
    final Map<String, String> headers;
    final HttpRequest.BodyPublisher body;
    final int maxRetries;
    final Duration timeout;

    Call(OperationMeta op, URI url, Map<String, String> headers, HttpRequest.BodyPublisher body, int maxRetries, Duration timeout) {
      this.op = op;
      this.url = url;
      this.headers = headers;
      this.body = body;
      this.maxRetries = maxRetries;
      this.timeout = timeout;
    }
  }

  /** Sends one operation and completes with the answer when it is 2xx, after retries. */
  CompletableFuture<HttpResponse<byte[]>> send(OperationMeta op, RequestArgs args, RequestOptions options) {
    Call call;
    try {
      Map<String, JsonNode> query = args.getQueryNodes() != null ? args.getQueryNodes() : queryNodes(args.getQuery());
      URI url = url(op, args, query);
      Map<String, String> headers = baseHeaders();
      headers.put("accept", "json".equals(op.getResponse()) ? "application/json" : "*/*");
      HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
      if ("json".equals(op.getBody())) {
        headers.put("content-type", "application/json");
        Object value = args.getBody() != null ? args.getBody() : Collections.emptyMap();
        body = HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(value));
      } else if ("multipart".equals(op.getBody())) {
        Multipart form = Multipart.of(args.getBody() != null ? args.getBody() : Collections.emptyMap());
        headers.put("content-type", form.contentType());
        body = HttpRequest.BodyPublishers.ofByteArray(form.bytes());
      }
      if (!"none".equals(op.getIdempotency())) headers.put("idempotency-key", options.getIdempotencyKey() != null ? options.getIdempotencyKey() : newKey.get());
      headers.putAll(options.getHeaders());
      boolean canRetry = SAFE.contains(op.getMethod()) || headers.containsKey("idempotency-key");
      int retries = canRetry ? (options.getMaxRetries() != null ? options.getMaxRetries() : maxRetries) : 0;
      call = new Call(op, url, headers, body, retries, options.getTimeout() != null ? options.getTimeout() : timeout);
    } catch (RuntimeException | IOException e) {
      return failed(e instanceof RuntimeException ? e : new MediSyncException("Could not encode the request: " + e.getMessage(), e));
    }
    CompletableFuture<HttpResponse<byte[]>> result = new CompletableFuture<>();
    attempt(call, 0, false, result);
    return result;
  }

  private void attempt(Call call, int attempt, boolean tokenRetried, CompletableFuture<HttpResponse<byte[]>> result) {
    if (result.isDone()) return;
    OperationMeta op = call.op;
    authorization(op.getApi())
        .thenCompose(
            auth -> {
              HttpRequest.Builder b = HttpRequest.newBuilder(call.url).timeout(call.timeout).method(op.getMethod(), call.body);
              call.headers.forEach(b::header);
              b.header("authorization", auth);
              return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            })
        .whenComplete(
            (response, err) -> {
              try {
                if (err != null) onFailure(call, attempt, tokenRetried, strip(err), result);
                else onAnswer(call, attempt, tokenRetried, response, result);
              } catch (RuntimeException e) {
                result.completeExceptionally(e);
              }
            });
  }

  private void retryAfter(long ms, Call call, int attempt, boolean tokenRetried, CompletableFuture<HttpResponse<byte[]>> result) {
    sleeper
        .sleep(Duration.ofMillis(ms))
        .whenComplete(
            (v, e) -> {
              if (e != null) result.completeExceptionally(unwrap(e));
              else attempt(call, attempt + 1, tokenRetried, result);
            });
  }

  /** The error inside CompletionException and ExecutionException wrappers. */
  private static Throwable strip(Throwable error) {
    Throwable t = error;
    while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
    return t;
  }

  private void onFailure(Call call, int attempt, boolean tokenRetried, Throwable err, CompletableFuture<HttpResponse<byte[]>> result) {
    // A refused app token is an answer of the authorization server, not a network fault.
    if (err instanceof Error || (err instanceof MediSyncException && !(err instanceof ApiConnectionException))) {
      result.completeExceptionally(err);
      return;
    }
    Throwable cause = err instanceof ApiConnectionException && err.getCause() != null ? err.getCause() : err;
    if (attempt < call.maxRetries) {
      retryAfter(backoffMs(attempt), call, attempt, tokenRetried, result);
      return;
    }
    String what = call.op.getMethod() + " " + call.url.getPath();
    if (cause instanceof HttpTimeoutException) result.completeExceptionally(new ApiTimeoutException(what + " timed out after " + call.timeout.toMillis() + " ms.", cause));
    else result.completeExceptionally(new ApiConnectionException(what + " failed: " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()), cause));
  }

  private void onAnswer(Call call, int attempt, boolean tokenRetried, HttpResponse<byte[]> response, CompletableFuture<HttpResponse<byte[]>> result) {
    HttpHeaders headers = response.headers();
    String requestId = headers.firstValue("request-id").or(() -> headers.firstValue("medisync-request-id")).orElse(null);
    Consumer<ResponseInfo> callback = onResponse;
    if (callback != null) callback.accept(new ResponseInfo(call.op.getOperationId(), response.statusCode(), requestId, attempt, headers));
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      result.complete(response);
      return;
    }
    ApiException error = ApiErrors.fromResponse(status, response.body() == null ? new byte[0] : response.body(), headers);
    AppTokenSource t = tokens;
    if (status == 401 && t != null && "connect".equals(call.op.getApi()) && "invalid_token".equals(error.getCode()) && !tokenRetried) {
      // The access token ended early: get a new one and send again, once.
      t.clear();
      attempt(call, attempt, true, result);
      return;
    }
    if (attempt < call.maxRetries && retryableStatus(status, error)) {
      Long wait = retryAfterMs(headers, error);
      retryAfter(Math.min(wait != null ? wait : backoffMs(attempt), MAX_RETRY_WAIT_MS), call, attempt, tokenRetried, result);
      return;
    }
    result.completeExceptionally(error);
  }

  @SuppressWarnings("unchecked")
  private static <T> @Nullable T parse(OperationMeta op, HttpResponse<byte[]> response, JavaType type) {
    byte[] body = response.body() == null ? new byte[0] : response.body();
    switch (op.getResponse()) {
      case "empty":
        return null;
      case "binary":
        return (T) body;
      case "text":
        return (T) new String(body, StandardCharsets.UTF_8);
      default:
        if (body.length == 0) return null;
        try {
          return Json.MAPPER.readValue(body, type);
        } catch (IOException e) {
          throw new MediSyncException("The answer of " + op.getOperationId() + " is not the JSON the SDK expected: " + e.getMessage(), e);
        }
    }
  }

  /** Sends one operation and completes with its parsed answer. */
  public <T> CompletableFuture<T> request(OperationMeta op, RequestArgs args, JavaType type, RequestOptions options) {
    return send(op, args, options).thenApply(response -> ClientCore.<T>parse(op, response, type));
  }

  /** A list operation: completes with the first page, which loads the next ones. */
  public <T, L> CompletableFuture<Page<T, L>> requestPage(OperationMeta op, RequestArgs args, JavaType item, JavaType list, RequestOptions options) {
    Map<String, JsonNode> query;
    try {
      query = queryNodes(args.getQuery());
    } catch (RuntimeException e) {
      return failed(e);
    }
    return loadPage(op, args, query, item, list, options);
  }

  private <T, L> CompletableFuture<Page<T, L>> loadPage(OperationMeta op, RequestArgs args, Map<String, JsonNode> query, JavaType item, JavaType list, RequestOptions options) {
    return send(op, args.withQueryNodes(query), options)
        .thenApply(
            response -> {
              try {
                JsonNode tree = Json.MAPPER.readTree(response.body());
                if (tree == null || !tree.isObject()) tree = Json.MAPPER.createObjectNode();
                L raw = Json.MAPPER.convertValue(tree, list);
                JsonNode dataNode = tree.has("data") && tree.get("data").isArray() ? tree.get("data") : Json.MAPPER.createArrayNode();
                List<T> data = Json.MAPPER.convertValue((ArrayNode) dataNode, Types.listOf(item));
                JsonNode cursorNode = tree.get("next_cursor");
                String cursor = cursorNode != null && cursorNode.isTextual() ? cursorNode.asText() : null;
                return new Page<T, L>(
                    data,
                    cursor,
                    raw,
                    next -> {
                      Map<String, JsonNode> q = new LinkedHashMap<>(query);
                      q.put("cursor", TextNode.valueOf(next));
                      return loadPage(op, args, q, item, list, options);
                    });
              } catch (IOException e) {
                throw new MediSyncException("The answer of " + op.getOperationId() + " is not JSON: " + e.getMessage(), e);
              }
            });
  }
}
