// The intermediate model of both OpenAPI specs and the AsyncAPI document: operations with
// their MediSync names and x-medisync fields, and the schemas they reach as a small type model.
// The emitters for each language read only this model.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { HELPERS, PLATFORM_NAMES, type Helper } from './names.ts';

export const ROOT = join(import.meta.dir, '..', '..');
export const SPECS = join(ROOT, 'specs');

export type Api = 'platform' | 'connect' | 'live';

export type TypeRef =
  | { kind: 'ref'; api: Api; name: string }
  | { kind: 'string'; enum?: string[]; format?: string }
  | { kind: 'literal'; value: string | number | boolean }
  | { kind: 'number' }
  | { kind: 'integer' }
  | { kind: 'boolean' }
  | { kind: 'null' }
  | { kind: 'unknown' }
  | { kind: 'binary' }
  | { kind: 'array'; items: TypeRef }
  | { kind: 'map'; values: TypeRef }
  | { kind: 'union'; of: TypeRef[] };

export interface Prop {
  name: string;
  type: TypeRef;
  required: boolean;
  description?: string;
  deprecated?: boolean;
}

export interface ObjectModel {
  kind: 'object';
  api: Api;
  name: string;
  description?: string;
  props: Prop[];
  /** The type of extra keys, when the schema names one. */
  additional?: TypeRef;
}

export interface AliasModel {
  kind: 'alias';
  api: Api;
  name: string;
  description?: string;
  type: TypeRef;
}

export type Model = ObjectModel | AliasModel;

export interface Param {
  name: string;
  type: TypeRef;
  required: boolean;
  description?: string;
  /** An array in the query as one comma-separated value (`style: form`, `explode: false`). */
  comma?: boolean;
}

export type ResponseKind = 'json' | 'binary' | 'text' | 'empty';

export interface Operation {
  api: 'platform' | 'connect';
  operationId: string;
  /** The dotted name of the method on the client, in camelCase: `connect.transfers.create`. */
  name: string;
  httpMethod: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string;
  summary?: string;
  description?: string;
  tags: string[];
  pathParams: Param[];
  query: Param[];
  /** Name of the query model (`MembersListQuery`) when the operation takes query parameters. */
  queryModel?: string;
  body?: { encoding: 'json' | 'multipart'; model: string; required: boolean };
  response: { kind: ResponseKind; type?: TypeRef; status: number };
  idempotency: 'none' | 'optional' | 'required';
  /** A cursor list: `cursor` in the query, `data` and `next_cursor` in the answer. */
  page?: { item: TypeRef };
  /** Hand-written in each language (the live stream): the generated method delegates. */
  custom?: boolean;
  x: { scope?: string; verb?: string; phi?: boolean; metered?: string; usage?: string; public?: boolean };
}

export interface Ir {
  version: string;
  operations: Operation[];
  helpers: Helper[];
  models: Record<Api, Map<string, Model>>;
  /** The server messages of the live stream, by `type`. */
  liveServerMessages: { type: string; model: string }[];
  liveClientMessages: { type: string; model: string }[];
}

type Json = any;
const METHODS = ['get', 'post', 'put', 'patch', 'delete'] as const;

export const pascal = (s: string) =>
  s
    .replace(/[^A-Za-z0-9]+(.)?/g, (_m, c: string | undefined) => (c ? c.toUpperCase() : ''))
    .replace(/^./, (c) => c.toUpperCase());
export const camel = (s: string) => pascal(s).replace(/^./, (c) => c.toLowerCase());
const singular = (s: string) => (/ies$/.test(s) ? s.replace(/ies$/, 'y') : /(sses|xes|ches|shes)$/.test(s) ? s.slice(0, -2) : /[^s]s$/.test(s) ? s.slice(0, -1) : s);

/** Turns JSON Schemas of one API into models. Inline objects get a name from their parent. */
class SchemaReader {
  readonly models = new Map<string, Model>();
  private readonly pending = new Set<string>();
  constructor(
    readonly api: Api,
    readonly components: Record<string, Json>,
    /** Name prefix for component models (the live stream uses `Stream`). */
    readonly prefix = '',
    /** Component names that clash with a language name (`Error`) get another one. */
    readonly renames: Record<string, string> = {},
  ) {}

