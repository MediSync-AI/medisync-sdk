using System;
using System.Collections.Generic;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MediSync;

/// <summary>Base of every exception the SDK throws.</summary>
public class MediSyncException : Exception
{
    /// <summary>An exception with a message.</summary>
    public MediSyncException(string message) : base(message) { }

    /// <summary>An exception with a message and a cause.</summary>
    public MediSyncException(string message, Exception? inner) : base(message, inner) { }
}

/// <summary>
/// The <c>error</c> object of an error answer. Fields beyond code, type, message and param come
/// from MediSync Connect.
/// </summary>
public sealed class ErrorBody : JsonModel
{
    /// <summary>The class of error, for example <c>permission_error</c>.</summary>
    [JsonPropertyName("type")] public string? Type { get; set; }

    /// <summary>The exact reason, for example <c>missing_scope</c>.</summary>
    [JsonPropertyName("code")] public string? Code { get; set; }

    /// <summary>A sentence for a person.</summary>
    [JsonPropertyName("message")] public string? Message { get; set; }

    /// <summary>The parameter or header the error is about.</summary>
    [JsonPropertyName("param")] public string? Param { get; set; }

    /// <summary>Quote it to support.</summary>
    [JsonPropertyName("request_id")] public string? RequestId { get; set; }

    /// <summary>A page of the docs about this error.</summary>
    [JsonPropertyName("doc_url")] public string? DocUrl { get; set; }

    /// <summary>MediSync Connect: the verb of the call.</summary>
    [JsonPropertyName("verb")] public string? Verb { get; set; }

    /// <summary>MediSync Connect: the connection of the call.</summary>
    [JsonPropertyName("connection_id")] public string? ConnectionId { get; set; }

    /// <summary>MediSync Connect: why, for example <c>system_cannot</c> or <c>needs_kontext</c>.</summary>
    [JsonPropertyName("reason")] public string? Reason { get; set; }

    /// <summary>MediSync Connect: a verb that can do the job instead.</summary>
    [JsonPropertyName("alternative")] public string? Alternative { get; set; }

    /// <summary>Whether the same request may succeed later. Null when the API did not say.</summary>
    [JsonPropertyName("retryable")] public bool? Retryable { get; set; }

    /// <summary>Seconds to wait before a retry.</summary>
    [JsonPropertyName("retry_after")] public double? RetryAfter { get; set; }

    /// <summary>MediSync Connect: the error of the practice or hospital system (vendor_code, message).</summary>
    [JsonPropertyName("target_error")] public JsonElement? TargetError { get; set; }

    /// <summary>MediSync Connect <c>licence_required</c>: connector and reason.</summary>
    [JsonPropertyName("licence")] public JsonElement? Licence { get; set; }
}

/// <summary>
/// The API answered with a status outside 2xx. The SDK throws a subclass picked by
/// <c>error.code</c>, then <c>error.type</c>, then the HTTP status.
/// </summary>
public class ApiException : MediSyncException
{
    /// <summary>Made by the client.</summary>
    public ApiException(int status, ErrorBody? error, HttpResponseHeaders? headers, string? requestId, string? fallback)
        : base(error?.Message ?? fallback ?? $"HTTP {status}")
    {
        Status = status;
        Error = error;
        Headers = headers;
        RequestId = requestId;
        Code = error?.Code ?? "unknown";
        Type = error?.Type ?? "api_error";
        Param = error?.Param;
    }

    /// <summary>HTTP status.</summary>
    public int Status { get; }

    /// <summary>The exact reason, for example <c>missing_scope</c>. New codes can appear; then use the type or status.</summary>
    public string Code { get; }

    /// <summary>The class of error, for example <c>permission_error</c>.</summary>
    public string Type { get; }

    /// <summary>The parameter or header the error is about, if any.</summary>
    public string? Param { get; }

    /// <summary>Quote it to support.</summary>
    public string? RequestId { get; }

    /// <summary>The <c>error</c> object of the body, or null when the body had none.</summary>
    public ErrorBody? Error { get; }

    /// <summary>The headers of the answer, or null for an error the SDK made.</summary>
    public HttpResponseHeaders? Headers { get; }

    /// <summary>True when the same request may succeed later.</summary>
    public bool Retryable => Error?.Retryable ?? (Status == 408 || Status == 429 || Status >= 500);
}

