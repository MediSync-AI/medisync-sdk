using System.Collections.Generic;

namespace MediSync.Core;

/// <summary>What the client needs to know about one API operation. The generator writes one per operation.</summary>
public sealed class OperationMeta
{
    /// <summary>Made by the generated table.</summary>
    public OperationMeta(string name, string api, string operationId, string method, string path)
    {
        Name = name;
        Api = api;
        OperationId = operationId;
        Method = method;
        Path = path;
    }

    /// <summary>The dotted method name, <c>connect.transfers.create</c>.</summary>
    public string Name { get; }

    /// <summary><c>platform</c> or <c>connect</c>.</summary>
    public string Api { get; }

    /// <summary>The operationId of the spec.</summary>
    public string OperationId { get; }

    /// <summary>The HTTP method.</summary>
    public string Method { get; }

    /// <summary>The path with <c>{name}</c> placeholders.</summary>
    public string Path { get; }

    /// <summary><c>json</c>, <c>multipart</c>, or null without a body.</summary>
    public string? Body { get; set; }

    /// <summary>The operation takes query parameters.</summary>
    public bool Query { get; set; }

    /// <summary><c>json</c>, <c>binary</c>, <c>text</c> or <c>empty</c>.</summary>
    public string Response { get; set; } = "json";

    /// <summary><c>none</c>, <c>optional</c> or <c>required</c>: the client sends a key unless none.</summary>
    public string Idempotency { get; set; } = "none";

    /// <summary>A cursor list.</summary>
    public bool Paginated { get; set; }

    /// <summary>Query arrays sent as one comma-separated value.</summary>
    public IReadOnlyList<string> CommaQuery { get; set; } = new string[0];

    /// <summary>The x-medisync fields of the spec: scope, verb, phi, metered, usage, public.</summary>
    public IReadOnlyDictionary<string, string> X { get; set; } = new Dictionary<string, string>();
}
