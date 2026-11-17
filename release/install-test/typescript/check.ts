// Type test of the npm package: compiled with tsc --strict, for nodenext and for the old node10
// resolution. Not run.
import MediSync, { NotFoundError, type OperationName } from 'medisync';
import type { Connect } from 'medisync';

const client = new MediSync({ apiKey: 'msk_test_install' });
const op: OperationName = 'connect.transfers.create';

export async function typed(): Promise<string> {
  const page = await client.connect.systems.list({ limit: 2 });
  const first: Connect.System | undefined = page.data[0];
  try {
    await client.connect.sites.get('ste_missing');
  } catch (e) {
    if (e instanceof NotFoundError) return `${e.code} ${e.requestId ?? ''}`;
  }
  return `${op} ${first?.id ?? ''}`;
}
