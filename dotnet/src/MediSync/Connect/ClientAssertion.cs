using System;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace MediSync;

/// <summary>Client assertions of RFC 7523 for the token endpoint of MediSync Connect.</summary>
public static class ClientAssertion
{
    /// <summary>An assertion lives one minute; Connect allows five.</summary>
    private const long AssertionSeconds = 60;

    private static readonly Regex Pem = new("-----BEGIN PRIVATE KEY-----([\\s\\S]+?)-----END PRIVATE KEY-----");
    private static readonly byte[] OidEc = { 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01 };
    private static readonly byte[] OidP256 = { 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07 };
    private static readonly byte[] OidEd25519 = { 0x06, 0x03, 0x2b, 0x65, 0x70 };

    internal static string B64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static int IndexOf(byte[] data, byte[] part, int from = 0)
    {
        for (var i = from; i + part.Length <= data.Length; i++)
        {
            var ok = true;
            for (var j = 0; j < part.Length && ok; j++) ok = data[i + j] == part[j];
            if (ok) return i;
        }
        return -1;
    }

    /// <summary>Reads a DER length at <paramref name="at"/>; moves past it.</summary>
    private static int Length(byte[] der, ref int at)
    {
        int first = der[at++];
        if (first < 0x80) return first;
        var n = first & 0x7f;
        var len = 0;
        for (var i = 0; i < n; i++) len = (len << 8) | der[at++];
        return len;
    }

    internal static (string Alg, Func<byte[], byte[]> Sign) SigningKey(string pemText)
    {
        var m = Pem.Match(pemText.Replace("\\n", "\n"));
        if (!m.Success) throw new ArgumentException("privateKey must be a PKCS8 PEM (-----BEGIN PRIVATE KEY-----).");
        byte[] der;
        try
        {
            der = Convert.FromBase64String(Regex.Replace(m.Groups[1].Value, "\\s+", ""));
        }
        catch (FormatException e)
        {
            throw new ArgumentException("privateKey must be a PKCS8 PEM (-----BEGIN PRIVATE KEY-----).", e);
        }
        if (IndexOf(der, OidEc) >= 0)
        {
            if (IndexOf(der, OidP256) < 0) throw new ArgumentException("The app key must be an EC P-256 key (ES256).");
            var (d, x, y) = EcPrivateKey(der);
            if (x is null || y is null)
            {
#if NET5_0_OR_GREATER
                // The key has no public point: .NET derives it while it imports the PKCS8 key.
                return ("ES256", input =>
                {
                    using var ecdsa = ECDsa.Create();
                    ecdsa.ImportPkcs8PrivateKey(der, out _);
                    return ecdsa.SignData(input, HashAlgorithmName.SHA256);
                });
#else
                throw new ArgumentException("This PKCS8 key has no public key. Export the key again with its public key, for example: openssl pkcs8 -topk8 -nocrypt -in key.pem");
#endif
            }
            return ("ES256", input => EcSign(d, x, y, input));
        }
        if (IndexOf(der, OidEd25519) >= 0)
        {
            // .NET has no Ed25519 of its own, and the SDK carries no signing code of its own: a
            // hand-written one is hard to keep free of timing leaks. ES256 works on every .NET.
            throw new ArgumentException("Ed25519 app keys are not supported in the .NET SDK, because .NET has no built-in Ed25519. Use an EC P-256 key (ES256).");
        }
        throw new ArgumentException("privateKey must be an EC P-256 private key (ES256).");
    }

    /// <summary>The private scalar and the public point of a PKCS8 EC key (RFC 5915 inside RFC 5208).</summary>
    private static (byte[] D, byte[]? X, byte[]? Y) EcPrivateKey(byte[] der)
    {
        // The ECPrivateKey is the OCTET STRING after the algorithm: SEQUENCE { 1, OCTET STRING d, [0], [1] BIT STRING }.
        var at = IndexOf(der, OidP256) + OidP256.Length;
        while (at < der.Length && der[at] != 0x04) at++;
        at++;
        Length(der, ref at);
        if (der[at++] != 0x30) throw new ArgumentException("privateKey is not a valid EC PKCS8 key.");
        Length(der, ref at);
        at += 3; // INTEGER 1
        if (der[at++] != 0x04) throw new ArgumentException("privateKey is not a valid EC PKCS8 key.");
        var dLen = Length(der, ref at);
        var d = Pad(der.Skip(at).Take(dLen).ToArray());
        at += dLen;
        byte[]? x = null, y = null;
        var pub = IndexOf(der, new byte[] { 0xa1 }, at);
        if (pub >= 0)
        {
            var p = pub + 1;
            Length(der, ref p);
            if (der[p++] == 0x03)
            {
                var len = Length(der, ref p);
                // Unused-bits byte 0, then 0x04 || X || Y.
                if (len == 66 && der[p + 1] == 0x04)
                {
                    x = der.Skip(p + 2).Take(32).ToArray();
                    y = der.Skip(p + 34).Take(32).ToArray();
                }
            }
        }
        return (d, x, y);
    }

    private static byte[] Pad(byte[] value)
    {
        if (value.Length == 32) return value;
        if (value.Length > 32) return value.Skip(value.Length - 32).ToArray();
        var out_ = new byte[32];
        Array.Copy(value, 0, out_, 32 - value.Length, value.Length);
        return out_;
    }

    private static byte[] EcSign(byte[] d, byte[] x, byte[] y, byte[] input)
    {
        var parameters = new ECParameters { Curve = ECCurve.NamedCurves.nistP256, D = d, Q = new ECPoint { X = x, Y = y } };
        using var ecdsa = ECDsa.Create(parameters);
        // .NET signs ECDSA in the IEEE P1363 form (r || s), as ES256 wants it.
        return ecdsa.SignData(input, HashAlgorithmName.SHA256);
    }

    /// <summary>A client assertion (RFC 7523 2.2): iss = sub = client_id, aud = the endpoint, a one-time jti.</summary>
    public static string Create(AppCredentials c, string audience, long? nowSeconds = null)
    {
        var (alg, sign) = SigningKey(c.PrivateKeyPem);
        var now = nowSeconds ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var header = JsonSerializer.Serialize(new { alg, kid = c.KeyId, typ = "JWT" });
        var payload = JsonSerializer.Serialize(new { iss = c.ClientId, sub = c.ClientId, aud = audience, iat = now, exp = now + AssertionSeconds, jti = Guid.NewGuid().ToString() });
        var input = B64Url(Encoding.UTF8.GetBytes(header)) + "." + B64Url(Encoding.UTF8.GetBytes(payload));
        return input + "." + B64Url(sign(Encoding.ASCII.GetBytes(input)));
    }
}
