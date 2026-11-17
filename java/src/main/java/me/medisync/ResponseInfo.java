package me.medisync;

import java.net.http.HttpHeaders;
import org.jspecify.annotations.Nullable;

/** What the <code>onResponse</code> callback gets after each answer, also for retries and errors. */
public final class ResponseInfo {
  private final String operation;
  private final int status;
  private final @Nullable String requestId;
  private final int attempt;
  private final HttpHeaders headers;

  /** Made by the client. */
  public ResponseInfo(String operation, int status, @Nullable String requestId, int attempt, HttpHeaders headers) {
    this.operation = operation;
    this.status = status;
    this.requestId = requestId;
    this.attempt = attempt;
    this.headers = headers;
  }

  /** The operationId. */
  public String getOperation() {
    return operation;
  }

  /** HTTP status. */
  public int getStatus() {
    return status;
  }

  /** Quote it to support. */
  public @Nullable String getRequestId() {
    return requestId;
  }

  /** 0 for the first attempt. */
  public int getAttempt() {
    return attempt;
  }

  /** The headers of the answer. */
  public HttpHeaders getHeaders() {
    return headers;
  }
}
