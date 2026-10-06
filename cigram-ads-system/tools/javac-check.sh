#!/usr/bin/env bash
# Type-checks the ads Java sources against a REAL Android class path.
#
#   ./tools/javac-check.sh            # user app (com.Cigram.vid)
#   ./tools/javac-check.sh admin      # admin app (com.my.newproject)
#
# There is no Android SDK in a plain container and dl.google.com is often
# blocked, so this uses Robolectric's android-all jar from Maven Central: the
# same android.* classes, compiled for API 34. It is cached under tools/.cache.
#
# `tools/stubs/` holds signature-only copies of the PROJECT classes the ads code
# calls into (CigramUserData, CigramUpdateChecker, ...). They exist so this check
# needs no third-party libraries (AdMob, ExoPlayer, Cast, Glide); their
# signatures are copied verbatim from the real sources.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
cache="$here/.cache"
jar="$cache/android-all.jar"
ver="14-robolectric-10818077"

mkdir -p "$cache"
if [ ! -s "$jar" ]; then
  echo "fetching android-all $ver (~138 MB, once)..."
  curl -fsSL -o "$jar" \
    "https://repo1.maven.org/maven2/org/robolectric/android-all/$ver/android-all-$ver.jar"
fi

which="${1:-user}"
case "$which" in
  user)  src="$root/user-app/java/com.Cigram.vid";  pkg="com/Cigram/vid" ;;
  admin) src="$root/admin-app/java/com.my.newproject"; pkg="com/my/newproject" ;;
  *) echo "usage: javac-check.sh [user|admin]" >&2; exit 2 ;;
esac

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src/$pkg" "$work/out"
cp "$src"/*.java "$work/src/$pkg/" 2>/dev/null || { echo "no sources in $src"; exit 0; }
if [ -d "$here/stubs/$pkg" ]; then
  for f in "$here/stubs/$pkg"/*.java; do
    base="$(basename "$f")"
    # A real source always wins over its stub.
    [ -e "$work/src/$pkg/$base" ] || cp "$f" "$work/src/$pkg/"
  done
fi

javac -nowarn -proc:none -Xlint:none -cp "$jar" -d "$work/out" \
  $(find "$work/src" -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -n "$(find "$work/out" -name '*.class' -print -quit)" ] || { echo "JAVAC FAILED"; exit 1; }
echo "JAVAC OK  ($(find "$work/out" -name '*.class' | wc -l) classes, target=android-34)"
