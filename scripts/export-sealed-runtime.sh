#!/usr/bin/env bash
# Copy the sealed runtime into another checkout (the main harness).
#
#   scripts/export-sealed-runtime.sh <dest-dir> [--package <java-package>]
#
# <dest-dir> is the target module's java source root, e.g.
#   app/src/main/java
# The kernel has no Android dependency, so this is a file copy plus an optional
# package rename - nothing else in that repository needs to know about this one.
# Tests are copied into a sibling `test` root when one can be inferred, so the
# receiving project can run them on the JVM immediately.
#
# See docs/sealed-runtime-port.md for what to copy, what not to, and the four
# invariants the tests enforce.
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "$0")/../sealed-runtime/src" && pwd)"
DEST=""
PACKAGE="io.mcyber12.sealed"

while [ $# -gt 0 ]; do
  case "$1" in
    --package) PACKAGE="${2:?--package needs a value}"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) DEST="$1"; shift ;;
  esac
done

if [ -z "$DEST" ]; then
  echo "usage: $0 <dest-java-source-root> [--package <java-package>]" >&2
  exit 2
fi

PACKAGE_PATH="${PACKAGE//.//}"
TARGET="$DEST/$PACKAGE_PATH"
mkdir -p "$TARGET"

# The main sources, then the tests, keeping the same relative layout.
for file in "$SOURCE_DIR/main/java/io/mcyber12/sealed"/*.java; do
  sed "s/^package io\.mcyber12\.sealed;/package ${PACKAGE};/" "$file" > "$TARGET/$(basename "$file")"
done
echo "copied $(ls "$SOURCE_DIR/main/java/io/mcyber12/sealed"/*.java | wc -l) classes to $TARGET"

# Infer the test root: <...>/src/main/java -> <...>/src/test/java.
TEST_ROOT=""
case "$DEST" in
  */src/main/java) TEST_ROOT="${DEST%/main/java}/test/java/$PACKAGE_PATH" ;;
esac
if [ -n "$TEST_ROOT" ]; then
  mkdir -p "$TEST_ROOT"
  for file in "$SOURCE_DIR/test/java/io/mcyber12/sealed"/*.java; do
    sed "s/^package io\.mcyber12\.sealed;/package ${PACKAGE};/" "$file" > "$TEST_ROOT/$(basename "$file")"
  done
  echo "copied $(ls "$SOURCE_DIR/test/java/io/mcyber12/sealed"/*.java | wc -l) test classes to $TEST_ROOT"
  echo "run them with:  ./gradlew :<module>:test   (JUnit 4, no Android SDK needed)"
else
  echo "note: no src/main/java path in <dest>; copy the tests from $SOURCE_DIR/test manually"
fi

cat <<'EOF'

Next, in the receiving project:
  1. jniLibs.useLegacyPackaging = true  (so libraries are real files, exec-able)
  2. extractNativeLibs="true" in AndroidManifest.xml
  3. assets/userland-<abi>.zip + sealed-manifest-<abi>.txt
     + sealed-exec-<abi>.txt + sealed-symlinks-<abi>.txt, and lib<name>.so in jniLibs/<abi>/
  4. build a SealedInstaller.Request from your own paths and call install()

Everything else - extraction, verification, linking, sealing - is already here.
EOF
