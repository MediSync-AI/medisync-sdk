// The HTTP core with a fetch that answers from a list: auth, defaults, retries, timeouts, errors.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  APIConnectionError,
  APITimeoutError,
  AuthenticationError,
  GatewayTimeoutError,
  InternalServerError,
  MediSync,
  MediSyncError,
  PermissionDeniedError,
  ProductNotEnabledError,
  RateLimitError,
  ServiceUnavailableError,
  isFinalTransferStatus,
} from '../src/index.ts';
import { json, mockApi, transfer } from './support.ts';

describe('client options', () => {
  test('reads MEDISYNC_API_KEY and MEDISYNC_BASE_URL, and refuses no key at all', async () => {
    const saved = { key: process.env.MEDISYNC_API_KEY, url: process.env.MEDISYNC_BASE_URL };
    try {
      process.env.MEDISYNC_API_KEY = 'msk_test_from_env';
      process.env.MEDISYNC_BASE_URL = 'https://env.example.test/';
      const calls: Request[] = [];
      const c = new MediSync({ fetch: async (r) => (calls.push(r), json(200, { data: [] })) });
      assert.equal(c.baseUrl, 'https://env.example.test');
      await c.models.list();
      assert.equal(calls[0]?.headers.get('authorization'), 'Bearer msk_test_from_env');
      delete process.env.MEDISYNC_API_KEY;
      assert.throws(() => new MediSync(), MediSyncError);
    } finally {
      if (saved.key === undefined) delete process.env.MEDISYNC_API_KEY;
      else process.env.MEDISYNC_API_KEY = saved.key;
      if (saved.url === undefined) delete process.env.MEDISYNC_BASE_URL;
      else process.env.MEDISYNC_BASE_URL = saved.url;
    }
  });

  test('sends default headers and per-call headers, and reports each answer', async () => {
    const seen: string[] = [];
    const { client, calls } = mockApi([json(200, { data: [] }, { 'request-id': 'req_1' })], {
      defaultHeaders: { 'x-team': 'praxis-beispiel' },
      onResponse: (info) => seen.push(`${info.operation}:${info.status}:${info.requestId}`),
    });
    await client.models.list({ headers: { 'x-trace': 'abc' } });
    assert.equal(calls[0]?.headers.get('x-team'), 'praxis-beispiel');
    assert.equal(calls[0]?.headers.get('x-trace'), 'abc');
    assert.deepEqual(seen, ['listModels:200:req_1']);
  });
});

describe('retries', () => {
  test('a network error is retried, then thrown as APIConnectionError', async () => {
    const fail = () => {
      throw new TypeError('fetch failed');
    };
    const { client, calls } = mockApi([fail, fail, fail], { maxRetries: 2 });
    await assert.rejects(client.models.list(), APIConnectionError);
    assert.equal(calls.length, 3);
  });

  test('a POST without an Idempotency-Key is never retried: none such exists, every POST gets one', async () => {
    const { client, calls } = mockApi([json(503, {}), json(200, { result: [] })]);
    await client.decisions.create({ text: 'x', questions: {} } as never);
    assert.equal(calls.length, 2);
    assert.equal(calls[0]?.headers.get('idempotency-key'), calls[1]?.headers.get('idempotency-key'));
  });

  test('maxRetries per call overrides the client', async () => {
    const { client, calls } = mockApi([json(503, {}), json(503, {})], { maxRetries: 5 });
    await assert.rejects(client.models.list({ maxRetries: 1 }), ServiceUnavailableError);
    assert.equal(calls.length, 2);
  });

  test('a timeout per attempt throws APITimeoutError after the retries', async () => {
    // AbortSignal.timeout does not keep the event loop alive; a real socket would.
    const slow = (req: Request) =>
      new Promise<Response>((_resolve, reject) => {
        const alive = setTimeout(() => {}, 5_000);
        req.signal.addEventListener('abort', () => {
          clearTimeout(alive);
          reject(req.signal.reason);
        });
      });
    const { client, calls } = mockApi([slow, slow], { maxRetries: 1, timeoutMs: 20 });
    await assert.rejects(client.models.list(), APITimeoutError);
    assert.equal(calls.length, 2);
  });

  test('an abort of the caller stops at once', async () => {
    const controller = new AbortController();
    const hang = (req: Request) =>
      new Promise<Response>((_r, reject) => {
        if (req.signal.aborted) return reject(new DOMException('aborted', 'AbortError'));
        const alive = setTimeout(() => {}, 5_000);
        req.signal.addEventListener('abort', () => {
          clearTimeout(alive);
          reject(new DOMException('aborted', 'AbortError'));
        });
      });
    const { client, calls } = mockApi([hang, hang], { maxRetries: 3 });
    const p = client.models.list({ signal: controller.signal });
    controller.abort();
    await assert.rejects(p);
    assert.equal(calls.length, 1);
  });

  test('a retry-after date is honoured', async () => {
    const at = new Date(Date.now() + 3_000).toUTCString();
    const { client, sleeps } = mockApi([json(429, { type: 'error', error: { type: 'rate_limit_error', code: 'rate_limit_exceeded', message: 'x', param: null, request_id: 'r' }, request_id: 'r' }, { 'retry-after': at }), json(200, { data: [] })]);
    await client.models.list();
    assert.equal(sleeps.length, 1);
    assert.ok((sleeps[0] as number) > 1_000 && (sleeps[0] as number) <= 3_000, String(sleeps[0]));
  });
});

