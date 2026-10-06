#!/usr/bin/env bash
# Runs the backend suites against a REAL copy of your worker:
#   ./run.sh /path/to/cigram-admin-worker-v2.js          # every phase
#   ./run.sh /path/to/cigram-admin-worker-v2.js a        # one phase
#
# It merges the ads modules into that worker exactly the way deployment does,
# then exercises the merged file in Node against in-memory R2 / Durable Object
# mocks. Nothing touches Cloudflare and nothing touches your bucket.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
worker="${1:-}"
only="${2:-}"
if [ -z "$worker" ]; then
  echo "usage: ./run.sh /path/to/cigram-admin-worker-v2.js [phase]" >&2
  exit 2
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
python3 "$here/../apply-ads-patch.py" "$worker" "$tmp/worker.mjs"
cp "$here/harness.mjs" "$tmp/harness.mjs"

status=0
for suite in "$here"/test-phase-*.mjs; do
  phase="$(basename "$suite" .mjs)"; phase="${phase##test-phase-}"
  if [ -n "$only" ] && [ "$only" != "$phase" ]; then continue; fi
  echo
  echo "######## phase ($phase) ########"
  cp "$suite" "$tmp/suite.mjs"
  node "$tmp/suite.mjs" || status=1
done
exit $status
