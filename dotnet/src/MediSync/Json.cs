using System.Text.Json;
using System.Text.Json.Serialization;

namespace MediSync;

/// <summary>The JSON settings of the SDK.</summary>
public static class Json
{
    /// <summary>Leaves out nulls. Unknown fields of an answer go to <see cref="JsonModel.AdditionalProperties"/>.</summary>
    public static readonly JsonSerializerOptions Options = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };
}
