package me.medisync;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.medisync.connect.AppCredentials;
import me.medisync.connect.AppTokenSource;
import me.medisync.core.ClientCore;
import me.medisync.core.ClientSettings;
import me.medisync.core.Sleeper;
import me.medisync.resources.MediSyncResources;
import org.jspecify.annotations.Nullable;

/**
 * The MediSync client: one client for the platform API and MediSync Connect. Every method waits
 * for its answer; {@link #async()} has the same methods with {@code CompletableFuture} results.
 *
 * <pre>{@code
 * MediSyncClient client = new MediSyncClient(); // reads MEDISYNC_API_KEY
 * ModelList models = client.models().list();
 * }</pre>
 *
 * <p>Everything the API generates is a draft. A clinician reviews and releases it.
 */
public final class MediSyncClient extends MediSyncResources {
  private final ClientCore core;
  private final MediSyncClientAsync async;

  /** A client with the key of the MEDISYNC_API_KEY environment variable. */
  public MediSyncClient() {
    this(new MediSyncClientAsync(new ClientCore(new ClientSettings())));
  }

  /** A client with this API key. */
  public MediSyncClient(String apiKey) {
    this(builder().apiKey(apiKey).buildAsync());
  }

  private MediSyncClient(MediSyncClientAsync async) {
    super(async);
    this.async = async;
    this.core = async.core();
  }

  /** A builder for the other options. */
  public static Builder builder() {
    return new Builder();
  }

  /** The same client with async methods. Both share one HTTP client and one token. */
  public MediSyncClientAsync async() {
    return async;
  }

  /** The base URL the client calls. */
  public String getBaseUrl() {
    return core.baseUrl();
  }

  /** The MediSync-Version header the client sends. */
  public String getVersion() {
    return core.version();
  }

  /** Builds a {@link MediSyncClient}. */
  public static final class Builder {
    private final ClientSettings settings = new ClientSettings();
    private @Nullable AppCredentials appCredentials;

    private Builder() {}

    /** A secret API key, <code>msk_live_…</code> or <code>msk_test_…</code>. Default: MEDISYNC_API_KEY. */
    public Builder apiKey(String apiKey) {
      settings.apiKey = apiKey;
      return this;
    }

    /** A MediSync Connect app with a key pair: Connect calls use the app's access token instead of the API key. */
    public Builder app(AppCredentials credentials) {
      this.appCredentials = credentials;
      this.settings.app = null;
      return this;
    }

    /** Shares one app token between clients. */
    public Builder app(AppTokenSource tokens) {
      this.settings.app = tokens;
      this.appCredentials = null;
      return this;
    }

    /** Default: MEDISYNC_BASE_URL, else https://api.medisync.me. */
    public Builder baseUrl(String baseUrl) {
      settings.baseUrl = baseUrl;
      return this;
    }

    /** The MediSync-Version header. Default: the version this SDK was generated for. */
    public Builder version(String version) {
      settings.version = version;
      return this;
    }

    /** Per attempt. Default 120 seconds. */
    public Builder timeout(Duration timeout) {
      settings.timeout = timeout;
      return this;
    }

    /** Retries after a network error, 408, 409 idempotency_key_in_use, 429 or 5xx. Default 2. */
    public Builder maxRetries(int maxRetries) {
      settings.maxRetries = maxRetries;
      return this;
    }

    /** A header sent with every call. */
    public Builder defaultHeader(String name, String value) {
      settings.defaultHeaders.put(name, value);
      return this;
    }

    /** An HTTP client to use, for example with a proxy. */
    public Builder httpClient(HttpClient httpClient) {
      settings.httpClient = httpClient;
      return this;
    }

    /** Called after every answer. Log the request id: support looks a call up by it. */
    public Builder onResponse(Consumer<ResponseInfo> callback) {
      settings.onResponse = callback;
      return this;
    }

    /** Makes Idempotency-Keys when a call gives none. Default: random UUIDs. */
    public Builder idempotencyKeys(Supplier<String> keys) {
      settings.idempotencyKey = keys;
      return this;
    }

    /** Waits between retries and polls. Tests swap it. */
    public Builder sleeper(Sleeper sleeper) {
      settings.sleeper = sleeper;
      return this;
    }

    /** Reads environment variables. Tests swap it. */
    public Builder env(java.util.function.Function<String, @Nullable String> env) {
      settings.env = env;
      return this;
    }

    /** The client. Throws when there is neither an API key nor an app. */
    public MediSyncClient build() {
      return new MediSyncClient(buildAsync());
    }

    /** The async client. */
    public MediSyncClientAsync buildAsync() {
      AppCredentials credentials = appCredentials;
      if (credentials != null) settings.app = settings.httpClient != null ? new AppTokenSource(credentials, settings.httpClient) : new AppTokenSource(credentials);
      return new MediSyncClientAsync(new ClientCore(settings));
    }
  }
}
