// Copies the public API specs from a committed ref of the docs repository into specs/ and
// records the source commit and the sha256 of each file in specs/SOURCE.json.
//
//   bun scripts/specs-sync.ts [--repo <path>] [--ref <ref>]   copy (default ref origin/main)
//   bun scripts/specs-sync.ts --check                         check specs/ against SOURCE.json
//
// The files come from `git show <commit>:<path>`, never from a working tree, so uncommitted
// edits cannot reach a package.
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const ROOT = join(import.meta.dir, '..');
const SPECS = join(ROOT, 'specs');
const FILES: Record<string, string> = {
  'openapi.json': 'apps/docs/specs/openapi.json',
  'connect-openapi.json': 'apps/docs/specs/connect-openapi.json',
  'asyncapi.yaml': 'apps/docs/specs/asyncapi.yaml',
};

type Source = { ref: string; commit: string; files: Record<string, { path: string; sha256: string }> };

const sha256 = (data: string | Buffer) => createHash('sha256').update(data).digest('hex');
const arg = (name: string) => {
  const i = process.argv.indexOf(name);
  return i > 0 ? process.argv[i + 1] : undefined;
};

if (process.argv.includes('--check')) {
  const source = JSON.parse(readFileSync(join(SPECS, 'SOURCE.json'), 'utf8')) as Source;
  const bad = Object.entries(source.files).filter(([name, f]) => sha256(readFileSync(join(SPECS, name))) !== f.sha256);
  if (bad.length) {
    console.error(`specs/ differs from SOURCE.json: ${bad.map(([n]) => n).join(', ')}. Run bun run specs:sync.`);
    process.exit(1);
  }
  console.log(`specs/ matches SOURCE.json (commit ${source.commit.slice(0, 7)}).`);
} else {
  const repo = arg('--repo') ?? process.env.MEDISYNC_SPECS_REPO ?? join(homedir(), 'medisync/platform/api-platform');
  const ref = arg('--ref') ?? 'origin/main';
  const git = (...args: string[]) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8', maxBuffer: 64 << 20 });
  const commit = git('rev-parse', '--verify', `${ref}^{commit}`).trim();
  const source: Source = { ref, commit, files: {} };
  for (const [name, path] of Object.entries(FILES)) {
    const content = git('show', `${commit}:${path}`);
    writeFileSync(join(SPECS, name), content);
    source.files[name] = { path, sha256: sha256(content) };
  }
  writeFileSync(join(SPECS, 'SOURCE.json'), `${JSON.stringify(source, null, 2)}\n`);
  console.log(`specs/ from ${ref} at ${commit.slice(0, 7)}`);
}
