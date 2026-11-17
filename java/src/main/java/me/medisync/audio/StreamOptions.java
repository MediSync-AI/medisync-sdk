package me.medisync.audio;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import me.medisync.models.StreamConfig;
import org.jspecify.annotations.Nullable;

/** Options of <code>audio.stream.open</code>. */
public final class StreamOptions {
  private final @Nullable StreamConfig config;
  private final @Nullable String token;
  private final @Nullable Supplier<CompletableFuture<String>> tokenSupplier;
  private final boolean reconnect;
  private final int maxReconnects;
  private final double bufferSeconds;

  private StreamOptions(Builder b) {
    this.config = b.config;
    this.token = b.token;
    this.tokenSupplier = b.tokenSupplier;
    this.reconnect = b.reconnect;
    this.maxReconnects = b.maxReconnects;
    this.bufferSeconds = b.bufferSeconds;
  }

  /** 16 kHz mono pcm_s16le, language detected, reconnect on. */
  public static StreamOptions defaults() {
    return builder().build();
  }

  /** A builder. */
  public static Builder builder() {
    return new Builder();
  }

  @Nullable StreamConfig getConfig() {
    return config;
  }

  @Nullable String getToken() {
    return token;
  }

  @Nullable Supplier<CompletableFuture<String>> getTokenSupplier() {
    return tokenSupplier;
  }

  boolean isReconnect() {
    return reconnect;
  }

  int getMaxReconnects() {
    return maxReconnects;
  }

  double getBufferSeconds() {
    return bufferSeconds;
  }

  /** Builds {@link StreamOptions}. */
  public static final class Builder {
    private @Nullable StreamConfig config;
    private @Nullable String token;
    private @Nullable Supplier<CompletableFuture<String>> tokenSupplier;
    private boolean reconnect = true;
    private int maxReconnects = 5;
    private double bufferSeconds = 300;

    private Builder() {}

    /** The config. Its <code>type</code> is set by the SDK. Default: 16 kHz mono pcm_s16le, language detected. */
    public Builder config(StreamConfig value) {
      this.config = value;
      return this;
    }

    /** A single-use stream token (<code>mst_…</code>) instead of the API key. A stream with one token cannot reconnect. */
    public Builder token(String value) {
      this.token = value;
      return this;
    }

    /** Gets a new stream token for each connection, so the stream can connect again after a drop. */
    public Builder tokenSupplier(Supplier<CompletableFuture<String>> value) {
      this.tokenSupplier = value;
      return this;
    }

    /** Connect again after a drop or a fatal error a new stream can continue. Default true for pcm_s16le. */
    public Builder reconnect(boolean value) {
      this.reconnect = value;
      return this;
    }

    /** Attempts in a row without a session_ready before the stream gives up. Default 5. */
    public Builder maxReconnects(int value) {
      this.maxReconnects = value;
      return this;
    }

    /** Seconds of sent audio kept for a reconnect. Older audio is dropped. Default 300. */
    public Builder bufferSeconds(double value) {
      this.bufferSeconds = value;
      return this;
    }

    /** The options. */
    public StreamOptions build() {
      return new StreamOptions(this);
    }
  }
}
