using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MediSync.Resources;

namespace MediSync.Core;

/// <summary>
/// The HTTP core of the client: auth, headers, retries, idempotency keys, errors and pages. The
/// generated resource classes call it with the operation's meta.
/// </summary>
internal sealed class ClientCore
{
    public const string DefaultBaseUrl = "https://api.medisync.me";
    private const double MaxRetryWaitMs = 60_000;
    private static readonly HashSet<string> Safe = new() { "GET", "HEAD", "OPTIONS" };
    private static readonly HttpClient SharedHttp = new() { Timeout = System.Threading.Timeout.InfiniteTimeSpan };
    [ThreadStatic] private static Random? _random;

    private readonly string? _apiKey;
    private readonly AppTokenSource? _tokens;
    private readonly TimeSpan _timeout;
    private readonly int _maxRetries;
    private readonly Dictionary<string, string> _defaultHeaders;
    private readonly Action<ResponseInfo>? _onResponse;
    private readonly Func<string> _newKey;

    public ClientCore(MediSyncClientOptions o)
    {
        var env = o.Environment ?? System.Environment.GetEnvironmentVariable;
        var key = o.ApiKey ?? env("MEDISYNC_API_KEY");
        _apiKey = string.IsNullOrEmpty(key) ? null : key;
        Http = o.HttpClient ?? SharedHttp;
        _tokens = o.AppTokens ?? (o.App is not null ? new AppTokenSource(o.App, Http) : null);
        if (_apiKey is null && _tokens is null) throw new MediSyncException("No API key. Pass ApiKey, or set the MEDISYNC_API_KEY environment variable.");
        var baseUrl = o.BaseUrl ?? env("MEDISYNC_BASE_URL");
        BaseUrl = (string.IsNullOrEmpty(baseUrl) ? DefaultBaseUrl : baseUrl!).TrimEnd('/');
        Version = o.Version ?? Operations.ApiVersion;
        _timeout = o.Timeout;
        _maxRetries = o.MaxRetries;
        _defaultHeaders = o.DefaultHeaders.ToDictionary(kv => kv.Key.ToLowerInvariant(), kv => kv.Value);
        _onResponse = o.OnResponse;
        _newKey = o.IdempotencyKey ?? (() => Guid.NewGuid().ToString());
        Sleep = o.Sleep ?? ((d, ct) => Task.Delay(d, ct));
    }

    public string BaseUrl { get; }

    public string Version { get; }

    public HttpClient Http { get; }

    public Func<TimeSpan, CancellationToken, Task> Sleep { get; }

    public TimeSpan Timeout => _timeout;

    /// <summary>The bearer secret of an operation of the API.</summary>
    public async Task<string> AuthorizationAsync(string api, CancellationToken ct)
    {
        if (api == "connect" && _tokens is not null) return "Bearer " + await _tokens.GetAsync(ct).ConfigureAwait(false);
        if (_apiKey is null) throw new MediSyncException("This call needs an API key: the app credentials work for MediSync Connect only.");
        return "Bearer " + _apiKey;
    }

    /// <summary>Headers of every call: version, user agent and the default headers.</summary>
    public Dictionary<string, string> BaseHeaders()
    {
        var h = new Dictionary<string, string>
        {
            ["medisync-version"] = Version,
            ["user-agent"] = "medisync-dotnet/" + SdkVersion.Value,
        };
        foreach (var kv in _defaultHeaders) h[kv.Key] = kv.Value;
        return h;
    }

    /// <summary>encodeURIComponent of JavaScript.</summary>
    public static string Encode(string value) => Uri.EscapeDataString(value);

    private static Dictionary<string, JsonElement> QueryValues(object? query)
    {
        var out_ = new Dictionary<string, JsonElement>();
        if (query is null) return out_;
        var node = JsonSerializer.SerializeToElement(query, query.GetType(), Json.Options);
        if (node.ValueKind != JsonValueKind.Object) throw new MediSyncException("The query must be an object.");
        foreach (var p in node.EnumerateObject()) if (p.Value.ValueKind != JsonValueKind.Null) out_[p.Name] = p.Value.Clone();
        return out_;
    }

    private static string Text(JsonElement v) => v.ValueKind switch
    {
        JsonValueKind.String => v.GetString() ?? "",
        JsonValueKind.True => "true",
        JsonValueKind.False => "false",
        _ => v.GetRawText(),
    };

