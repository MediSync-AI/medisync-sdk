package me.medisync;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Options of one call. Every generated method has an overload that takes them last.
 *
 * <pre>{@code
 * RequestOptions.builder().idempotencyKey("enc-2026-11-16-0042").build()
 * }</pre>
 */
public final class RequestOptions {
  private static final RequestOptions NONE = builder().build();

  private final @Nullable String idempotencyKey;
  private final @Nullable Duration timeout;
  private final @Nullable Integer maxRetries;
  private final Map<String, String> headers;

  private RequestOptions(Builder b) {
    this.idempotencyKey = b.idempotencyKey;
    this.timeout = b.timeout;
    this.maxRetries = b.maxRetries;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
  }

  /** No options. */
  public static RequestOptions none() {
    return NONE;
  }

  /** A builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** The Idempotency-Key, or null to let the client make one. */
  public @Nullable String getIdempotencyKey() {
    return idempotencyKey;
  }

  /** Per attempt, or null for the client's timeout. */
  public @Nullable Duration getTimeout() {
    return timeout;
  }

  /** Or null for the client's value. */
  public @Nullable Integer getMaxRetries() {
    return maxRetries;
  }

  /** Extra headers for this call. */
  public Map<String, String> getHeaders() {
    return headers;
  }

  /** Builds {@link RequestOptions}. */
  public static final class Builder {
    private @Nullable String idempotencyKey;
    private @Nullable Duration timeout;
    private @Nullable Integer maxRetries;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private Builder() {}

    /**
     * The Idempotency-Key of a POST that takes one. The client makes a UUID when you give none,
     * and sends the same key with every retry of the call. Give your own to retry a call yourself.
     */
    public Builder idempotencyKey(String key) {
      this.idempotencyKey = key;
      return this;
    }

    /** Per attempt. Default: the client's timeout. */
    public Builder timeout(Duration value) {
      this.timeout = value;
      return this;
    }

    /** Default: the client's value. */
    public Builder maxRetries(int value) {
      this.maxRetries = value;
      return this;
    }

    /** An extra header for this call. */
    public Builder header(String name, String value) {
      headers.put(name.toLowerCase(java.util.Locale.ROOT), value);
      return this;
    }

    /** The options. */
    public RequestOptions build() {
      return new RequestOptions(this);
    }
  }
}
