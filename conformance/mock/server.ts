// A mock of the MediSync API built from specs/: every operation of both OpenAPI specs and the
// live stream of the AsyncAPI document. Local only, for the conformance tests of every language.
//
//   bun conformance/mock/server.ts [--port 0]
//
// It prints `MOCK_URL=http://127.0.0.1:<port>` on its first line. Control endpoints:
//   POST /__mock/reset     {"responses": [...], "ws": {...}}  clears the log and scripts answers
//   GET  /__mock/requests  the HTTP requests since the last reset, with the problems found in them
//   GET  /__mock/ws        the live stream connections since the last reset
//
// A scripted response is {"status", "headers", "body"} (JSON) or {"status", "text"} or
// {"status", "base64"}. Requests take scripted responses in order; with none left, the mock
// answers from the spec: a sample of the success schema.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ServerWebSocket } from 'bun';
import { resolve, sample, validate } from './schema.ts';

type Json = any;
const ROOT = join(import.meta.dir, '..', '..');
const specs = {
  platform: JSON.parse(readFileSync(join(ROOT, 'specs/openapi.json'), 'utf8')),
  connect: JSON.parse(readFileSync(join(ROOT, 'specs/connect-openapi.json'), 'utf8')),
};
const asyncapi = Bun.YAML.parse(readFileSync(join(ROOT, 'specs/asyncapi.yaml'), 'utf8')) as Json;

interface Route {
  doc: Json;
  method: string;
  template: string;
  regex: RegExp;
  names: string[];
  op: Json;
}

const routes: Route[] = [];
for (const doc of [specs.platform, specs.connect]) {
  for (const [template, item] of Object.entries<Json>(doc.paths)) {
    for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
      const op = item[method];
      if (!op) continue;
      const names: string[] = [];
      const regex = new RegExp(`^${template.replace(/\{([^}]+)\}/g, (_m, n: string) => (names.push(n), '([^/]+)'))}$`);
      routes.push({ doc, method: method.toUpperCase(), template, regex, names, op: { ...op, parameters: [...(item.parameters ?? []), ...(op.parameters ?? [])] } });
    }
  }
}

interface Scripted {
  status: number;
  headers?: Record<string, string>;
  body?: Json;
  text?: string;
  base64?: string;
}

interface WsScript {
  /** Answer the handshake of connection n (1-based) with this HTTP status and error body. */
  reject?: { connection?: number; status: number; body: Json; headers?: Record<string, string> };
  /** On connection n, after this many seconds of audio, send a fatal error and close. */
  fatal?: { connection?: number; afterSeconds: number; code: string; closeCode?: number };
  /** On connection n, close the socket after this many seconds of audio, without a message. */
  drop?: { connection?: number; afterSeconds: number; closeCode?: number };
  /** Answer the config with a fatal error instead of session_ready. */
  configError?: { code: string; message: string; param?: string };
  /** Seconds per transcript segment. Default 1. */
  segmentSeconds?: number;
  /**
   * The answers to the n-th `end_utterance` of a connection: `replies[n]`, or the last entry.
   * Default `["ack", "tail", "flushed"]`. `tail` is the transcript of the audio not yet
   * transcribed, `flushed` has the ack's `processed_until`, `rolesFinal` is a final
   * `speaker_roles`. `{ "delayMs": n }` waits, `{ "send": {...} }` sends a message as it is,
   * `{ "close": code }` closes the socket. A server before 23.11.2026 is `["ack", "tail"]`.
   */
  endUtterance?: { connection?: number; replies: Reply[][] };
}

type Reply = 'ack' | 'tail' | 'flushed' | 'rolesFinal' | { delayMs: number } | { send: Json } | { close: number };

interface WsLog {
  connection: number;
  query: Record<string, string>;
  headers: Record<string, string>;
  config: Json;
  messages: Json[];
  audioBytes: number;
  /** The first audio seconds after the offset, by frame: [start, end] on the stream timeline. */
  firstAudioAt: number | null;
  closed: { code: number; reason: string } | null;
  /** Problems of the config against the AsyncAPI schema. */
  problems?: string[];
}

let scripted: Scripted[] = [];
let wsScript: WsScript = {};
let requests: Json[] = [];
let wsLog: WsLog[] = [];

function error(status: number, type: string, code: string, message: string, param: string | null = null, extra: Record<string, string> = {}) {
  const requestId = `req_mock_${Math.random().toString(36).slice(2, 10)}`;
  return Response.json({ type: 'error', error: { type, code, message, param, request_id: requestId }, request_id: requestId }, { status, headers: { 'request-id': requestId, ...extra } });
}

