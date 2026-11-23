using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using MediSync.Models;
using MediSync.Resources;
using Xunit;

[assembly: CollectionBehavior(DisableTestParallelization = true)]

namespace MediSync.Tests;

/// <summary>Runs the shared conformance cases of conformance/cases against the mock server.</summary>
public class ConformanceTests
{
    private static readonly Dictionary<string, string> DotnetNames = LoadNames();

    private static Dictionary<string, string> LoadNames()
    {
        var names = JsonNode.Parse(File.ReadAllText(Path.Combine(Mock.Root, "conformance", "operations.json")))!;
        var out_ = new Dictionary<string, string>();
        foreach (var o in names["operations"]!.AsArray()) out_[o!["typescript"]!.GetValue<string>()] = o["dotnet"]!.GetValue<string>();
        return out_;
    }

    private static JsonArray Cases(string file) => JsonNode.Parse(File.ReadAllText(Path.Combine(Mock.Root, "conformance", "cases", file)))!["cases"]!.AsArray();

    public static IEnumerable<object[]> HttpCases()
    {
        foreach (var file in Directory.GetFiles(Path.Combine(Mock.Root, "conformance", "cases"), "*.json").Select(Path.GetFileName).OrderBy(f => f, StringComparer.Ordinal))
        {
            if (file == "live.json") continue;
            foreach (var c in Cases(file!)) yield return new object[] { $"{file}: {c!["name"]!.GetValue<string>()}" };
        }
    }

    public static IEnumerable<object[]> LiveCases() => Cases("live.json").Select(c => new object[] { c!["name"]!.GetValue<string>() });

    private static JsonNode Find(string label)
    {
        var parts = label.Split(new[] { ": " }, 2, StringSplitOptions.None);
        return Cases(parts[0]).First(c => c!["name"]!.GetValue<string>() == parts[1])!;
    }

    public static MediSyncClient Client(Mock mock, JsonNode? options, List<double> sleeps) => new(new MediSyncClientOptions
    {
        ApiKey = "msk_test_conformance",
        BaseUrl = mock.Url,
        MaxRetries = options?["maxRetries"]?.GetValue<int>() ?? 2,
        Version = options?["version"]?.GetValue<string>(),
        Sleep = (d, ct) =>
        {
            lock (sleeps) sleeps.Add(d.TotalMilliseconds);
            return Task.CompletedTask;
        },
    });

    private static string Pascal(string s) => string.Concat(s.Split('_').Where(p => p.Length > 0).Select(p => char.ToUpperInvariant(p[0]) + p.Substring(1)));

    /// <summary>A model of the type from JSON; {"$file": ...} values become FileInput.</summary>
    private static object? Model(JsonNode? json, Type type)
    {
        if (json is not JsonObject obj) return json is null ? null : JsonSerializer.Deserialize(json.ToJsonString(), type, Json.Options);
        var files = new Dictionary<string, FileInput>();
        var copy = (JsonObject)obj.DeepClone();
        foreach (var kv in obj)
        {
            if (kv.Value is JsonObject v && v["$file"] is JsonObject f)
            {
                files[kv.Key] = new FileInput(new byte[f["bytes"]!.GetValue<int>()], f["filename"]!.GetValue<string>());
                copy.Remove(kv.Key);
            }
        }
        var model = JsonSerializer.Deserialize(copy.ToJsonString(), type, Json.Options)!;
        foreach (var kv in files)
        {
            var prop = type.GetProperties().First(p => p.GetCustomAttribute<JsonPropertyNameAttribute>()?.Name == kv.Key);
            prop.SetValue(model, kv.Value);
        }
        return model;
    }

    private static async Task<object?> Await(object? value)
    {
        if (value is null) return null;
        var type = value.GetType();
        if (type.IsGenericType && type.GetGenericTypeDefinition().Name.StartsWith("PageTask", StringComparison.Ordinal))
        {
            value = type.GetMethod("AsTask")!.Invoke(value, null);
            type = value!.GetType();
        }
        if (value is Task task)
        {
            await task;
            var result = task.GetType().GetProperty("Result");
            if (result is null || result.PropertyType.Name == "VoidTaskResult") return null;
            var v = result.GetValue(task);
            return v?.GetType().Name == "VoidTaskResult" ? null : v;
        }
        return value;
    }

