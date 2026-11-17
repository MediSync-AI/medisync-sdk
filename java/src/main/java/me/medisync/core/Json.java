package me.medisync.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** The JSON mapper of the SDK. */
public final class Json {
  private Json() {}

  /** Leaves out nulls, keeps unknown fields of an answer in the additional properties. */
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
}
