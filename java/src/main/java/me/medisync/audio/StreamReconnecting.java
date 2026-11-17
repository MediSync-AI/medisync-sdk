package me.medisync.audio;

/** Sent by the SDK, not the server: the stream lost its socket and connects again. */
public final class StreamReconnecting implements StreamEvent {
  private final int attempt;
  private final double offsetSeconds;
  private final String reason;

  StreamReconnecting(int attempt, double offsetSeconds, String reason) {
    this.attempt = attempt;
    this.offsetSeconds = offsetSeconds;
    this.reason = reason;
  }

  @Override
  public String getType() {
    return "reconnecting";
  }

  /** 1 for the first attempt after a drop. */
  public int getAttempt() {
    return attempt;
  }

  /** The <code>timeline_offset_seconds</code> of the new stream: the last <code>segment.end</code>. */
  public double getOffsetSeconds() {
    return offsetSeconds;
  }

  /** The error code or close code that ended the old stream. */
  public String getReason() {
    return reason;
  }

  @Override
  public String toString() {
    return "StreamReconnecting{attempt=" + attempt + ", offsetSeconds=" + offsetSeconds + ", reason=" + reason + "}";
  }
}
