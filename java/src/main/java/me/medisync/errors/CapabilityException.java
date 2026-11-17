package me.medisync.errors;

import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/** MediSync Connect: the connection cannot run a verb, or the app may not use it. */
public class CapabilityException extends ApiException {
  private static final long serialVersionUID = 1L;

  /** Made by {@link ApiErrors#fromResponse}. */
  public CapabilityException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(status, error, headers, requestId, fallback);
  }

  /** The verb of the call. */
  public @Nullable String getVerb() {
    ErrorBody e = getError();
    return e == null ? null : e.getVerb();
  }

  /** Why, for example <code>system_cannot</code>. */
  public @Nullable String getReason() {
    ErrorBody e = getError();
    return e == null ? null : e.getReason();
  }

  /** A verb that can do the job instead, if one exists. */
  public @Nullable String getAlternative() {
    ErrorBody e = getError();
    return e == null ? null : e.getAlternative();
  }
}
