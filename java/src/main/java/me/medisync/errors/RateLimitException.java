package me.medisync.errors;

import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/** Too many requests, or a spend cap: 429. */
public class RateLimitException extends ApiException {
  private static final long serialVersionUID = 1L;

  /** Made by {@link ApiErrors#fromResponse}. */
  public RateLimitException(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback) {
    super(status, error, headers, requestId, fallback);
  }

  /** Seconds to wait, from the <code>retry-after</code> header or the body. */
  public @Nullable Double getRetryAfter() {
    String header = getHeaders().firstValue("retry-after").orElse(null);
    if (header != null) {
      try {
        double seconds = Double.parseDouble(header);
        if (seconds > 0) return seconds;
      } catch (NumberFormatException ignored) {
        // A date: read the body instead.
      }
    }
    ErrorBody e = getError();
    return e == null ? null : e.getRetryAfter();
  }
}
