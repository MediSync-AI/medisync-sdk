package me.medisync.errors;

import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/** <code>verb_not_granted</code>: the practice admin has not granted the verb, or the role may not release it. */
public class VerbNotGrantedException extends CapabilityException {
  private static final long serialVersionUID = 1L;

  /** Made by {@link ApiErrors#fromResponse}. */
  public VerbNotGrantedException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(status, error, headers, requestId, fallback);
  }
}
