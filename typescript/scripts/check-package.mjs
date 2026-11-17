// Checks the built package before a publish: both entry points load, the version constant
// matches package.json and jsr.json, and the tarball holds only dist/, README.md, LICENSE and
// package.json. The leak check (release/leak-check.ts) runs on the tarball itself.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const jsr = JSON.parse(readFileSync(join(root, 'jsr.json'), 'utf8'));
const failures = [];
const check = (ok, message) => ok || failures.push(message);

const esm = await import(pathToFileURL(join(root, 'dist/esm/index.js')).href);
const cjs = createRequire(import.meta.url)(join(root, 'dist/cjs/index.js'));
for (const [name, mod] of [['esm', esm], ['cjs', cjs]]) {
  check(typeof mod.MediSync === 'function', `${name}: MediSync missing`);
  check(mod.VERSION === pkg.version, `${name}: VERSION ${mod.VERSION} is not package.json ${pkg.version}`);
  const client = new mod.MediSync({ apiKey: 'msk_test_check' });
  check(typeof client.connect.transfers.create === 'function', `${name}: client.connect.transfers.create missing`);
  check(typeof client.audio.stream.open === 'function', `${name}: client.audio.stream.open missing`);
}
check(esm.default === esm.MediSync, 'esm: the default export is not MediSync');
check(jsr.version === pkg.version, `jsr.json ${jsr.version} is not package.json ${pkg.version}`);
check(JSON.parse(readFileSync(join(root, 'dist/cjs/package.json'), 'utf8')).type === 'commonjs', 'dist/cjs/package.json must say commonjs');
check(pkg.publishConfig?.registry === 'https://registry.npmjs.org' && pkg.publishConfig?.access === 'public', 'publishConfig must be npmjs.com, public');
check(!pkg.private, 'package.json is private');
check(Object.keys(pkg.dependencies ?? {}).length === 0, 'the package has runtime dependencies');

const packed = JSON.parse(execFileSync('npm', ['pack', '--dry-run', '--json', '--ignore-scripts'], { cwd: root, encoding: 'utf8' }));
const files = packed[0].files.map((f) => f.path);
const stray = files.filter((f) => !(f.startsWith('dist/') || f === 'README.md' || f === 'LICENSE' || f === 'package.json'));
check(stray.length === 0, `unexpected files in the tarball: ${stray.join(', ')}`);
for (const need of ['dist/esm/index.js', 'dist/esm/index.d.ts', 'dist/cjs/index.js', 'dist/cjs/index.d.ts', 'LICENSE', 'README.md']) check(files.includes(need), `${need} missing from the tarball`);
check(!files.some((f) => /\.test\.|\/test\//.test(f)), 'tests in the tarball');

if (failures.length) {
  console.error(failures.map((f) => `- ${f}`).join('\n'));
  process.exit(1);
}
console.log(`${pkg.name}@${pkg.version}: ${files.length} files, esm and cjs load, no runtime dependencies`);
