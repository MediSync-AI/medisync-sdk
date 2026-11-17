// The generated conformance cases: one call per API operation, with arguments that fit the spec.
// Each language runs them against the mock server, which checks the request against the spec.
import { resolve, sample } from '../../conformance/mock/schema.ts';
import { loadJson, type Ir } from './ir.ts';

type Json = any;

export function operationCases(ir: Ir): Json[] {
  const docs = { platform: loadJson('openapi.json'), connect: loadJson('connect-openapi.json') };
  const cases: Json[] = [];
  for (const op of ir.operations) {
    if (op.custom) continue;
    const doc = docs[op.api];
    const raw = doc.paths[op.path][op.httpMethod.toLowerCase()];
    const params = [...(doc.paths[op.path].parameters ?? []), ...(raw.parameters ?? [])].map((p: Json) => resolve(doc, p));
    const path = op.pathParams.map((p) => {
      const schema = resolve(doc, params.find((x: Json) => x.in === 'path' && x.name === p.name)?.schema ?? {});
      return schema.enum ? schema.enum[0] : `${p.name}_1`;
    });
    const args: Json = { path };
    if (op.queryModel) {
      const query: Json = {};
      for (const q of params.filter((x: Json) => x.in === 'query' && x.required)) query[q.name] = sample(doc, q.schema, 'request', 0, q.name);
      args.query = query;
    }
    if (op.body) {
      const schema = resolve(doc, raw.requestBody.content[op.body.encoding === 'json' ? 'application/json' : 'multipart/form-data'].schema);
      const body = sample(doc, schema, 'request') ?? {};
      for (const [key, prop] of Object.entries<Json>(schema.properties ?? {})) {
        if (resolve(doc, prop)?.format === 'binary') body[key] = { $file: { filename: 'sample.bin', bytes: 64 } };
      }
      args.body = body;
    }
    let filled = op.path;
    op.pathParams.forEach((p, i) => (filled = filled.replace(`{${p.name}}`, encodeURIComponent(path[i] as string))));
    cases.push({
      name: `operation ${op.name}`,
      call: { method: op.name, args, ...(op.page ? { mode: 'page' } : {}) },
      expect: {
        requests: [{ method: op.httpMethod, path: filled, problems: [] }],
        result: op.response.kind === 'empty' ? { null: true } : op.response.kind === 'binary' ? { bytes: 8 } : op.response.kind === 'text' ? { text: true } : { notNull: true },
      },
    });
  }
  return cases;
}
