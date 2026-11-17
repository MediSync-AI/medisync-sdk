using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace MediSync;

/// <summary>Options of a <see cref="MediSyncClient"/>.</summary>
public sealed class MediSyncClientOptions
{
    /// <summary>A secret API key, <c>msk_live_…</c> or <c>msk_test_…</c>. Default: the MEDISYNC_API_KEY environment variable.</summary>
    public string? ApiKey { get; set; }

    /// <summary>A MediSync Connect app with a key pair: Connect calls use the app's access token instead of the API key.</summary>
    public AppCredentials? App { get; set; }

    /// <summary>Shares one app token between clients. Wins over <see cref="App"/>.</summary>
    public AppTokenSource? AppTokens { get; set; }

    /// <summary>Default: MEDISYNC_BASE_URL, else https://api.medisync.me.</summary>
    public string? BaseUrl { get; set; }

    /// <summary>The MediSync-Version header. Default: the version this SDK was generated for.</summary>
    public string? Version { get; set; }

    /// <summary>Per attempt. Default 120 seconds.</summary>
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(120);

    /// <summary>Retries after a network error, 408, 409 idempotency_key_in_use, 429 or 5xx. Default 2.</summary>
    public int MaxRetries { get; set; } = 2;

    /// <summary>Headers sent with every call.</summary>
    public IDictionary<string, string> DefaultHeaders { get; set; } = new Dictionary<string, string>();

    /// <summary>An HTTP client to use, for example with a proxy. The SDK does not dispose it.</summary>
    public HttpClient? HttpClient { get; set; }

    /// <summary>Called after every answer. Log the request id: support looks a call up by it.</summary>
    public Action<ResponseInfo>? OnResponse { get; set; }

    /// <summary>Makes Idempotency-Keys when a call gives none. Default: random UUIDs.</summary>
    public Func<string>? IdempotencyKey { get; set; }

    /// <summary>Waits between retries and polls. Tests swap it.</summary>
    public Func<TimeSpan, CancellationToken, Task>? Sleep { get; set; }

    /// <summary>Reads environment variables. Tests swap it.</summary>
    public Func<string, string?>? Environment { get; set; }
}
