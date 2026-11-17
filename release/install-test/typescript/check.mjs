// ESM install test of the npm package, on Node, Bun and Deno. run.sh sets MOCK_URL and
// EXPECTED_VERSION.
import MediSync, { NotFoundError, VERSION } from 'medisync';

const mock = process.env.MOCK_URL;
const expected = process.env.EXPECTED_VERSION;
const runtime = globalThis.Deno ? `deno ${Deno.version.deno}` : globalThis.Bun ? `bun ${Bun.version}` : `node ${process.version}`;
const control = async (path, body) =>
  (await fetch(`${mock}/__mock/${path}`, body ? { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) } : {})).json();
const check = (ok, what) => {
  if (!ok) throw new Error(`${runtime}: ${what}`);
};

check(VERSION === expected, `VERSION is ${VERSION}, expected ${expected}`);
const client = new MediSync({ apiKey: 'msk_test_install', baseUrl: mock });

await control('reset', { responses: [] });
const models = await client.models.list();
check(Array.isArray(models.data), 'models.list gave no data');
const systems = await client.connect.systems.list({ limit: 2 });
check(Array.isArray(systems.data), 'connect.systems.list gave no data');
const log = await control('requests');
check(log.length === 2, `the mock saw ${log.length} requests, expected 2`);
for (const r of log) {
  check(r.problems.length === 0, `${r.method} ${r.path}: ${r.problems.join('; ')}`);
  check(r.headers['user-agent'] === `medisync-typescript/${expected}`, `User-Agent ${r.headers['user-agent']}`);
}

await control('reset', { responses: [{ status: 404, body: { type: 'error', error: { type: 'not_found_error', code: 'not_found', message: 'No such site.', request_id: 'req_install', param: null }, request_id: 'req_install' } }] });
const err = await client.connect.sites.get('ste_missing').then(() => null, (e) => e);
check(err instanceof NotFoundError, `a 404 gave ${err?.constructor?.name}`);
check(err.code === 'not_found' && err.requestId === 'req_install', `error code ${err.code}, request ID ${err.requestId}`);

console.log(`${runtime} esm: medisync ${VERSION} ok`);
