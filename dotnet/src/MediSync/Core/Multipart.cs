using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MediSync.Core;

/// <summary>
/// The multipart form of a body. Files go as files; strings as they are; numbers and booleans as
/// text; arrays and objects as JSON text. Fields that are null are left out.
/// </summary>
internal sealed class Multipart
{
    public string Boundary { get; } = "medisync-" + Guid.NewGuid().ToString("N");
    private readonly MemoryStream _out = new();

    public static Multipart Of(object body)
    {
        var form = new Multipart();
        foreach (var (name, value) in Fields(body))
        {
            if (value is null) continue;
            if (value is FileInput file)
            {
                form.Part(name, file.FileName ?? name, file.ContentType ?? "application/octet-stream", file.Data);
                continue;
            }
            var json = value is JsonElement e ? e : JsonSerializer.SerializeToElement(value, value.GetType(), Json.Options);
            var text = json.ValueKind switch
            {
                JsonValueKind.Null or JsonValueKind.Undefined => null,
                JsonValueKind.String => json.GetString(),
                JsonValueKind.True => "true",
                JsonValueKind.False => "false",
                _ => json.GetRawText(),
            };
            if (text is null) continue;
            form.Part(name, null, null, Encoding.UTF8.GetBytes(text));
        }
        form.Write($"--{form.Boundary}--\r\n");
        return form;
    }

    /// <summary>The wire names and values of a model (properties with JsonPropertyName, then the additional properties), or of a dictionary.</summary>
    private static IEnumerable<(string, object?)> Fields(object body)
    {
        if (body is IDictionary<string, object?> map)
        {
            foreach (var kv in map) yield return (kv.Key, kv.Value);
            yield break;
        }
        foreach (var prop in body.GetType().GetProperties(BindingFlags.Public | BindingFlags.Instance))
        {
            var attr = prop.GetCustomAttribute<JsonPropertyNameAttribute>();
            if (attr is null) continue;
            yield return (attr.Name, prop.GetValue(body));
        }
        if (body is JsonModel model)
        {
            foreach (var kv in model.AdditionalProperties) yield return (kv.Key, kv.Value);
        }
    }

    private static string Quote(string value) => value.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "").Replace("\n", "");

    private void Part(string name, string? fileName, string? contentType, byte[] data)
    {
        Write($"--{Boundary}\r\n");
        Write($"Content-Disposition: form-data; name=\"{Quote(name)}\"" + (fileName is null ? "" : $"; filename=\"{Quote(fileName)}\"") + "\r\n");
        if (contentType is not null) Write($"Content-Type: {contentType}\r\n");
        Write("\r\n");
        _out.Write(data, 0, data.Length);
        Write("\r\n");
    }

    private void Write(string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        _out.Write(bytes, 0, bytes.Length);
    }

    public byte[] ToArray() => _out.ToArray();

    public string ContentType => $"multipart/form-data; boundary={Boundary}";
}
