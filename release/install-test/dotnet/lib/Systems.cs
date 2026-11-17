using System.Threading.Tasks;
using MediSync;
using MediSync.Models.Connect;

namespace InstallTest;

/// <summary>Code compiled against the netstandard2.0 build of the package.</summary>
public static class Systems
{
    public static async Task<int> CountAsync(string baseUrl)
    {
        var client = new MediSyncClient(new MediSyncClientOptions { ApiKey = "msk_test_install", BaseUrl = baseUrl });
        var page = await client.Connect.Systems.ListAsync(new SystemsListQuery { Limit = 2 });
        return page.Data.Count;
    }
}
