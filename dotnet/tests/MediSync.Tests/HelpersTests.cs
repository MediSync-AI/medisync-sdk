using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using MediSync.Models;
using MediSync.Models.Connect;
using Xunit;

namespace MediSync.Tests;

public class HelpersTests
{
    private static Func<TimeSpan, CancellationToken, Task> Recording(List<double> sleeps) => (d, ct) =>
    {
        sleeps.Add(d.TotalMilliseconds);
        return Task.CompletedTask;
    };

    [Fact]
    public async Task TransfersWaitStopsAtAFinalStatusOrInDoubtAndDoublesThePause()
    {
        var states = new Queue<string>(new[] { "queued", "delivered", "delivered", "in_doubt" });
        var sleeps = new List<double>();
        var updates = new List<string?>();
        var t = await Waits.TransferAsync((id, ct) => Task.FromResult(new Transfer { Id = id, Status = states.Dequeue() }), "trf_1", new TransferWaitOptions { OnUpdate = x => updates.Add(x.Status) }, Recording(sleeps), default);
        Assert.Equal("in_doubt", t.Status);
        Assert.Equal(new[] { 1000.0, 2000.0, 4000.0 }, sleeps);
        Assert.Equal(new[] { "queued", "delivered", "in_doubt" }, updates);
    }

    [Fact]
    public async Task TransfersWaitTimesOutWithTheLastState()
    {
        var e = await Assert.ThrowsAsync<TransferWaitTimeoutException>(() => Waits.TransferAsync((id, ct) => Task.FromResult(new Transfer { Id = id, Status = "queued" }), "trf_1", new TransferWaitOptions { Timeout = TimeSpan.Zero }, Recording(new List<double>()), default));
        Assert.Equal("queued", e.Transfer.Status);
        Assert.True(Transfers.IsFinalStatus("partially_completed"));
        Assert.False(Transfers.IsFinalStatus("in_doubt"));
    }

    [Fact]
    public async Task TranscriptionsWaitGrowsThePauseByHalf()
    {
        var states = new Queue<string>(new[] { "queued", "processing", "processing", "completed" });
        var sleeps = new List<double>();
        var t = await Waits.TranscriptionAsync((id, ct) => Task.FromResult(new Transcription { Id = id, Status = states.Dequeue() }), "tr_1", new TranscriptionWaitOptions(), Recording(sleeps), default);
        Assert.Equal("completed", t.Status);
        Assert.Equal(new[] { 2000.0, 3000.0, 4500.0 }, sleeps);
    }

    [Fact]
    public async Task CapabilitiesAnswerFromOneLoad()
    {
        var loads = 0;
        const string json = "{\"connection_id\":\"con_1\",\"verbs\":{"
            + "\"record.write\":{\"status\":\"supported\"},"
            + "\"diagnosis.write\":{\"status\":\"partial\",\"limits\":[\"no_side\"]},"
            + "\"medication.order\":{\"status\":\"unverified\",\"reason\":\"not_tested\"},"
            + "\"document.attach\":{\"status\":\"supported\",\"granted\":false},"
            + "\"billing.read\":{\"status\":\"supported\",\"licensed\":false},"
            + "\"patient.search\":{\"status\":\"future_status\"}}}";
        var c = new Capabilities("con_1", ct =>
        {
            loads++;
            return Task.FromResult(JsonSerializer.Deserialize<CapabilitySet>(json, Json.Options)!);
        });
        Assert.True(await c.SupportsAsync("record.write"));
        Assert.True(await c.SupportsAsync("diagnosis.write"));
        Assert.False(await c.SupportsAsync("medication.order"));
        Assert.True(await c.SupportsAsync("medication.order", allowUnverified: true));
        Assert.False(await c.SupportsAsync("document.attach"));
        Assert.False(await c.SupportsAsync("patient.search"));
        Assert.False(await c.SupportsAsync("appointment.book"));
        Assert.Equal(new[] { "diagnosis.write", "record.write" }, await c.CallableAsync());
        Assert.Equal(new[] { "no_side" }, await c.LimitsAsync("diagnosis.write"));
        Assert.Empty(await c.LimitsAsync("record.write"));
        var ns = await Assert.ThrowsAsync<VerbNotSupportedException>(() => c.RequireAsync("appointment.book"));
        Assert.Equal(422, ns.Status);
        Assert.Equal("verb_unsupported", ns.Code);
        Assert.Equal("local", ns.RequestId);
        await Assert.ThrowsAsync<UnverifiedVerbException>(() => c.RequireAsync("medication.order"));
        await c.RequireAsync("medication.order", allowUnverified: true);
        await Assert.ThrowsAsync<VerbNotGrantedException>(() => c.RequireAsync("document.attach"));
        await Assert.ThrowsAsync<LicenceRequiredException>(() => c.RequireAsync("billing.read"));
        Assert.Equal("capability_status_unknown", (await Assert.ThrowsAsync<CapabilityException>(() => c.RequireAsync("patient.search"))).Code);
        Assert.Equal(1, loads);
        await c.RefreshAsync();
        Assert.Equal(2, loads);
    }

