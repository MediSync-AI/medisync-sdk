package me.medisync.errors;

import org.jspecify.annotations.Nullable;

/** No answer within the timeout, after all retries. */
public class ApiTimeoutException extends ApiConnectionException {
  private static final long serialVersionUID = 1L;

  /** An exception with a message and the timeout error. */
  public ApiTimeoutException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
