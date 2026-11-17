using System.Collections.Generic;
using System.Text.Json;

namespace MediSync.Core;

/// <summary>The path parameters, body and query of one call.</summary>
internal sealed class RequestArgs
{
    public Dictionary<string, string> PathParams { get; } = new();

    public object? Body { get; private set; }

    public object? Query { get; private set; }

    /// <summary>The query as JSON values, after the first page.</summary>
    public Dictionary<string, JsonElement>? QueryValues { get; private set; }

    public RequestArgs Path(string name, string value)
    {
        PathParams[name] = value;
        return this;
    }

    public RequestArgs WithBody(object? body)
    {
        Body = body;
        return this;
    }

    public RequestArgs WithQuery(object? query)
    {
        Query = query;
        return this;
    }

    public RequestArgs WithQueryValues(Dictionary<string, JsonElement> values)
    {
        var copy = new RequestArgs { Body = Body, QueryValues = values };
        foreach (var kv in PathParams) copy.PathParams[kv.Key] = kv.Value;
        return copy;
    }
}
