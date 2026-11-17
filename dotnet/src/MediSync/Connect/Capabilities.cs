using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MediSync.Models.Connect;

namespace MediSync;

/// <summary>
/// What one connection supports. Loads <c>capabilities.get</c> once and answers from that copy;
/// call <see cref="RefreshAsync"/> after <c>connection.capabilities_changed</c>.
/// <code>
/// if (await client.Connect.Capabilities.ForConnection(connectionId).SupportsAsync("record.write")) { ... }
/// </code>
/// </summary>
public sealed class Capabilities
{
    private static readonly HashSet<string> KnownStatuses = new() { "supported", "partial", "unverified", "unsupported" };
    private readonly Func<CancellationToken, Task<CapabilitySet>> _fetch;
    private readonly object _lock = new();
    private Task<CapabilitySet>? _pending;

    internal Capabilities(string connectionId, Func<CancellationToken, Task<CapabilitySet>> fetch)
    {
        ConnectionId = connectionId;
        _fetch = fetch;
    }

    /// <summary>The connection.</summary>
    public string ConnectionId { get; }

    /// <summary>The capability set, loaded once.</summary>
    public async Task<CapabilitySet> LoadAsync(CancellationToken cancellationToken = default)
    {
        Task<CapabilitySet> task;
        lock (_lock) task = _pending ??= _fetch(cancellationToken);
        try
        {
            return await task.ConfigureAwait(false);
        }
        catch
        {
            lock (_lock)
            {
                if (_pending == task) _pending = null;
            }
            throw;
        }
    }

    /// <summary>Loads the set again.</summary>
    public Task<CapabilitySet> RefreshAsync(CancellationToken cancellationToken = default)
    {
        lock (_lock) _pending = null;
        return LoadAsync(cancellationToken);
    }

    /// <summary>The entry for one verb. A verb the API does not list is unsupported.</summary>
    public async Task<VerbCapability> VerbAsync(string verb, CancellationToken cancellationToken = default)
    {
        var set = await LoadAsync(cancellationToken).ConfigureAwait(false);
        return set.Verbs is not null && set.Verbs.TryGetValue(verb, out var cap) && cap is not null ? cap : new VerbCapability { Status = "unsupported" };
    }

    private static bool IsCallable(VerbCapability cap, bool allowUnverified)
    {
        var ok = cap.Status == "supported" || cap.Status == "partial" || (allowUnverified && cap.Status == "unverified");
        return ok && cap.Granted != false && cap.Licensed != false;
    }

    /// <summary>
    /// True when the verb is supported (fully or <c>partial</c>) here, granted to this app and licensed.
    /// With allowUnverified, verbs built but not proven count too. A status this SDK version does not
    /// know counts as not callable.
    /// </summary>
    public async Task<bool> SupportsAsync(string verb, bool allowUnverified = false, CancellationToken cancellationToken = default)
        => IsCallable(await VerbAsync(verb, cancellationToken).ConfigureAwait(false), allowUnverified);

    /// <summary>Every verb that SupportsAsync would answer true for, sorted.</summary>
    public async Task<IReadOnlyList<string>> CallableAsync(bool allowUnverified = false, CancellationToken cancellationToken = default)
    {
        var set = await LoadAsync(cancellationToken).ConfigureAwait(false);
        return (set.Verbs ?? new Dictionary<string, VerbCapability>()).Where(kv => kv.Value is not null && IsCallable(kv.Value, allowUnverified)).Select(kv => kv.Key).OrderBy(v => v, StringComparer.Ordinal).ToList();
    }

    /// <summary>The <c>limits</c> codes of a <c>partial</c> verb, else an empty list. Show unknown codes as text.</summary>
    public async Task<IReadOnlyList<string>> LimitsAsync(string verb, CancellationToken cancellationToken = default)
    {
        var cap = await VerbAsync(verb, cancellationToken).ConfigureAwait(false);
        return cap.Status == "partial" && cap.Limits is not null ? cap.Limits.ToList() : new List<string>();
    }

    /// <summary>Throws the exception the API would answer for this verb, so a UI can explain it before a call.</summary>
    public async Task RequireAsync(string verb, bool allowUnverified = false, CancellationToken cancellationToken = default)
    {
        var cap = await VerbAsync(verb, cancellationToken).ConfigureAwait(false);
        var status = cap.Status ?? "";
        if (status == "unsupported") throw Error(422, verb, "capability_error", "verb_unsupported", $"This connection cannot run {verb}.", cap.Reason, cap.Alternative);
        if (status == "unverified" && !allowUnverified) throw Error(422, verb, "capability_error", "verb_unverified", $"{verb} is not proven on this system.", cap.Reason, null);
        // A status added to the contract after this SDK version: not callable until the SDK knows it.
        if (!KnownStatuses.Contains(status)) throw Error(422, verb, "capability_error", "capability_status_unknown", $"{verb} has the status {status}, which this SDK version does not know.", null, null);
        if (cap.Granted == false) throw Error(403, verb, "capability_error", "verb_not_granted", $"The practice admin has not granted {verb} to this app.", null, null);
        if (cap.Licensed == false) throw Error(403, verb, "licence_error", "licence_required", "No active licence for this connector.", null, null);
    }

    private ApiException Error(int status, string verb, string type, string code, string message, string? reason, string? alternative)
    {
        var error = new Dictionary<string, object> { ["request_id"] = "local", ["verb"] = verb, ["connection_id"] = ConnectionId, ["type"] = type, ["code"] = code, ["message"] = message };
        if (reason is not null) error["reason"] = reason;
        if (alternative is not null) error["alternative"] = alternative;
        return ApiErrors.FromJson(status, JsonSerializer.SerializeToElement(new Dictionary<string, object> { ["error"] = error }), null);
    }
}
