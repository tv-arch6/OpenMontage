#!/usr/bin/env bash
# Runs the phase (أ) backend suite against a REAL copy of your worker:
#   ./run.sh /path/to/cigram-admin-worker-v2.js
# It merges the ads module into that worker exactly the way deployment does,
# then exercises the merged file in Node against in-memory R2 / Durable Object
# mocks. Nothing touches Cloudflare and nothing touches your bucket.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
worker="${1:-}"
if [ -z "$worker" ]; then echo "usage: ./run.sh /path/to/cigram-admin-worker-v2.js" >&2; exit 2; fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
python3 "$here/../apply-ads-patch.py" "$worker" "$here/../cigram-ads-module.js" "$tmp/worker.mjs"
cp "$here/harness.mjs" "$tmp/harness.mjs"
cp "$here/test-phase-a.mjs" "$tmp/test-phase-a.mjs"
node "$tmp/test-phase-a.mjs"
