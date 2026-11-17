package me.medisync.audio;

import com.fasterxml.jackson.databind.JsonNode;

/** A server message of a type this SDK version does not know. */
public final class UnknownStreamEvent implements StreamEvent {
  private final String type;
  private final JsonNode json;

  UnknownStreamEvent(String type, JsonNode json) {
    this.type = type;
    this.json = json;
  }

  @Override
  public String getType() {
    return type;
  }

  /** The whole message. */
  public JsonNode getJson() {
    return json;
  }

  @Override
  public String toString() {
    return json.toString();
  }
}
