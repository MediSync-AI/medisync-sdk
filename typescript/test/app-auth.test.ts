// App mode of MediSync Connect: the SDK signs the client assertion and keeps the access token.
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { describe, test } from 'node:test';
import { AppAuthError, AppTokenSource, AuthenticationError, clientAssertion, MediSync, MediSyncError } from '../src/index.ts';
import { json } from './support.ts';

const APP = 'app_6Tn3Cw9KhZaB';
const ec = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const ed = generateKeyPairSync('ed25519');
const pem = (k: typeof ec.privateKey) => k.export({ format: 'pem', type: 'pkcs8' }).toString();

function parts(jwt: string) {
  const [h, p, s] = jwt.split('.') as [string, string, string];
  return { header: JSON.parse(Buffer.from(h, 'base64url').toString()), payload: JSON.parse(Buffer.from(p, 'base64url').toString()), input: `${h}.${p}`, signature: Buffer.from(s, 'base64url') };
}

type Seen = { url: string; form: URLSearchParams | null; auth: string | null };
function tokenServer(answers: Array<(n: number) => Response>) {
  const seen: Seen[] = [];
  let n = 0;
  const fetch = async (req: Request) => {
    const text = req.body ? await req.text() : '';
    seen.push({ url: req.url, form: req.url.includes('/oauth/') ? new URLSearchParams(text) : null, auth: req.headers.get('authorization') });
    const next = answers.shift();
    if (!next) throw new Error('no more answers');
    return next(++n);
  };
  return { fetch, seen };
}

describe('client assertion (RFC 7523)', () => {
  test('signs ES256 in the JWS form with iss, sub, aud, exp and a one-time jti', async () => {
    const jwt = await clientAssertion({ clientId: APP, keyId: 'k1', privateKey: pem(ec.privateKey) }, 'https://connect.medisync.me/oauth/token', 1_800_000_000);
    const { header, payload, input, signature } = parts(jwt);
    assert.deepEqual(header, { alg: 'ES256', kid: 'k1', typ: 'JWT' });
    assert.equal(payload.iss, APP);
    assert.equal(payload.sub, APP);
    assert.equal(payload.aud, 'https://connect.medisync.me/oauth/token');
    assert.equal(payload.exp, 1_800_000_060);
    assert.match(payload.jti, /^[0-9a-f-]{36}$/);
    assert.equal(signature.length, 64);
    assert.ok(verify('sha256', Buffer.from(input), { key: ec.publicKey, dsaEncoding: 'ieee-p1363' }, signature));
  });

  test('signs EdDSA with an Ed25519 key and refuses other keys', async () => {
    const { header, input, signature } = parts(await clientAssertion({ clientId: APP, keyId: 'k2', privateKey: pem(ed.privateKey) }, 'aud'));
    assert.equal(header.alg, 'EdDSA');
    assert.ok(verify(null, Buffer.from(input), ed.publicKey, signature));
    const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 });
    await assert.rejects(clientAssertion({ clientId: APP, keyId: 'k3', privateKey: pem(rsa.privateKey) }, 'aud'), /P-256 or an Ed25519/);
    await assert.rejects(clientAssertion({ clientId: APP, keyId: 'k3', privateKey: 'not a pem' }, 'aud'), /PKCS8 PEM/);
  });
});

