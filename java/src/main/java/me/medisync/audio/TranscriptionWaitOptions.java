package me.medisync.audio;

import java.time.Duration;
import java.util.function.Consumer;
import me.medisync.models.Transcription;
import org.jspecify.annotations.Nullable;

/** Options of <code>audio.transcriptions.wait</code>. */
public final class TranscriptionWaitOptions {
  private final Duration timeout;
  private final Duration interval;
  private final @Nullable Consumer<Transcription> onUpdate;

  private TranscriptionWaitOptions(Builder b) {
    this.timeout = b.timeout;
    this.interval = b.interval;
    this.onUpdate = b.onUpdate;
  }

  /** The defaults: 600 s, first pause 2 s. */
  public static TranscriptionWaitOptions defaults() {
    return builder().build();
  }

  /** A builder. */
  public static Builder builder() {
    return new Builder();
  }

  Duration getTimeout() {
    return timeout;
  }

  Duration getInterval() {
    return interval;
  }

  @Nullable Consumer<Transcription> getOnUpdate() {
    return onUpdate;
  }

  /** Builds {@link TranscriptionWaitOptions}. */
  public static final class Builder {
    private Duration timeout = Duration.ofSeconds(600);
    private Duration interval = Duration.ofSeconds(2);
    private @Nullable Consumer<Transcription> onUpdate;

    private Builder() {}

    /** Give up after this long with TranscriptionWaitTimeoutException. Default 600 s. */
    public Builder timeout(Duration value) {
      this.timeout = value;
      return this;
    }

    /** First pause between reads. Grows by half up to 10 s. Default 2 s. */
    public Builder interval(Duration value) {
      this.interval = value;
      return this;
    }

    /** Called after each read whose status differs from the read before. */
    public Builder onUpdate(Consumer<Transcription> value) {
      this.onUpdate = value;
      return this;
    }

    /** The options. */
    public TranscriptionWaitOptions build() {
      return new TranscriptionWaitOptions(this);
    }
  }
}
