package me.medisync.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import me.medisync.core.ClientCore;
import me.medisync.core.Json;
import me.medisync.errors.ApiConnectionException;
import me.medisync.errors.AppAuthException;
import org.jspecify.annotations.Nullable;

/**
 * Gets and keeps the access token of a MediSync Connect app. {@link #get()} answers the current
 * token and fetches a new one when it expires within a minute; calls at the same moment share one
 * request. {@link #clear()} drops the token, so the next call fetches one (after a 401
 * <code>invalid_token</code>).
 */
public final class AppTokenSource {
  /** The authorization server of MediSync Connect. */
  public static final String DEFAULT_ISSUER = "https://connect.medisync.me";

  private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
  /** A new token this long before the old one expires. */
  private static final long REFRESH_MARGIN_MS = 60_000;

  private final AppCredentials credentials;
  private final HttpClient http;
  private final LongSupplier now;
  private @Nullable String token;
  private long expiresAt;
  private @Nullable TokenAnswer answer;
  private @Nullable CompletableFuture<TokenAnswer> pending;

  /** A source with its own HTTP client. */
  public AppTokenSource(AppCredentials credentials) {
    this(credentials, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
  }

  /** A source that uses this HTTP client. */
  public AppTokenSource(AppCredentials credentials, HttpClient http) {
    this(credentials, http, System::currentTimeMillis);
  }

  /** A source with a clock in milliseconds, for tests. */
  public AppTokenSource(AppCredentials credentials, HttpClient http, LongSupplier nowMillis) {
    this.credentials = credentials;
    this.http = http;
    this.now = nowMillis;
  }

  /** The authorization server. */
  public String getIssuer() {
    return credentials.getIssuer();
  }

  /** The current token, or a new one. */
  public synchronized CompletableFuture<String> get() {
    String current = token;
    if (current != null && expiresAt - REFRESH_MARGIN_MS > now.getAsLong()) return CompletableFuture.completedFuture(current);
    return fetchToken().thenApply(a -> String.valueOf(a.getAccessToken()));
  }

  /** The last token answer: installation id and authorization details. Null before the first call. */
  public synchronized @Nullable TokenAnswer getAnswer() {
    return token == null ? null : answer;
  }

  /** Drops the token. */
  public synchronized void clear() {
    token = null;
  }

  /** Ends the current token at Connect (RFC 7009), for example when the app signs out. */
  public CompletableFuture<Void> revoke() {
    String current;
    synchronized (this) {
      current = token;
      token = null;
    }
    if (current == null) return CompletableFuture.completedFuture(null);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("token", current);
    return post("/oauth/revoke", fields).thenApply(x -> null);
  }

  private synchronized CompletableFuture<TokenAnswer> fetchToken() {
    CompletableFuture<TokenAnswer> p = pending;
    if (p != null) return p;
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("grant_type", "client_credentials");
    if (!credentials.getScopes().isEmpty()) fields.put("scope", String.join(" ", credentials.getScopes()));
    if (credentials.getInstallationId() != null) {
      ObjectNode detail = Json.MAPPER.createObjectNode().put("type", "medisync_connect").put("installation_id", credentials.getInstallationId());
      if (!credentials.getVerbs().isEmpty()) {
        ArrayNode verbs = detail.putArray("verbs");
        for (String v : credentials.getVerbs()) {
          ObjectNode verb = verbs.addObject().put("verb", v);
          verb.putArray("roles");
        }
      }
      fields.put("authorization_details", Json.MAPPER.createArrayNode().add(detail).toString());
    } else if (credentials.getEnvironment() != null) {
      fields.put("environment", credentials.getEnvironment());
    }
    CompletableFuture<TokenAnswer> next =
        post("/oauth/token", fields)
            .thenApply(
                json -> {
                  TokenAnswer a = Json.MAPPER.convertValue(json, TokenAnswer.class);
                  if (a.getAccessToken() == null || a.getExpiresIn() == null) throw new AppAuthException(502, "invalid_response", "The token endpoint answered without a token.");
                  synchronized (this) {
                    token = a.getAccessToken();
                    expiresAt = now.getAsLong() + Math.round(a.getExpiresIn() * 1000);
                    answer = a;
                  }
                  return a;
                });
    pending = next;
    next.whenComplete(
        (a, e) -> {
          synchronized (this) {
            if (pending == next) pending = null;
          }
        });
    return next;
  }

  private static String form(Map<String, String> fields) {
    StringJoiner out = new StringJoiner("&");
    fields.forEach((k, v) -> out.add(ClientCore.encode(k) + "=" + ClientCore.encode(v)));
    return out.toString();
  }

  private CompletableFuture<JsonNode> post(String path, Map<String, String> fields) {
    String url = credentials.getIssuer() + path;
    Map<String, String> all = new LinkedHashMap<>(fields);
    all.put("client_assertion_type", ASSERTION_TYPE);
    try {
      all.put("client_assertion", ClientAssertion.create(credentials, url));
    } catch (RuntimeException e) {
      CompletableFuture<JsonNode> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("content-type", "application/x-www-form-urlencoded")
            .header("accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form(all)))
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
        .handle(
            (res, err) -> {
              if (err != null) throw new ApiConnectionException("POST " + path + " failed", ClientCore.unwrap(err));
              String text = new String(res.body(), StandardCharsets.UTF_8);
              JsonNode json;
              try {
                json = text.isEmpty() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(text);
              } catch (Exception notJson) {
                json = null;
              }
              if (res.statusCode() < 200 || res.statusCode() >= 300) {
                String error = json != null && json.path("error").isTextual() ? json.get("error").asText() : "http_" + res.statusCode();
                String description = json != null && json.path("error_description").isTextual() ? json.get("error_description").asText() : text.substring(0, Math.min(200, text.length()));
                throw new AppAuthException(res.statusCode(), error, description);
              }
              return json == null ? Json.MAPPER.createObjectNode() : json;
            });
  }
}