    /// <summary>Calls the method of a dotted name and waits for it.</summary>
    public static async Task<object?> Call(MediSyncClient client, JsonNode spec)
    {
        var dotted = spec["method"]!.GetValue<string>();
        var meta = Operations.All[dotted];
        var args = spec["args"] ?? new JsonObject();
        object node = client;
        var parts = DotnetNames[dotted].Split('.');
        foreach (var p in parts.Take(parts.Length - 1)) node = node.GetType().GetProperty(p)!.GetValue(node)!;
        var method = node.GetType().GetMethod(parts.Last())!;
        var list = new List<object?>();
        var pathArgs = args["path"]?.AsArray() ?? new JsonArray();
        foreach (var p in pathArgs) list.Add(p!.GetValue<string>());
        var ps = method.GetParameters();
        if (meta.Body is not null) list.Add(Model(args["body"] ?? new JsonObject(), Nullable.GetUnderlyingType(ps[list.Count].ParameterType) ?? ps[list.Count].ParameterType));
        else if (meta.Query) list.Add(args["query"] is { } q ? Model(q, ps[list.Count].ParameterType) : null);
        var options = new RequestOptions();
        if (args["options"]?["idempotencyKey"] is { } key) options.IdempotencyKey = key.GetValue<string>();
        list.Add(options);
        list.Add(CancellationToken.None);
        object? raw;
        try
        {
            raw = method.Invoke(node, list.ToArray());
        }
        catch (TargetInvocationException e) when (e.InnerException is not null)
        {
            throw e.InnerException;
        }
        var result = await Await(raw);
        if (spec["mode"]?.GetValue<string>() == "iterate")
        {
            var items = new List<object>();
            var enumerable = (IAsyncEnumerable<object>)result!.GetType().GetMethod("AutoPagingAsync")!.Invoke(result, new object[] { CancellationToken.None })!;
            await foreach (var item in enumerable) items.Add(item);
            return items;
        }
        return result;
    }

    private static bool Matches(JsonNode? expected, JsonNode? actual)
    {
        if (expected is JsonValue ev && ev.TryGetValue<string>(out var s) && s.StartsWith("~", StringComparison.Ordinal))
            return actual is JsonValue av && av.TryGetValue<string>(out var a) && Regex.IsMatch(a, s.Substring(1));
        return JsonNode.DeepEquals(expected, actual);
    }

    public static void Partial(JsonNode? expected, JsonNode? actual, string where)
    {
        if (expected is JsonObject obj)
        {
            Assert.True(actual is JsonObject, $"{where}: expected an object, got {actual?.ToJsonString()}");
            foreach (var kv in obj) Partial(kv.Value, actual![kv.Key], $"{where}.{kv.Key}");
            return;
        }
        Assert.True(Matches(expected, actual), $"{where}: expected {expected?.ToJsonString()}, got {actual?.ToJsonString()}");
    }