    [Fact]
    public void BuildReleaseTakesTheHashesOfThePlan()
    {
        var plan = new Plan { Id = "pln_1", DocumentHash = "dh", PlanHash = "ph" };
        var patient = new PatientRef { Id = "10482", Family = "Beispiel", Given = "Erika" };
        var r = Releases.BuildRelease(plan, new ReleaseBy("mem_1", "doctor", "ARZT-03", patient) { DisplayName = "Dr. Lea Brandt", ReleasedAt = DateTimeOffset.Parse("2026-11-16T10:00:00.123+01:00") });
        Assert.True(JsonNode.DeepEquals(
            JsonNode.Parse("{\"released_by\":{\"member_id\":\"mem_1\",\"display_name\":\"Dr. Lea Brandt\"},\"role\":\"doctor\",\"practitioner_link\":\"ARZT-03\",\"patient_confirmed\":{\"id\":\"10482\",\"family\":\"Beispiel\",\"given\":\"Erika\"},\"document_hash\":\"dh\",\"released_at\":\"2026-11-16T09:00:00Z\",\"plan_hash\":\"ph\"}"),
            JsonNode.Parse(r.ToJson())), r.ToJson());
    }

    [Fact]
    public async Task EventsPollGivesPagesWithEventsAndKeepsTheCursor()
    {
        var mock = Mock.Get();
        await mock.Reset(JsonNode.Parse(
            "[{\"status\":200,\"body\":{\"data\":[{\"id\":\"evt_1\",\"type\":\"entry.filed\"}],\"next_cursor\":\"c1\"}},"
            + "{\"status\":200,\"body\":{\"data\":[],\"next_cursor\":\"c1\"}},"
            + "{\"status\":200,\"body\":{\"data\":[{\"id\":\"evt_2\",\"type\":\"entry.filed\"}],\"next_cursor\":\"c2\"}}]"));
        var client = new MediSyncClient(new MediSyncClientOptions { ApiKey = "msk_test_unit", BaseUrl = mock.Url });
        var ids = new List<string>();
        using var cts = new CancellationTokenSource();
        await foreach (var page in client.Connect.Events.PollAsync(new EventsListQuery { SiteId = "ste_1" }, new PollOptions { Interval = TimeSpan.FromMilliseconds(10) }, cts.Token))
        {
            ids.AddRange(page.Data.Select(e => e.Id));
            if (ids.Count == 2) cts.Cancel();
        }
        Assert.Equal(new[] { "evt_1", "evt_2" }, ids);
        var cursors = (await mock.Requests()).Select(r => r!["query"]!["cursor"]?.GetValue<string>() ?? "-").ToList();
        Assert.Equal(new[] { "-", "c1", "c1" }, cursors);
    }

    [Fact]
    public async Task ListsAwaitTheFirstPageAndIterateAll()
    {
        var mock = Mock.Get();
        await mock.Reset(JsonNode.Parse(
            "[{\"status\":200,\"body\":{\"data\":[{\"id\":\"site_a\"},{\"id\":\"site_b\"}],\"next_cursor\":\"cur_2\"}},"
            + "{\"status\":200,\"body\":{\"data\":[{\"id\":\"site_c\"}],\"next_cursor\":null}},"
            + "{\"status\":200,\"body\":{\"data\":[{\"id\":\"site_a\"}],\"next_cursor\":null}}]"));
        var client = new MediSyncClient(new MediSyncClientOptions { ApiKey = "msk_test_unit", BaseUrl = mock.Url });
        var all = new List<string>();
        await foreach (var site in client.Connect.Sites.ListAsync(new SitesListQuery { Limit = 2 })) all.Add(site.Id);
        Assert.Equal(new[] { "site_a", "site_b", "site_c" }, all);
        var page = await client.Connect.Sites.ListAsync();
        Assert.Equal("site_a", page.Data[0].Id);
        Assert.False(page.HasNextPage);
    }
}