function paramSchema(doc: Json, p: Json): Json {
  const r = resolve(doc, p);
  return { ...r, schema: resolve(doc, r.schema) };
}

function checkParams(route: Route, url: URL, headers: Headers): string[] {
  const problems: string[] = [];
  for (const raw of route.op.parameters as Json[]) {
    const p = paramSchema(route.doc, raw);
    if (p.in === 'query') {
      const values = url.searchParams.getAll(p.name);
      if (p.required && values.length === 0) problems.push(`query ${p.name}: is required`);
      if (values.length > 1 && p.schema?.type !== 'array') problems.push(`query ${p.name}: sent ${values.length} times`);
      if (p.schema?.type === 'array' && p.explode === false && values.length > 1) problems.push(`query ${p.name}: must be one comma-separated value`);
    }
    if (p.in === 'header' && p.required && !headers.get(p.name)) problems.push(`header ${p.name}: is required`);
  }
  const known = new Set((route.op.parameters as Json[]).map((raw) => paramSchema(route.doc, raw)).filter((p) => p.in === 'query').map((p) => p.name));
  for (const key of url.searchParams.keys()) if (!known.has(key)) problems.push(`query ${key}: is not a parameter`);
  return problems;
}

async function readBody(route: Route, request: Request): Promise<{ body: Json; problems: string[]; multipart?: Json }> {
  const rb = route.op.requestBody;
  const type = request.headers.get('content-type') ?? '';
  if (!rb) {
    const text = await request.text();
    return { body: text || null, problems: text ? ['body: the operation takes none'] : [] };
  }
  const ct = Object.keys(rb.content)[0] as string;
  const schema = rb.content[ct].schema;
  if (ct === 'application/json') {
    if (!type.startsWith('application/json')) return { body: await request.text(), problems: [`content-type: is ${type}, not application/json`] };
    const text = await request.text();
    let body: Json;
    try {
      body = text ? JSON.parse(text) : undefined;
    } catch {
      return { body: text, problems: ['body: is not JSON'] };
    }
    if (body === undefined) return { body, problems: rb.required ? ['body: is required'] : [] };
    return { body, problems: validate(route.doc, schema, body, 'body') };
  }
  if (!type.startsWith('multipart/form-data')) return { body: await request.text(), problems: [`content-type: is ${type}, not multipart/form-data`] };
  const form = await request.formData();
  const fields: Record<string, Json> = {};
  for (const [key, value] of form.entries()) {
    fields[key] = typeof value === 'string' ? value : { filename: (value as File).name, size: (value as File).size, type: (value as File).type };
  }
  const s = resolve(route.doc, schema);
  const problems = (s.required ?? []).filter((k: string) => !(k in fields)).map((k: string) => `form ${k}: is required`);
  for (const key of Object.keys(fields)) if (s.properties && !(key in s.properties)) problems.push(`form ${key}: is not a field`);
  return { body: null, multipart: fields, problems };
}

function defaultResponse(route: Route): Response {
  const ok = Object.entries<Json>(route.op.responses).find(([code]) => /^2\d\d$/.test(code));
  if (!ok) {
    if (route.template.endsWith('.json')) return Response.json(specs.platform);
    if (route.template.endsWith('.yaml')) return new Response(readFileSync(join(ROOT, 'specs/asyncapi.yaml'), 'utf8'), { headers: { 'content-type': 'application/yaml' } });
    return new Response(null, { status: 204 });
  }
  const [code, res] = ok;
  const content = res.content ?? {};
  if (content['application/json']) {
    const body = sample(route.doc, content['application/json'].schema, 'response');
    if (body && typeof body === 'object' && 'next_cursor' in body) body.next_cursor = null;
    return Response.json(body, { status: Number(code) });
  }
  if (Object.keys(content).length) return new Response(new Uint8Array([37, 80, 68, 70, 45, 49, 46, 55]), { status: Number(code), headers: { 'content-type': Object.keys(content)[0] as string } });
  return new Response(null, { status: Number(code) });
}

function scriptedResponse(s: Scripted): Response {
  const headers = { 'request-id': `req_mock_${requests.length}`, ...(s.headers ?? {}) };
  if (s.base64 !== undefined) return new Response(Buffer.from(s.base64, 'base64'), { status: s.status, headers: { 'content-type': 'application/octet-stream', ...headers } });
  if (s.text !== undefined) return new Response(s.text, { status: s.status, headers: { 'content-type': 'text/plain', ...headers } });
  if (s.body === undefined || s.status === 204) return new Response(null, { status: s.status, headers });
  return Response.json(s.body, { status: s.status, headers });
}

