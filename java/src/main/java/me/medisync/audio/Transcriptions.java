package me.medisync.audio;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import me.medisync.core.Sleeper;
import me.medisync.errors.TranscriptionWaitTimeoutException;
import me.medisync.models.Transcription;
import org.jspecify.annotations.Nullable;

/** Transcription states, and the wait of <code>audio.transcriptions.wait</code>. */
public final class Transcriptions {
  private Transcriptions() {}

  /** A transcription in one of these states changes no more. */
  public static final List<String> FINAL_STATUSES = Collections.unmodifiableList(Arrays.asList("completed", "failed", "expired"));

  /**
   * Reads a transcription until it is completed, failed or expired. A failed transcription is
   * answered, not thrown: check its status and error.
   */
  public static CompletableFuture<Transcription> waitFor(Function<String, CompletableFuture<Transcription>> get, String id, TranscriptionWaitOptions options, Sleeper sleeper) {
    long deadline = System.nanoTime() + options.getTimeout().toNanos();
    return loop(get, id, options, sleeper, deadline, options.getInterval().toMillis(), null);
  }

  private static CompletableFuture<Transcription> loop(Function<String, CompletableFuture<Transcription>> get, String id, TranscriptionWaitOptions options, Sleeper sleeper, long deadline, long interval, @Nullable String last) {
    return get.apply(id)
        .thenCompose(
            t -> {
              String status = t.getStatus();
              if (!Objects.equals(status, last) && options.getOnUpdate() != null) options.getOnUpdate().accept(t);
              if (FINAL_STATUSES.contains(status)) return CompletableFuture.completedFuture(t);
              long left = (deadline - System.nanoTime()) / 1_000_000;
              if (left <= 0) throw new TranscriptionWaitTimeoutException(t);
              return sleeper.sleep(Duration.ofMillis(Math.min(interval, left))).thenCompose(v -> loop(get, id, options, sleeper, deadline, Math.min(Math.round(interval * 1.5), 10_000L), status));
            });
  }
}