    /// <summary>The URL of an operation with its path and query parameters.</summary>
    internal Uri Url(OperationMeta op, RequestArgs args, Dictionary<string, JsonElement> query)
    {
        var path = new StringBuilder();
        var template = op.Path;
        var i = 0;
        while (i < template.Length)
        {
            var open = template.IndexOf('{', i);
            if (open < 0)
            {
                path.Append(template, i, template.Length - i);
                break;
            }
            var close = template.IndexOf('}', open);
            path.Append(template, i, open - i);
            var name = template.Substring(open + 1, close - open - 1);
            if (!args.PathParams.TryGetValue(name, out var value) || string.IsNullOrEmpty(value)) throw new MediSyncException($"Missing the path parameter {name}.");
            path.Append(Encode(value));
            i = close + 1;
        }
        var pairs = new List<string>();
        foreach (var kv in query)
        {
            if (kv.Value.ValueKind == JsonValueKind.Array)
            {
                var items = kv.Value.EnumerateArray().Select(Text).ToList();
                if (op.CommaQuery.Contains(kv.Key)) pairs.Add(Encode(kv.Key) + "=" + Encode(string.Join(",", items)));
                else pairs.AddRange(items.Select(item => Encode(kv.Key) + "=" + Encode(item)));
            }
            else
            {
                pairs.Add(Encode(kv.Key) + "=" + Encode(Text(kv.Value)));
            }
        }
        return new Uri(BaseUrl + path + (pairs.Count == 0 ? "" : "?" + string.Join("&", pairs)));
    }

    /// <summary>Exponential backoff with jitter: about 0.5 s, 1 s, 2 s, ... up to 8 s.</summary>
    public static double BackoffMs(int attempt)
    {
        var random = _random ??= new Random(Guid.NewGuid().GetHashCode());
        var b = Math.Min(500 * Math.Pow(2, attempt), 8_000);
        return Math.Round(b * (1 - random.NextDouble() * 0.25));
    }

    /// <summary>True when an answer with this status and error may succeed on a retry.</summary>
    public static bool RetryableStatus(int status, ApiException error)
    {
        if (error.Error?.Retryable == false) return false;
        if (status == 408) return true;
        if (status == 409) return error.Code == "idempotency_key_in_use";
        // A spend cap or the daily test allowance does not reset within a retry.
        if (status == 429) return error.Code != "spend_cap_reached" && error.Code != "test_daily_limit_reached";
        return status >= 500 && status != 501;
    }

    private static double? RetryAfterMs(HttpResponseHeaders headers, ApiException error)
    {
        if (headers.TryGetValues("retry-after", out var values))
        {
            var header = values.FirstOrDefault()?.Trim();
            if (header is not null)
            {
                if (double.TryParse(header, NumberStyles.Float, CultureInfo.InvariantCulture, out var seconds) && seconds >= 0) return seconds * 1000;
                if (DateTimeOffset.TryParse(header, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var at)) return Math.Max((at - DateTimeOffset.UtcNow).TotalMilliseconds, 0);
            }
        }
        return error.Error?.RetryAfter is { } r ? r * 1000 : null;
    }

    private sealed class Body
    {
        public byte[]? Bytes;
        public string? ContentType;
    }

    /// <summary>Sends one operation and answers the response when it is 2xx, after retries. The caller disposes it.</summary>
    internal async Task<HttpResponseMessage> SendCoreAsync(OperationMeta op, RequestArgs args, RequestOptions? options, CancellationToken ct)
    {
        options ??= new RequestOptions();
        var query = args.QueryValues ?? QueryValues(args.Query);
        var url = Url(op, args, query);
        var headers = BaseHeaders();
        headers["accept"] = op.Response == "json" ? "application/json" : "*/*";
        var body = new Body();
        if (op.Body == "json")
        {
            var value = args.Body ?? new Dictionary<string, object>();
            body.Bytes = JsonSerializer.SerializeToUtf8Bytes(value, value.GetType(), Json.Options);
            body.ContentType = "application/json";
        }
        else if (op.Body == "multipart")
        {
            var form = Multipart.Of(args.Body ?? new Dictionary<string, object?>());
            body.Bytes = form.ToArray();
            body.ContentType = form.ContentType;
        }
        if (op.Idempotency != "none") headers["idempotency-key"] = options.IdempotencyKey ?? _newKey();
        foreach (var kv in options.Headers) headers[kv.Key.ToLowerInvariant()] = kv.Value;
        var canRetry = Safe.Contains(op.Method) || headers.ContainsKey("idempotency-key");
        var maxRetries = canRetry ? options.MaxRetries ?? _maxRetries : 0;
        var timeout = options.Timeout ?? _timeout;
        var tokenRetried = false;

        for (var attempt = 0; ; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            HttpResponseMessage response;
            using (var cts = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                cts.CancelAfter(timeout);
                try
                {
                    var authorization = await AuthorizationAsync(op.Api, cts.Token).ConfigureAwait(false);
                    using var request = new HttpRequestMessage(new HttpMethod(op.Method), url);
                    foreach (var kv in headers)
                    {
                        if (kv.Key == "content-type") continue;
                        request.Headers.TryAddWithoutValidation(kv.Key, kv.Value);
                    }
                    request.Headers.TryAddWithoutValidation("authorization", authorization);
                    if (body.Bytes is not null)
                    {
                        request.Content = new ByteArrayContent(body.Bytes);
                        request.Content.Headers.TryAddWithoutValidation("content-type", body.ContentType);
                    }
                    response = await Http.SendAsync(request, HttpCompletionOption.ResponseContentRead, cts.Token).ConfigureAwait(false);
                }
                catch (Exception err) when (err is not MediSyncException || err is ApiConnectionException)
                {
                    if (ct.IsCancellationRequested) throw;
                    if (attempt < maxRetries)
                    {
                        await Sleep(TimeSpan.FromMilliseconds(BackoffMs(attempt)), ct).ConfigureAwait(false);
                        continue;
                    }
                    var what = $"{op.Method} {url.AbsolutePath}";
                    if (err is OperationCanceledException) throw new ApiTimeoutException($"{what} timed out after {(long)timeout.TotalMilliseconds} ms.", err);
                    throw new ApiConnectionException($"{what} failed: {err.Message}", err);
                }
            }
            var status = (int)response.StatusCode;
            var requestId = First(response.Headers, "request-id") ?? First(response.Headers, "medisync-request-id");
            _onResponse?.Invoke(new ResponseInfo(op.OperationId, status, requestId, attempt, response.Headers));
            if (status >= 200 && status < 300) return response;

            string text;
            using (response)
            {
                text = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
            }
            var error = ApiErrors.FromResponse(status, text, response.Headers);
            if (status == 401 && _tokens is not null && op.Api == "connect" && error.Code == "invalid_token" && !tokenRetried)
            {
                // The access token ended early: get a new one and send again, once.
                _tokens.Clear();
                tokenRetried = true;
                attempt--;
                continue;
            }
            if (attempt < maxRetries && RetryableStatus(status, error))
            {
                var wait = RetryAfterMs(response.Headers, error);
                await Sleep(TimeSpan.FromMilliseconds(Math.Min(wait ?? BackoffMs(attempt), MaxRetryWaitMs)), ct).ConfigureAwait(false);
                continue;
            }
            throw error;
        }
    }

