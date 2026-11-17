using System;
using System.Collections.Generic;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MediSync.Models;
using MediSync.Models.Connect;
using MediSync.Resources;

namespace MediSync;

/// <summary>Options of <c>Connect.Transfers.WaitAsync</c>.</summary>
public sealed class TransferWaitOptions
{
    /// <summary>Stop when this holds. Default: a final status, or <c>in_doubt</c> (a person must check it).</summary>
    public Func<Transfer, bool>? Until { get; set; }

    /// <summary>Give up after this long with TransferWaitTimeoutException. Default 120 s.</summary>
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(120);

    /// <summary>First pause between reads. Doubles up to 10 s. Default 1 s.</summary>
    public TimeSpan Interval { get; set; } = TimeSpan.FromSeconds(1);

    /// <summary>Called after each read whose status differs from the read before.</summary>
    public Action<Transfer>? OnUpdate { get; set; }
}

/// <summary>Options of <c>Audio.Transcriptions.WaitAsync</c>.</summary>
public sealed class TranscriptionWaitOptions
{
    /// <summary>Give up after this long with TranscriptionWaitTimeoutException. Default 600 s.</summary>
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(600);

    /// <summary>First pause between reads. Grows by half up to 10 s. Default 2 s.</summary>
    public TimeSpan Interval { get; set; } = TimeSpan.FromSeconds(2);

    /// <summary>Called after each read whose status differs from the read before.</summary>
    public Action<Transcription>? OnUpdate { get; set; }
}

/// <summary>Options of <c>Connect.Events.PollAsync</c>.</summary>
public sealed class PollOptions
{
    /// <summary>The pause after a page with no events. Default 5 s.</summary>
    public TimeSpan Interval { get; set; } = TimeSpan.FromSeconds(5);
}

/// <summary>Transfer states.</summary>
public static class Transfers
{
    /// <summary>A transfer in one of these states changes no more.</summary>
    public static readonly IReadOnlyList<string> FinalStatuses = new[] { "completed", "partially_completed", "failed", "expired" };

    /// <summary>True for completed, partially_completed, failed and expired. An unknown status is not final.</summary>
    public static bool IsFinalStatus(string? status) => status is not null && ((IList<string>)FinalStatuses).Contains(status);
}

/// <summary>Transcription states.</summary>
public static class Transcriptions
{
    /// <summary>A transcription in one of these states changes no more.</summary>
    public static readonly IReadOnlyList<string> FinalStatuses = new[] { "completed", "failed", "expired" };
}

/// <summary>The waits and the event poll behind the helper methods.</summary>
internal static class Waits
{
    /// <summary>
    /// Reads a transcription until it is completed, failed or expired. A failed transcription is
    /// answered, not thrown: check its status and error.
    /// </summary>
    public static async Task<Transcription> TranscriptionAsync(Func<string, CancellationToken, Task<Transcription>> get, string id, TranscriptionWaitOptions options, Func<TimeSpan, CancellationToken, Task> sleep, CancellationToken ct)
    {
        var deadline = DateTimeOffset.UtcNow + options.Timeout;
        var interval = options.Interval.TotalMilliseconds;
        string? last = null;
        while (true)
        {
            var t = await get(id, ct).ConfigureAwait(false);
            if (t.Status != last)
            {
                last = t.Status;
                options.OnUpdate?.Invoke(t);
            }
            if (((IList<string>)Transcriptions.FinalStatuses).Contains(t.Status ?? "")) return t;
            var left = (deadline - DateTimeOffset.UtcNow).TotalMilliseconds;
            if (left <= 0) throw new TranscriptionWaitTimeoutException(t);
            await sleep(TimeSpan.FromMilliseconds(Math.Min(interval, left)), ct).ConfigureAwait(false);
            interval = Math.Min(Math.Round(interval * 1.5), 10_000);
        }
    }

