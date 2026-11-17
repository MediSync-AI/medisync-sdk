using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace MediSync;

/// <summary>
/// A MediSync Connect app with a key pair (<c>private_key_jwt</c>). The SDK signs a client
/// assertion with the private key and trades it for a 15-minute access token. The private key
/// never leaves the process; MediSync holds only the public key.
/// </summary>
public sealed class AppCredentials
{
    private static readonly Regex ClientIdPattern = new("^app_[A-Za-z0-9]{8,32}$");

    /// <summary>
    /// The app id (<c>app_…</c>, the OAuth client_id), the key id of the public key in the console, and
    /// the private key as a PKCS8 PEM (<c>-----BEGIN PRIVATE KEY-----</c>). EC P-256 signs with ES256.
    /// Ed25519 keys are not supported: .NET has no built-in Ed25519.
    /// </summary>
    public AppCredentials(string clientId, string keyId, string privateKeyPem)
    {
        if (!ClientIdPattern.IsMatch(clientId)) throw new ArgumentException("clientId is the app id from the console, app_….", nameof(clientId));
        ClientId = clientId;
        KeyId = keyId;
        PrivateKeyPem = privateKeyPem;
    }

    /// <summary>The app id.</summary>
    public string ClientId { get; }

    /// <summary>The key id (<c>kid</c>).</summary>
    public string KeyId { get; }

    internal string PrivateKeyPem { get; }

    /// <summary>
    /// Act at one site: the installation id (<c>ins_…</c>). The token then reaches only that site and
    /// carries the installation's verbs. Leave it out for an app token (sites.create, lists).
    /// </summary>
    public string? InstallationId { get; set; }

    /// <summary>Fewer verbs than the installation holds, for this client's token.</summary>
    public IList<string> Verbs { get; set; } = new List<string>();

    /// <summary>Fewer scopes than the app holds.</summary>
    public IList<string> Scopes { get; set; } = new List<string>();

    /// <summary>An app token of an app with a live and a test project names the environment: live or test.</summary>
    public string? Environment { get; set; }

    /// <summary>The authorization server. Default https://connect.medisync.me.</summary>
    public string Issuer { get; set; } = AppTokenSource.DefaultIssuer;
}
