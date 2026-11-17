package me.medisync.connect;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Predicate;
import me.medisync.models.connect.Transfer;
import org.jspecify.annotations.Nullable;

/** Options of <code>connect.transfers.wait</code>. */
public final class TransferWaitOptions {
  private final Predicate<Transfer> until;
  private final Duration timeout;
  private final Duration interval;
  private final @Nullable Consumer<Transfer> onUpdate;

  private TransferWaitOptions(Builder b) {
    this.until = b.until;
    this.timeout = b.timeout;
    this.interval = b.interval;
    this.onUpdate = b.onUpdate;
  }

  /** The defaults: a final status or <code>in_doubt</code>, 120 s, first pause 1 s. */
  public static TransferWaitOptions defaults() {
    return builder().build();
  }

  /** A builder. */
  public static Builder builder() {
    return new Builder();
  }

  Predicate<Transfer> getUntil() {
    return until;
  }

  Duration getTimeout() {
    return timeout;
  }

  Duration getInterval() {
    return interval;
  }

  @Nullable Consumer<Transfer> getOnUpdate() {
    return onUpdate;
  }

  /** Builds {@link TransferWaitOptions}. */
  public static final class Builder {
    private Predicate<Transfer> until = Transfers::isDone;
    private Duration timeout = Duration.ofSeconds(120);
    private Duration interval = Duration.ofSeconds(1);
    private @Nullable Consumer<Transfer> onUpdate;

    private Builder() {}

    /** Stop when this holds. Default: a final status, or <code>in_doubt</code> (a person must check it). */
    public Builder until(Predicate<Transfer> value) {
      this.until = value;
      return this;
    }

    /** Give up after this long with TransferWaitTimeoutException. Default 120 s. */
    public Builder timeout(Duration value) {
      this.timeout = value;
      return this;
    }

    /** First pause between reads. Doubles up to 10 s. Default 1 s. */
    public Builder interval(Duration value) {
      this.interval = value;
      return this;
    }

    /** Called after each read whose status differs from the read before. */
    public Builder onUpdate(Consumer<Transfer> value) {
      this.onUpdate = value;
      return this;
    }

    /** The options. */
    public TransferWaitOptions build() {
      return new TransferWaitOptions(this);
    }
  }
}
