package me.medisync.connect;

import java.time.Duration;

/** Options of <code>connect.events.poll</code>. */
public final class PollOptions {
  private final Duration interval;

  private PollOptions(Duration interval) {
    this.interval = interval;
  }

  /** A pause of 5 s after a page with no events. */
  public static PollOptions defaults() {
    return new PollOptions(Duration.ofSeconds(5));
  }

  /** The pause after a page with no events. */
  public static PollOptions interval(Duration interval) {
    return new PollOptions(interval);
  }

  /** The pause after a page with no events. */
  public Duration getInterval() {
    return interval;
  }
}
