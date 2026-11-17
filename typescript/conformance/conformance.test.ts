// Runs the shared conformance cases of ../../conformance/cases against the mock server.
// Starts the mock with bun (BUN or `bun` on PATH), or uses MEDISYNC_MOCK_URL.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { after, before, describe, test } from 'node:test';
import WS from 'ws';
import { MediSync, OPERATIONS, toFile, type StreamEvent, type WebSocketLike } from '../src/index.ts';
import { startMock, type Mock } from './mock.ts';

type Json = any;
const CASES = new URL('../../conformance/cases/', import.meta.url);
const files = readdirSync(CASES).filter((f) => f.endsWith('.json'));
const load = (file: string): Json[] => JSON.parse(readFileSync(new URL(file, CASES), 'utf8')).cases;

// Node 20 has no global WebSocket: there the SDK takes the one of the `ws` package.
const NO_GLOBAL_WEBSOCKET = typeof (globalThis as { WebSocket?: unknown }).WebSocket === 'undefined';

let mock: Mock;
before(async () => {
  mock = await startMock();
});
after(async () => {
  await mock?.stop();
});

function client(options: Json = {}, sleeps: number[] = []) {
  return new MediSync({
    apiKey: 'msk_test_conformance',
    baseUrl: mock.url,
    maxRetries: options.maxRetries ?? 2,
    ...(options.version ? { version: options.version } : {}),
    sleep: async (ms) => {
      sleeps.push(ms);
    },
  });
}

function value(v: Json): Json {
  if (Array.isArray(v)) return v.map(value);
  if (v && typeof v === 'object') {
    if (v.$file) return toFile(new Uint8Array(v.$file.bytes), v.$file.filename);
    return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, value(x)]));
  }
  return v;
}

function method(c: MediSync, dotted: string): (...args: unknown[]) => unknown {
  const parts = dotted.split('.');
  const name = parts.pop() as string;
  const owner = parts.reduce<Json>((node, p) => node[p], c);
  return (owner[name] as (...args: unknown[]) => unknown).bind(owner);
}

async function call(c: MediSync, spec: Json): Promise<Json> {
  const meta = (OPERATIONS as Json)[spec.method];
  const args = spec.args ?? {};
  const list: unknown[] = [...(args.path ?? [])];
  if (meta.body) list.push(value(args.body ?? {}));
  else if (meta.query) list.push(args.query ? value(args.query) : undefined);
  list.push(args.options ?? {});
  const result = method(c, spec.method)(...list) as Json;
  if (spec.mode === 'iterate') {
    const items: Json[] = [];
    for await (const item of result) items.push(item);
    return { items };
  }
  return await result;
}

function matches(expected: Json, actual: Json): boolean {
  if (typeof expected === 'string' && expected.startsWith('~')) return typeof actual === 'string' && new RegExp(expected.slice(1)).test(actual);
  return JSON.stringify(expected) === JSON.stringify(actual);
}

