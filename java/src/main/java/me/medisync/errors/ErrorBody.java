package me.medisync.errors;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import me.medisync.core.JsonModel;
import org.jspecify.annotations.Nullable;

/**
 * The <code>error</code> object of an error answer. Fields beyond code, type, message and param
 * come from MediSync Connect. Other fields are in {@link #getAdditionalProperties()}.
 */
public final class ErrorBody extends JsonModel {
  @JsonProperty("type")
  private @Nullable String type;

  @JsonProperty("code")
  private @Nullable String code;

  @JsonProperty("message")
  private @Nullable String message;

  @JsonProperty("param")
  private @Nullable String param;

  @JsonProperty("request_id")
  private @Nullable String requestId;

  @JsonProperty("doc_url")
  private @Nullable String docUrl;

  @JsonProperty("verb")
  private @Nullable String verb;

  @JsonProperty("connection_id")
  private @Nullable String connectionId;

  @JsonProperty("reason")
  private @Nullable String reason;

  @JsonProperty("alternative")
  private @Nullable String alternative;

  @JsonProperty("retryable")
  private @Nullable Boolean retryable;

  @JsonProperty("retry_after")
  private @Nullable Double retryAfter;

  @JsonProperty("target_error")
  private @Nullable Map<String, Object> targetError;

  @JsonProperty("licence")
  private @Nullable Map<String, Object> licence;

  /** An empty error object. */
  public ErrorBody() {}

  /** The class of error, for example <code>permission_error</code>. */
  public @Nullable String getType() {
    return type;
  }

  /** The exact reason, for example <code>missing_scope</code>. */
  public @Nullable String getCode() {
    return code;
  }

  /** A sentence for a person. */
  public @Nullable String getMessage() {
    return message;
  }

  /** The parameter or header the error is about. */
  public @Nullable String getParam() {
    return param;
  }

  /** Quote it to support. */
  public @Nullable String getRequestId() {
    return requestId;
  }

  /** A page of the docs about this error. */
  public @Nullable String getDocUrl() {
    return docUrl;
  }

  /** MediSync Connect: the verb of the call. */
  public @Nullable String getVerb() {
    return verb;
  }

  /** MediSync Connect: the connection of the call. */
  public @Nullable String getConnectionId() {
    return connectionId;
  }

  /** MediSync Connect: why, for example <code>system_cannot</code> or <code>needs_kontext</code>. */
  public @Nullable String getReason() {
    return reason;
  }

  /** MediSync Connect: a verb that can do the job instead. */
  public @Nullable String getAlternative() {
    return alternative;
  }

  /** Whether the same request may succeed later. Null when the API did not say. */
  public @Nullable Boolean getRetryable() {
    return retryable;
  }

  /** Seconds to wait before a retry. */
  public @Nullable Double getRetryAfter() {
    return retryAfter;
  }

  /** MediSync Connect: the error of the practice or hospital system (vendor_code, message). */
  public @Nullable Map<String, Object> getTargetError() {
    return targetError;
  }

  /** MediSync Connect <code>licence_required</code>: connector and reason. */
  public @Nullable Map<String, Object> getLicence() {
    return licence;
  }
}
