using System.Net.Http.Headers;

namespace MediSync;

/// <summary>What the <c>OnResponse</c> callback gets after each answer, also for retries and errors.</summary>
public sealed class ResponseInfo
{
    internal ResponseInfo(string operation, int status, string? requestId, int attempt, HttpResponseHeaders headers)
    {
        Operation = operation;
        Status = status;
        RequestId = requestId;
        Attempt = attempt;
        Headers = headers;
    }

    /// <summary>The operationId.</summary>
    public string Operation { get; }

    /// <summary>HTTP status.</summary>
    public int Status { get; }

    /// <summary>Quote it to support.</summary>
    public string? RequestId { get; }

    /// <summary>0 for the first attempt.</summary>
    public int Attempt { get; }

    /// <summary>The headers of the answer.</summary>
    public HttpResponseHeaders Headers { get; }
}
