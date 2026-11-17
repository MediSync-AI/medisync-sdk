// Install test of the NuGet package. Arguments: the mock URL and the expected version.
using System.Net.Http.Json;
using System.Text.Json;
using MediSync;

string mock = args[0];
string expected = args[1];
string runtime = $".NET {Environment.Version}";
var http = new HttpClient();

void Check(bool ok, string what)
{
    if (!ok) throw new InvalidOperationException($"{runtime}: {what}");
}

async Task<JsonElement> Control(string path, object? body = null) =>
    body is null
        ? await http.GetFromJsonAsync<JsonElement>($"{mock}/__mock/{path}")
        : await (await http.PostAsJsonAsync($"{mock}/__mock/{path}", body)).Content.ReadFromJsonAsync<JsonElement>();

Check(SdkVersion.Value == expected, $"SdkVersion.Value is {SdkVersion.Value}, expected {expected}");
var client = new MediSyncClient(new MediSyncClientOptions { ApiKey = "msk_test_install", BaseUrl = mock });

await Control("reset", new { responses = Array.Empty<object>() });
var models = await client.Models.ListAsync();
Check(models.Data is not null, "models.list gave no data");
await foreach (var system in client.Connect.Systems.ListAsync()) { Check(system.Id.Length > 0, "a system without ID"); break; }
Check(await InstallTest.Systems.CountAsync(mock) >= 0, "netstandard2.0 library call");
var log = await Control("requests");
Check(log.GetArrayLength() == 3, $"the mock saw {log.GetArrayLength()} requests, expected 3");
foreach (var r in log.EnumerateArray())
{
    Check(r.GetProperty("problems").GetArrayLength() == 0, $"{r.GetProperty("path")}: {r.GetProperty("problems")}");
    Check(r.GetProperty("headers").GetProperty("user-agent").GetString() == $"medisync-dotnet/{expected}", $"User-Agent {r.GetProperty("headers").GetProperty("user-agent")}");
}

var notFound = new { type = "error", error = new { type = "not_found_error", code = "not_found", message = "No such site.", request_id = "req_install", param = (string?)null }, request_id = "req_install" };
await Control("reset", new { responses = new[] { new { status = 404, body = notFound } } });
try
{
    await client.Connect.Sites.GetAsync("ste_missing");
    Check(false, "a 404 threw nothing");
}
catch (NotFoundException e)
{
    Check(e.Code == "not_found" && e.RequestId == "req_install", $"error code {e.Code}, request ID {e.RequestId}");
}

Console.WriteLine($"{runtime}: MediSync {SdkVersion.Value} ok");
