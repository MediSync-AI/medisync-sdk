// Read-only calls against the live API: models, a coding search and the Connect catalogue.
//
//   MEDISYNC_API_KEY=msk_test_… node scripts/smoke-live.ts
//
// Without MEDISYNC_API_KEY it reads ~/.config/medisync-sdk/test-key. It prints counts and
// request ids, never the key. MEDISYNC_BASE_URL points it at another server.
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { MediSync } from '../src/index.ts';

const keyFile = join(homedir(), '.config/medisync-sdk/test-key');
const apiKey = process.env.MEDISYNC_API_KEY ?? (existsSync(keyFile) ? readFileSync(keyFile, 'utf8').trim() : '');
if (!apiKey) {
  console.error(`No key: set MEDISYNC_API_KEY or write it to ${keyFile}.`);
  process.exit(2);
}
const ids: string[] = [];
const client = new MediSync({ apiKey, onResponse: (r) => ids.push(`${r.operation} ${r.status} ${r.requestId ?? '-'}`) });

const models = await client.models.list();
console.log(`models.list: ${models.data.length} models`);
const search = await client.coding.search({ q: 'Hypertonie', limit: 3 });
console.log(`coding.search: ${JSON.stringify(search).length} bytes`);
const systems = await client.connect.systems.list({ limit: 5 });
console.log(`connect.systems.list: ${systems.data.length} systems on the first page`);
console.log(`requests:\n  ${ids.join('\n  ')}`);
