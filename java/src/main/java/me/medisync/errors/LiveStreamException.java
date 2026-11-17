package me.medisync.errors;

import org.jspecify.annotations.Nullable;

/** A fatal stream error, a refused handshake or a close the stream cannot continue after. */
public class LiveStreamException extends MediSyncException {
  private static final long serialVersionUID = 1L;

  private final String code;
  private final @Nullable String param;
  private final @Nullable String requestId;
  private final @Nullable Integer closeCode;

  /** An exception with the code and message. */
  public LiveStreamException(String code, String message) {
    this(code, message, null, null, null);
  }

  /** An exception with every field. */
  public LiveStreamException(String code, String message, @Nullable String param, @Nullable String requestId, @Nullable Integer closeCode) {
    super(message);
    this.code = code;
    this.param = param;
    this.requestId = requestId;
    this.closeCode = closeCode;
  }

  /** The error code, for example <code>config_invalid</code>, or <code>handshake_failed</code> and <code>closed</code>. */
  public String getCode() {
    return code;
  }

  /** The config field the error is about. */
  public @Nullable String getParam() {
    return param;
  }

  /** Quote it to support. */
  public @Nullable String getRequestId() {
    return requestId;
  }

  /** The WebSocket close code, when the socket closed. */
  public @Nullable Integer getCloseCode() {
    return closeCode;
  }
}
