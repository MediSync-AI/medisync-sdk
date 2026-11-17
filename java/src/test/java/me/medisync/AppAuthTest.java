package me.medisync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import me.medisync.connect.AppCredentials;
import me.medisync.connect.AppTokenSource;
import me.medisync.connect.ClientAssertion;
import me.medisync.core.ClientCore;
import me.medisync.core.Json;
import me.medisync.errors.AppAuthException;
import me.medisync.errors.AuthenticationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** App mode of MediSync Connect: the SDK signs the client assertion and keeps the access token. */
class AppAuthTest {
  static final String APP = "app_6Tn3Cw9KhZaB";

  static String pem(byte[] der) {
    return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der) + "\n-----END PRIVATE KEY-----\n";
  }

  static KeyPair ec() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  static byte[] hex(String s) {
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
    return out;
  }

  /** PKCS8 of an Ed25519 seed (RFC 8410). */
  static byte[] ed25519Pkcs8(byte[] seed) {
    byte[] prefix = hex("302e020100300506032b657004220420");
    byte[] out = new byte[prefix.length + 32];
    System.arraycopy(prefix, 0, out, 0, prefix.length);
    System.arraycopy(seed, 0, out, prefix.length, 32);
    return out;
  }

  static String[] parts(String jwt) {
    return jwt.split("\\.");
  }

  static JsonNode decode(String part) throws Exception {
    return Json.MAPPER.readTree(Base64.getUrlDecoder().decode(part));
  }

  @Test
  void signsEs256InTheJwsFormWithIssSubAudExpAndJti() throws Exception {
    KeyPair kp = ec();
    AppCredentials c = AppCredentials.builder(APP, "k1", pem(kp.getPrivate().getEncoded())).build();
    String jwt = ClientAssertion.create(c, "https://connect.medisync.me/oauth/token", 1_800_000_000L);
    String[] p = parts(jwt);
    JsonNode header = decode(p[0]);
    JsonNode payload = decode(p[1]);
    assertEquals("ES256", header.get("alg").asText());
    assertEquals("k1", header.get("kid").asText());
    assertEquals("JWT", header.get("typ").asText());
    assertEquals(APP, payload.get("iss").asText());
    assertEquals(APP, payload.get("sub").asText());
    assertEquals("https://connect.medisync.me/oauth/token", payload.get("aud").asText());
    assertEquals(1_800_000_060L, payload.get("exp").asLong());
    assertTrue(payload.get("jti").asText().matches("[0-9a-f-]{36}"));
    byte[] sig = Base64.getUrlDecoder().decode(p[2]);
    assertEquals(64, sig.length);
    Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
    v.initVerify(kp.getPublic());
    v.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(v.verify(sig));
  }

  @Test
  void signsEdDsaWithTheJdkAndTheRfc8032Key() throws Exception {
    Assumptions.assumeTrue(Runtime.version().feature() >= 15, "the JDK has Ed25519 from Java 15 on");
    // RFC 8032 7.1, test 2: the key pair.
    byte[] seed = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
    byte[] pub = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
    AppCredentials c = AppCredentials.builder(APP, "k2", pem(ed25519Pkcs8(seed))).build();
    String jwt = ClientAssertion.create(c, "aud", 1_800_000_000L);
    String[] p = parts(jwt);
    assertEquals("EdDSA", decode(p[0]).get("alg").asText());
    byte[] spki = new byte[44];
    System.arraycopy(hex("302a300506032b6570032100"), 0, spki, 0, 12);
    System.arraycopy(pub, 0, spki, 12, 32);
    Signature v = Signature.getInstance("Ed25519");
    v.initVerify(java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(spki)));
    v.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(v.verify(Base64.getUrlDecoder().decode(p[2])));
  }

  @Test
  void ed25519OnJava11SaysUseEs256() {
    Assumptions.assumeTrue(Runtime.version().feature() < 15, "only Java 11 to 14 lack Ed25519");
    byte[] seed = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
    AppCredentials c = AppCredentials.builder(APP, "k2", pem(ed25519Pkcs8(seed))).build();
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ClientAssertion.create(c, "aud", 1_800_000_000L));
    assertTrue(e.getMessage().contains("Java 15"));
  }

  @Test
  void edDsaVerifiesWithTheJdkWhereItHasEd25519() throws Exception {
    Assumptions.assumeTrue(Runtime.version().feature() >= 15, "the JDK has Ed25519 from Java 15 on");
    KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
    KeyPair kp = g.generateKeyPair();
    String jwt = ClientAssertion.create(AppCredentials.builder(APP, "k3", pem(kp.getPrivate().getEncoded())).build(), "aud");
    String[] p = parts(jwt);
    Signature v = Signature.getInstance("Ed25519");
    v.initVerify(kp.getPublic());
    v.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(v.verify(Base64.getUrlDecoder().decode(p[2])));
    // A key object signs with the JDK.
    String jwt2 = ClientAssertion.create(AppCredentials.builder(APP, "k3", kp.getPrivate()).build(), "aud");
    String[] p2 = parts(jwt2);
    v.initVerify(kp.getPublic());
    v.update((p2[0] + "." + p2[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(v.verify(Base64.getUrlDecoder().decode(p2[2])));
  }

  @Test
  void refusesOtherKeys() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    String rsa = pem(g.generateKeyPair().getPrivate().getEncoded());
    assertThrows(IllegalArgumentException.class, () -> ClientAssertion.create(AppCredentials.builder(APP, "k", rsa).build(), "aud"));
    assertThrows(IllegalArgumentException.class, () -> ClientAssertion.create(AppCredentials.builder(APP, "k", "not a pem").build(), "aud"));
    assertThrows(IllegalArgumentException.class, () -> AppCredentials.builder("bad", "k", rsa).build());
  }

  static Map<String, String> form(String body) {
    Map<String, String> out = new HashMap<>();
    for (String pair : body.split("&")) {
      String[] kv = pair.split("=", 2);
      out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
    }
    return out;
  }

  @Test
  void fetchesOnceSharesAndRefreshesAMinuteBeforeExpiry() throws Exception {
    AtomicLong now = new AtomicLong(1_000_000);
    try (LocalServer server = new LocalServer()) {
      server.json(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":900}").json(200, "{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\",\"expires_in\":900}");
      AppCredentials c = AppCredentials.builder(APP, "k1", pem(ec().getPrivate().getEncoded())).installationId("ins_3Rv8Qa2MxK").verbs(java.util.Arrays.asList("patient.context")).issuer(server.url()).build();
      AppTokenSource source = new AppTokenSource(c, HttpClient.newHttpClient(), now::get);
      CompletableFuture<String> a = source.get();
      CompletableFuture<String> b = source.get();
      assertEquals("tok-1", a.join());
      assertEquals("tok-1", b.join());
      assertEquals(1, server.seen.size());
      Map<String, String> f = form(server.seen.get(0).body);
      assertEquals("/oauth/token", server.seen.get(0).path);
      assertEquals("client_credentials", f.get("grant_type"));
      assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", f.get("client_assertion_type"));
      assertEquals(Json.MAPPER.readTree("[{\"type\":\"medisync_connect\",\"installation_id\":\"ins_3Rv8Qa2MxK\",\"verbs\":[{\"verb\":\"patient.context\",\"roles\":[]}]}]"), Json.MAPPER.readTree(f.get("authorization_details")));
      assertEquals(server.url() + "/oauth/token", decode(parts(f.get("client_assertion"))[1]).get("aud").asText());
      now.addAndGet(800_000);
      assertEquals("tok-1", source.get().join());
      now.addAndGet(50_000);
      assertEquals("tok-2", source.get().join());
      assertEquals(2, server.seen.size());
    }
  }

  @Test
  void aRefusalIsAnAppAuthException() throws Exception {
    try (LocalServer server = new LocalServer()) {
      server.json(401, "{\"error\":\"invalid_client\",\"error_description\":\"Unknown key.\"}");
      AppCredentials c = AppCredentials.builder(APP, "k1", pem(ec().getPrivate().getEncoded())).issuer(server.url()).build();
      AppAuthException e = assertThrows(AppAuthException.class, () -> ClientCore.join(new AppTokenSource(c).get()));
      assertEquals(401, e.getStatus());
      assertEquals("invalid_client", e.getError());
    }
  }

  @Test
  void connectCallsUseTheAppTokenAndRetryOnceAfterInvalidToken() throws Exception {
    try (LocalServer server = new LocalServer()) {
      String invalid = "{\"error\":{\"type\":\"authentication_error\",\"code\":\"invalid_token\",\"message\":\"Expired.\",\"request_id\":\"req_1\"}}";
      server
          .json(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":900}")
          .json(401, invalid)
          .json(200, "{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\",\"expires_in\":900}")
          .json(200, "{\"id\":\"site_1\",\"name\":\"Praxis Beispiel\"}")
          .json(200, "{\"access_token\":\"tok-3\",\"token_type\":\"Bearer\",\"expires_in\":900}");
      AppCredentials c = AppCredentials.builder(APP, "k1", pem(ec().getPrivate().getEncoded())).issuer(server.url()).build();
      MediSyncClient client = MediSyncClient.builder().app(c).baseUrl(server.url()).env(k -> null).build();
      assertEquals("site_1", client.connect().sites().get("site_1").getId());
      assertEquals("Bearer tok-1", server.seen.get(1).headers.getFirst("authorization"));
      assertEquals("Bearer tok-2", server.seen.get(3).headers.getFirst("authorization"));
      // A platform call needs an API key.
      assertThrows(me.medisync.errors.MediSyncException.class, () -> client.models().list());
      // A second invalid_token in a row is thrown.
      server.json(401, invalid).json(200, "{\"access_token\":\"tok-4\",\"token_type\":\"Bearer\",\"expires_in\":900}").json(401, invalid);
      AppTokenSource tokens = new AppTokenSource(c);
      MediSyncClient other = MediSyncClient.builder().app(tokens).baseUrl(server.url()).env(k -> null).maxRetries(0).build();
      assertThrows(AuthenticationException.class, () -> other.connect().sites().get("site_1"));
    }
  }
}
