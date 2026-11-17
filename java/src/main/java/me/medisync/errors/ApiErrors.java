package me.medisync.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import me.medisync.core.Json;
import org.jspecify.annotations.Nullable;

/** Turns an answer outside 2xx into the typed exception. */
public final class ApiErrors {
  private ApiErrors() {}

  private interface Factory {
    ApiException make(int status, @Nullable ErrorBody error, HttpHeaders headers, @Nullable String requestId, @Nullable String fallback);
  }

  /** Codes with their own class. Codes not listed fall back to the type, then the status. */
  private static final Map<String, Factory> BY_CODE = new HashMap<>();
  private static final Map<String, Factory> BY_TYPE = new HashMap<>();

  static {
    BY_CODE.put("product_not_enabled", ProductNotEnabledException::new);
    BY_CODE.put("already_transferred", AlreadyTransferredException::new);
    BY_CODE.put("plan_stale", PlanStaleException::new);
    BY_CODE.put("plan_expired", PlanExpiredException::new);
    BY_CODE.put("verb_unsupported", NotSupportedException::new);
    BY_CODE.put("verb_unverified", UnverifiedVerbException::new);
    BY_CODE.put("verb_not_granted", VerbNotGrantedException::new);
    BY_CODE.put("licence_required", LicenceRequiredException::new);
    BY_CODE.put("not_implemented", OperationNotImplementedException::new);
    BY_CODE.put("agent_offline", AgentOfflineException::new);
    BY_CODE.put("target_refused", TargetException::new);
    BY_CODE.put("connection_failed", TargetException::new);
    BY_CODE.put("payload_too_large", RequestTooLargeException::new);
    // MediSync Connect codes whose type is shared with other statuses.
    BY_CODE.put("unauthenticated", AuthenticationException::new);
    BY_CODE.put("invalid_token", AuthenticationException::new);
    BY_CODE.put("unavailable", ServiceUnavailableException::new);
    BY_CODE.put("internal", InternalServerException::new);

    BY_TYPE.put("invalid_request_error", InvalidRequestException::new);
    BY_TYPE.put("authentication_error", AuthenticationException::new);
    BY_TYPE.put("permission_error", PermissionDeniedException::new);
    BY_TYPE.put("not_found_error", NotFoundException::new);
    BY_TYPE.put("conflict_error", ConflictException::new);
    BY_TYPE.put("request_too_large", RequestTooLargeException::new);
    BY_TYPE.put("rate_limit_error", RateLimitException::new);
    BY_TYPE.put("api_error", InternalServerException::new);
    BY_TYPE.put("overloaded_error", ServiceUnavailableException::new);
    BY_TYPE.put("timeout_error", GatewayTimeoutException::new);
    BY_TYPE.put("target_error", TargetException::new);
    BY_TYPE.put("capability_error", CapabilityException::new);
    BY_TYPE.put("licence_error", LicenceRequiredException::new);
  }

  private static Factory byStatus(int status) {
    if (status == 401) return AuthenticationException::new;
    if (status == 403) return PermissionDeniedException::new;
    if (status == 404) return NotFoundException::new;
    if (status == 409) return ConflictException::new;
    if (status == 413) return RequestTooLargeException::new;
    if (status == 422) return CapabilityException::new;
    if (status == 429) return RateLimitException::new;
    if (status == 501) return OperationNotImplementedException::new;
    if (status == 502) return TargetException::new;
    if (status == 503) return ServiceUnavailableException::new;
    if (status == 504) return GatewayTimeoutException::new;
    if (status >= 500) return InternalServerException::new;
    return InvalidRequestException::new;
  }

  /** The typed exception for an answer with this status, body and headers. */
  public static ApiException fromResponse(int status, byte[] body, HttpHeaders headers) {
    String text = new String(body, StandardCharsets.UTF_8);
    JsonNode payload = null;
    if (!text.isEmpty()) {
      try {
        payload = Json.MAPPER.readTree(text);
      } catch (Exception notJson) {
        payload = null;
      }
    }
    String fallback = payload == null && !text.isEmpty() ? text.substring(0, Math.min(200, text.length())) : null;
    return fromJson(status, payload, headers, fallback);
  }

  /** The typed exception for a parsed error body. */
  public static ApiException fromJson(int status, @Nullable JsonNode payload, HttpHeaders headers, @Nullable String fallback) {
    ErrorBody error = null;
    JsonNode e = payload == null ? null : payload.get("error");
    if (e != null && e.isObject() && e.path("code").isTextual() && e.path("message").isTextual()) {
      error = Json.MAPPER.convertValue(e, ErrorBody.class);
    }
    String requestId = error == null ? null : error.getRequestId();
    if (requestId == null && payload != null && payload.path("request_id").isTextual()) requestId = payload.get("request_id").asText();
    if (requestId == null) requestId = headers.firstValue("request-id").or(() -> headers.firstValue("medisync-request-id")).orElse(null);
    Factory factory = null;
    if (error != null) {
      factory = BY_CODE.get(error.getCode());
      if (factory == null && error.getType() != null) factory = BY_TYPE.get(error.getType());
    }
    if (factory == null) factory = byStatus(status);
    return factory.make(status, error, headers, requestId, fallback);
  }
}