#pragma warning disable CS1591 // The constructors repeat the one of ApiException.
/// <summary>The request was not valid: 400 or 422 of the platform API.</summary>
public class InvalidRequestException : ApiException { public InvalidRequestException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>No valid API key or token: 401.</summary>
public class AuthenticationException : ApiException { public AuthenticationException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>The key or token may not do this: 403.</summary>
public class PermissionDeniedException : ApiException { public PermissionDeniedException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary><c>product_not_enabled</c>: the product, for example MediSync Connect, is off for the key's project.</summary>
public class ProductNotEnabledException : PermissionDeniedException { public ProductNotEnabledException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>Not found: 404.</summary>
public class NotFoundException : ApiException { public NotFoundException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>A conflict: 409.</summary>
public class ConflictException : ApiException { public ConflictException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>MediSync Connect: the document went to this patient and connection before.</summary>
public class AlreadyTransferredException : ConflictException { public AlreadyTransferredException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>MediSync Connect: the slots or the adapter changed since the plan. Plan again.</summary>
public class PlanStaleException : ConflictException { public PlanStaleException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>MediSync Connect: the plan is older than 30 minutes. Plan again.</summary>
public class PlanExpiredException : ConflictException { public PlanExpiredException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>A body or file over the size limit: 413.</summary>
public class RequestTooLargeException : ApiException { public RequestTooLargeException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>MediSync Connect: the connection cannot run a verb, or the app may not use it.</summary>
public class CapabilityException : ApiException
{
    public CapabilityException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { }

    /// <summary>The verb of the call.</summary>
    public string? Verb => Error?.Verb;

    /// <summary>Why, for example <c>system_cannot</c>.</summary>
    public string? Reason => Error?.Reason;

    /// <summary>A verb that can do the job instead, if one exists.</summary>
    public string? Alternative => Error?.Alternative;
}

/// <summary><c>verb_unsupported</c>: the system or its protocol cannot do this verb.</summary>
public class VerbNotSupportedException : CapabilityException { public VerbNotSupportedException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary><c>verb_unverified</c>: built or planned, not proven on this system.</summary>
public class UnverifiedVerbException : CapabilityException { public UnverifiedVerbException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary><c>verb_not_granted</c>: the practice admin has not granted the verb, or the role may not release it.</summary>
public class VerbNotGrantedException : CapabilityException { public VerbNotGrantedException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>MediSync Connect <c>licence_required</c>: no active licence for the connector, or its seats are used up.</summary>
public class LicenceRequiredException : ApiException
{
    public LicenceRequiredException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { }

    /// <summary>The connector and the reason.</summary>
    public JsonElement? Licence => Error?.Licence;
}

/// <summary>Too many requests, or a spend cap: 429.</summary>
public class RateLimitException : ApiException
{
    public RateLimitException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { }

    /// <summary>Seconds to wait, from the <c>retry-after</c> header or the body.</summary>
    public double? RetryAfter
    {
        get
        {
            var delta = Headers?.RetryAfter?.Delta;
            if (delta is { } d && d > TimeSpan.Zero) return d.TotalSeconds;
            return Error?.RetryAfter;
        }
    }
}

/// <summary>An error of the API: 500.</summary>
public class InternalServerException : ApiException { public InternalServerException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>MediSync Connect: the operation is not built for this system yet.</summary>
public class OperationNotImplementedException : ApiException { public OperationNotImplementedException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>The practice or hospital system answered with an error, or could not be reached.</summary>
public class TargetException : ApiException
{
    public TargetException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { }

    /// <summary>The vendor code and message of the system.</summary>
    public JsonElement? TargetError => Error?.TargetError;
}

/// <summary>A service is busy or down. Retry after <c>retry-after</c>.</summary>
public class ServiceUnavailableException : ApiException { public ServiceUnavailableException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>MediSync Connect: the hub of the site is offline. Reads cannot run; writes queue.</summary>
public class AgentOfflineException : ServiceUnavailableException { public AgentOfflineException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }

/// <summary>The gateway waited too long for a service. Retry with the same Idempotency-Key.</summary>
public class GatewayTimeoutException : ApiException { public GatewayTimeoutException(int s, ErrorBody? e, HttpResponseHeaders? h, string? r, string? f) : base(s, e, h, r, f) { } }
#pragma warning restore CS1591

/// <summary>No answer: a network failure after all retries.</summary>
public class ApiConnectionException : MediSyncException
{
    /// <summary>An exception with a message and the network error.</summary>
    public ApiConnectionException(string message, Exception? inner) : base(message, inner) { }
}

/// <summary>No answer within the timeout, after all retries.</summary>
public class ApiTimeoutException : ApiConnectionException
{
    /// <summary>An exception with a message and the timeout error.</summary>
    public ApiTimeoutException(string message, Exception? inner) : base(message, inner) { }
}

/// <summary>The token endpoint of MediSync Connect refused: <see cref="Error"/> is the OAuth error code (RFC 6749 5.2).</summary>
public class AppAuthException : MediSyncException
{
    /// <summary>An exception with the HTTP status, the OAuth error and its description.</summary>
    public AppAuthException(int status, string error, string description) : base($"{error}: {description}")
    {
        Status = status;
        Error = error;
    }