    private static void CheckRequest(JsonNode exp, JsonNode? req, int i)
    {
        var at = $"request {i}";
        Assert.True(req is not null, $"{at}: missing");
        if (exp["method"] is { } m) Assert.Equal(m.GetValue<string>(), req!["method"]!.GetValue<string>());
        if (exp["path"] is { } p) Assert.Equal(p.GetValue<string>(), req!["path"]!.GetValue<string>());
        if (exp["query"] is { } q) Partial(q, req!["query"], $"{at}.query");
        foreach (var k in exp["absentQuery"]?.AsArray() ?? new JsonArray()) Assert.False(req!["query"]!.AsObject().ContainsKey(k!.GetValue<string>()), $"{at}: query {k} must be absent");
        if (exp["rawQuery"] is { } rq) Assert.True(Matches(rq, req!["rawQuery"]), $"{at}: raw query {req["rawQuery"]}");
        if (exp["headers"] is { } h) Partial(h, req!["headers"], $"{at}.headers");
        foreach (var k in exp["absentHeaders"]?.AsArray() ?? new JsonArray()) Assert.False(req!["headers"]!.AsObject().ContainsKey(k!.GetValue<string>()), $"{at}: header {k} must be absent");
        if (exp["body"] is { } b) Assert.True(JsonNode.DeepEquals(b, req!["body"]), $"{at}: body {req["body"]?.ToJsonString()}");
        if (exp["multipart"] is { } mp) Partial(mp, req!["multipart"], $"{at}.multipart");
        if (exp["problems"] is { } pr) Assert.True(JsonNode.DeepEquals(pr, req!["problems"]), $"{at}: the mock found problems: {req["problems"]?.ToJsonString()}");
    }

    private static JsonNode? ToNode(object? value) => value is null ? null : JsonSerializer.SerializeToNode(value, value.GetType(), Json.Options);

    private static JsonArray Ids(System.Collections.IEnumerable items)
    {
        var out_ = new JsonArray();
        foreach (var item in items) out_.Add(ToNode(item)!["id"]?.DeepClone());
        return out_;
    }

    private static void CheckResult(JsonNode exp, object? result)
    {
        if (exp["notNull"] is not null) Assert.NotNull(result);
        if (exp["null"] is not null) Assert.Null(result);
        if (exp["bytes"] is { } bytes) Assert.Equal(bytes.GetValue<int>(), Assert.IsType<byte[]>(result).Length);
        if (exp["text"] is not null) Assert.IsType<string>(result);
        if (exp["textStarts"] is { } ts) Assert.StartsWith(ts.GetValue<string>(), (string)result!, StringComparison.Ordinal);
        if (exp["fields"] is { } fields) Partial(fields, ToNode(result), "result");
        if (exp["items"] is { } items) Assert.True(JsonNode.DeepEquals(items, Ids((System.Collections.IEnumerable)result!)), "items");
        if (exp["pageIds"] is { } ids) Assert.True(JsonNode.DeepEquals(ids, Ids((System.Collections.IEnumerable)result!.GetType().GetProperty("Data")!.GetValue(result)!)), "page ids");
        if (exp.AsObject().ContainsKey("nextCursor")) Assert.Equal(exp["nextCursor"]?.GetValue<string>(), (string?)result!.GetType().GetProperty("NextCursor")!.GetValue(result));
    }

    /// <summary>The .NET name of an error class of the cases.</summary>
    public static string DotnetClass(string name) => name switch
    {
        "APIError" => "ApiException",
        "APIConnectionError" => "ApiConnectionException",
        "APITimeoutError" => "ApiTimeoutException",
        "NotSupportedError" => "VerbNotSupportedException",
        _ => Regex.Replace(name, "Error$", "Exception"),
    };

    public static void CheckError(JsonNode exp, Exception? err)
    {
        Assert.True(err is not null, $"expected {exp["class"]}, got no error");
        Assert.Equal(DotnetClass(exp["class"]!.GetValue<string>()), err!.GetType().Name);
        if (err is ApiException e)
        {
            if (exp["status"] is { } s) Assert.Equal(s.GetValue<int>(), e.Status);
            if (exp["code"] is { } c) Assert.Equal(c.GetValue<string>(), e.Code);
            if (exp["type"] is { } t) Assert.Equal(t.GetValue<string>(), e.Type);
            if (exp["requestId"] is { } r) Assert.Equal(r.GetValue<string>(), e.RequestId);
            if (exp.AsObject().ContainsKey("param")) Assert.Equal(exp["param"]?.GetValue<string>(), e.Param);
            if (exp["verb"] is { } v) Assert.Equal(v.GetValue<string>(), ((CapabilityException)e).Verb);
            if (exp["reason"] is { } rs) Assert.Equal(rs.GetValue<string>(), e.Error?.Reason);
            if (exp["alternative"] is { } a) Assert.Equal(a.GetValue<string>(), e.Error?.Alternative);
        }
        else if (err is LiveStreamException l)
        {
            if (exp["code"] is { } c) Assert.Equal(c.GetValue<string>(), l.Code);
            if (exp["codeOneOf"] is { } one) Assert.Contains(l.Code, one.AsArray().Select(x => x!.GetValue<string>()));
        }
    }

