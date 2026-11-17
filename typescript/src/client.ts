import { DEFAULT_BASE_URL, type ClientOptions } from './core/client.ts';
import { API_VERSION } from './generated/operations.ts';
import { MediSyncResources } from './generated/resources.ts';

/**
 * The MediSync API client: speech, notes and letters, ICD-10-GM coding, decisions, account
 * reads and MediSync Connect. One method per API operation, named after the operation:
 *
 *   const client = new MediSync(); // reads MEDISYNC_API_KEY
 *   const note = await client.notes.create({ ... });
 *   const transfer = await client.connect.transfers.create({ plan_id, release });
 *
 * Everything the API generates is a draft that a clinician reviews and releases.
 */
export class MediSync extends MediSyncResources {
  static readonly DEFAULT_BASE_URL = DEFAULT_BASE_URL;
  static readonly API_VERSION = API_VERSION;

  constructor(options: ClientOptions = {}) {
    super(options);
  }
}
