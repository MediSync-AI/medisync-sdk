package me.medisync.connect;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.medisync.core.Json;

/** Client assertions of RFC 7523 for the token endpoint of MediSync Connect. */
public final class ClientAssertion {
  private ClientAssertion() {}

  /** An assertion lives one minute; Connect allows five. */
  private static final long ASSERTION_SECONDS = 60;

  private static final Pattern PEM = Pattern.compile("-----BEGIN PRIVATE KEY-----([\\s\\S]+?)-----END PRIVATE KEY-----");
  private static final byte[] OID_EC = {0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01};
  private static final byte[] OID_ED25519 = {0x06, 0x03, 0x2b, 0x65, 0x70};

  static String b64url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** A key that can sign: ES256 with EC P-256, EdDSA with Ed25519. */
  static final class SigningKey {
    final String alg;
    final Signer signer;

    SigningKey(String alg, Signer signer) {
      this.alg = alg;
      this.signer = signer;
    }
  }

  interface Signer {
    byte[] sign(byte[] input) throws GeneralSecurityException;
  }

  private static int indexOf(byte[] data, byte[] part) {
    outer:
    for (int i = 0; i + part.length <= data.length; i++) {
      for (int j = 0; j < part.length; j++) if (data[i + j] != part[j]) continue outer;
      return i;
    }
    return -1;
  }

  private static SigningKey ec(PrivateKey key) {
    if (!(key instanceof ECPrivateKey) || ((ECPrivateKey) key).getParams().getCurve().getField().getFieldSize() != 256) {
      throw new IllegalArgumentException("The app key must be an EC P-256 or an Ed25519 key.");
    }
    // The JWS form of the signature (r || s), as ES256 wants it.
    return new SigningKey(
        "ES256",
        input -> {
          Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
          s.initSign(key);
          s.update(input);
          return s.sign();
        });
  }

  private static SigningKey edJdk(PrivateKey key) {
    return new SigningKey(
        "EdDSA",
        input -> {
          Signature s = Signature.getInstance("Ed25519");
          s.initSign(key);
          s.update(input);
          return s.sign();
        });
  }

  static SigningKey signingKey(AppCredentials c) {
    PrivateKey object = c.getPrivateKey();
    if (object != null) {
      String alg = object.getAlgorithm();
      if ("EC".equals(alg)) return ec(object);
      if ("EdDSA".equals(alg) || "Ed25519".equals(alg)) return edJdk(object);
      throw new IllegalArgumentException("The app key must be an EC P-256 or an Ed25519 key.");
    }
    String pem = c.getPrivateKeyPem() == null ? "" : c.getPrivateKeyPem().replace("\\n", "\n");
    Matcher m = PEM.matcher(pem);
    if (!m.find()) throw new IllegalArgumentException("privateKey must be a PKCS8 PEM (-----BEGIN PRIVATE KEY-----).");
    byte[] der;
    try {
      der = Base64.getMimeDecoder().decode(m.group(1).replaceAll("\\s+", ""));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("privateKey must be a PKCS8 PEM (-----BEGIN PRIVATE KEY-----).", e);
    }
    if (indexOf(der, OID_EC) >= 0) {
      try {
        return ec(KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der)));
      } catch (GeneralSecurityException e) {
        throw new IllegalArgumentException("privateKey must be an EC P-256 or an Ed25519 private key.", e);
      }
    }
    if (indexOf(der, OID_ED25519) >= 0) {
      // Ed25519 signs only with the JDK's own implementation (Java 15 and later). The SDK carries
      // no signing code of its own: a hand-written one is hard to keep free of timing leaks.
      KeyFactory factory;
      try {
        factory = KeyFactory.getInstance("Ed25519");
      } catch (GeneralSecurityException e) {
        throw new IllegalArgumentException("An Ed25519 app key needs Java 15 or later. On Java 11, use an EC P-256 key (ES256).", e);
      }
      try {
        return edJdk(factory.generatePrivate(new PKCS8EncodedKeySpec(der)));
      } catch (GeneralSecurityException e) {
        throw new IllegalArgumentException("privateKey is not a valid Ed25519 PKCS8 key.", e);
      }
    }
    throw new IllegalArgumentException("privateKey must be an EC P-256 or an Ed25519 private key.");
  }

  /** A client assertion (RFC 7523 2.2): iss = sub = client_id, aud = the endpoint, a one-time jti. */
  public static String create(AppCredentials c, String audience, long nowSeconds) {
    SigningKey key = signingKey(c);
    ObjectNode header = Json.MAPPER.createObjectNode().put("alg", key.alg).put("kid", c.getKeyId()).put("typ", "JWT");
    ObjectNode payload =
        Json.MAPPER.createObjectNode()
            .put("iss", c.getClientId())
            .put("sub", c.getClientId())
            .put("aud", audience)
            .put("iat", nowSeconds)
            .put("exp", nowSeconds + ASSERTION_SECONDS)
            .put("jti", UUID.randomUUID().toString());
    String input = b64url(header.toString().getBytes(StandardCharsets.UTF_8)) + "." + b64url(payload.toString().getBytes(StandardCharsets.UTF_8));
    try {
      return input + "." + b64url(key.signer.sign(input.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not sign the client assertion: " + e.getMessage(), e);
    }
  }

  /** A client assertion signed now. */
  public static String create(AppCredentials c, String audience) {
    return create(c, audience, System.currentTimeMillis() / 1000);
  }
}
