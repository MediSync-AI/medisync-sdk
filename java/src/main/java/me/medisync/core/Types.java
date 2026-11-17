package me.medisync.core;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.util.List;
import java.util.Map;

/** Jackson types of the answers, for the generated code. */
public final class Types {
  private Types() {}

  private static final TypeFactory FACTORY = Json.MAPPER.getTypeFactory();

  /** A class. */
  public static JavaType of(Class<?> type) {
    return FACTORY.constructType(type);
  }

  /** A list of a type. */
  public static JavaType listOf(JavaType item) {
    return FACTORY.constructCollectionType(List.class, item);
  }

  /** A map from string to a type. */
  public static JavaType mapOf(JavaType value) {
    return FACTORY.constructMapType(Map.class, FACTORY.constructType(String.class), value);
  }
}
