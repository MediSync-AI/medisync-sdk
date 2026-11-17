// Builds dist/esm (import) and dist/cjs (require), each with its .d.ts files. dist/cjs gets a
// package.json with "type": "commonjs", so Node and TypeScript read its .js and .d.ts as CommonJS.
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const tsc = createRequire(import.meta.url).resolve('typescript/bin/tsc');

rmSync(join(root, 'dist'), { recursive: true, force: true });
for (const config of ['tsconfig.build.json', 'tsconfig.cjs.json']) {
  execFileSync(process.execPath, [tsc, '-p', join(root, config)], { stdio: 'inherit' });
}
writeFileSync(join(root, 'dist/cjs/package.json'), `${JSON.stringify({ type: 'commonjs' }, null, 2)}\n`);

// tsc rewrites `./x.ts` to `./x.js` in .js files only. Do the same in the .d.ts files, so a
// TypeScript project without allowImportingTsExtensions reads them.
for (const dir of ['dist/esm', 'dist/cjs']) {
  for (const entry of readdirSync(join(root, dir), { recursive: true })) {
    const file = join(root, dir, String(entry));
    if (!file.endsWith('.d.ts')) continue;
    const text = readFileSync(file, 'utf8');
    const fixed = text.replace(/((?:from|import)\s*\(?\s*['"])(\.{1,2}\/[^'"]+)\.ts(['"])/g, '$1$2.js$3');
    if (fixed !== text) writeFileSync(file, fixed);
  }
}
