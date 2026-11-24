// The leak check before a publish. Fails when a built package, or a file of this repository,
// names something that must not leave MediSync:
//
// - a service-only operation, a service-only path or an `x-medisync-internal` field of the full
//   MediSync Connect contract (api/openapi.yaml of the Connect repository), and
// - a name of the denylist (release/public-names.txt of the Connect repository): customers,
//   partners, people, internal hosts and repository paths; and e-mail addresses outside
//   MediSync's own domains and the example domains.
//
// Neither the contract nor the denylist is copied into this repository. They are read at check
// time from (first that is set):
//   contract:  CONNECT_CONTRACT_FILE, else CONNECT_REPO (a git checkout; ref CONNECT_REF, default
//              origin/dev), else the GitHub API with CONNECT_CONTRACT_TOKEN
//   denylist:  PUBLIC_NAMES_DENYLIST (the file's text, a CI secret), else PUBLIC_NAMES_DENYLIST_FILE,
//              else the same sources as the contract (release/public-names.txt)
//
//   bun release/leak-check.ts <file.tgz | .whl | .tar.gz | .jar | .nupkg | .snupkg | folder>...   built packages
//   bun release/leak-check.ts --repo                                           tracked files
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join, relative } from 'node:path';

type Json = any;
const ROOT = join(import.meta.dir, '..');
const METHODS = ['get', 'put', 'post', 'patch', 'delete'];
const TEXT = /\.(ts|tsx|mts|cts|js|mjs|cjs|json|ya?ml|md|txt|py|pyi|toml|cfg|html|css|java|kt|kts|cs|csproj|sln|props|targets|xml|nuspec|properties|cmd|sh|MF|psmdcp|rels|list)$|(^|\/)(LICENSE|README|METADATA|RECORD|WHEEL|mvnw)[^/]*$/;
/** Compiled files: their strings are checked (Java class files, .NET assemblies and symbols). */
const BINARY = /\.(class|dll|pdb)$/;

// --- sources -----------------------------------------------------------------------------------

