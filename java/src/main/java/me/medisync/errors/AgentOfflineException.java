package me.medisync.errors;

import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/** MediSync Connect: the hub of the site is offline. Reads cannot run; writes queue. */
public class AgentOfflineException extends ServiceUnavailableException {
  private static final long serialVersionUID = 1L;

  /** Made by {@link ApiErrors#fromResponse}. */
  public AgentOfflineException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(status, error, headers, requestId, fallback);
  }
}