  refName(ref: string): string {
    const name = ref.split('/').pop() as string;
    return this.renames[name] ?? this.prefix + name;
  }

  /** Reads a component schema by name, once. */
  component(name: string): string {
    const full = this.renames[name] ?? this.prefix + name;
    if (this.models.has(full) || this.pending.has(full)) return full;
    const schema = this.components[name];
    if (!schema) throw new Error(`${this.api}: no schema ${name}`);
    this.pending.add(full);
    this.named(full, schema);
    this.pending.delete(full);
    return full;
  }

  /** Registers `schema` under `name`: an object model or an alias. */
  named(name: string, schema: Json): string {
    if (this.isObject(schema)) {
      const model: ObjectModel = { kind: 'object', api: this.api, name, description: schema.description, props: [] };
      this.models.set(name, model);
      const required = new Set<string>(schema.required ?? []);
      for (const [prop, sub] of Object.entries<Json>(schema.properties ?? {})) {
        const childName = name + pascal(prop);
        model.props.push({ name: prop, type: this.type(sub, childName), required: required.has(prop), description: describe(sub), deprecated: sub?.deprecated === true || undefined });
      }
      if (schema.additionalProperties && typeof schema.additionalProperties === 'object') model.additional = this.type(schema.additionalProperties, `${name}Value`);
      else if (schema.additionalProperties === true) model.additional = { kind: 'unknown' };
      return name;
    }
    this.models.set(name, { kind: 'alias', api: this.api, name, description: schema?.description, type: this.type(schema, name, true) });
    return name;
  }

  private isObject(s: Json): boolean {
    return !!s && typeof s === 'object' && !s.$ref && !s.oneOf && !s.anyOf && (s.type === 'object' || (!s.type && !!s.properties)) && !!s.properties && Object.keys(s.properties).length > 0;
  }

  /** The type of a schema. `nameHint` names an inline object. `top` is true for the alias body itself. */
  type(s: Json, nameHint: string, top = false): TypeRef {
    if (s === true || s === undefined || s === null || (typeof s === 'object' && Object.keys(s).filter((k) => !['description', 'example', 'examples', 'title', 'deprecated', 'default'].includes(k)).length === 0)) return { kind: 'unknown' };
    if (s.$ref) {
      const target = (s.$ref as string).split('/').pop() as string;
      return { kind: 'ref', api: this.api, name: this.component(target) };
    }
    const alts = s.oneOf ?? s.anyOf;
    if (alts) {
      const objects = (alts as Json[]).filter((a) => this.isObject(a));
      const of = (alts as Json[]).map((a, i) => {
        if (!this.isObject(a) || objects.length < 2) return this.type(a, nameHint);
        const tag = a.properties?.type?.const;
        const req: string[] = a.required ?? [];
        const suffix = typeof tag === 'string' ? pascal(tag) : req.length === 1 ? pascal(req[0] as string) : String(i + 1);
        return this.type(a, nameHint + suffix);
      });
      return union(of);
    }
    if (s.allOf) throw new Error(`allOf is not supported (${nameHint})`);
    if ('const' in s) return { kind: 'literal', value: s.const };
    if (Array.isArray(s.type)) {
      return union(s.type.map((t: string) => this.type({ ...s, type: t }, nameHint)));
    }
    switch (s.type) {
      case 'string':
        if (s.format === 'binary') return { kind: 'binary' };
        return { kind: 'string', ...(s.enum ? { enum: s.enum } : {}), ...(s.format ? { format: s.format } : {}) };
      case 'number':
        return { kind: 'number' };
      case 'integer':
        return { kind: 'integer' };
      case 'boolean':
        return { kind: 'boolean' };
      case 'null':
        return { kind: 'null' };
      case 'array':
        return { kind: 'array', items: this.type(s.items ?? {}, singular(nameHint)) };
      default:
        break;
    }
    if (s.enum) return { kind: 'string', enum: s.enum };
    if (this.isObject(s)) {
      if (top) throw new Error(`object alias ${nameHint}`);
      let name = nameHint;
      while (this.models.has(name)) name += 'Item';
      return { kind: 'ref', api: this.api, name: this.named(name, s) };
    }
    if (s.type === 'object' || s.additionalProperties !== undefined) {
      const ap = s.additionalProperties;
      return { kind: 'map', values: ap && typeof ap === 'object' ? this.type(ap, singular(nameHint)) : { kind: 'unknown' } };
    }
    return { kind: 'unknown' };
  }
}

