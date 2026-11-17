package me.medisync.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What the client needs to know about one API operation. The generator writes one per operation. */
public final class OperationMeta {
  private final String name;
  private final String api;
  private final String operationId;
  private final String method;
  private final String path;
  private final @Nullable String body;
  private final boolean query;
  private final String response;
  private final String idempotency;
  private final boolean paginated;
  private final List<String> commaQuery;
  private final Map<String, String> x;

  private OperationMeta(Builder b) {
    this.name = b.name;
    this.api = b.api;
    this.operationId = b.operationId;
    this.method = b.method;
    this.path = b.path;
    this.body = b.body;
    this.query = b.query;
    this.response = b.response;
    this.idempotency = b.idempotency;
    this.paginated = b.paginated;
    this.commaQuery = Collections.unmodifiableList(b.commaQuery);
    this.x = Collections.unmodifiableMap(b.x);
  }

  /** A builder, for the generated table. */
  public static Builder builder(String name, String api, String operationId, String method, String path) {
    return new Builder(name, api, operationId, method, path);
  }

  /** The dotted method name, <code>connect.transfers.create</code>. */
  public String getName() {
    return name;
  }

  /** <code>platform</code> or <code>connect</code>. */
  public String getApi() {
    return api;
  }

  /** The operationId of the spec. */
  public String getOperationId() {
    return operationId;
  }

  /** The HTTP method. */
  public String getMethod() {
    return method;
  }

  /** The path with <code>{name}</code> placeholders. */
  public String getPath() {
    return path;
  }

  /** <code>json</code>, <code>multipart</code>, or null without a body. */
  public @Nullable String getBody() {
    return body;
  }

  /** The operation takes query parameters. */
  public boolean hasQuery() {
    return query;
  }

  /** <code>json</code>, <code>binary</code>, <code>text</code> or <code>empty</code>. */
  public String getResponse() {
    return response;
  }

  /** <code>none</code>, <code>optional</code> or <code>required</code>: the client sends a key unless none. */
  public String getIdempotency() {
    return idempotency;
  }

  /** A cursor list. */
  public boolean isPaginated() {
    return paginated;
  }

  /** Query arrays sent as one comma-separated value. */
  public List<String> getCommaQuery() {
    return commaQuery;
  }

  /** The x-medisync fields of the spec: scope, verb, phi, metered, usage, public. */
  public Map<String, String> getX() {
    return x;
  }

  /** Builds an {@link OperationMeta}. */
  public static final class Builder {
    private final String name;
    private final String api;
    private final String operationId;
    private final String method;
    private final String path;
    private @Nullable String body;
    private boolean query;
    private String response = "json";
    private String idempotency = "none";
    private boolean paginated;
    private List<String> commaQuery = Collections.emptyList();
    private final Map<String, String> x = new LinkedHashMap<>();

    private Builder(String name, String api, String operationId, String method, String path) {
      this.name = name;
      this.api = api;
      this.operationId = operationId;
      this.method = method;
      this.path = path;
    }

    /** The body encoding. */
    public Builder body(String encoding) {
      this.body = encoding;
      return this;
    }

    /** The operation takes query parameters. */
    public Builder query(boolean value) {
      this.query = value;
      return this;
    }

    /** The answer kind. */
    public Builder response(String kind) {
      this.response = kind;
      return this;
    }

    /** The Idempotency-Key rule. */
    public Builder idempotency(String rule) {
      this.idempotency = rule;
      return this;
    }

    /** A cursor list. */
    public Builder paginated(boolean value) {
      this.paginated = value;
      return this;
    }

    /** Query arrays sent as one comma-separated value. */
    public Builder commaQuery(String... names) {
      this.commaQuery = Arrays.asList(names);
      return this;
    }

    /** An x-medisync field. */
    public Builder x(String key, String value) {
      x.put(key, value);
      return this;
    }

    /** The meta. */
    public OperationMeta build() {
      return new OperationMeta(this);
    }
  }
}