    [Theory]
    [MemberData(nameof(HttpCases))]
    public async Task Http(string label)
    {
        var c = Find(label);
        var mock = Mock.Get();
        await mock.Reset(c["responses"]);
        var sleeps = new List<double>();
        object? result = null;
        Exception? error = null;
        try
        {
            result = await Call(Client(mock, c["client"], sleeps), c["call"]!);
        }
        catch (Exception e)
        {
            error = e;
        }
        var exp = c["expect"]!;
        if (exp["error"] is null && error is not null) throw new Xunit.Sdk.XunitException($"unexpected error: {error}");
        var requests = await mock.Requests();
        if (exp["requestCount"] is { } rc) Assert.Equal(rc.GetValue<int>(), requests.Count);
        var reqs = exp["requests"]?.AsArray() ?? new JsonArray();
        for (var i = 0; i < reqs.Count; i++) CheckRequest(reqs[i]!, i < requests.Count ? requests[i] : null, i);
        if (exp["sameHeaderAcrossRequests"] is { } same)
        {
            var values = requests.Select(r => r!["headers"]![same.GetValue<string>()]?.GetValue<string>()).Distinct().ToList();
            Assert.Single(values);
        }
        if (exp["sleepsMs"] is JsonArray expected)
        {
            Assert.Equal(expected.Count, sleeps.Count);
            for (var i = 0; i < expected.Count; i++)
            {
                var s = sleeps[i];
                if (expected[i] is JsonValue n) Assert.Equal(n.GetValue<double>(), Math.Round(s));
                else Assert.InRange(s, expected[i]!["min"]!.GetValue<double>(), expected[i]!["max"]!.GetValue<double>());
            }
        }
        if (exp["result"] is { } res) CheckResult(res, result);
        if (exp["error"] is { } er) CheckError(er, error);
    }

