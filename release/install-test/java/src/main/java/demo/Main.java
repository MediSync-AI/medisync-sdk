package demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import me.medisync.MediSyncClient;
import me.medisync.Version;
import me.medisync.errors.NotFoundException;
import me.medisync.models.ModelList;
import me.medisync.models.connect.SystemsListQuery;

/** Install test of the Maven Central package. Arguments: the mock URL and the expected version. */
public class Main {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static String runtime = "java " + Runtime.version().feature();

  public static void main(String[] args) throws Exception {
    String mock = args[0];
    String expected = args[1];
    check(Version.VERSION.equals(expected), "Version.VERSION is " + Version.VERSION + ", expected " + expected);
    MediSyncClient client = MediSyncClient.builder().apiKey("msk_test_install").baseUrl(mock).build();

    control(mock, "reset", "{\"responses\":[]}");
    ModelList models = client.models().list();
    check(models.getData() != null, "models.list gave no data");
    check(client.connect().systems().list(new SystemsListQuery().limit(2L)).getData() != null, "connect.systems.list gave no data");
    check(client.async().models().list().get().getData() != null, "async models.list gave no data");
    String log = control(mock, "requests", null);
    check(count(log, "\"user-agent\":\"medisync-java/" + expected + "\"") == 3, "not every request carried User-Agent medisync-java/" + expected + ": " + log);
    check(count(log, "\"problems\":[]") == 3, "the mock found problems: " + log);

    control(mock, "reset", "{\"responses\":[{\"status\":404,\"body\":{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"code\":\"not_found\",\"message\":\"No such site.\",\"request_id\":\"req_install\",\"param\":null},\"request_id\":\"req_install\"}}]}");
    try {
      client.connect().sites().get("ste_missing");
      check(false, "a 404 threw nothing");
    } catch (NotFoundException e) {
      check("not_found".equals(e.getCode()) && "req_install".equals(e.getRequestId()), "error code " + e.getCode() + ", request ID " + e.getRequestId());
    }
    System.out.println(runtime + ": me.medisync:medisync " + Version.VERSION + " ok");
  }

  private static String control(String mock, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(mock + "/__mock/" + path)).header("content-type", "application/json");
    b = body == null ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
  }

  private static int count(String text, String part) {
    int n = 0;
    for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
    return n;
  }

  private static void check(boolean ok, String what) {
    if (!ok) throw new IllegalStateException(runtime + ": " + what);
  }
}
