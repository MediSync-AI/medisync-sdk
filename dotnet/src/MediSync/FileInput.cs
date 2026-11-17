using System.IO;

namespace MediSync;

/// <summary>A file for a multipart upload: audio for a transcription, a PDF for MediSync Connect.</summary>
public sealed class FileInput
{
    private readonly byte[] _data;

    /// <summary>Bytes with a file name and a content type (null: application/octet-stream).</summary>
    public FileInput(byte[] data, string? fileName = null, string? contentType = null)
    {
        _data = (byte[])data.Clone();
        FileName = fileName;
        ContentType = contentType;
    }

    /// <summary>A file on disk; its name is the file name. Reads the whole file.</summary>
    public static FileInput FromPath(string path, string? contentType = null) => new(File.ReadAllBytes(path), Path.GetFileName(path), contentType);

    /// <summary>The file name, or null.</summary>
    public string? FileName { get; }

    /// <summary>The content type, or null.</summary>
    public string? ContentType { get; }

    /// <summary>A copy of the bytes.</summary>
    public byte[] GetData() => (byte[])_data.Clone();

    internal byte[] Data => _data;
}