function partial(expected: Json, actual: Json, where: string): void {
  if (expected && typeof expected === 'object' && !Array.isArray(expected)) {
    assert.ok(actual && typeof actual === 'object', `${where}: expected an object, got ${JSON.stringify(actual)}`);
    for (const [k, v] of Object.entries(expected)) partial(v, actual[k], `${where}.${k}`);
    return;
  }
  assert.ok(matches(expected, actual), `${where}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}

function checkRequest(exp: Json, req: Json, i: number): void {
  const at = `request ${i}`;
  assert.ok(req, `${at}: missing`);
  if (exp.method) assert.equal(req.method, exp.method, `${at}: method`);
  if (exp.path) assert.equal(req.path, exp.path, `${at}: path`);
  if (exp.query) partial(exp.query, req.query, `${at}.query`);
  for (const k of exp.absentQuery ?? []) assert.ok(!(k in req.query), `${at}: query ${k} must be absent`);
  if (exp.rawQuery) assert.ok(matches(exp.rawQuery, req.rawQuery), `${at}: raw query ${req.rawQuery}`);
  if (exp.headers) partial(exp.headers, req.headers, `${at}.headers`);
  for (const h of exp.absentHeaders ?? []) assert.ok(!(h in req.headers), `${at}: header ${h} must be absent`);
  if (exp.body) assert.deepEqual(req.body, exp.body, `${at}: body`);
  if (exp.multipart) partial(exp.multipart, req.multipart, `${at}.multipart`);
  if (exp.problems) assert.deepEqual(req.problems, exp.problems, `${at}: the mock found problems`);
}

function checkResult(exp: Json, result: Json): void {
  if (exp.notNull) assert.ok(result !== undefined && result !== null, 'result: expected a value');
  if (exp.null) assert.ok(result === undefined || result === null, 'result: expected nothing');
  if (exp.bytes !== undefined) {
    assert.ok(result instanceof Blob, 'result: expected a Blob');
    assert.equal(result.size, exp.bytes);
  }
  if (exp.text) assert.equal(typeof result, 'string');
  if (exp.textStarts) assert.ok(String(result).startsWith(exp.textStarts));
  if (exp.fields) partial(exp.fields, result, 'result');
  if (exp.items) assert.deepEqual(result.items.map((x: Json) => x.id), exp.items);
  if (exp.pageIds) assert.deepEqual(result.data.map((x: Json) => x.id), exp.pageIds);
  if ('nextCursor' in exp) assert.equal(result.next_cursor, exp.nextCursor);
}

const ERROR_FIELDS: Record<string, (e: Json) => Json> = {
  status: (e) => e.status,
  code: (e) => e.code,
  type: (e) => e.type,
  requestId: (e) => e.requestId,
  param: (e) => e.param,
  verb: (e) => e.verb,
  reason: (e) => e.reason,
  alternative: (e) => e.alternative,
};

function checkError(exp: Json, err: Json): void {
  assert.ok(err, `expected ${exp.class}, got no error`);
  assert.equal(err.constructor.name, exp.class, `error class: ${err.constructor.name} ${err.message}`);
  for (const [k, read] of Object.entries(ERROR_FIELDS)) if (k in exp) assert.deepEqual(read(err), exp[k], `error.${k}`);
  if (exp.codeOneOf) assert.ok(exp.codeOneOf.includes(err.code), `error.code ${err.code}`);
}

function checkSleeps(exp: Json[], sleeps: number[]): void {
  assert.equal(sleeps.length, exp.length, `sleeps: ${JSON.stringify(sleeps)}`);
  exp.forEach((e, i) => {
    const s = sleeps[i] as number;
    if (typeof e === 'number') assert.equal(Math.round(s), e, `sleep ${i}`);
    else assert.ok(s >= e.min && s <= e.max, `sleep ${i}: ${s} not in ${e.min}..${e.max}`);
  });
}

for (const file of files.filter((f) => f !== 'live.json')) {
  describe(`conformance ${file}`, () => {
    for (const c of load(file)) {
      test(c.name, async () => {
        await mock.reset({ responses: c.responses ?? [] });
        const sleeps: number[] = [];
        let result: Json;
        let error: Json;
        try {
          result = await call(client(c.client, sleeps), c.call);
        } catch (e) {
          error = e;
        }
        const exp = c.expect;
        if (!exp.error && error) throw error;
        const requests = await mock.requests();
        if (exp.requestCount !== undefined) assert.equal(requests.length, exp.requestCount, 'request count');
        (exp.requests ?? []).forEach((r: Json, i: number) => checkRequest(r, requests[i], i));
        if (exp.sameHeaderAcrossRequests) {
          const values = new Set(requests.map((r: Json) => r.headers[exp.sameHeaderAcrossRequests]));
          assert.equal(values.size, 1, `${exp.sameHeaderAcrossRequests} differs between attempts`);
        }
        if (exp.sleepsMs) checkSleeps(exp.sleepsMs, sleeps);
        if (exp.result) checkResult(exp.result, result);
        if (exp.error) checkError(exp.error, error);
      });
    }
  });
}

describe('conformance live.json', () => {
  for (const c of load('live.json')) {
    test(c.name, async () => {
      await mock.reset({ ws: c.ws ?? {} });
      const c1 = client();
      const events: StreamEvent[] = [];
      let error: Json;
      let reading: Promise<void> | null = null;
      try {
        let stream: Json = null;
        for (const step of c.steps) {
          if (step.open) {
            stream = await c1.audio.stream.open({ ...(NO_GLOBAL_WEBSOCKET ? { WebSocket: WS as unknown as WebSocketLike } : {}), ...(step.open.config ? { config: step.open.config } : {}), ...(step.open.token ? { token: step.open.token } : {}) });
            reading = (async () => {
              for await (const e of stream) events.push(e);
            })();
          } else if (step.setTerms) {
            stream.setTerms(step.setTerms);
          } else if (step.sendAudio) {
            const frame = new Uint8Array(Math.round(step.sendAudio.frameSeconds * 32000));
            const n = Math.round(step.sendAudio.seconds / step.sendAudio.frameSeconds);
            for (let i = 0; i < n; i++) stream.send(frame);
          } else if (step.finish) {
            await stream.finish();
          } else if (step.close) {
            stream.close();
          }
        }
        await reading;
      } catch (e) {
        error = e;
      }
      const exp = c.expect;
      if (!exp.error && error) throw error;
      if (exp.error) checkError(exp.error, error);
      const log = await mock.ws();
      if (exp.connectionCount !== undefined) assert.equal(log.length, exp.connectionCount, 'connections');
      (exp.connections ?? []).forEach((e: Json, i: number) => {
        const conn = log[i];
        assert.ok(conn, `connection ${i} missing`);
        if (e.headers) partial(e.headers, conn.headers, `connection ${i}.headers`);
        for (const h of e.absentHeaders ?? []) assert.ok(!(h in conn.headers), `connection ${i}: header ${h} must be absent`);
        if (e.query) partial(e.query, conn.query, `connection ${i}.query`);
        for (const q of e.absentQuery ?? []) assert.ok(!(q in conn.query), `connection ${i}: query ${q} must be absent`);
        if (e.config) partial(e.config, conn.config, `connection ${i}.config`);
        if (e.audioBytes !== undefined) assert.equal(conn.audioBytes, e.audioBytes, `connection ${i}: audio bytes`);
        if (e.firstAudioAt !== undefined) assert.ok(Math.abs(conn.firstAudioAt - e.firstAudioAt) < 1e-6, `connection ${i}: first audio at ${conn.firstAudioAt}`);
        if (e.messageTypes) assert.deepEqual(conn.messages.map((m: Json) => m.type), e.messageTypes, `connection ${i}: message types`);
        if (e.terms) assert.deepEqual(conn.messages.find((m: Json) => m.type === 'terms')?.terms, e.terms);
      });
      const types = events.map((e) => e.type);
      if (exp.eventTypes) assert.deepEqual(types, exp.eventTypes, 'event types');
      for (const t of exp.eventTypesInclude ?? []) assert.ok(types.includes(t as never), `event ${t} missing in ${types.join(', ')}`);
      if (exp.lastSegmentEnd !== undefined) {
        const ends = events.filter((e) => e.type === 'transcript').map((e) => (e as Json).segment.end);
        assert.equal(Math.max(...ends), exp.lastSegmentEnd);
      }
    });
  }
});
