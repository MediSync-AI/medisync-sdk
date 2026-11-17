package me.medisync.errors;

import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/** Not found: 404. */
public class NotFoundException extends ApiException {
  private static final long serialVersionUID = 1L;

  /** Made by {@link ApiErrors#fromResponse}. */
  public NotFoundException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(status, error, headers, requestId, fallback);
  }
}
