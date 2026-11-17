package me.medisync.errors;

import java.net.http.HttpHeaders;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The practice or hospital system answered with an error, or could not be reached. */
public class TargetException extends ApiException {
  private static final long serialVersionUID = 1L;

  /** Made by {@link ApiErrors#fromResponse}. */
  public TargetException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(status, error, headers, requestId, fallback);
  }

  /** The vendor code and message of the system. */
  public @Nullable Map<String, Object> getTargetError() {
    ErrorBody e = getError();
    return e == null ? null : e.getTargetError();
  }
}
