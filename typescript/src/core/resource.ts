import type { MediSyncCore } from './client.ts';

/** Base of the generated resource classes. */
export class APIResource {
  protected readonly _client: MediSyncCore;

  constructor(client: MediSyncCore) {
    this._client = client;
  }
}
