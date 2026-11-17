#!/usr/bin/env bash
# Installs the wheel on Python 3.9 and 3.13, and the sdist on Python 3.13, each into a clean
# virtual environment, and runs check.py against the mock. Needs uv.
#
#   release/install-test/python/run.sh <dist folder> <X.Y.Z, in PEP 440 form>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DIST="$(cd "$1" && pwd)"
export EXPECTED_VERSION="$2"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

run() {
  uv venv -q --python "$1" "$WORK/$3"
  uv pip install -q --python "$WORK/$3/bin/python" "$2"
  "$HERE/../with-mock.sh" "$WORK/$3/bin/python" -I "$HERE/check.py"
}
run 3.9 "$DIST/medisync-$EXPECTED_VERSION-py3-none-any.whl" wheel39
run 3.13 "$DIST/medisync-$EXPECTED_VERSION-py3-none-any.whl" wheel313
run 3.13 "$DIST/medisync-$EXPECTED_VERSION.tar.gz" sdist313
