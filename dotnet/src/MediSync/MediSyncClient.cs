using MediSync.Core;
using MediSync.Resources;

namespace MediSync;

/// <summary>
/// The MediSync client: one client for the platform API and MediSync Connect. Every method is
/// async and takes a CancellationToken.
/// <code>
/// var client = new MediSyncClient(); // reads MEDISYNC_API_KEY
/// var models = await client.Models.ListAsync();
/// </code>
/// Everything the API generates is a draft. A clinician reviews and releases it.
/// </summary>
public sealed class MediSyncClient : MediSyncResources
{
    private readonly ClientCore _core;

    /// <summary>A client with these options. Throws when there is neither an API key nor an app.</summary>
    public MediSyncClient(MediSyncClientOptions? options = null) : this(new ClientCore(options ?? new MediSyncClientOptions())) { }

    /// <summary>A client with this API key.</summary>
    public MediSyncClient(string apiKey) : this(new MediSyncClientOptions { ApiKey = apiKey }) { }

    private MediSyncClient(ClientCore core) : base(core) => _core = core;

    /// <summary>The base URL the client calls.</summary>
    public string BaseUrl => _core.BaseUrl;

    /// <summary>The MediSync-Version header the client sends.</summary>
    public string Version => _core.Version;
}

/// <summary>The version of this SDK.</summary>
public static class SdkVersion
{
    /// <summary>The SDK version, also in the User-Agent header.</summary>
    public const string Value = "0.3.0";
}
