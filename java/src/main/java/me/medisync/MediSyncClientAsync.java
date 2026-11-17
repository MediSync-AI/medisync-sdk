package me.medisync;

import me.medisync.core.ClientCore;
import me.medisync.resources.MediSyncAsyncResources;

/**
 * The MediSync client with async methods: each answers a {@code CompletableFuture}. Get it from
 * {@link MediSyncClient#async()} or {@link MediSyncClient.Builder#buildAsync()}.
 */
public final class MediSyncClientAsync extends MediSyncAsyncResources {
  private final ClientCore core;

  MediSyncClientAsync(ClientCore core) {
    super(core);
    this.core = core;
  }

  ClientCore core() {
    return core;
  }

  /** The base URL the client calls. */
  public String getBaseUrl() {
    return core.baseUrl();
  }
}
