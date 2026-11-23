package me.medisync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.medisync.core.Json;
import me.medisync.resources.Operations;
import org.junit.jupiter.api.Test;

/** The clients have exactly one public method name per operationId of both specs, plus the helpers. */
class ParityTest {
  private static JsonNode read(String path) throws Exception {
    return Json.MAPPER.readTree(Files.readAllBytes(Mock.ROOT.resolve(path)));
  }

  private static List<String> specOperationIds(String file) throws Exception {
    List<String> out = new ArrayList<>();
    for (JsonNode item : read("specs/" + file).get("paths")) {
      for (String m : new String[] {"get", "post", "put", "patch", "delete"}) if (item.has(m)) out.add(item.get(m).get("operationId").asText());
    }
    return out;
  }

  private static boolean isService(Class<?> type) {
    return type.getPackage() != null && type.getPackage().getName().equals("me.medisync.resources") && type.getSimpleName().matches(".*Service(Async)?");
  }

  /** Every public method name reachable from the client's namespaces, as dotted names. */
  static TreeSet<String> publicMethods(Object root) throws Exception {
    TreeSet<String> out = new TreeSet<>();
    walk(root, "", out);
    return out;
  }

  private static void walk(Object node, String prefix, TreeSet<String> out) throws Exception {
    for (Method m : node.getClass().getMethods()) {
      if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers())) continue;
      String path = prefix.isEmpty() ? m.getName() : prefix + "." + m.getName();
      if (m.getParameterCount() == 0 && isService(m.getReturnType())) walk(m.invoke(node), path, out);
      else if (isService(node.getClass())) out.add(path);
    }
  }

  @Test
  void oneMethodPerOperationIdAndTheHelpers() throws Exception {
    TreeSet<String> ids = new TreeSet<>();
    for (String id : specOperationIds("openapi.json")) ids.add("platform:" + id);
    for (String id : specOperationIds("connect-openapi.json")) ids.add("connect:" + id);
    JsonNode names = read("conformance/operations.json");
    TreeSet<String> listed = new TreeSet<>();
    TreeSet<String> expected = new TreeSet<>();
    TreeSet<String> operations = new TreeSet<>();
    for (JsonNode o : names.get("operations")) {
      listed.add(o.get("api").asText() + ":" + o.get("operationId").asText());
      expected.add(o.get("java").asText());
      operations.add(o.get("java").asText());
    }
    TreeSet<String> helpers = new TreeSet<>();
    for (JsonNode h : names.get("helpers")) {
      expected.add(h.get("java").asText());
      helpers.add(h.get("java").asText());
    }
    assertEquals(ids, listed);
    assertEquals(100, specOperationIds("connect-openapi.json").size());
    MediSyncClient client = new MediSyncClient("msk_test_parity");
    assertEquals(expected, publicMethods(client));
    assertEquals(expected, publicMethods(client.async()));
    assertEquals(operations, new TreeSet<>(Operations.ALL.keySet()));
    assertEquals(helpers, new TreeSet<>(Operations.HELPERS));
  }

  @Test
  void versionMatchesThePom() throws Exception {
    String pom = new String(Files.readAllBytes(Mock.ROOT.resolve("java/pom.xml")), java.nio.charset.StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("<artifactId>medisync</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
    assertTrue(m.find());
    assertEquals(m.group(1), Version.VERSION);
  }
}
