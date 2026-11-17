package me.medisync.core;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.medisync.ResponseInfo;
import me.medisync.connect.AppTokenSource;
import org.jspecify.annotations.Nullable;

/** The settings of a client. {@link me.medisync.MediSyncClient.Builder} fills them. */
public final class ClientSettings {
  /** A secret API key, or null for the MEDISYNC_API_KEY environment variable. */
  public @Nullable String apiKey;

  /** The token source of a MediSync Connect app, or null. */
  public @Nullable AppTokenSource app;

  /** Or null for MEDISYNC_BASE_URL, else https://api.medisync.me. */
  public @Nullable String baseUrl;

  /** The MediSync-Version header, or null for the version of this SDK. */
  public @Nullable String version;

  /** Per attempt. */
  public Duration timeout = Duration.ofSeconds(120);

  /** Retries of a call that may be retried. */
  public int maxRetries = 2;

  /** Headers sent with every call. */
  public Map<String, String> defaultHeaders = new LinkedHashMap<>();

  /** Or null for a client of the SDK. */
  public @Nullable HttpClient httpClient;

  /** Called after every answer. */
  public @Nullable Consumer<ResponseInfo> onResponse;

  /** Makes Idempotency-Keys. */
  public Supplier<String> idempotencyKey = () -> java.util.UUID.randomUUID().toString();

  /** Waits between retries and polls. */
  public Sleeper sleeper = Sleeper.DEFAULT;

  /** Reads an environment variable; tests swap it. */
  public java.util.function.Function<String, @Nullable String> env = System::getenv;
}
