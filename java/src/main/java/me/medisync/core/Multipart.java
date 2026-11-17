package me.medisync.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import me.medisync.FileInput;
import org.jspecify.annotations.Nullable;

/**
 * The multipart form of a body. Files go as files; strings as they are; numbers and booleans as
 * text; arrays and objects as JSON text. Fields that are null are left out.
 */
final class Multipart {
  final String boundary = "medisync-" + UUID.randomUUID();
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  static Multipart of(Object body) {
    Multipart form = new Multipart();
    ObjectNode node = Json.MAPPER.valueToTree(body);
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      JsonNode value = field.getValue();
      if (value.isNull() || value.isMissingNode()) continue;
      if (value instanceof POJONode && ((POJONode) value).getPojo() instanceof FileInput) {
        FileInput file = (FileInput) ((POJONode) value).getPojo();
        String name = file.getFilename() != null ? file.getFilename() : field.getKey();
        String type = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        form.part(field.getKey(), name, type, file.getData());
      } else if (value.isTextual() || value.isNumber() || value.isBoolean()) {
        form.part(field.getKey(), null, null, value.asText().getBytes(StandardCharsets.UTF_8));
      } else {
        form.part(field.getKey(), null, null, value.toString().getBytes(StandardCharsets.UTF_8));
      }
    }
    form.write("--" + form.boundary + "--\r\n");
    return form;
  }

  private static String quote(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
  }

  private void part(String name, @Nullable String filename, @Nullable String contentType, byte[] data) {
    write("--" + boundary + "\r\n");
    write("Content-Disposition: form-data; name=\"" + quote(name) + "\"" + (filename != null ? "; filename=\"" + quote(filename) + "\"" : "") + "\r\n");
    if (contentType != null) write("Content-Type: " + contentType + "\r\n");
    write("\r\n");
    out.write(data, 0, data.length);
    write("\r\n");
  }

  private void write(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    out.write(bytes, 0, bytes.length);
  }

  byte[] bytes() {
    return out.toByteArray();
  }

  String contentType() {
    return "multipart/form-data; boundary=" + boundary;
  }
}
