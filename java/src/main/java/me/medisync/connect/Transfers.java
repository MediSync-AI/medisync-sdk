package me.medisync.connect;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import me.medisync.core.Sleeper;
import me.medisync.errors.TransferWaitTimeoutException;
import me.medisync.models.connect.Transfer;
import org.jspecify.annotations.Nullable;

/** Transfer states, and the wait of <code>connect.transfers.wait</code>. */
public final class Transfers {
  private Transfers() {}

  /** A transfer in one of these states changes no more. */
  public static final List<String> FINAL_STATUSES = Collections.unmodifiableList(Arrays.asList("completed", "partially_completed", "failed", "expired"));

  private static final long MAX_INTERVAL_MS = 10_000;

  /** True for completed, partially_completed, failed and expired. An unknown status is not final. */
  public static boolean isFinalStatus(String status) {
    return FINAL_STATUSES.contains(status);
  }

  static boolean isDone(Transfer t) {
    return isFinalStatus(String.valueOf(t.getStatus())) || "in_doubt".equals(t.getStatus());
  }

  /**
   * Reads a transfer until the <code>until</code> check holds. A transfer waits in the queue while the
   * site's hub is offline, up to 24 hours: for long waits, follow <code>events.poll</code> instead.
   */
  public static CompletableFuture<Transfer> waitFor(Function<String, CompletableFuture<Transfer>> get, String transferId, TransferWaitOptions options, Sleeper sleeper) {
    long deadline = System.nanoTime() + options.getTimeout().toNanos();
    return loop(get, transferId, options, sleeper, deadline, options.getInterval().toMillis(), null);
  }

  private static CompletableFuture<Transfer> loop(Function<String, CompletableFuture<Transfer>> get, String id, TransferWaitOptions options, Sleeper sleeper, long deadline, long interval, @Nullable String last) {
    return get.apply(id)
        .thenCompose(
            transfer -> {
              String status = transfer.getStatus();
              if (!Objects.equals(status, last) && options.getOnUpdate() != null) options.getOnUpdate().accept(transfer);
              if (options.getUntil().test(transfer)) return CompletableFuture.completedFuture(transfer);
              long left = (deadline - System.nanoTime()) / 1_000_000;
              if (left <= 0) throw new TransferWaitTimeoutException(transfer);
              return sleeper.sleep(Duration.ofMillis(Math.min(interval, left))).thenCompose(v -> loop(get, id, options, sleeper, deadline, Math.min(interval * 2, MAX_INTERVAL_MS), status));
            });
  }
}
