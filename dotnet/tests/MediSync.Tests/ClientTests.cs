using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using MediSync.Models.Connect;
using Xunit;

namespace MediSync.Tests;

public class ClientTests
{
    [Fact]
    public void NeedsAKeyFromTheArgumentOrTheEnvironment()
    {
        var e = Assert.Throws<MediSyncException>(() => new MediSyncClient(new MediSyncClientOptions { Environment = _ => null }));
        Assert.Contains("MEDISYNC_API_KEY", e.Message);
        var fromEnv = new MediSyncClient(new MediSyncClientOptions { Environment = k => k == "MEDISYNC_API_KEY" ? "msk_test_env" : k == "MEDISYNC_BASE_URL" ? "http://127.0.0.1:1/" : null });
        Assert.Equal("http://127.0.0.1:1", fromEnv.BaseUrl);
        Assert.Equal("https://api.medisync.me", new MediSyncClient("msk_test_x").BaseUrl);
    }

    [Fact]
    public void ModelsSendExplicitNullsAndKeepUnknownFields()
    {
        var update = new AppUpdate { Description = null, HomepageUrl = "https://example.com" };
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse("{\"homepage_url\":\"https://example.com\",\"description\":null}"), JsonNode.Parse(update.ToJson())), update.ToJson());
        update.Description = "Praxissoftware";
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse("{\"description\":\"Praxissoftware\",\"homepage_url\":\"https://example.com\"}"), JsonNode.Parse(update.ToJson())), update.ToJson());
        var site = JsonSerializer.Deserialize<Site>("{\"id\":\"site_1\",\"name\":\"Praxis Beispiel\",\"future_field\":{\"a\":1}}", Json.Options)!;
        Assert.Equal("site_1", site.Id);
        Assert.Equal(1, site.AdditionalProperties["future_field"].GetProperty("a").GetInt32());
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse("{\"id\":\"site_1\",\"name\":\"Praxis Beispiel\",\"future_field\":{\"a\":1}}"), JsonNode.Parse(site.ToJson())), site.ToJson());
        Assert.StartsWith("Site{", site.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public async Task CallOptionsCallbackAndErrorFields()
    {
        var handler = new FakeHandler()
            .Json(200, "{\"id\":\"site_1\"}", r => r.Headers.Add("request-id", "req_hdr_1"))
            .Json(404, "{\"error\":{\"type\":\"not_found_error\",\"code\":\"not_found\",\"message\":\"No site.\",\"request_id\":\"req_body_2\"}}");
        var seen = new List<ResponseInfo>();
        var client = new MediSyncClient(new MediSyncClientOptions
        {
            ApiKey = "msk_test_unit",
            BaseUrl = "https://api.example.test",
            HttpClient = new HttpClient(handler),
            DefaultHeaders = new Dictionary<string, string> { ["X-Partner"] = "praxis-app" },
            OnResponse = seen.Add,
        });
        var site = await client.Connect.Sites.CreateAsync(new SiteCreate { Name = "Praxis Beispiel", Kind = "practice" }, new RequestOptions { IdempotencyKey = "site-key-1", Headers = new Dictionary<string, string> { ["X-Trace"] = "t1" } });
        Assert.Equal("site_1", site.Id);
        var first = handler.Seen[0];
        Assert.Equal("site-key-1", string.Join(",", first.Request.Headers.GetValues("idempotency-key")));
        Assert.Equal("praxis-app", string.Join(",", first.Request.Headers.GetValues("x-partner")));
        Assert.Equal("t1", string.Join(",", first.Request.Headers.GetValues("x-trace")));
        Assert.Equal("medisync-dotnet/" + SdkVersion.Value, first.Request.Headers.UserAgent.ToString());
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse("{\"name\":\"Praxis Beispiel\",\"kind\":\"practice\"}"), JsonNode.Parse(first.Body)), first.Body);
        var nf = await Assert.ThrowsAsync<NotFoundException>(() => client.Connect.Sites.GetAsync("site_x"));
        Assert.Equal("req_body_2", nf.RequestId);
        Assert.Equal("No site.", nf.Message);
        Assert.Equal(2, seen.Count);
        Assert.Equal("req_hdr_1", seen[0].RequestId);
        Assert.Equal("sites.create", seen[0].Operation);
        Assert.Equal(404, seen[1].Status);
    }

    [Fact]
    public async Task ATimeoutIsRetriedThenThrown()
    {
        var handler = new FakeHandler()
            .Then(async _ => { await Task.Delay(2000); return new HttpResponseMessage(HttpStatusCode.OK); })
            .Then(async _ => { await Task.Delay(2000); return new HttpResponseMessage(HttpStatusCode.OK); });
        var sleeps = new List<double>();
        var client = new MediSyncClient(new MediSyncClientOptions
        {
            ApiKey = "msk_test_unit",
            BaseUrl = "https://api.example.test",
            HttpClient = new HttpClient(handler),
            Timeout = TimeSpan.FromMilliseconds(200),
            MaxRetries = 1,
            Sleep = (d, ct) => { sleeps.Add(d.TotalMilliseconds); return Task.CompletedTask; },
        });
        var e = await Assert.ThrowsAsync<ApiTimeoutException>(() => client.Models.ListAsync());
        Assert.Contains("timed out after 200 ms", e.Message);
        Assert.Single(sleeps);
    }

    [Fact]
    public async Task ARefusedConnectionIsAnApiConnectionException()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        var client = new MediSyncClient(new MediSyncClientOptions { ApiKey = "msk_test_unit", BaseUrl = $"http://127.0.0.1:{port}", MaxRetries = 0 });
        var e = await Assert.ThrowsAsync<ApiConnectionException>(() => client.Models.ListAsync());
        Assert.StartsWith("GET /v1/models failed", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ACancelledCallThrowsOperationCanceled()
    {
        var handler = new FakeHandler().Then(async _ => { await Task.Delay(5000); return new HttpResponseMessage(HttpStatusCode.OK); });
        var client = new MediSyncClient(new MediSyncClientOptions { ApiKey = "msk_test_unit", BaseUrl = "https://api.example.test", HttpClient = new HttpClient(handler) });
        using var cts = new CancellationTokenSource(100);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => client.Models.ListAsync(null, cts.Token));
    }
}