    /// <summary>HTTP status of the token endpoint.</summary>
    public int Status { get; }

    /// <summary>The OAuth error, for example <c>invalid_client</c>.</summary>
    public string Error { get; }
}

/// <summary>A fatal stream error, a refused handshake or a close the stream cannot continue after.</summary>
public class LiveStreamException : MediSyncException
{
    /// <summary>An exception with every field.</summary>
    public LiveStreamException(string code, string message, string? param = null, string? requestId = null, int? closeCode = null) : base(message)
    {
        Code = code;
        Param = param;
        RequestId = requestId;
        CloseCode = closeCode;
    }

    /// <summary>The error code, for example <c>config_invalid</c>, or <c>handshake_failed</c> and <c>closed</c>.</summary>
    public string Code { get; }

    /// <summary>The config field the error is about.</summary>
    public string? Param { get; }

    /// <summary>Quote it to support.</summary>
    public string? RequestId { get; }

    /// <summary>The WebSocket close code, when the socket closed.</summary>
    public int? CloseCode { get; }
}

/// <summary><c>Connect.Transfers.WaitAsync</c> ran out of time. <see cref="Transfer"/> is the last state it read.</summary>
public class TransferWaitTimeoutException : MediSyncException
{
    /// <summary>An exception with the last state of the transfer.</summary>
    public TransferWaitTimeoutException(Models.Connect.Transfer transfer) : base($"Transfer {transfer.Id} is still {transfer.Status}.") => Transfer = transfer;

    /// <summary>The last state the wait read.</summary>
    public Models.Connect.Transfer Transfer { get; }
}

/// <summary><c>Audio.Transcriptions.WaitAsync</c> ran out of time. <see cref="Transcription"/> is the last state it read.</summary>
public class TranscriptionWaitTimeoutException : MediSyncException
{
    /// <summary>An exception with the last state of the transcription.</summary>
    public TranscriptionWaitTimeoutException(Models.Transcription transcription) : base($"Transcription {transcription.Id} is still {transcription.Status}.") => Transcription = transcription;

    /// <summary>The last state the wait read.</summary>
    public Models.Transcription Transcription { get; }
}

/// <summary>Turns an answer outside 2xx into the typed exception.</summary>
public static class ApiErrors
{
    private delegate ApiException Factory(int status, ErrorBody? error, HttpResponseHeaders? headers, string? requestId, string? fallback);

    private static readonly Dictionary<string, Factory> ByCode = new()
    {
        ["product_not_enabled"] = (s, e, h, r, f) => new ProductNotEnabledException(s, e, h, r, f),
        ["already_transferred"] = (s, e, h, r, f) => new AlreadyTransferredException(s, e, h, r, f),
        ["plan_stale"] = (s, e, h, r, f) => new PlanStaleException(s, e, h, r, f),
        ["plan_expired"] = (s, e, h, r, f) => new PlanExpiredException(s, e, h, r, f),
        ["verb_unsupported"] = (s, e, h, r, f) => new VerbNotSupportedException(s, e, h, r, f),
        ["verb_unverified"] = (s, e, h, r, f) => new UnverifiedVerbException(s, e, h, r, f),
        ["verb_not_granted"] = (s, e, h, r, f) => new VerbNotGrantedException(s, e, h, r, f),
        ["licence_required"] = (s, e, h, r, f) => new LicenceRequiredException(s, e, h, r, f),
        ["not_implemented"] = (s, e, h, r, f) => new OperationNotImplementedException(s, e, h, r, f),
        ["agent_offline"] = (s, e, h, r, f) => new AgentOfflineException(s, e, h, r, f),
        ["target_refused"] = (s, e, h, r, f) => new TargetException(s, e, h, r, f),
        ["connection_failed"] = (s, e, h, r, f) => new TargetException(s, e, h, r, f),
        ["payload_too_large"] = (s, e, h, r, f) => new RequestTooLargeException(s, e, h, r, f),
        // MediSync Connect codes whose type is shared with other statuses.
        ["unauthenticated"] = (s, e, h, r, f) => new AuthenticationException(s, e, h, r, f),
        ["invalid_token"] = (s, e, h, r, f) => new AuthenticationException(s, e, h, r, f),
        ["unavailable"] = (s, e, h, r, f) => new ServiceUnavailableException(s, e, h, r, f),
        ["internal"] = (s, e, h, r, f) => new InternalServerException(s, e, h, r, f),
    };

