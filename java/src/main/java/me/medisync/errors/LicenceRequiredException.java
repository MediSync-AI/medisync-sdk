package me.medisync.errors;

import java.net.http.HttpHeaders;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** MediSync Connect <code>licence_required</code>: no active licence for the connector, or its seats are used up. */
public class LicenceRequiredException extends ApiException {
  private static final long serialVersionUID = 1L;

  /** Made by {@link ApiErrors#fromResponse}. */
  public LicenceRequiredException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(status, error, headers, requestId, fallback);
  }

  /** The connector and the reason. */
  public @Nullable Map<String, Object> getLicence() {
    ErrorBody e = getError();
    return e == null ? null : e.getLicence();
  }
}