async function fromConnect(path: string): Promise<string> {
  const ref = process.env.CONNECT_REF ?? 'origin/dev';
  const repo = process.env.CONNECT_REPO ?? '';
  if (!repo && !process.env.CONNECT_CONTRACT_TOKEN) throw new Error('Set CONNECT_CONTRACT_FILE, CONNECT_REPO or CONNECT_CONTRACT_TOKEN.');
  if (repo) {
    if (!existsSync(repo)) throw new Error(`No Connect checkout at ${repo}. Set CONNECT_REPO, CONNECT_CONTRACT_FILE or CONNECT_CONTRACT_TOKEN.`);
    return execFileSync('git', ['-C', repo, 'show', `${ref}:${path}`], { encoding: 'utf8', maxBuffer: 64 << 20 });
  }
  const branch = ref.replace(/^origin\//, '');
  const res = await fetch(`https://api.github.com/repos/MediSync-AI/medisync-connect/contents/${path}?ref=${branch}`, {
    headers: { authorization: `Bearer ${process.env.CONNECT_CONTRACT_TOKEN}`, accept: 'application/vnd.github.raw+json', 'x-github-api-version': '2022-11-28' },
  });
  if (!res.ok) throw new Error(`GitHub answered ${res.status} for ${path}.`);
  return res.text();
}

async function contractText(): Promise<string> {
  if (process.env.CONNECT_CONTRACT_FILE) return readFileSync(process.env.CONNECT_CONTRACT_FILE, 'utf8');
  return fromConnect('api/openapi.yaml');
}

async function denylistText(): Promise<string> {
  if (process.env.PUBLIC_NAMES_DENYLIST) return process.env.PUBLIC_NAMES_DENYLIST;
  if (process.env.PUBLIC_NAMES_DENYLIST_FILE) return readFileSync(process.env.PUBLIC_NAMES_DENYLIST_FILE, 'utf8');
  return fromConnect('release/public-names.txt');
}

// --- what is internal in the contract -----------------------------------------------------------

/** Only a MediSync service token may call it (the gateway's rule). */
function serviceOnly(op: Json): boolean {
  const security: Json[] = op.security ?? [];
  return security.length > 0 && security.every((s) => Object.keys(s).length === 1 && 'serviceToken' in s);
}

function stripInternal(node: Json): void {
  if (Array.isArray(node)) {
    for (let i = node.length - 1; i >= 0; i--) {
      if (node[i] && typeof node[i] === 'object' && node[i]['x-medisync-internal'] === true) node.splice(i, 1);
      else stripInternal(node[i]);
    }
    return;
  }
  if (!node || typeof node !== 'object') return;
  if (node.properties && typeof node.properties === 'object') {
    for (const [name, prop] of Object.entries<Json>(node.properties)) {
      if (prop && prop['x-medisync-internal'] === true) {
        delete node.properties[name];
        if (Array.isArray(node.required)) node.required = node.required.filter((r: string) => r !== name);
      }
    }
  }
  for (const value of Object.values(node)) stripInternal(value);
}

function partnerView(doc: Json): Json {
  const spec = structuredClone(doc);
  for (const [path, item] of Object.entries<Json>(spec.paths)) {
    for (const m of METHODS) if (item[m] && serviceOnly(item[m])) delete item[m];
    if (!METHODS.some((m) => item[m])) delete spec.paths[path];
  }
  stripInternal(spec);
  return spec;
}

/** Names that must not appear: service-only operationIds and internal paths. */
export function internalNames(contract: Json): string[] {
  const ops = Object.values<Json>(contract.paths).flatMap((item) => METHODS.map((m) => item[m]).filter(Boolean));
  const ids = ops.filter(serviceOnly).map((op) => op.operationId as string);
  const paths = Object.entries<Json>(contract.paths)
    .filter(([, item]) => METHODS.every((m) => !item[m] || serviceOnly(item[m])))
    .map(([path]) => path);
  // The SDKs name an operation in camelCase (TypeScript, Java), snake_case (Python) and
  // PascalCase with Async (.NET, per dotted part).
  const snake = ids.map((id) => id.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase());
  const pascal = ids.map((id) => id.split('.').map((p) => p.charAt(0).toUpperCase() + p.slice(1)).join('.'));
  return [...new Set([...ids, ...snake, ...pascal, ...pascal.map((p) => `${p}Async`), ...paths])];
}

/** The `x-medisync-internal` fields of the contract that the partner view does not have. */
export function internalFields(contract: Json): string[] {
  const partner = JSON.stringify(partnerView(contract));
  return [...new Set([...JSON.stringify(contract).matchAll(/"([a-z_]+)":\{"x-medisync-internal":true/g)].map((m) => m[1] as string).filter((name) => !partner.includes(`"${name}"`)))];
}

// --- the denylist (same rules as the Connect repository's check-public-names.mjs) ---------------

const ALNUM = /[\p{L}\p{N}]/u;
const UPPER = /\p{Lu}/u;
const LOWER = /\p{Ll}/u;
const DIGIT = /\p{N}/u;
const MAIL = /\b([A-Za-z0-9._%+-]+)@((?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,})\b/g;
const MAIL_OK = /(^|\.)(medisync\.me|medisynchealth\.ai|example|example\.com|example\.org|test|invalid|localhost)$/i;

function edge(a: string | undefined, b: string | undefined, c: string | undefined): boolean {
  if (!a || !b || !ALNUM.test(a) || !ALNUM.test(b)) return true;
  if (DIGIT.test(a) !== DIGIT.test(b)) return true;
  if (LOWER.test(a) && UPPER.test(b)) return true;
  return UPPER.test(a) && UPPER.test(b) && !!c && LOWER.test(c);
}

type Pattern = { label: string; exec(text: string): RegExpExecArray | null };

function wholeWord(source: string): Pattern {
  const re = new RegExp(source.replace(/^\\b/, '').replace(/\\b$/, ''), 'giu');
  return {
    label: 'denylist name',
    exec(text) {
      re.lastIndex = 0;
      for (let m = re.exec(text); m; m = re.exec(text)) {
        const start = m.index;
        const end = start + m[0].length;
        if (m[0] && edge(text[start - 1], text[start], text[start + 1]) && edge(text[end - 1], text[end], text[end + 1])) return m;
        re.lastIndex = start + 1;
      }
      return null;
    },
  };
}

function regex(source: string, label: string): Pattern {
  const re = new RegExp(source, 'iu');
  return { label, exec: (text) => re.exec(text) };
}

export function loadDenylist(text: string): { names: Pattern[]; shipped: Pattern[] } {
  const lists = { names: [] as Pattern[], shipped: [] as Pattern[] };
  let section: 'names' | 'shipped' | null = null;
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const head = /^\[(names|shipped)\]$/.exec(line);
    if (head) {
      section = head[1] as 'names' | 'shipped';
      continue;
    }
    if (!section) throw new Error('The denylist has a pattern before its first section.');
    lists[section].push(section === 'names' ? wholeWord(line) : regex(line, 'denylist path'));
  }
  if (lists.names.length === 0) throw new Error('The denylist has no names. Is it the right file?');
  return lists;
}

/**
 * An internal field where code names it: a quoted key, a property or argument (`name:`, `name?:`,
 * `name =`), or the Java and C# accessors (`getName(`, `setName(`, `Name { get`). The same word in
 * a sentence of a public description ("the dashboard shows") is no field and does not count.
 */
function fieldPattern(name: string): Pattern {
  const e = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const camel = name.replace(/_([a-z0-9])/g, (_, c: string) => c.toUpperCase());
  const pascal = camel.charAt(0).toUpperCase() + camel.slice(1);
  const re = new RegExp(`"${e}"|(?<![A-Za-z0-9_])${e}\\??\\s*[:=](?!=)|(?<![A-Za-z0-9_])[gs]et${pascal}\\s*\\(|(?<![A-Za-z0-9_])${pascal}\\s*\\{\\s*get`, 'u');
  return { label: 'internal Connect field', exec: (text) => re.exec(text) };
}

/** Literal names, matched as a whole identifier or path. */
function literal(name: string): Pattern {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`(?<![A-Za-z0-9_])${escaped}(?![A-Za-z0-9_])`, 'u');
  return { label: 'internal Connect name', exec: (text) => re.exec(text) };
}

