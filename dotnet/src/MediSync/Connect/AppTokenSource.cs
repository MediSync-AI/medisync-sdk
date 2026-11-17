using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace MediSync;

/// <summary>The answer of the token endpoint.</summary>
public sealed class TokenAnswer : JsonModel
{
    /// <summary>The access token. Do not log it.</summary>
    [JsonPropertyName("access_token")] public string? AccessToken { get; set; }

    /// <summary><c>Bearer</c>.</summary>
    [JsonPropertyName("token_type")] public string? TokenType { get; set; }

    /// <summary>Seconds until the token expires.</summary>
    [JsonPropertyName("expires_in")] public double? ExpiresIn { get; set; }

    /// <summary>The granted scopes.</summary>
    [JsonPropertyName("scope")] public string? Scope { get; set; }

    /// <summary>The installation of the token, or null for an app token.</summary>
    [JsonPropertyName("installation_id")] public string? InstallationId { get; set; }

    /// <summary>The verbs of the installation.</summary>
    [JsonPropertyName("authorization_details")] public JsonElement? AuthorizationDetails { get; set; }
}

/// <summary>
/// Gets and keeps the access token of a MediSync Connect app. <see cref="GetAsync"/> answers the
/// current token and fetches a new one when it expires within a minute; calls at the same moment
/// share one request. <see cref="Clear"/> drops the token, so the next call fetches one (after a
/// 401 <c>invalid_token</c>).
/// </summary>
public sealed class AppTokenSource
{
    /// <summary>The authorization server of MediSync Connect.</summary>
    public const string DefaultIssuer = "https://connect.medisync.me";

    private const string AssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    /// <summary>A new token this long before the old one expires.</summary>
    private const double RefreshMarginMs = 60_000;
    private static readonly HttpClient SharedHttp = new() { Timeout = TimeSpan.FromSeconds(30) };

    private readonly AppCredentials _credentials;
    private readonly HttpClient _http;
    private readonly Func<double> _now;
    private readonly object _lock = new();
    private string? _token;
    private double _expiresAt;
    private TokenAnswer? _answer;
    private Task<TokenAnswer>? _pending;

    /// <summary>A source that uses this HTTP client, or one of the SDK.</summary>
    public AppTokenSource(AppCredentials credentials, HttpClient? http = null) : this(credentials, http, () => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()) { }

    /// <summary>A source with a clock in milliseconds, for tests.</summary>
    public AppTokenSource(AppCredentials credentials, HttpClient? http, Func<double> nowMilliseconds)
    {
        _credentials = credentials;
        _http = http ?? SharedHttp;
        _now = nowMilliseconds;
    }

    /// <summary>The authorization server.</summary>
    public string Issuer => _credentials.Issuer.TrimEnd('/');

    /// <summary>The last token answer: installation id and authorization details. Null before the first call.</summary>
    public TokenAnswer? Answer
    {
        get
        {
            lock (_lock) return _token is null ? null : _answer;
        }
    }

    /// <summary>The current token, or a new one.</summary>
    public async Task<string> GetAsync(CancellationToken cancellationToken = default)
    {
        Task<TokenAnswer> pending;
        lock (_lock)
        {
            if (_token is not null && _expiresAt - RefreshMarginMs > _now()) return _token;
            pending = _pending ??= FetchAsync();
        }
        try
        {
            var answer = await pending.ConfigureAwait(false);
            return answer.AccessToken!;
        }
        finally
        {
            lock (_lock)
            {
                if (_pending == pending) _pending = null;
            }
        }
    }

    /// <summary>Drops the token.</summary>
    public void Clear()
    {
        lock (_lock) _token = null;
    }

    /// <summary>Ends the current token at Connect (RFC 7009), for example when the app signs out.</summary>
    public async Task RevokeAsync(CancellationToken cancellationToken = default)
    {
        string? current;
        lock (_lock)
        {
            current = _token;
            _token = null;
        }
        if (current is null) return;
        await PostAsync("/oauth/revoke", new Dictionary<string, string> { ["token"] = current }, cancellationToken).ConfigureAwait(false);
    }

    private async Task<TokenAnswer> FetchAsync()
    {
        {
            var c = _credentials;
            var fields = new Dictionary<string, string> { ["grant_type"] = "client_credentials" };
            if (c.Scopes.Count > 0) fields["scope"] = string.Join(" ", c.Scopes);
            if (c.InstallationId is not null)
            {
                var detail = new Dictionary<string, object> { ["type"] = "medisync_connect", ["installation_id"] = c.InstallationId };
                if (c.Verbs.Count > 0) detail["verbs"] = c.Verbs.Select(v => new Dictionary<string, object> { ["verb"] = v, ["roles"] = new string[0] }).ToList();
                fields["authorization_details"] = JsonSerializer.Serialize(new[] { detail });
            }
            else if (c.Environment is not null)
            {
                fields["environment"] = c.Environment;
            }
            var json = await PostAsync("/oauth/token", fields, CancellationToken.None).ConfigureAwait(false);
            var answer = json.Deserialize<TokenAnswer>(Json.Options);
            if (answer?.AccessToken is null || answer.ExpiresIn is null) throw new AppAuthException(502, "invalid_response", "The token endpoint answered without a token.");
            lock (_lock)
            {
                _token = answer.AccessToken;
                _expiresAt = _now() + answer.ExpiresIn.Value * 1000;
                _answer = answer;
            }
            return answer;
        }
    }

    private async Task<JsonElement> PostAsync(string path, Dictionary<string, string> fields, CancellationToken ct)
    {
        var url = Issuer + path;
        var all = new Dictionary<string, string>(fields)
        {
            ["client_assertion_type"] = AssertionType,
            ["client_assertion"] = ClientAssertion.Create(_credentials, url),
        };
        var body = string.Join("&", all.Select(kv => Uri.EscapeDataString(kv.Key) + "=" + Uri.EscapeDataString(kv.Value)));
        HttpResponseMessage res;
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, url) { Content = new StringContent(body, Encoding.UTF8, "application/x-www-form-urlencoded") };
            request.Headers.TryAddWithoutValidation("accept", "application/json");
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(30));
            res = await _http.SendAsync(request, cts.Token).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HttpRequestException || e is OperationCanceledException && !ct.IsCancellationRequested)
        {
            throw new ApiConnectionException($"POST {path} failed", e);
        }
        using (res)
        {
            var text = await res.Content.ReadAsStringAsync().ConfigureAwait(false);
            JsonElement? json = null;
            try
            {
                json = text.Length == 0 ? JsonDocument.Parse("{}").RootElement.Clone() : JsonDocument.Parse(text).RootElement.Clone();
            }
            catch (JsonException)
            {
                json = null;
            }
            var status = (int)res.StatusCode;
            if (status < 200 || status >= 300)
            {
                var error = json is { ValueKind: JsonValueKind.Object } j && j.TryGetProperty("error", out var e) && e.ValueKind == JsonValueKind.String ? e.GetString()! : $"http_{status}";
                var description = json is { ValueKind: JsonValueKind.Object } k && k.TryGetProperty("error_description", out var d) && d.ValueKind == JsonValueKind.String ? d.GetString()! : text.Substring(0, Math.Min(200, text.Length));
                throw new AppAuthException(status, error, description);
            }
            return json ?? JsonDocument.Parse("{}").RootElement.Clone();
        }
    }
}
