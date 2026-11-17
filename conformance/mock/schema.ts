// JSON Schema helpers for the mock server and the generated conformance cases: a sample value
// for a schema, and a check of a value against a schema. Enough of JSON Schema for the MediSync
// specs: $ref, type (also as a list), properties, required, additionalProperties, items, enum,
// const, oneOf and anyOf.

type Json = any;

export function resolve(doc: Json, schema: Json): Json {
  let s = schema;
  for (let i = 0; s && typeof s === 'object' && s.$ref && i < 20; i++) {
    const parts = (s.$ref as string).replace(/^#\//, '').split('/');
    s = parts.reduce((node: Json, key) => node?.[key], doc);
  }
  return s;
}

const DATE_TIME = '2026-11-16T09:00:00Z';

/**
 * A value that fits the schema. `request` gives only the required properties and the first
 * alternative; `response` gives every property, so a client reads a full answer.
 */
export function sample(doc: Json, schema: Json, mode: 'request' | 'response', depth = 0, name = 'value'): Json {
  const s = resolve(doc, schema);
  if (!s || typeof s !== 'object' || depth > 8) return null;
  if ('const' in s) return s.const;
  if (s.example !== undefined && (s.type !== 'object' || mode === 'response') && !(s.type === 'string' && s.format === 'binary')) return s.example;
  if (Array.isArray(s.examples) && s.examples.length && s.type !== 'object') return s.examples[0];
  if (s.default !== undefined && s.type !== 'object') return s.default;
  if (s.enum) return s.enum[0];
  const alts = s.oneOf ?? s.anyOf;
  if (alts) {
    const nonNull = (alts as Json[]).filter((a) => resolve(doc, a)?.type !== 'null');
    return sample(doc, nonNull[0] ?? alts[0], mode, depth + 1, name);
  }
  const type = Array.isArray(s.type) ? s.type.find((t: string) => t !== 'null') : s.type;
  switch (type) {
    case 'string':
      if (s.format === 'date-time') return DATE_TIME;
      if (s.format === 'date') return '2026-11-16';
      if (s.format === 'uri') return 'https://example.com/x';
      if (s.format === 'binary') return 'binary';
      return `${name}_sample`.slice(0, Math.max(s.maxLength ?? 64, 1)).padEnd(s.minLength ?? 0, 'x');
    case 'integer':
      return Math.max(s.minimum ?? 1, Math.min(1, s.maximum ?? 1));
    case 'number':
      return Math.max(s.minimum ?? 0.5, Math.min(0.5, s.maximum ?? 0.5));
    case 'boolean':
      return true;
    case 'null':
      return null;
    case 'array': {
      const n = Math.max(s.minItems ?? 1, 1);
      return Array.from({ length: n }, () => sample(doc, s.items ?? {}, mode, depth + 1, singular(name)));
    }
    default:
      break;
  }
  if (s.properties || type === 'object') {
    const out: Record<string, Json> = {};
    const required = new Set<string>(s.required ?? []);
    for (const [key, prop] of Object.entries<Json>(s.properties ?? {})) {
      if (mode === 'request' && !required.has(key)) continue;
      out[key] = sample(doc, prop, mode, depth + 1, key);
    }
    if (mode === 'response' && key0(s.properties) === undefined && s.additionalProperties && typeof s.additionalProperties === 'object') {
      out.key = sample(doc, s.additionalProperties, mode, depth + 1, 'key');
    }
    return out;
  }
  return null;
}

const key0 = (o: Json) => (o ? Object.keys(o)[0] : undefined);
const singular = (s: string) => s.replace(/s$/, '');

function typeOf(v: Json): string {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  if (typeof v === 'number') return Number.isInteger(v) ? 'integer' : 'number';
  return typeof v;
}

function typeMatches(want: string, got: string): boolean {
  return want === got || (want === 'number' && got === 'integer');
}

/** The problems of a value against a schema: `path: message` lines. Empty when it fits. */
export function validate(doc: Json, schema: Json, value: Json, path = '$'): string[] {
  const s = resolve(doc, schema);
  if (!s || typeof s !== 'object' || s === true) return [];
  const alts = s.oneOf ?? s.anyOf;
  if (alts) {
    const results = (alts as Json[]).map((a) => validate(doc, a, value, path));
    if (results.some((r) => r.length === 0)) return [];
    return [`${path}: matches no alternative (${results.map((r) => r[0]).join('; ')})`];
  }
  if ('const' in s && value !== s.const) return [`${path}: is not ${JSON.stringify(s.const)}`];
  if (s.enum && !s.enum.includes(value)) return [`${path}: ${JSON.stringify(value)} is not one of ${s.enum.join(', ')}`];
  const types: string[] | null = s.type ? (Array.isArray(s.type) ? s.type : [s.type]) : null;
  const got = typeOf(value);
  if (types && !types.some((t) => typeMatches(t, got))) return [`${path}: is ${got}, not ${types.join(' or ')}`];
  const problems: string[] = [];
  if (got === 'object' && (s.properties || s.type === 'object')) {
    for (const key of s.required ?? []) if (!(key in value)) problems.push(`${path}.${key}: is required`);
    for (const [key, v] of Object.entries<Json>(value)) {
      const prop = s.properties?.[key];
      if (prop) problems.push(...validate(doc, prop, v, `${path}.${key}`));
      else if (s.additionalProperties === false) problems.push(`${path}.${key}: is not a field`);
      else if (s.additionalProperties && typeof s.additionalProperties === 'object') problems.push(...validate(doc, s.additionalProperties, v, `${path}.${key}`));
    }
  }
  if (got === 'array' && s.items) (value as Json[]).forEach((v, i) => problems.push(...validate(doc, s.items, v, `${path}[${i}]`)));
  return problems;
}
