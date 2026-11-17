package me.medisync.errors;

import org.jspecify.annotations.Nullable;

/** No answer: a network failure after all retries. */
public class ApiConnectionException extends MediSyncException {
  private static final long serialVersionUID = 1L;

  /** An exception with a message and the network error. */
  public ApiConnectionException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
