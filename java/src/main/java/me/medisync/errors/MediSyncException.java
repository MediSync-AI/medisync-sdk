package me.medisync.errors;

import org.jspecify.annotations.Nullable;

/** Base of every exception the SDK throws. */
public class MediSyncException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** An exception with a message. */
  public MediSyncException(String message) {
    super(message);
  }

  /** An exception with a message and a cause. */
  public MediSyncException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