    private static string? First(HttpResponseHeaders headers, string name) => headers.TryGetValues(name, out var v) ? v.FirstOrDefault() : null;

    /// <summary>Sends one operation and answers its parsed body.</summary>
    internal async Task<T> RequestAsync<T>(OperationMeta op, RequestArgs args, RequestOptions? options, CancellationToken ct)
    {
        using var response = await SendCoreAsync(op, args, options, ct).ConfigureAwait(false);
        var bytes = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
        switch (op.Response)
        {
            case "binary":
                return (T)(object)bytes;
            case "text":
                return (T)(object)Encoding.UTF8.GetString(bytes);
            case "empty":
                return default!;
            default:
                if (bytes.Length == 0) return default!;
                try
                {
                    return JsonSerializer.Deserialize<T>(bytes, Json.Options)!;
                }
                catch (JsonException e)
                {
                    throw new MediSyncException($"The answer of {op.OperationId} is not the JSON the SDK expected: {e.Message}", e);
                }
        }
    }

    /// <summary>Sends an operation without an answer body.</summary>
    internal async Task SendAsync(OperationMeta op, RequestArgs args, RequestOptions? options, CancellationToken ct)
    {
        using var response = await SendCoreAsync(op, args, options, ct).ConfigureAwait(false);
    }

    /// <summary>A list operation: the first page, which loads the next ones.</summary>
    internal PageTask<T, TList> RequestPage<T, TList>(OperationMeta op, RequestArgs args, RequestOptions? options, CancellationToken ct)
        => new(() => LoadPageAsync<T, TList>(op, args, QueryValues(args.Query), options, ct));

    private async Task<Page<T, TList>> LoadPageAsync<T, TList>(OperationMeta op, RequestArgs args, Dictionary<string, JsonElement> query, RequestOptions? options, CancellationToken ct)
    {
        using var response = await SendCoreAsync(op, args.WithQueryValues(query), options, ct).ConfigureAwait(false);
        var bytes = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
        JsonElement tree;
        try
        {
            tree = bytes.Length == 0 ? JsonDocument.Parse("{}").RootElement : JsonDocument.Parse(bytes).RootElement;
        }
        catch (JsonException e)
        {
            throw new MediSyncException($"The answer of {op.OperationId} is not JSON: {e.Message}", e);
        }
        var raw = tree.Deserialize<TList>(Json.Options)!;
        var data = tree.ValueKind == JsonValueKind.Object && tree.TryGetProperty("data", out var d) && d.ValueKind == JsonValueKind.Array
            ? d.Deserialize<List<T>>(Json.Options) ?? new List<T>()
            : new List<T>();
        string? cursor = tree.ValueKind == JsonValueKind.Object && tree.TryGetProperty("next_cursor", out var c) && c.ValueKind == JsonValueKind.String ? c.GetString() : null;
        return new Page<T, TList>(data, cursor, raw, (next, token) =>
        {
            var q = new Dictionary<string, JsonElement>(query) { ["cursor"] = JsonSerializer.SerializeToElement(next) };
            return LoadPageAsync<T, TList>(op, args, q, options, token);
        });
    }
}