    private static readonly Dictionary<string, Factory> ByType = new()
    {
        ["invalid_request_error"] = (s, e, h, r, f) => new InvalidRequestException(s, e, h, r, f),
        ["authentication_error"] = (s, e, h, r, f) => new AuthenticationException(s, e, h, r, f),
        ["permission_error"] = (s, e, h, r, f) => new PermissionDeniedException(s, e, h, r, f),
        ["not_found_error"] = (s, e, h, r, f) => new NotFoundException(s, e, h, r, f),
        ["conflict_error"] = (s, e, h, r, f) => new ConflictException(s, e, h, r, f),
        ["request_too_large"] = (s, e, h, r, f) => new RequestTooLargeException(s, e, h, r, f),
        ["rate_limit_error"] = (s, e, h, r, f) => new RateLimitException(s, e, h, r, f),
        ["api_error"] = (s, e, h, r, f) => new InternalServerException(s, e, h, r, f),
        ["overloaded_error"] = (s, e, h, r, f) => new ServiceUnavailableException(s, e, h, r, f),
        ["timeout_error"] = (s, e, h, r, f) => new GatewayTimeoutException(s, e, h, r, f),
        ["target_error"] = (s, e, h, r, f) => new TargetException(s, e, h, r, f),
        ["capability_error"] = (s, e, h, r, f) => new CapabilityException(s, e, h, r, f),
        ["licence_error"] = (s, e, h, r, f) => new LicenceRequiredException(s, e, h, r, f),
    };

    private static Factory ByStatus(int status) => status switch
    {
        401 => (s, e, h, r, f) => new AuthenticationException(s, e, h, r, f),
        403 => (s, e, h, r, f) => new PermissionDeniedException(s, e, h, r, f),
        404 => (s, e, h, r, f) => new NotFoundException(s, e, h, r, f),
        409 => (s, e, h, r, f) => new ConflictException(s, e, h, r, f),
        413 => (s, e, h, r, f) => new RequestTooLargeException(s, e, h, r, f),
        422 => (s, e, h, r, f) => new CapabilityException(s, e, h, r, f),
        429 => (s, e, h, r, f) => new RateLimitException(s, e, h, r, f),
        501 => (s, e, h, r, f) => new OperationNotImplementedException(s, e, h, r, f),
        502 => (s, e, h, r, f) => new TargetException(s, e, h, r, f),
        503 => (s, e, h, r, f) => new ServiceUnavailableException(s, e, h, r, f),
        504 => (s, e, h, r, f) => new GatewayTimeoutException(s, e, h, r, f),
        >= 500 => (s, e, h, r, f) => new InternalServerException(s, e, h, r, f),
        _ => (s, e, h, r, f) => new InvalidRequestException(s, e, h, r, f),
    };

    /// <summary>The typed exception for an answer with this status, body text and headers.</summary>
    public static ApiException FromResponse(int status, string text, HttpResponseHeaders? headers)
    {
        JsonElement? payload = null;
        if (text.Length > 0)
        {
            try
            {
                payload = JsonDocument.Parse(text).RootElement.Clone();
            }
            catch (JsonException)
            {
                payload = null;
            }
        }
        var fallback = payload is null && text.Length > 0 ? text.Substring(0, Math.Min(200, text.Length)) : null;
        return FromJson(status, payload, headers, fallback);
    }

    /// <summary>The typed exception for a parsed error body.</summary>
    public static ApiException FromJson(int status, JsonElement? payload, HttpResponseHeaders? headers, string? fallback = null)
    {
        ErrorBody? error = null;
        if (payload is { ValueKind: JsonValueKind.Object } p && p.TryGetProperty("error", out var e) && e.ValueKind == JsonValueKind.Object
            && e.TryGetProperty("code", out var c) && c.ValueKind == JsonValueKind.String
            && e.TryGetProperty("message", out var m) && m.ValueKind == JsonValueKind.String)
        {
            error = e.Deserialize<ErrorBody>(Json.Options);
        }
        var requestId = error?.RequestId;
        if (requestId is null && payload is { ValueKind: JsonValueKind.Object } outer && outer.TryGetProperty("request_id", out var rid) && rid.ValueKind == JsonValueKind.String) requestId = rid.GetString();
        if (requestId is null && headers is not null)
        {
            if (headers.TryGetValues("request-id", out var v1)) requestId = First(v1);
            else if (headers.TryGetValues("medisync-request-id", out var v2)) requestId = First(v2);
        }
        Factory? factory = null;
        if (error?.Code is { } code) ByCode.TryGetValue(code, out factory);
        if (factory is null && error?.Type is { } type) ByType.TryGetValue(type, out factory);
        factory ??= ByStatus(status);
        return factory(status, error, headers, requestId, fallback);
    }

    private static string? First(IEnumerable<string> values)
    {
        foreach (var v in values) return v;
        return null;
    }
}
