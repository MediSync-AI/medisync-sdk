using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using MediSync.Resources;
using Xunit;

namespace MediSync.Tests;

/// <summary>The client has exactly one public method per operationId of both specs, plus the helpers.</summary>
public class ParityTests
{
    private static JsonNode Read(string path) => JsonNode.Parse(File.ReadAllText(Path.Combine(Mock.Root, path)))!;

    private static List<string> SpecOperationIds(string file)
    {
        var ids = new List<string>();
        foreach (var item in Read(Path.Combine("specs", file))["paths"]!.AsObject())
        {
            foreach (var m in new[] { "get", "post", "put", "patch", "delete" })
            {
                if (item.Value![m] is { } op) ids.Add(op["operationId"]!.GetValue<string>());
            }
        }
        return ids;
    }

    private static bool IsService(Type t) => t.Namespace == "MediSync.Resources" && t.Name.EndsWith("Service", StringComparison.Ordinal);

    private static SortedSet<string> PublicMethods(object root)
    {
        var out_ = new SortedSet<string>(StringComparer.Ordinal);
        void Walk(object node, string prefix)
        {
            foreach (var p in node.GetType().GetProperties(BindingFlags.Public | BindingFlags.Instance))
            {
                if (IsService(p.PropertyType)) Walk(p.GetValue(node)!, prefix.Length == 0 ? p.Name : $"{prefix}.{p.Name}");
            }
            if (!IsService(node.GetType())) return;
            foreach (var m in node.GetType().GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly))
            {
                if (!m.IsSpecialName) out_.Add($"{prefix}.{m.Name}");
            }
        }
        Walk(root, "");
        return out_;
    }

    [Fact]
    public void OneMethodPerOperationIdAndTheHelpers()
    {
        var ids = new SortedSet<string>(SpecOperationIds("openapi.json").Select(i => "platform:" + i).Concat(SpecOperationIds("connect-openapi.json").Select(i => "connect:" + i)), StringComparer.Ordinal);
        var names = Read(Path.Combine("conformance", "operations.json"));
        var ops = names["operations"]!.AsArray();
        var helpers = names["helpers"]!.AsArray();
        Assert.Equal(ids, new SortedSet<string>(ops.Select(o => $"{o!["api"]}:{o["operationId"]}"), StringComparer.Ordinal));
        Assert.Equal(100, SpecOperationIds("connect-openapi.json").Count);
        var expected = new SortedSet<string>(ops.Select(o => o!["dotnet"]!.GetValue<string>()).Concat(helpers.Select(h => h!["dotnet"]!.GetValue<string>())), StringComparer.Ordinal);
        Assert.Equal(expected, PublicMethods(new MediSyncClient("msk_test_parity")));
        Assert.Equal(new SortedSet<string>(ops.Select(o => o!["typescript"]!.GetValue<string>()), StringComparer.Ordinal), new SortedSet<string>(Operations.All.Keys, StringComparer.Ordinal));
        Assert.Equal(new SortedSet<string>(helpers.Select(h => h!["typescript"]!.GetValue<string>()), StringComparer.Ordinal), new SortedSet<string>(Operations.Helpers, StringComparer.Ordinal));
    }

    [Fact]
    public void VersionMatchesTheProject()
    {
        var csproj = File.ReadAllText(Path.Combine(Mock.Root, "dotnet", "src", "MediSync", "MediSync.csproj"));
        Assert.Equal(Regex.Match(csproj, "<Version>([^<]+)</Version>").Groups[1].Value, SdkVersion.Value);
        Assert.Equal(typeof(MediSyncClient).Assembly.GetName().Version!.ToString(3), SdkVersion.Value);
    }
}
