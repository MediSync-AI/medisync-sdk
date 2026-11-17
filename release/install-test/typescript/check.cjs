// CommonJS install test of the npm package on Node. run.sh sets MOCK_URL and EXPECTED_VERSION.
const { MediSync, NotFoundError, VERSION } = require('medisync');

async function main() {
  if (VERSION !== process.env.EXPECTED_VERSION) throw new Error(`VERSION is ${VERSION}, expected ${process.env.EXPECTED_VERSION}`);
  if (typeof NotFoundError !== 'function') throw new Error('NotFoundError is missing');
  const client = new MediSync({ apiKey: 'msk_test_install', baseUrl: process.env.MOCK_URL });
  const systems = await client.connect.systems.list({ limit: 2 });
  if (!Array.isArray(systems.data)) throw new Error('connect.systems.list gave no data');
  console.log(`node ${process.version} cjs: medisync ${VERSION} ok`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
