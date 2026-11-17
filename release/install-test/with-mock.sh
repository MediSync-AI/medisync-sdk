#!/usr/bin/env bash
# Runs a command with MOCK_URL set to a fresh mock of the MediSync API, and stops the mock after.
#
#   release/install-test/with-mock.sh <command> [args...]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOG="$(mktemp)"
bun "$ROOT/conformance/mock/server.ts" --port 0 >"$LOG" 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true; rm -f "$LOG"' EXIT
for _ in $(seq 1 100); do grep -q '^MOCK_URL=' "$LOG" && break; sleep 0.1; done
MOCK_URL="$(sed -n 's/^MOCK_URL=//p' "$LOG" | head -1)"
if [ -z "$MOCK_URL" ]; then cat "$LOG" >&2; exit 1; fi
export MOCK_URL
"$@"
