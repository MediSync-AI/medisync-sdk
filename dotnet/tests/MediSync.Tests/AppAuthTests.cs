using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Xunit;

namespace MediSync.Tests;

/// <summary>App mode of MediSync Connect: the SDK signs the client assertion and keeps the access token.</summary>
public class AppAuthTests
{
    private const string App = "app_6Tn3Cw9KhZaB";

    public static string Pem(byte[] der) => "-----BEGIN PRIVATE KEY-----\n" + Convert.ToBase64String(der, Base64FormattingOptions.InsertLineBreaks) + "\n-----END PRIVATE KEY-----\n";

    private static byte[] FromB64Url(string s)
    {
        s = s.Replace('-', '+').Replace('_', '/');
        return Convert.FromBase64String(s + new string('=', (4 - s.Length % 4) % 4));
    }

    private static JsonNode Decode(string part) => JsonNode.Parse(FromB64Url(part))!;

    private static byte[] Hex(string s) => Enumerable.Range(0, s.Length / 2).Select(i => Convert.ToByte(s.Substring(2 * i, 2), 16)).ToArray();

    [Fact]
    public void SignsEs256InTheJwsFormWithIssSubAudExpAndJti()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var c = new AppCredentials(App, "k1", Pem(key.ExportPkcs8PrivateKey()));
        var jwt = ClientAssertion.Create(c, "https://connect.medisync.me/oauth/token", 1_800_000_000);
        var p = jwt.Split('.');
        var header = Decode(p[0]);
        var payload = Decode(p[1]);
        Assert.Equal("ES256", header["alg"]!.GetValue<string>());
        Assert.Equal("k1", header["kid"]!.GetValue<string>());
        Assert.Equal("JWT", header["typ"]!.GetValue<string>());
        Assert.Equal(App, payload["iss"]!.GetValue<string>());
        Assert.Equal(App, payload["sub"]!.GetValue<string>());
        Assert.Equal("https://connect.medisync.me/oauth/token", payload["aud"]!.GetValue<string>());
        Assert.Equal(1_800_000_060, payload["exp"]!.GetValue<long>());
        Assert.Matches("^[0-9a-f-]{36}$", payload["jti"]!.GetValue<string>());
        var sig = FromB64Url(p[2]);
        Assert.Equal(64, sig.Length);
        Assert.True(key.VerifyData(Encoding.ASCII.GetBytes(p[0] + "." + p[1]), sig, HashAlgorithmName.SHA256));
    }

    [Fact]
    public void SignsWithAPkcs8KeyThatHasNoPublicKey()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var d = key.ExportParameters(true).D!;
        // PKCS8 { 0, { ecPublicKey, prime256v1 }, OCTET STRING { ECPrivateKey { 1, d } } }: no public key inside.
        var pkcs8 = Hex("3041020100301306072a8648ce3d020106082a8648ce3d030107042730250201010420").Concat(d).ToArray();
        var jwt = ClientAssertion.Create(new AppCredentials(App, "k1", Pem(pkcs8)), "aud", 1_800_000_000);
        var p = jwt.Split('.');
        Assert.True(key.VerifyData(Encoding.ASCII.GetBytes(p[0] + "." + p[1]), FromB64Url(p[2]), HashAlgorithmName.SHA256));
    }

    [Fact]
    public void RefusesEd25519KeysAndNamesEs256()
    {
        var seed = Hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
        var pkcs8 = Hex("302e020100300506032b657004220420").Concat(seed).ToArray();
        var e = Assert.Throws<ArgumentException>(() => ClientAssertion.Create(new AppCredentials(App, "k2", Pem(pkcs8)), "aud", 1_800_000_000));
        Assert.Contains("ES256", e.Message);
    }

    [Fact]
    public void RefusesOtherKeys()
    {
        using var rsa = RSA.Create(2048);
        Assert.Throws<ArgumentException>(() => ClientAssertion.Create(new AppCredentials(App, "k", Pem(rsa.ExportPkcs8PrivateKey())), "aud"));
        Assert.Throws<ArgumentException>(() => ClientAssertion.Create(new AppCredentials(App, "k", "not a pem"), "aud"));
        Assert.Throws<ArgumentException>(() => new AppCredentials("bad", "k", "x"));
    }

    private static Dictionary<string, string> Form(string body) => body.Split('&').Select(p => p.Split(new[] { '=' }, 2)).ToDictionary(kv => Uri.UnescapeDataString(kv[0]), kv => Uri.UnescapeDataString(kv[1]));

    [Fact]
    public async Task FetchesOnceSharesAndRefreshesAMinuteBeforeExpiry()
    {
        double now = 1_000_000;
        var handler = new FakeHandler()
            .Json(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":900}")
            .Json(200, "{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\",\"expires_in\":900}");
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var c = new AppCredentials(App, "k1", Pem(key.ExportPkcs8PrivateKey())) { InstallationId = "ins_3Rv8Qa2MxK", Verbs = new List<string> { "patient.context" } };
        var source = new AppTokenSource(c, new HttpClient(handler), () => now);
        var both = await Task.WhenAll(source.GetAsync(), source.GetAsync());
        Assert.Equal(new[] { "tok-1", "tok-1" }, both);
        Assert.Single(handler.Seen);
        var f = Form(handler.Seen[0].Body);
        Assert.Equal("https://connect.medisync.me/oauth/token", handler.Seen[0].Request.RequestUri!.ToString());
        Assert.Equal("client_credentials", f["grant_type"]);
        Assert.Equal("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", f["client_assertion_type"]);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse("[{\"type\":\"medisync_connect\",\"installation_id\":\"ins_3Rv8Qa2MxK\",\"verbs\":[{\"verb\":\"patient.context\",\"roles\":[]}]}]"), JsonNode.Parse(f["authorization_details"])));
        now += 800_000;
        Assert.Equal("tok-1", await source.GetAsync());
        now += 50_000;
        Assert.Equal("tok-2", await source.GetAsync());
        Assert.Equal(2, handler.Seen.Count);
    }

    [Fact]
    public async Task ARefusalIsAnAppAuthException()
    {
        var handler = new FakeHandler().Json(401, "{\"error\":\"invalid_client\",\"error_description\":\"Unknown key.\"}");
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var source = new AppTokenSource(new AppCredentials(App, "k1", Pem(key.ExportPkcs8PrivateKey())), new HttpClient(handler));
        var e = await Assert.ThrowsAsync<AppAuthException>(() => source.GetAsync());
        Assert.Equal(401, e.Status);
        Assert.Equal("invalid_client", e.Error);
    }

    [Fact]
    public async Task ConnectCallsUseTheAppTokenAndRetryOnceAfterInvalidToken()
    {
        const string invalid = "{\"error\":{\"type\":\"authentication_error\",\"code\":\"invalid_token\",\"message\":\"Expired.\",\"request_id\":\"req_1\"}}";
        var handler = new FakeHandler()
            .Json(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":900}")
            .Json(401, invalid)
            .Json(200, "{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\",\"expires_in\":900}")
            .Json(200, "{\"id\":\"site_1\",\"name\":\"Praxis Beispiel\"}");
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var client = new MediSyncClient(new MediSyncClientOptions
        {
            App = new AppCredentials(App, "k1", Pem(key.ExportPkcs8PrivateKey())),
            BaseUrl = "https://api.example.test",
            HttpClient = new HttpClient(handler),
            Environment = _ => null,
        });
        Assert.Equal("site_1", (await client.Connect.Sites.GetAsync("site_1")).Id);
        Assert.Equal("Bearer tok-1", handler.Seen[1].Request.Headers.Authorization!.ToString());
        Assert.Equal("Bearer tok-2", handler.Seen[3].Request.Headers.Authorization!.ToString());
        await Assert.ThrowsAsync<MediSyncException>(() => client.Models.ListAsync());
        handler.Json(401, invalid).Json(200, "{\"access_token\":\"tok-3\",\"token_type\":\"Bearer\",\"expires_in\":900}").Json(401, invalid);
        await Assert.ThrowsAsync<AuthenticationException>(() => client.Connect.Sites.GetAsync("site_1"));
    }
}
