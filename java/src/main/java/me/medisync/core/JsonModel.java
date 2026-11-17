package me.medisync.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Base of every model. Keeps the fields of an answer this SDK version does not know, and the
 * fields set to an explicit null, which a request then sends as <code>null</code>.
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE, creatorVisibility = Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class JsonModel {
  @JsonIgnore private final Map<String, @Nullable Object> additionalProperties = new LinkedHashMap<>();
  @JsonIgnore private final Set<String> explicitNulls = new LinkedHashSet<>();

  /** For the generated models. */
  protected JsonModel() {}

  /** Fields of the object that this SDK version has no property for. */
  public Map<String, @Nullable Object> getAdditionalProperties() {
    return Collections.unmodifiableMap(additionalProperties);
  }

  /** Sets a field that this SDK version has no property for. It is sent with the request. */
  public void setAdditionalProperty(String key, @Nullable Object value) {
    additionalProperties.put(key, value);
  }

  /** The generated setters call it: a nullable field set to null is sent as null. */
  protected final void explicitNull(String key, boolean isNull) {
    if (isNull) explicitNulls.add(key);
    else explicitNulls.remove(key);
  }

  @JsonAnySetter
  private void readUnknown(String key, @Nullable Object value) {
    additionalProperties.put(key, value);
  }

  @JsonAnyGetter
  @JsonInclude(content = JsonInclude.Include.ALWAYS)
  private Map<String, @Nullable Object> writeUnknown() {
    if (explicitNulls.isEmpty()) return additionalProperties;
    Map<String, @Nullable Object> all = new LinkedHashMap<>(additionalProperties);
    for (String key : explicitNulls) all.put(key, null);
    return all;
  }

  /** The object as JSON. */
  public String toJson() {
    try {
      return Json.MAPPER.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + toJson();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (this == other) return true;
    if (other == null || other.getClass() != getClass()) return false;
    return Json.MAPPER.valueToTree(this).equals(Json.MAPPER.valueToTree(other));
  }

  @Override
  public int hashCode() {
    return Json.MAPPER.valueToTree(this).hashCode();
  }
}
