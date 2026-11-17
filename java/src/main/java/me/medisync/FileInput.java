package me.medisync;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A file for a multipart upload: audio for a transcription, a PDF for MediSync Connect.
 *
 * <pre>{@code
 * FileInput.of(Path.of("visit.wav"))
 * FileInput.of(bytes, "visit.wav", "audio/wav")
 * }</pre>
 */
@JsonSerialize(using = FileInput.Serializer.class)
public final class FileInput {
  private final byte[] data;
  private final @Nullable String filename;
  private final @Nullable String contentType;

  private FileInput(byte[] data, @Nullable String filename, @Nullable String contentType) {
    this.data = data;
    this.filename = filename;
    this.contentType = contentType;
  }

  /** Bytes with a file name and a content type (null: application/octet-stream). */
  public static FileInput of(byte[] data, @Nullable String filename, @Nullable String contentType) {
    return new FileInput(data.clone(), filename, contentType);
  }

  /** Bytes with a file name. */
  public static FileInput of(byte[] data, String filename) {
    return of(data, filename, null);
  }

  /** A file on disk; its name is the file name. Reads the whole file. */
  public static FileInput of(Path path) {
    try {
      Path name = path.getFileName();
      return new FileInput(Files.readAllBytes(path), name == null ? null : name.toString(), null);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The bytes. */
  public byte[] getData() {
    return data.clone();
  }

  /** The file name, or null. */
  public @Nullable String getFilename() {
    return filename;
  }

  /** The content type, or null. */
  public @Nullable String getContentType() {
    return contentType;
  }

  /** Writes the file as an embedded object: only the multipart encoder reads it. */
  static final class Serializer extends StdSerializer<FileInput> {
    private static final long serialVersionUID = 1L;

    Serializer() {
      super(FileInput.class);
    }

    @Override
    public void serialize(FileInput value, JsonGenerator gen, SerializerProvider provider) throws IOException {
      gen.writeEmbeddedObject(value);
    }
  }
}