// --- files ---------------------------------------------------------------------------------------

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name);
    if (e.isDirectory()) return e.name === 'node_modules' || e.name === '.git' ? [] : walk(path);
    return TEXT.test(e.name) || BINARY.test(e.name) ? [path] : [];
  });
}

function unpack(artifact: string): { dir: string; cleanup: () => void } {
  if (statSync(artifact).isDirectory()) return { dir: artifact, cleanup: () => {} };
  const dir = mkdtempSync(join(tmpdir(), 'medisync-leak-'));
  if (/\.(whl|zip|jar|nupkg|snupkg)$/.test(artifact)) execFileSync('python3', ['-I', '-m', 'zipfile', '-e', artifact, dir]);
  else if (/\.(tgz|tar\.gz)$/.test(artifact)) execFileSync('tar', ['-xzf', artifact, '-C', dir]);
  else throw new Error(`Unknown artifact type: ${artifact}`);
  return { dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

type Hit = { file: string; line: number; label: string; match: string };

/** The text of a file; for a compiled file, its printable strings (UTF-8 and UTF-16), one per line. */
function textOf(file: string): string {
  if (!BINARY.test(file)) return readFileSync(file, 'utf8');
  const bytes = readFileSync(file);
  const runs = (text: string) => text.match(/[\x20-\x7e\u00a0-\u024f]{4,}/g) ?? [];
  return [...runs(bytes.toString('latin1')), ...runs(bytes.toString('utf16le')), ...runs(bytes.subarray(1).toString('utf16le'))].join('\n');
}

function scan(file: string, base: string, patterns: Pattern[], mail: boolean): Hit[] {
  const hits: Hit[] = [];
  textOf(file)
    .split('\n')
    .forEach((content, i) => {
      for (const p of patterns) {
        const m = p.exec(content);
        if (m) hits.push({ file: relative(base, file), line: i + 1, label: p.label, match: m[0] });
      }
      if (mail) {
        for (const m of content.matchAll(MAIL)) if (m[1] !== 'name' && !MAIL_OK.test(m[2] ?? '')) hits.push({ file: relative(base, file), line: i + 1, label: 'e-mail', match: m[0] });
      }
    });
  return hits;
}

if (import.meta.main) {
  const args = process.argv.slice(2);
  if (args.length === 0) {
    console.error('Usage: bun release/leak-check.ts <artifact>... | --repo');
    process.exit(2);
  }
  const contract = Bun.YAML.parse(await contractText()) as Json;
  const internal = [...internalNames(contract).map(literal), ...internalFields(contract).map(fieldPattern)];
  const denylist = loadDenylist(await denylistText());
  const hits: Hit[] = [];
  let files = 0;
  if (args[0] === '--repo') {
    // The repository becomes public: its tracked files may not name a denied name either.
    const tracked = execFileSync('git', ['-C', ROOT, 'ls-files'], { encoding: 'utf8' }).split('\n').filter((f) => f && TEXT.test(f));
    for (const f of tracked) {
      files++;
      // specs/ holds the documents docs.medisync.me publishes as they are; they are checked
      // for denied names, and the generator reads only their partner operations.
      const patterns = f.startsWith('specs/') ? denylist.names : [...denylist.names, ...internal];
      hits.push(...scan(join(ROOT, f), ROOT, patterns, true));
    }
  } else {
    for (const artifact of args) {
      const { dir, cleanup } = unpack(artifact);
      try {
        for (const f of walk(dir)) {
          files++;
          hits.push(...scan(f, dir, [...denylist.names, ...denylist.shipped, ...internal], true).map((h) => ({ ...h, file: `${basename(artifact)}:${h.file}` })));
        }
      } finally {
        cleanup();
      }
    }
  }
  // Print where, never the denied pattern itself: CI logs are visible.
  for (const h of hits) console.error(`${h.file}:${h.line}: ${h.label}${h.label === 'internal Connect name' || h.label === 'internal Connect field' || h.label === 'e-mail' ? ` (${h.match})` : ''}`);
  if (hits.length) {
    console.error(`Leak check failed: ${hits.length} hit(s) in ${files} files.`);
    process.exit(1);
  }
  console.log(`Leak check passed: ${files} files, ${internal.length} internal Connect names, ${denylist.names.length + denylist.shipped.length} denylist patterns.`);
}
