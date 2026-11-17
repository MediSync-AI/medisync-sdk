import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { resolve, sample, validate } from '../../conformance/mock/schema.ts';
import { buildIr, loadJson, ROOT } from '../src/ir.ts';
import { generatedFiles } from '../src/main.ts';

describe('generator', () => {
  const ir = buildIr();

  test('reads every operation of both specs once', () => {
    expect(ir.operations.filter((o) => o.api === 'connect')).toHaveLength(94);
    expect(ir.operations.filter((o) => o.api === 'platform')).toHaveLength(19);
    expect(new Set(ir.operations.map((o) => o.name)).size).toBe(ir.operations.length);
  });

  test('marks idempotency, pages and x-medisync fields', () => {
    const op = (name: string) => ir.operations.find((o) => o.name === name);
    expect(op('connect.transfers.create')?.idempotency).toBe('required');
    expect(op('notes.create')?.idempotency).toBe('optional');
    expect(op('models.list')?.idempotency).toBe('none');
    expect(op('connect.sites.list')?.page).toBeTruthy();
    expect(op('connect.records.list')?.x).toMatchObject({ scope: 'connect.read', verb: 'record.read', phi: true });
    expect(op('audio.stream.open')?.custom).toBe(true);
  });

  test('the generated files are up to date', () => {
    for (const [file, content] of Object.entries(generatedFiles())) expect(readFileSync(join(ROOT, file), 'utf8')).toBe(content);
  });

  test('a request sample of every body fits its schema, so the mock checks real requests', () => {
    const docs = { platform: loadJson('openapi.json'), connect: loadJson('connect-openapi.json') };
    for (const op of ir.operations.filter((o) => o.body?.encoding === 'json')) {
      const doc = docs[op.api];
      const schema = resolve(doc, doc.paths[op.path][op.httpMethod.toLowerCase()].requestBody.content['application/json'].schema);
      expect({ op: op.name, problems: validate(doc, schema, sample(doc, schema, 'request')) }).toEqual({ op: op.name, problems: [] });
    }
  });
});
