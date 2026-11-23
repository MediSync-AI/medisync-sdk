// The client has exactly one public method per operationId of both specs, plus the helpers.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { HELPERS, MediSync, OPERATIONS, VERSION } from '../src/index.ts';

const read = (path: string) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const names = read('../../conformance/operations.json') as { operations: { api: string; operationId: string; typescript: string }[]; helpers: { typescript: string }[] };

function specOperationIds(file: string): string[] {
  const spec = read(`../../specs/${file}`);
  return Object.values<Record<string, { operationId?: string }>>(spec.paths).flatMap((item) =>
    ['get', 'post', 'put', 'patch', 'delete'].filter((m) => item[m]).map((m) => item[m]?.operationId as string),
  );
}

/** Every function reachable from the client's namespaces, as dotted names. */
function publicMethods(client: object): string[] {
  const out: string[] = [];
  const skip = new Set(['constructor']);
  const walk = (node: object, prefix: string) => {
    for (const [key, value] of Object.entries(node)) {
      if (key.startsWith('_') || !value || typeof value !== 'object') continue;
      const path = prefix ? `${prefix}.${key}` : key;
      let proto = Object.getPrototypeOf(value);
      while (proto && proto !== Object.prototype && proto.constructor.name !== 'APIResource') {
        for (const name of Object.getOwnPropertyNames(proto)) {
          if (!skip.has(name) && typeof (value as Record<string, unknown>)[name] === 'function') out.push(`${path}.${name}`);
        }
        proto = Object.getPrototypeOf(proto);
      }
      walk(value, path);
    }
  };
  walk(client, '');
  return [...new Set(out)].sort();
}

test('one method per operationId of both specs, plus the helpers, and no other', () => {
  const ids = [...specOperationIds('openapi.json').map((id) => `platform:${id}`), ...specOperationIds('connect-openapi.json').map((id) => `connect:${id}`)].sort();
  assert.deepEqual(names.operations.map((o) => `${o.api}:${o.operationId}`).sort(), ids);
  assert.equal(specOperationIds('connect-openapi.json').length, 100);
  const expected = [...names.operations.map((o) => o.typescript), ...names.helpers.map((h) => h.typescript)].sort();
  assert.deepEqual(publicMethods(new MediSync({ apiKey: 'msk_test_parity' })), expected);
  assert.deepEqual(Object.keys(OPERATIONS).sort(), names.operations.map((o) => o.typescript).sort());
  assert.deepEqual([...HELPERS].sort(), names.helpers.map((h) => h.typescript).sort());
});

test('the version constant, package.json and jsr.json agree', () => {
  assert.equal(VERSION, read('../package.json').version);
  assert.equal(VERSION, read('../jsr.json').version);
});
