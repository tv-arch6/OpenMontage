#!/usr/bin/env bash
# Proves CigramWs against a hand-rolled RFC6455 server: handshake and
# Sec-WebSocket-Accept, client masking, the 7/16/64-bit length paths,
# fragmented messages, server ping -> our pong, and a clean close.
#
#   ./run-ws-test.sh
#
# Needs node and a JDK. The Android class path comes from tools/javac-check.sh's
# cache (run that once first, or let this script fetch it the same way).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
jar="$root/tools/.cache/android-all.jar"
ver="14-robolectric-10818077"
if [ ! -s "$jar" ]; then
  mkdir -p "$(dirname "$jar")"
  echo "fetching android-all $ver (~138 MB, once)..."
  curl -fsSL -o "$jar" \
    "https://repo1.maven.org/maven2/org/robolectric/android-all/$ver/android-all-$ver.jar"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"; [ -n "${srv:-}" ] && kill "$srv" 2>/dev/null || true' EXIT
port=18099

javac -nowarn -proc:none -cp "$jar" -d "$work" \
  "$root/user-app/java/com.Cigram.vid/CigramWs.java" "$here/WsSmokeTest.java" \
  2>&1 | grep -v '^Note:' || true

node "$here/ws-echo-server.mjs" "$port" >"$work/server.log" 2>&1 &
srv=$!
sleep 1
java -cp "$work:$jar" WsSmokeTest "$port"
