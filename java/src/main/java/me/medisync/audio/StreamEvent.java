package me.medisync.audio;

/**
 * A message of the live stream: a server message ({@code StreamTranscript}, {@code StreamAck}, ...)
 * or {@link StreamReconnecting}, which the SDK sends. Switch on {@link #getType()}.
 */
public interface StreamEvent {
  /** The message type, for example <code>transcript</code>. */
  String getType();
}