async function handleApi(request: Request, url: URL): Promise<Response> {
  const route = routes.find((r) => r.method === request.method && r.regex.test(url.pathname));
  const headers = Object.fromEntries(request.headers.entries());
  const entry: Json = { method: request.method, path: url.pathname, rawQuery: url.search.replace(/^\?/, ''), query: Object.fromEntries(url.searchParams.entries()), headers, body: null, problems: [] };
  requests.push(entry);
  if (!route) {
    entry.problems.push(`route: ${request.method} ${url.pathname} is not in the specs`);
    return error(404, 'not_found_error', 'unknown_route', `No route ${request.method} ${url.pathname}.`);
  }
  entry.operationId = route.op.operationId;
  entry.problems.push(...checkParams(route, url, request.headers));
  const read = await readBody(route, request);
  entry.body = read.body;
  if (read.multipart) entry.multipart = read.multipart;
  entry.problems.push(...read.problems);
  if (!/^Bearer \S+$/.test(request.headers.get('authorization') ?? '')) return error(401, 'authentication_error', 'missing_api_key', 'No API key.');
  const next = scripted.shift();
  if (next) return scriptedResponse(next);
  if (entry.problems.length) return error(400, 'invalid_request_error', 'invalid_request', entry.problems.join('; '), null);
  return defaultResponse(route);
}

// The live stream.
type WsData = { log: WsLog; bytesPerSecond: number; offset: number; received: number; emittedUntil: number; seq: number; ready: boolean; utterances: number };

const DEFAULT_REPLIES: Reply[] = ['ack', 'tail', 'flushed'];

async function answerEndUtterance(ws: ServerWebSocket<WsData>, replies: Reply[]) {
  const d = ws.data;
  const until = d.offset + d.received / d.bytesPerSecond;
  for (const r of replies) {
    if (d.log.closed) return;
    if (r === 'ack') ws.send(JSON.stringify({ type: 'ack', processed_until: round(until) }));
    else if (r === 'flushed') ws.send(JSON.stringify({ type: 'flushed', processed_until: round(until) }));
    else if (r === 'tail') {
      if (until > d.emittedUntil + 1e-6) sendTranscript(ws, d.emittedUntil, until);
    } else if (r === 'rolesFinal') ws.send(JSON.stringify({ ...liveExample('speakerRoles'), final: true }));
    else if ('delayMs' in r) await Bun.sleep(r.delayMs);
    else if ('send' in r) ws.send(JSON.stringify(r.send));
    else if ('close' in r) {
      ws.close(r.close, '');
      return;
    }
  }
}

function liveExample(name: string): Json {
  return structuredClone(asyncapi.components.messages[name].examples[0].payload);
}

function connectionScript<T extends { connection?: number }>(item: T | undefined, n: number): T | undefined {
  return item && (item.connection ?? 1) === n ? item : undefined;
}

function sendTranscript(ws: ServerWebSocket<WsData>, start: number, end: number) {
  const d = ws.data;
  d.seq++;
  ws.send(
    JSON.stringify({
      type: 'transcript',
      seq: d.seq,
      segment: { start: round(start), end: round(end) },
      language_detected: 'de',
      diarization_enabled: false,
      text: `Segment ${round(start)} bis ${round(end)}.`,
      words: [],
      processed_until: round(end),
    }),
  );
  d.emittedUntil = end;
}

const round = (n: number) => Math.round(n * 1000) / 1000;