function describe(s: Json): string | undefined {
  if (!s || typeof s !== 'object') return undefined;
  if (s.description) return s.description;
  const alt = (s.oneOf ?? s.anyOf ?? []).find((a: Json) => a?.description);
  return alt?.description;
}

function union(of: TypeRef[]): TypeRef {
  const flat: TypeRef[] = [];
  for (const t of of) for (const u of t.kind === 'union' ? t.of : [t]) if (!flat.some((f) => JSON.stringify(f) === JSON.stringify(u))) flat.push(u);
  return flat.length === 1 ? (flat[0] as TypeRef) : { kind: 'union', of: flat };
}

export function loadJson(name: string): Json {
  return JSON.parse(readFileSync(join(SPECS, name), 'utf8'));
}

export function loadAsyncApi(): Json {
  return Bun.YAML.parse(readFileSync(join(SPECS, 'asyncapi.yaml'), 'utf8'));
}

function resolveParam(doc: Json, p: Json): Json {
  if (!p.$ref) return p;
  return doc.components.parameters[(p.$ref as string).split('/').pop() as string];
}

function readOperations(api: 'platform' | 'connect', doc: Json, reader: SchemaReader): Operation[] {
  const ops: Operation[] = [];
  for (const [path, item] of Object.entries<Json>(doc.paths)) {
    for (const m of METHODS) {
      const op = item[m];
      if (!op) continue;
      const id: string = op.operationId;
      const name = api === 'connect' ? `connect.${id}` : PLATFORM_NAMES[id];
      if (!name) throw new Error(`No SDK name for the platform operation ${id}. Add it to generator/src/names.ts.`);
      const params = [...(item.parameters ?? []), ...(op.parameters ?? [])].map((p) => resolveParam(doc, p));
      const toParam = (p: Json, hint: string): Param => ({
        name: p.name,
        type: reader.type(p.schema ?? {}, hint),
        required: p.required === true,
        description: p.description,
        ...(p.in === 'query' && p.schema?.type === 'array' && p.explode === false ? { comma: true } : {}),
      });
      const pathParams = [...path.matchAll(/\{([^}]+)\}/g)].map((mm) => {
        const p = params.find((x) => x.in === 'path' && x.name === mm[1]);
        if (!p) throw new Error(`${id}: path parameter ${mm[1]} is not declared`);
        return toParam(p, pascal(id) + pascal(p.name));
      });
      const query = params.filter((p) => p.in === 'query').map((p) => toParam(p, pascal(id) + pascal(p.name)));
      const idem = params.find((p) => p.in === 'header' && p.name.toLowerCase() === 'idempotency-key');
      let body: Operation['body'];
      if (op.requestBody) {
        const content = op.requestBody.content ?? {};
        const ct = Object.keys(content)[0] as string;
        if (Object.keys(content).length !== 1 || !['application/json', 'multipart/form-data'].includes(ct)) throw new Error(`${id}: body ${Object.keys(content)}`);
        const schema = content[ct].schema;
        const model = schema.$ref ? reader.component((schema.$ref as string).split('/').pop() as string) : reader.named(`${pascal(id)}Body`, schema);
        if (reader.models.get(model)?.kind !== 'object') throw new Error(`${id}: the body is not an object with properties`);
        body = { encoding: ct === 'application/json' ? 'json' : 'multipart', model, required: op.requestBody.required === true };
      }
      const ok = Object.entries<Json>(op.responses ?? {}).filter(([code]) => /^(2\d\d|101)$/.test(code));
      let response: Operation['response'];
      if (ok.length === 0) {
        // Spec documents without a schema: the extension tells the format.
        response = { kind: path.endsWith('.json') ? 'json' : 'text', type: { kind: 'unknown' }, status: 200 };
      } else {
        const [code, res] = ok[0] as [string, Json];
        const content = res.content ?? {};
        const json = content['application/json'];
        if (json) response = { kind: 'json', type: reader.type(json.schema ?? {}, `${pascal(id)}Response`), status: Number(code) };
        else if (Object.keys(content).length) response = { kind: 'binary', status: Number(code) };
        else response = { kind: 'empty', status: Number(code) };
      }
      let queryModel: string | undefined;
      if (query.length && response.status !== 101) {
        queryModel = `${pascal(id)}Query`;
        reader.models.set(queryModel, { kind: 'object', api, name: queryModel, props: query.map((q) => ({ name: q.name, type: q.type, required: q.required, description: q.description })) });
      }
      let page: Operation['page'];
      if (query.some((q) => q.name === 'cursor') && response.kind === 'json' && response.type?.kind === 'ref') {
        const list = reader.models.get(response.type.name);
        const data = list?.kind === 'object' ? list.props.find((p) => p.name === 'data') : undefined;
        if (list?.kind === 'object' && data?.type.kind === 'array' && list.props.some((p) => p.name === 'next_cursor')) page = { item: data.type.items };
      }
      const x = {
        scope: op['x-medisync-scope'],
        verb: op['x-medisync-verb'],
        phi: op['x-medisync-phi'],
        metered: op['x-medisync-metered'],
        usage: op['x-medisync-usage'],
        public: op['x-medisync-public'],
      };
      ops.push({
        api,
        operationId: id,
        name,
        httpMethod: m.toUpperCase() as Operation['httpMethod'],
        path,
        summary: op.summary,
        description: op.description,
        tags: op.tags ?? [],
        pathParams,
        query,
        queryModel,
        body,
        response,
        idempotency: idem ? (idem.required ? 'required' : 'optional') : 'none',
        page,
        custom: response.status === 101 || undefined,
        x: Object.fromEntries(Object.entries(x).filter(([, v]) => v !== undefined)),
      });
    }
  }
  return ops;
}

