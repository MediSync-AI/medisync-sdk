package me.medisync.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The path parameters, body and query of one call. */
public final class RequestArgs {
  private final Map<String, String> path = new LinkedHashMap<>();
  private @Nullable Object body;
  private @Nullable Object query;
  private @Nullable Map<String, JsonNode> queryNodes;

  private RequestArgs() {}

  /** Empty arguments. */
  public static RequestArgs create() {
    return new RequestArgs();
  }

  /** A path parameter. */
  public RequestArgs path(String name, String value) {
    path.put(name, value);
    return this;
  }

  /** The body: a model, or a map. */
  public RequestArgs body(@Nullable Object value) {
    this.body = value;
    return this;
  }

  /** The query: a model, or a map. */
  public RequestArgs query(@Nullable Object value) {
    this.query = value;
    return this;
  }

  RequestArgs withQueryNodes(Map<String, JsonNode> nodes) {
    RequestArgs copy = new RequestArgs();
    copy.path.putAll(path);
    copy.body = body;
    copy.queryNodes = nodes;
    return copy;
  }

  Map<String, String> getPath() {
    return Collections.unmodifiableMap(path);
  }

  @Nullable Object getBody() {
    return body;
  }

  @Nullable Object getQuery() {
    return query;
  }

  @Nullable Map<String, JsonNode> getQueryNodes() {
    return queryNodes;
  }
}
