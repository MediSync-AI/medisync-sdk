package me.medisync.errors;

import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/** No valid API key or token: 401. */
public class AuthenticationException extends ApiException {
  private static final long serialVersionUID = 1L;

  /** Made by {@link ApiErrors#fromResponse}. */
  public AuthenticationException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(status, error, headers, requestId, fallback);
  }
}
