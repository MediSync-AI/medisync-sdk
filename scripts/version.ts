// The one version of all four packages. Every package ships with the same version, from one tag
// v<version>.
//
//   bun scripts/version.ts                 prints the version; fails when the files disagree
//   bun scripts/version.ts set 0.2.0       writes it into every file below
//   bun scripts/version.ts set 0.2.0-beta.1
//   bun scripts/version.ts --github        prints version, python, prerelease and npm_tag lines
//                                          for $GITHUB_OUTPUT
//
// The version is semver: X.Y.Z, or X.Y.Z-alpha.N, -beta.N or -rc.N for a prerelease. Python
// writes the same version in PEP 440 form (0.2.0b1).
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(import.meta.dir, '..');
const SEMVER = /^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$/;
const PEP440: Record<string, string> = { alpha: 'a', beta: 'b', rc: 'rc' };

/** Each place that holds the version: the file, the pattern around it, and the form it uses. */
const PLACES: { file: string; re: RegExp; python?: boolean }[] = [
  { file: 'typescript/package.json', re: /^(  "version": ")([^"]+)(")/m },
  { file: 'typescript/package-lock.json', re: /^(  "version": ")([^"]+)(")/m },
  { file: 'typescript/package-lock.json', re: /^(      "name": "medisync",\n      "version": ")([^"]+)(")/m },
  { file: 'typescript/jsr.json', re: /^(  "version": ")([^"]+)(")/m },
  { file: 'typescript/src/version.ts', re: /^(export const VERSION = ')([^']+)(')/m },
  { file: 'python/pyproject.toml', re: /^(version = ")([^"]+)(")/m, python: true },
  { file: 'python/uv.lock', re: /^(name = "medisync"\nversion = ")([^"]+)(")/m, python: true },
  { file: 'python/src/medisync/_version.py', re: /^(__version__ = ")([^"]+)(")/m, python: true },
  { file: 'java/pom.xml', re: /^(  <artifactId>medisync<\/artifactId>\n  <version>)([^<]+)(<\/version>)/m },
  { file: 'java/src/main/java/me/medisync/Version.java', re: /^(  public static final String VERSION = ")([^"]+)(")/m },
  { file: 'dotnet/src/MediSync/MediSync.csproj', re: /^(    <Version>)([^<]+)(<\/Version>)/m },
  { file: 'dotnet/src/MediSync/MediSyncClient.cs', re: /^(    public const string Value = ")([^"]+)(")/m },
];

function parse(version: string) {
  const m = SEMVER.exec(version);
  if (!m) throw new Error(`${version} is not X.Y.Z or X.Y.Z-(alpha|beta|rc).N`);
  const [, major, minor, patch, pre, n] = m;
  return {
    version,
    python: `${major}.${minor}.${patch}${pre ? `${PEP440[pre]}${n}` : ''}`,
    prerelease: Boolean(pre),
    // A prerelease never becomes "latest" on npm.
    npmTag: pre ? (pre === 'rc' ? 'next' : pre) : 'latest',
  };
}

function read(): string {
  const found = PLACES.map((p) => {
    const m = p.re.exec(readFileSync(join(ROOT, p.file), 'utf8'));
    if (!m) throw new Error(`No version found in ${p.file}`);
    return { ...p, value: m[2] };
  });
  const version = found[0].value;
  const want = parse(version);
  const wrong = found.filter((f) => f.value !== (f.python ? want.python : want.version));
  if (wrong.length) {
    throw new Error(`The versions disagree with ${version}:\n${wrong.map((w) => `  ${w.file}: ${w.value}`).join('\n')}\nRun bun scripts/version.ts set <version>.`);
  }
  return version;
}

function set(version: string): void {
  const want = parse(version);
  for (const p of PLACES) {
    const path = join(ROOT, p.file);
    const text = readFileSync(path, 'utf8');
    if (!p.re.test(text)) throw new Error(`No version found in ${p.file}`);
    writeFileSync(path, text.replace(p.re, `$1${p.python ? want.python : want.version}$3`));
  }
}

const [cmd, arg] = process.argv.slice(2);
try {
  if (cmd === 'set') {
    if (!arg) throw new Error('Usage: bun scripts/version.ts set <version>');
    set(arg);
    console.log(`All packages are at ${read()}.`);
  } else if (cmd === '--github') {
    const v = parse(read());
    console.log(`version=${v.version}\npython=${v.python}\nprerelease=${v.prerelease}\nnpm_tag=${v.npmTag}`);
  } else if (!cmd) {
    console.log(read());
  } else {
    throw new Error(`Unknown command ${cmd}`);
  }
} catch (e) {
  console.error((e as Error).message);
  process.exit(1);
}
