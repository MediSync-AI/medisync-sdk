using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Tasks;

namespace MediSync.Tests;

/// <summary>The mock server of conformance/mock, started with bun once per run, or MEDISYNC_MOCK_URL.</summary>
public sealed class Mock
{
    public static readonly string Root = FindRoot();
    private static readonly Lazy<Mock> Instance = new(Start);
    private static readonly HttpClient Http = new();

    public string Url { get; }

    private Mock(string url) => Url = url;

    public static Mock Get() => Instance.Value;

    private static string FindRoot()
    {
        var dir = AppContext.BaseDirectory;
        while (dir is not null && !File.Exists(Path.Combine(dir, "conformance", "operations.json"))) dir = Path.GetDirectoryName(dir);
        return dir ?? throw new InvalidOperationException("The repository root was not found.");
    }

    private static Mock Start()
    {
        var url = Environment.GetEnvironmentVariable("MEDISYNC_MOCK_URL");
        if (!string.IsNullOrEmpty(url)) return new Mock(url!);
        var bun = Environment.GetEnvironmentVariable("BUN") ?? "bun";
        var info = new ProcessStartInfo(bun, $"\"{Path.Combine(Root, "conformance", "mock", "server.ts")}\" --port 0") { RedirectStandardOutput = true, UseShellExecute = false };
        var process = Process.Start(info) ?? throw new InvalidOperationException("The mock server did not start. Is bun installed?");
        var line = process.StandardOutput.ReadLine();
        if (line is null || !line.StartsWith("MOCK_URL=", StringComparison.Ordinal)) throw new InvalidOperationException($"The mock server did not start: {line}");
        AppDomain.CurrentDomain.ProcessExit += (_, _) =>
        {
            try
            {
                process.Kill();
            }
            catch (InvalidOperationException)
            {
                // Ended already.
            }
        };
        return new Mock(line.Substring("MOCK_URL=".Length).Trim());
    }

    private async Task<JsonNode> Call(string path, JsonNode? body)
    {
        HttpResponseMessage res = body is null
            ? await Http.GetAsync(Url + path)
            : await Http.PostAsync(Url + path, new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
        return JsonNode.Parse(await res.Content.ReadAsStringAsync())!;
    }

    public Task Reset(JsonNode? responses = null, JsonNode? ws = null)
        => Call("/__mock/reset", new JsonObject { ["responses"] = responses?.DeepClone() ?? new JsonArray(), ["ws"] = ws?.DeepClone() ?? new JsonObject() });

    public async Task<JsonArray> Requests() => (JsonArray)await Call("/__mock/requests", null);

    public async Task<JsonArray> Ws() => (JsonArray)await Call("/__mock/ws", null);
}
