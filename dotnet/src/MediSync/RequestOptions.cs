using System;
using System.Collections.Generic;

namespace MediSync;

/// <summary>Options of one call. Every generated method takes them after its arguments.</summary>
public sealed class RequestOptions
{
    /// <summary>
    /// The Idempotency-Key of a POST that takes one. The client makes a UUID when you give none,
    /// and sends the same key with every retry of the call. Give your own to retry a call yourself.
    /// </summary>
    public string? IdempotencyKey { get; set; }

    /// <summary>Per attempt. Default: the client's timeout.</summary>
    public TimeSpan? Timeout { get; set; }

    /// <summary>Default: the client's value.</summary>
    public int? MaxRetries { get; set; }

    /// <summary>Extra headers for this call.</summary>
    public IDictionary<string, string> Headers { get; set; } = new Dictionary<string, string>();
}