    [Theory]
    [MemberData(nameof(LiveCases))]
    public async Task Live(string name)
    {
        var c = Cases("live.json").First(x => x!["name"]!.GetValue<string>() == name)!;
        var mock = Mock.Get();
        await mock.Reset(null, c["ws"]);
        var client = Client(mock, null, new List<double>());
        var events = new List<IStreamEvent>();
        Exception? error = null;
        Task? reader = null;
        Exception? readError = null;
        long? finishMs = null;
        try
        {
            LiveStream? stream = null;
            foreach (var step in c["steps"]!.AsArray())
            {
                if (step!["open"] is { } open)
                {
                    var options = new StreamOptions();
                    if (open["config"] is { } cfg) options.Config = JsonSerializer.Deserialize<StreamConfig>(cfg.ToJsonString(), Json.Options);
                    if (open["token"] is { } tok) options.Token = tok.GetValue<string>();
                    stream = await client.Audio.Stream.OpenAsync(options);
                    var s = stream;
                    reader = Task.Run(async () =>
                    {
                        try
                        {
                            await foreach (var e in s) lock (events) events.Add(e);
                        }
                        catch (Exception e)
                        {
                            readError = e;
                        }
                    });
                }
                else if (step["setTerms"] is { } terms)
                {
                    await stream!.SetTermsAsync(terms.AsArray().Select(t => t!.GetValue<string>()));
                }
                else if (step["sendAudio"] is { } audio)
                {
                    var frameSeconds = audio["frameSeconds"]!.GetValue<double>();
                    var frame = new byte[(int)Math.Round(frameSeconds * 32000)];
                    var n = Math.Round(audio["seconds"]!.GetValue<double>() / frameSeconds);
                    for (var i = 0; i < n; i++) await stream!.SendAsync(frame);
                }
                else if (step["endUtterance"] is not null)
                {
                    await stream!.EndUtteranceAsync();
                }
                else if (step["finish"] is { } finish)
                {
                    var started = System.Diagnostics.Stopwatch.StartNew();
                    if (finish["flushTimeoutMs"] is { } ft) await stream!.FinishAsync(TimeSpan.FromMilliseconds(ft.GetValue<double>()));
                    else await stream!.FinishAsync();
                    finishMs = started.ElapsedMilliseconds;
                }
                else if (step["close"] is not null)
                {
                    await stream!.CloseAsync();
                }
            }
            if (reader is not null) await Task.WhenAny(reader, Task.Delay(30_000));
        }
        catch (Exception e)
        {
            error = e;
        }
        var exp = c["expect"]!;
        if (exp["error"] is null && error is not null) throw new Xunit.Sdk.XunitException($"unexpected error: {error}");
        if (exp["error"] is null && readError is not null) throw new Xunit.Sdk.XunitException($"unexpected read error: {readError}");
        if (exp["error"] is { } er) CheckError(er, error);
        var log = await mock.Ws();
        if (exp["connectionCount"] is { } cc) Assert.Equal(cc.GetValue<int>(), log.Count);
        var conns = exp["connections"]?.AsArray() ?? new JsonArray();
        for (var i = 0; i < conns.Count; i++)
        {
            var e = conns[i]!;
            var conn = log[i]!;
            if (e["headers"] is { } h) Partial(h, conn["headers"], $"connection {i}.headers");
            foreach (var k in e["absentHeaders"]?.AsArray() ?? new JsonArray()) Assert.False(conn["headers"]!.AsObject().ContainsKey(k!.GetValue<string>()));
            if (e["query"] is { } q) Partial(q, conn["query"], $"connection {i}.query");
            foreach (var k in e["absentQuery"]?.AsArray() ?? new JsonArray()) Assert.False(conn["query"]!.AsObject().ContainsKey(k!.GetValue<string>()));
            if (e["config"] is { } cfg) Partial(cfg, conn["config"], $"connection {i}.config");
            if (e["audioBytes"] is { } ab) Assert.Equal(ab.GetValue<long>(), conn["audioBytes"]!.GetValue<long>());
            if (e["firstAudioAt"] is { } fa) Assert.True(Math.Abs(conn["firstAudioAt"]!.GetValue<double>() - fa.GetValue<double>()) < 1e-6);
            if (e["messageTypes"] is { } mt) Assert.Equal(mt.AsArray().Select(x => x!.GetValue<string>()), conn["messages"]!.AsArray().Select(m => m!["type"]!.GetValue<string>()));
            if (e["terms"] is { } terms) Assert.True(JsonNode.DeepEquals(terms, conn["messages"]!.AsArray().First(m => m!["type"]!.GetValue<string>() == "terms")!["terms"]));
        }
        List<string> types;
        lock (events) types = events.Select(x => x.Type).ToList();
        if (exp["eventTypes"] is { } et) Assert.Equal(et.AsArray().Select(x => x!.GetValue<string>()), types);
        foreach (var t in exp["eventTypesInclude"]?.AsArray() ?? new JsonArray()) Assert.Contains(t!.GetValue<string>(), types);
        if (exp["finishMs"] is { } fm)
        {
            Assert.True(finishMs is not null, "finish did not return");
            if (fm["min"] is { } min) Assert.True(finishMs >= min.GetValue<double>(), $"finish took {finishMs} ms");
            if (fm["max"] is { } max) Assert.True(finishMs <= max.GetValue<double>(), $"finish took {finishMs} ms");
        }
        if (exp["lastSegmentEnd"] is { } lse)
        {
            double max;
            lock (events) max = events.OfType<StreamTranscript>().Max(x => x.Segment?.End ?? 0);
            Assert.Equal(lse.GetValue<double>(), max, 9);
        }
    }
}
