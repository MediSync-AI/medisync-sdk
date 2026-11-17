package me.medisync.errors;

import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/** MediSync Connect: the document went to this patient and connection before. */
public class AlreadyTransferredException extends ConflictException {
  private static final long serialVersionUID = 1L;

  /** Made by {@link ApiErrors#fromResponse}. */
  public AlreadyTransferredException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(status, error, headers, requestId, fallback);
  }
}