describe('gateway and Connect answers', () => {
  test('reads both error shapes and the request id of the header', async () => {
    const { client } = mockApi(
      [
        json(403, { type: 'error', error: { type: 'permission_error', code: 'product_not_enabled', message: 'Off.', param: null, request_id: 'req_01' }, request_id: 'req_01' }),
        json(403, { error: { type: 'permission_error', code: 'forbidden', message: 'No.', request_id: 'req_02', reason: 'role' } }),
        new Response('upstream broke', { status: 500, headers: { 'request-id': 'req_from_header' } }),
        json(504, {}),
      ],
      { maxRetries: 0 },
    );
    const off = await client.connect.systems.list().catch((e: unknown) => e);
    assert.ok(off instanceof ProductNotEnabledError && off instanceof PermissionDeniedError);
    assert.equal(off.requestId, 'req_01');
    const forbidden = await client.connect.sites.list().catch((e: unknown) => e);
    assert.ok(forbidden instanceof PermissionDeniedError);
    assert.equal(forbidden.error?.reason, 'role');
    const broken = await client.connect.sites.list().catch((e: unknown) => e);
    assert.ok(broken instanceof InternalServerError);
    assert.equal(broken.requestId, 'req_from_header');
    assert.equal(broken.message, 'upstream broke');
    await assert.rejects(client.connect.transfers.get('trf_1'), GatewayTimeoutError);
  });

  test('RateLimitError reads retry-after; AuthenticationError has the code', async () => {
    const { client } = mockApi(
      [
        json(429, { error: { type: 'rate_limit_error', code: 'rate_limited', message: 'Wait.', request_id: 'r', retry_after: 12 } }),
        json(401, { type: 'error', error: { type: 'authentication_error', code: 'api_key_revoked', message: 'Revoked.', param: null, request_id: 'r2' }, request_id: 'r2' }),
      ],
      { maxRetries: 0 },
    );
    const limited = await client.connect.sites.list().catch((e: unknown) => e);
    assert.ok(limited instanceof RateLimitError);
    assert.equal(limited.retryAfter, 12);
    assert.equal(limited.retryable, true);
    const revoked = await client.models.list().catch((e: unknown) => e);
    assert.ok(revoked instanceof AuthenticationError);
    assert.equal(revoked.code, 'api_key_revoked');
  });

  test('passes unknown fields and enum values through untouched', async () => {
    const { client } = mockApi([json(200, transfer('archived', { priority: 'high' }))]);
    const t = await client.connect.transfers.get('trf_7Yc3Nd9LxB');
    assert.equal(t.status, 'archived');
    assert.equal((t as unknown as Record<string, unknown>).priority, 'high');
    assert.equal(isFinalTransferStatus(t.status), false);
  });
});

describe('pages', () => {
  test('await gives one page with the other list fields; for await reads every page', async () => {
    const { client, calls } = mockApi([
      json(200, { data: [{ id: 'ste_A' }, { id: 'ste_B' }], next_cursor: 'c2', extra: 1 }),
      json(200, { data: [{ id: 'ste_A' }, { id: 'ste_B' }], next_cursor: 'c2' }),
      json(200, { data: [{ id: 'ste_C' }], next_cursor: null }),
    ]);
    const page = await client.connect.sites.list({ limit: 2 });
    assert.deepEqual(page.data.map((s) => s.id), ['ste_A', 'ste_B']);
    assert.equal(page.hasNextPage(), true);
    assert.equal((page as unknown as { extra: number }).extra, 1);
    const ids: string[] = [];
    for await (const site of client.connect.sites.list({ limit: 2 })) ids.push(site.id);
    assert.deepEqual(ids, ['ste_A', 'ste_B', 'ste_C']);
    assert.deepEqual(calls.map((c) => new URL(c.url).search), ['?limit=2', '?limit=2', '?limit=2&cursor=c2']);
  });
});
