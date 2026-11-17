using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MediSync;

/// <summary>
/// Base of every model. Keeps the fields of an answer this SDK version does not know, and the
/// fields set to an explicit null, which a request then sends as <c>null</c>.
/// </summary>
public abstract class JsonModel
{
    private static readonly JsonElement NullElement = JsonDocument.Parse("null").RootElement.Clone();

    /// <summary>
    /// Fields of the object that this SDK version has no property for. Values you add here are
    /// sent with a request. A nullable field set to null shows here as a null value.
    /// </summary>
    [JsonExtensionData]
    public IDictionary<string, JsonElement> AdditionalProperties { get; set; } = new Dictionary<string, JsonElement>();

    /// <summary>The generated setters call it: a nullable field set to null is sent as null.</summary>
    protected void ExplicitNull(string key, bool isNull)
    {
        if (isNull)
        {
            AdditionalProperties[key] = NullElement;
        }
        else if (AdditionalProperties.TryGetValue(key, out var value) && value.ValueKind == JsonValueKind.Null)
        {
            AdditionalProperties.Remove(key);
        }
    }

    /// <summary>The object as JSON.</summary>
    public string ToJson() => JsonSerializer.Serialize(this, GetType(), Json.Options);

    /// <inheritdoc />
    public override string ToString() => GetType().Name + ToJson();
}