export function buildIr(): Ir {
  const platformDoc = loadJson('openapi.json');
  const connectDoc = loadJson('connect-openapi.json');
  const asyncDoc = loadAsyncApi();
  const platform = new SchemaReader('platform', platformDoc.components.schemas, '', { Error: 'ErrorResponse' });
  const connect = new SchemaReader('connect', connectDoc.components.schemas, '', { Error: 'ErrorObject' });
  const live = new SchemaReader('live', asyncDoc.components.schemas, 'Stream');

  const operations = [...readOperations('platform', platformDoc, platform), ...readOperations('connect', connectDoc, connect)];
  // Every model of the error body: the client reads it for typed errors.
  platform.component('Error');
  connect.component('ErrorEnvelope');

  // The live messages: each message payload is a model named after the message.
  const liveServerMessages: Ir['liveServerMessages'] = [];
  const liveClientMessages: Ir['liveClientMessages'] = [];
  const ops = asyncDoc.operations as Record<string, Json>;
  for (const op of Object.values(ops)) {
    for (const ref of op.messages as Json[]) {
      const key = (ref.$ref as string).split('/').pop() as string;
      const message = asyncDoc.components.messages[asyncDoc.channels.stream.messages[key].$ref.split('/').pop()];
      if (message.contentType === 'application/octet-stream') continue;
      const payload = message.payload;
      const typeValue: string = payload.$ref ? asyncDoc.components.schemas[payload.$ref.split('/').pop()].properties.type.const : payload.properties.type.const;
      const model = payload.$ref ? live.component(payload.$ref.split('/').pop()) : live.named(`Stream${pascal(message.name)}`, payload);
      (op.action === 'send' ? liveClientMessages : liveServerMessages).push({ type: typeValue, model });
    }
  }

  const ids = new Set<string>();
  for (const op of operations) {
    if (ids.has(op.name)) throw new Error(`Two operations are named ${op.name}`);
    ids.add(op.name);
    if (op.body && op.query.length) throw new Error(`${op.operationId}: body and query together are not supported`);
  }
  for (const h of HELPERS) if (ids.has(h.name)) throw new Error(`The helper ${h.name} has the name of an operation`);
  for (const id of Object.keys(PLATFORM_NAMES)) if (!operations.some((o) => o.api === 'platform' && o.operationId === id)) throw new Error(`names.ts names ${id}, which the platform spec does not have`);

  const version: string = platformDoc.info.version;
  if (connectDoc.info['x-medisync-api-version'] !== version) throw new Error(`The specs name two versions: ${version} and ${connectDoc.info['x-medisync-api-version']}`);
  return { version, operations, helpers: HELPERS, models: { platform: platform.models, connect: connect.models, live: live.models }, liveServerMessages, liveClientMessages };
}
