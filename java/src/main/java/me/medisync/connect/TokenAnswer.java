package me.medisync.connect;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import me.medisync.core.JsonModel;
import org.jspecify.annotations.Nullable;

/** The answer of the token endpoint. */
public final class TokenAnswer extends JsonModel {
  @JsonProperty("access_token")
  private @Nullable String accessToken;

  @JsonProperty("token_type")
  private @Nullable String tokenType;

  @JsonProperty("expires_in")
  private @Nullable Double expiresIn;

  @JsonProperty("scope")
  private @Nullable String scope;

  @JsonProperty("installation_id")
  private @Nullable String installationId;

  @JsonProperty("authorization_details")
  private @Nullable List<Object> authorizationDetails;

  /** An empty answer. */
  public TokenAnswer() {}

  /** The access token. Do not log it. */
  public @Nullable String getAccessToken() {
    return accessToken;
  }

  /** <code>Bearer</code>. */
  public @Nullable String getTokenType() {
    return tokenType;
  }

  /** Seconds until the token expires. */
  public @Nullable Double getExpiresIn() {
    return expiresIn;
  }

  /** The granted scopes. */
  public @Nullable String getScope() {
    return scope;
  }

  /** The installation of the token, or null for an app token. */
  public @Nullable String getInstallationId() {
    return installationId;
  }

  /** The verbs of the installation. */
  public @Nullable List<Object> getAuthorizationDetails() {
    return authorizationDetails;
  }
}