const server = Bun.serve<WsData>({
  hostname: '127.0.0.1',
  port: Number(process.argv[process.argv.indexOf('--port') + 1] || 0) || 0,
  async fetch(request, srv) {
    const url = new URL(request.url);
    if (url.pathname === '/__mock/reset' && request.method === 'POST') {
      const body = (await request.json().catch(() => ({}))) as { responses?: Scripted[]; ws?: WsScript };
      scripted = body.responses ?? [];
      wsScript = body.ws ?? {};
      requests = [];
      wsLog = [];
      return Response.json({ ok: true });
    }
    if (url.pathname === '/__mock/requests') return Response.json(requests);
    if (url.pathname === '/__mock/ws') return Response.json(wsLog);
    if (url.pathname === '/v1/audio/stream') {
      const n = wsLog.length + 1;
      const log: WsLog = { connection: n, query: Object.fromEntries(url.searchParams.entries()), headers: Object.fromEntries(request.headers.entries()), config: null, messages: [], audioBytes: 0, firstAudioAt: null, closed: null };
      wsLog.push(log);
      const reject = connectionScript(wsScript.reject, n);
      if (reject) return Response.json(reject.body, { status: reject.status, headers: { 'request-id': `req_mock_ws_${n}`, ...(reject.headers ?? {}) } });
      const auth = request.headers.get('authorization');
      const token = url.searchParams.get('token');
      if (!auth && !token) return error(401, 'authentication_error', 'missing_api_key', 'No API key or stream token.');
      if (srv.upgrade(request, { headers: { 'request-id': `req_mock_ws_${n}` }, data: { log, bytesPerSecond: 32000, offset: 0, received: 0, emittedUntil: 0, seq: 0, ready: false, utterances: 0 } })) return undefined;
      return new Response('upgrade failed', { status: 400 });
    }
    return handleApi(request, url);
  },
  websocket: {
    maxPayloadLength: 2 * 1024 * 1024,
    message(ws, message) {
      const d = ws.data;
      const n = d.log.connection;
      if (typeof message === 'string') {
        let msg: Json;
        try {
          msg = JSON.parse(message);
        } catch {
          return;
        }
        d.log.messages.push(msg);
        if (msg.type === 'config') {
          d.log.config = msg;
          const problems = validate(asyncapi, asyncapi.components.schemas.Config, msg, 'config');
          if (problems.length) {
            d.log.problems = problems;
            ws.send(JSON.stringify({ type: 'error', code: 'config_invalid', message: problems.join('; '), param: null, request_id: `req_mock_ws_${n}`, fatal: true }));
            ws.close(1008, 'config_invalid');
            return;
          }
          if (wsScript.configError) {
            const e = wsScript.configError;
            ws.send(JSON.stringify({ type: 'error', code: e.code, message: e.message, param: e.param ?? null, request_id: `req_mock_ws_${n}`, fatal: true }));
            ws.close(1008, e.code);
            return;
          }
          const audio = msg.audio ?? {};
          d.bytesPerSecond = (audio.sample_rate ?? 16000) * 2 * (audio.channels ?? 1);
          d.offset = Number(msg.timeline_offset_seconds ?? 0);
          d.emittedUntil = d.offset;
          const ready = liveExample('sessionReady');
          ready.session_id = `sess_mock_${n}`;
          ready.effective.timeline_offset_seconds = d.offset;
          ready.effective.language = msg.language ?? null;
          ready.effective.diarization = msg.diarization?.enabled === true;
          ready.effective.speaker_roles = msg.speaker_roles === true && msg.diarization?.enabled === true;
          d.ready = true;
          ws.send(JSON.stringify(ready));
          return;
        }
        if (msg.type === 'end_utterance') {
          const script = connectionScript(wsScript.endUtterance, n);
          const replies = script ? (script.replies[Math.min(d.utterances, script.replies.length - 1)] ?? DEFAULT_REPLIES) : DEFAULT_REPLIES;
          d.utterances++;
          void answerEndUtterance(ws, replies);
          return;
        }
        if (msg.type === 'close') {
          ws.close(1000, 'close');
          return;
        }
        if (msg.type !== 'terms') {
          ws.send(JSON.stringify({ type: 'error', code: 'unknown_message', message: `Unknown message type ${msg.type}.`, param: 'type', request_id: `req_mock_ws_${n}`, fatal: false }));
        }
        return;
      }
      if (!d.ready) return;
      const bytes = message.byteLength;
      if (d.log.firstAudioAt === null) d.log.firstAudioAt = d.offset + d.received / d.bytesPerSecond;
      d.received += bytes;
      d.log.audioBytes += bytes;
      const now = d.offset + d.received / d.bytesPerSecond;
      const seconds = d.received / d.bytesPerSecond;
      const fatal = connectionScript(wsScript.fatal, n);
      if (fatal && seconds >= fatal.afterSeconds) {
        ws.send(JSON.stringify({ type: 'error', code: fatal.code, message: `Mock ${fatal.code}.`, param: null, request_id: `req_mock_ws_${n}`, fatal: true }));
        ws.close(fatal.closeCode ?? 1011, fatal.code);
        return;
      }
      const drop = connectionScript(wsScript.drop, n);
      if (drop && seconds >= drop.afterSeconds) {
        ws.close(drop.closeCode ?? 1011, '');
        return;
      }
      const step = wsScript.segmentSeconds ?? 1;
      while (now - d.emittedUntil >= step - 1e-9) sendTranscript(ws, d.emittedUntil, d.emittedUntil + step);
    },
    close(ws, code, reason) {
      ws.data.log.closed = { code, reason };
    },
  },
});

console.log(`MOCK_URL=http://127.0.0.1:${server.port}`);
