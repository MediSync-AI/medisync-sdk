package me.medisync.errors;

import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/**
 * The API answered with a status outside 2xx. The SDK throws a subclass picked by
 * <code>error.code</code>, then <code>error.type</code>, then the HTTP status.
 */
public class ApiException extends MediSyncException {
  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final String type;
  private final @Nullable String param;
  private final @Nullable String requestId;
  private final transient @Nullable ErrorBody error;
  private final transient HttpHeaders headers;

  /** Made by {@link ApiErrors#fromResponse}. */
  public ApiException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(message(status, error, fallback));
    this.status = status;
    this.error = error;
    this.headers = headers;
    this.requestId = requestId;
    String c = error == null ? null : error.getCode();
    String t = error == null ? null : error.getType();
    this.code = c == null ? "unknown" : c;
    this.type = t == null ? "api_error" : t;
    this.param = error == null ? null : error.getParam();
  }

  private static String message(int status, @Nullable ErrorBody error, @Nullable String fallback) {
    String m = error == null ? null : error.getMessage();
    if (m != null) return m;
    return fallback != null ? fallback : "HTTP " + status;
  }

  /** HTTP status. */
  public int getStatus() {
    return status;
  }

  /** The exact reason, for example <code>missing_scope</code>. New codes can appear; then use the type or status. */
  public String getCode() {
    return code;
  }

  /** The class of error, for example <code>permission_error</code>. */
  public String getType() {
    return type;
  }

  /** The parameter or header the error is about, if any. */
  public @Nullable String getParam() {
    return param;
  }

  /** Quote it to support. */
  public @Nullable String getRequestId() {
    return requestId;
  }

  /** The <code>error</code> object of the body, or null when the body had none. */
  public @Nullable ErrorBody getError() {
    return error;
  }

  /** The headers of the answer. */
  public HttpHeaders getHeaders() {
    return headers;
  }

  /** True when the same request may succeed later. */
  public boolean isRetryable() {
    Boolean r = error == null ? null : error.getRetryable();
    if (r != null) return r;
    return status == 408 || status == 429 || status >= 500;
  }
}