describe('AppTokenSource', () => {
  test('fetches a token once, shares it, and gets a new one a minute before it expires', async () => {
    let now = 1_000_000;
    const { fetch, seen } = tokenServer([(n) => json(200, { access_token: `tok-${n}`, token_type: 'Bearer', expires_in: 900 }), (n) => json(200, { access_token: `tok-${n}`, token_type: 'Bearer', expires_in: 900 })]);
    const source = new AppTokenSource({ clientId: APP, keyId: 'k1', privateKey: pem(ec.privateKey), installationId: 'ins_3Rv8Qa2MxK', verbs: ['patient.context'] }, fetch, () => now);
    assert.deepEqual(await Promise.all([source.get(), source.get()]), ['tok-1', 'tok-1']);
    assert.equal(seen.length, 1);
    const form = seen[0]?.form as URLSearchParams;
    assert.equal(form.get('grant_type'), 'client_credentials');
    assert.equal(form.get('client_assertion_type'), 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer');
    assert.deepEqual(JSON.parse(form.get('authorization_details') as string), [{ type: 'medisync_connect', installation_id: 'ins_3Rv8Qa2MxK', verbs: [{ verb: 'patient.context', roles: [] }] }]);
    now += 800_000;
    assert.equal(await source.get(), 'tok-1');
    now += 50_000;
    assert.equal(await source.get(), 'tok-2');
  });

  test('names the environment of an app token, and throws the OAuth error', async () => {
    const { fetch, seen } = tokenServer([() => json(401, { error: 'invalid_client', error_description: 'The client assertion did not verify.' })]);
    const source = new AppTokenSource({ clientId: APP, keyId: 'k1', privateKey: pem(ec.privateKey), environment: 'test', issuer: 'https://connect.example.test/' }, fetch);
    const err = await source.get().catch((e: unknown) => e);
    assert.ok(err instanceof AppAuthError);
    assert.equal(err.status, 401);
    assert.equal(err.error, 'invalid_client');
    assert.equal(seen[0]?.url, 'https://connect.example.test/oauth/token');
    assert.equal(seen[0]?.form?.get('environment'), 'test');
    assert.throws(() => new AppTokenSource({ clientId: 'proj_x', keyId: 'k', privateKey: '' }), /app_/);
  });

  test('revokes its token at Connect', async () => {
    const { fetch, seen } = tokenServer([() => json(200, { access_token: 'tok-1', token_type: 'Bearer', expires_in: 900 }), () => json(200, {})]);
    const source = new AppTokenSource({ clientId: APP, keyId: 'k1', privateKey: pem(ec.privateKey) }, fetch);
    await source.get();
    await source.revoke();
    assert.equal(seen[1]?.url, 'https://connect.medisync.me/oauth/revoke');
    assert.equal(seen[1]?.form?.get('token'), 'tok-1');
    assert.equal(source.answer, null);
  });
});

describe('the client in app mode', () => {
  test('sends the access token to Connect, and gets a new one after 401 invalid_token', async () => {
    const { fetch, seen } = tokenServer([
      (n) => json(200, { access_token: `tok-${n}`, token_type: 'Bearer', expires_in: 900 }),
      () => json(200, { data: [], next_cursor: null }),
      () => json(401, { error: { type: 'authentication_error', code: 'invalid_token', message: 'The access token was revoked.', request_id: 'req_x' } }),
      (n) => json(200, { access_token: `tok-${n}`, token_type: 'Bearer', expires_in: 900 }),
      () => json(200, { data: [], next_cursor: null }),
    ]);
    const client = new MediSync({ app: { clientId: APP, keyId: 'k1', privateKey: pem(ec.privateKey) }, baseUrl: 'https://api.example.test', fetch });
    await client.connect.installations.list();
    assert.equal(seen[1]?.url, 'https://api.example.test/v1/connect/installations');
    assert.equal(seen[1]?.auth, 'Bearer tok-1');
    // The refused token is dropped, a new one fetched, and the call sent again once.
    await client.connect.sites.list();
    assert.equal(seen[3]?.url, 'https://connect.medisync.me/oauth/token');
    assert.equal(seen[4]?.auth, 'Bearer tok-4');
  });

  test('a second invalid_token is thrown', async () => {
    const refused = () => json(401, { error: { type: 'authentication_error', code: 'invalid_token', message: 'No.', request_id: 'req_y' } });
    const { fetch } = tokenServer([(n) => json(200, { access_token: `tok-${n}`, expires_in: 900 }), refused, (n) => json(200, { access_token: `tok-${n}`, expires_in: 900 }), refused]);
    const client = new MediSync({ app: { clientId: APP, keyId: 'k1', privateKey: pem(ec.privateKey) }, baseUrl: 'https://api.example.test', fetch });
    await assert.rejects(client.connect.sites.list(), AuthenticationError);
  });

  test('a refused token reaches the caller as AppAuthError, without retries', async () => {
    const { fetch, seen } = tokenServer([() => json(400, { error: 'invalid_request', error_description: 'The app has no live project.' })]);
    const client = new MediSync({ app: { clientId: APP, keyId: 'k1', privateKey: pem(ec.privateKey), environment: 'live' }, fetch, sleep: async () => {} });
    await assert.rejects(client.connect.systems.list(), AppAuthError);
    assert.equal(seen.length, 1);
  });

  test('platform calls need an API key; with both, Connect uses the app token', async () => {
    const { fetch, seen } = tokenServer([() => json(200, { data: [] }), (n) => json(200, { access_token: `tok-${n}`, expires_in: 900 }), () => json(200, { data: [], next_cursor: null })]);
    const appOnly = new MediSync({ app: { clientId: APP, keyId: 'k1', privateKey: pem(ec.privateKey) }, fetch, apiKey: '' });
    await assert.rejects(appOnly.models.list(), MediSyncError);
    const both = new MediSync({ apiKey: 'msk_test_both', app: { clientId: APP, keyId: 'k1', privateKey: pem(ec.privateKey) }, fetch });
    await both.models.list();
    await both.connect.sites.list();
    assert.equal(seen.find((s) => s.url.endsWith('/v1/models'))?.auth, 'Bearer msk_test_both');
    assert.equal(seen.find((s) => s.url.endsWith('/v1/connect/sites'))?.auth, 'Bearer tok-2');
  });
});
