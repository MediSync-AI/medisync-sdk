package me.medisync.connect;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A MediSync Connect app with a key pair (<code>private_key_jwt</code>). The SDK signs a client
 * assertion with the private key and trades it for a 15-minute access token. The private key
 * never leaves the process; MediSync holds only the public key.
 *
 * <pre>{@code
 * AppCredentials.builder("app_6Tn3Cw9KhZaB", "k1", pem).installationId("ins_3Rv8Qa2MxK").build()
 * }</pre>
 */
public final class AppCredentials {
  private static final Pattern CLIENT_ID = Pattern.compile("^app_[A-Za-z0-9]{8,32}$");

  private final String clientId;
  private final String keyId;
  private final @Nullable String privateKeyPem;
  private final @Nullable PrivateKey privateKey;
  private final @Nullable String installationId;
  private final List<String> verbs;
  private final List<String> scopes;
  private final @Nullable String environment;
  private final String issuer;

  private AppCredentials(Builder b) {
    if (!CLIENT_ID.matcher(b.clientId).matches()) throw new IllegalArgumentException("clientId is the app id from the console, app_….");
    this.clientId = b.clientId;
    this.keyId = b.keyId;
    this.privateKeyPem = b.privateKeyPem;
    this.privateKey = b.privateKey;
    this.installationId = b.installationId;
    this.verbs = Collections.unmodifiableList(new ArrayList<>(b.verbs));
    this.scopes = Collections.unmodifiableList(new ArrayList<>(b.scopes));
    this.environment = b.environment;
    this.issuer = b.issuer.replaceAll("/+$", "");
  }

  /**
   * A builder with the app id (<code>app_…</code>, the OAuth client_id), the key id of the public key
   * in the console, and the private key as a PKCS8 PEM (<code>-----BEGIN PRIVATE KEY-----</code>).
   * EC P-256 signs with ES256 on every Java version. Ed25519 signs with EdDSA on Java 15 or later.
   */
  public static Builder builder(String clientId, String keyId, String privateKeyPem) {
    Builder b = new Builder(clientId, keyId);
    b.privateKeyPem = privateKeyPem;
    return b;
  }

  /** A builder with a private key object: an EC P-256 key, or an Ed25519 key on Java 15 or later. */
  public static Builder builder(String clientId, String keyId, PrivateKey privateKey) {
    Builder b = new Builder(clientId, keyId);
    b.privateKey = privateKey;
    return b;
  }

  /** The app id. */
  public String getClientId() {
    return clientId;
  }

  /** The key id (<code>kid</code>). */
  public String getKeyId() {
    return keyId;
  }

  @Nullable String getPrivateKeyPem() {
    return privateKeyPem;
  }

  @Nullable PrivateKey getPrivateKey() {
    return privateKey;
  }

  /** The installation the token acts at, or null for an app token. */
  public @Nullable String getInstallationId() {
    return installationId;
  }

  /** Fewer verbs than the installation holds, or empty. */
  public List<String> getVerbs() {
    return verbs;
  }

  /** Fewer scopes than the app holds, or empty. */
  public List<String> getScopes() {
    return scopes;
  }

  /** <code>live</code> or <code>test</code> for an app token, or null. */
  public @Nullable String getEnvironment() {
    return environment;
  }

  /** The authorization server. */
  public String getIssuer() {
    return issuer;
  }

  /** Builds {@link AppCredentials}. */
  public static final class Builder {
    private final String clientId;
    private final String keyId;
    private @Nullable String privateKeyPem;
    private @Nullable PrivateKey privateKey;
    private @Nullable String installationId;
    private List<String> verbs = new ArrayList<>();
    private List<String> scopes = new ArrayList<>();
    private @Nullable String environment;
    private String issuer = AppTokenSource.DEFAULT_ISSUER;

    private Builder(String clientId, String keyId) {
      this.clientId = clientId;
      this.keyId = keyId;
    }

    /**
     * Act at one site: the installation id (<code>ins_…</code>). The token then reaches only that site
     * and carries the installation's verbs. Leave it out for an app token (sites.create, lists).
     */
    public Builder installationId(String value) {
      this.installationId = value;
      return this;
    }

    /** Fewer verbs than the installation holds, for this client's token. */
    public Builder verbs(List<String> value) {
      this.verbs = new ArrayList<>(value);
      return this;
    }

    /** Fewer scopes than the app holds. */
    public Builder scopes(List<String> value) {
      this.scopes = new ArrayList<>(value);
      return this;
    }

    /** An app token of an app with a live and a test project names the environment: live or test. */
    public Builder environment(String value) {
      this.environment = value;
      return this;
    }

    /** The authorization server. Default https://connect.medisync.me. */
    public Builder issuer(String value) {
      this.issuer = value;
      return this;
    }

    /** The credentials. */
    public AppCredentials build() {
      return new AppCredentials(this);
    }
  }
}
