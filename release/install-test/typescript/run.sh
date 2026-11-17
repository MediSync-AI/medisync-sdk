#!/usr/bin/env bash
# Installs the npm tarball into clean projects and runs it on Node (ESM, CommonJS, tsc --strict),
# Bun and Deno against the mock. Runtimes that are not installed are skipped, except Node.
#
#   release/install-test/typescript/run.sh <medisync-X.Y.Z.tgz> <X.Y.Z>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TARBALL="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
export EXPECTED_VERSION="$2"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir "$WORK/node" && cd "$WORK/node"
cp "$HERE/check.mjs" "$HERE/check.cjs" "$HERE/check.ts" .
npm init -y >/dev/null
npm install --no-audit --no-fund "$TARBALL" typescript@5 @types/node@22 >/dev/null
"$HERE/../with-mock.sh" node check.mjs
"$HERE/../with-mock.sh" node check.cjs
npx tsc --strict --noEmit --skipLibCheck false --module nodenext --moduleResolution nodenext --target es2022 check.ts
npx tsc --strict --noEmit --module commonjs --moduleResolution node10 --target es2022 --esModuleInterop check.ts
echo "tsc --strict: types ok (nodenext, node10)"
if command -v deno >/dev/null; then "$HERE/../with-mock.sh" deno run --allow-env --allow-net --allow-read check.mjs; fi

if command -v bun >/dev/null; then
  mkdir "$WORK/bun" && cd "$WORK/bun"
  cp "$HERE/check.mjs" .
  echo '{"name":"install-test","private":true}' > package.json
  bun add "$TARBALL" >/dev/null
  "$HERE/../with-mock.sh" bun check.mjs
fi