    /// <summary>
    /// Reads a transfer until the Until check holds. A transfer waits in the queue while the site's
    /// hub is offline, up to 24 hours: for long waits, follow <c>Events.PollAsync</c> instead.
    /// </summary>
    public static async Task<Transfer> TransferAsync(Func<string, CancellationToken, Task<Transfer>> get, string transferId, TransferWaitOptions options, Func<TimeSpan, CancellationToken, Task> sleep, CancellationToken ct)
    {
        var until = options.Until ?? (t => Transfers.IsFinalStatus(t.Status) || t.Status == "in_doubt");
        var deadline = DateTimeOffset.UtcNow + options.Timeout;
        var interval = options.Interval.TotalMilliseconds;
        string? last = null;
        while (true)
        {
            var t = await get(transferId, ct).ConfigureAwait(false);
            if (t.Status != last)
            {
                last = t.Status;
                options.OnUpdate?.Invoke(t);
            }
            if (until(t)) return t;
            var left = (deadline - DateTimeOffset.UtcNow).TotalMilliseconds;
            if (left <= 0) throw new TransferWaitTimeoutException(t);
            await sleep(TimeSpan.FromMilliseconds(Math.Min(interval, left)), ct).ConfigureAwait(false);
            interval = Math.Min(interval * 2, 10_000);
        }
    }

    /// <summary>
    /// Polls <c>events.list</c> and gives every page that has events. Save <c>page.NextCursor</c> after
    /// you handled a page and pass it as the cursor on the next start, so no event is lost or read
    /// twice. Cancelling the token ends the iteration without an exception.
    /// </summary>
    public static async IAsyncEnumerable<Page<Event, EventList>> PollEventsAsync(ConnectEventsService events, EventsListQuery? query, PollOptions options, Func<TimeSpan, CancellationToken, Task> sleep, [EnumeratorCancellation] CancellationToken ct)
    {
        var cursor = query?.Cursor;
        while (!ct.IsCancellationRequested)
        {
            var q = query is null ? new EventsListQuery() : JsonSerializer.Deserialize<EventsListQuery>(JsonSerializer.Serialize(query, Json.Options), Json.Options)!;
            if (cursor is not null) q.Cursor = cursor;
            Page<Event, EventList> page;
            try
            {
                page = await events.ListAsync(q, null, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                yield break;
            }
            if (page.NextCursor is not null) cursor = page.NextCursor;
            if (page.Data.Count > 0)
            {
                yield return page;
                continue;
            }
            try
            {
                await sleep(options.Interval, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                yield break;
            }
        }
    }
}

/// <summary>Who released a reviewed plan, for <see cref="Releases.BuildRelease"/>.</summary>
public sealed class ReleaseBy
{
    /// <summary>
    /// The member who read the preview and released it, their role at the site (the grant names the
    /// roles that may release a verb), their practitioner id in the target system, and the patient
    /// the person confirmed on screen.
    /// </summary>
    public ReleaseBy(string memberId, string role, string practitionerLink, PatientRef patientConfirmed)
    {
        MemberId = memberId;
        Role = role;
        PractitionerLink = practitionerLink;
        PatientConfirmed = patientConfirmed;
    }

    /// <summary>The member who released it.</summary>
    public string MemberId { get; }

    /// <summary>Their role at the site.</summary>
    public string Role { get; }

    /// <summary>Their practitioner id in the target system.</summary>
    public string PractitionerLink { get; }

    /// <summary>The patient the person confirmed on screen.</summary>
    public PatientRef PatientConfirmed { get; }

    /// <summary>The member's name.</summary>
    public string? DisplayName { get; set; }

    /// <summary>When the person released it. Default: now.</summary>
    public DateTimeOffset? ReleasedAt { get; set; }
}

/// <summary>The release of a plan a person reviewed.</summary>
public static class Releases
{
    /// <summary>
    /// The <c>release</c> of <c>transfers.create</c> for a plan a person reviewed. Takes
    /// <c>document_hash</c> and <c>plan_hash</c> from the plan, so the release names exactly what the
    /// person saw. Call it only after the person released the preview in your app. The SDK does not
    /// check that; Connect and the hub check the member, role, practitioner link, patient and hashes.
    /// </summary>
    public static ReleaseInput BuildRelease(Plan plan, ReleaseBy by)
    {
        var at = (by.ReleasedAt ?? DateTimeOffset.UtcNow).ToUniversalTime();
        // RFC 3339 with seconds and without fractions, as the API takes it.
        var releasedAt = at.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", System.Globalization.CultureInfo.InvariantCulture);
        var release = new ReleaseInput
        {
            ReleasedBy = new MemberRef { MemberId = by.MemberId, DisplayName = by.DisplayName },
            Role = by.Role,
            PractitionerLink = by.PractitionerLink,
            PatientConfirmed = by.PatientConfirmed,
            DocumentHash = plan.DocumentHash,
            ReleasedAt = releasedAt,
        };
        if (plan.PlanHash is not null) release.PlanHash = plan.PlanHash;
        return release;
    }
}
