package me.medisync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A small HTTP server for unit tests: answers each request with the next scripted handler. */
final class LocalServer implements AutoCloseable {
  interface Handler {
    void handle(HttpExchange exchange, String body) throws IOException;
  }

  static final class Seen {
    final String method;
    final String path;
    final String body;
    final com.sun.net.httpserver.Headers headers;

    Seen(String method, String path, String body, com.sun.net.httpserver.Headers headers) {
      this.method = method;
      this.path = path;
      this.body = body;
      this.headers = headers;
    }
  }

  private final HttpServer server;
  final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
  private final List<Handler> handlers = Collections.synchronizedList(new ArrayList<>());

  LocalServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body, exchange.getRequestHeaders()));
      Handler h = handlers.isEmpty() ? null : handlers.remove(0);
      if (h == null) send(exchange, 500, "{\"error\":\"no answer scripted\"}");
      else h.handle(exchange, body);
      exchange.close();
    });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  LocalServer then(Handler h) {
    handlers.add(h);
    return this;
  }

  LocalServer json(int status, String json) {
    return then((e, b) -> send(e, status, json));
  }

  static void send(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("content-type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
