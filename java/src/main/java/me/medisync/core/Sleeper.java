package me.medisync.core;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Waits between retries and polls. Tests swap it for one that only records the waits. */
@FunctionalInterface
public interface Sleeper {
  /** Completes after the duration. */
  CompletableFuture<Void> sleep(Duration duration);

  /** Waits with a delayed executor; no thread blocks. */
  Sleeper DEFAULT =
      duration -> CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(Math.max(0, duration.toMillis()), TimeUnit.MILLISECONDS));
}
