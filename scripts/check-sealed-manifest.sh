#!/usr/bin/env bash
# Verifies that a sealed payload is actually sealed.
#
#   check-sealed-manifest.sh <payload-tree> <sealed-exec.txt> [jniLibsDir ...]
#
# A sealed payload is the userland that ships inside the APK: every file whose
# bytes get executed must have been moved into lib<name>.so and replaced by an
# entry in sealed-exec.txt. Anything executable left in the payload tree is a
# file that would need Android's data-directory exec exemption on device, i.e.
# a file that breaks a modern targetSdkVersion — so this script fails the build.
#
# It checks three things and nothing else:
#   1. no ELF or "#!" file with an execute bit remains in the payload tree;
#   2. every manifest entry is a relative path, unique, and has a library name;
#   3. every manifest library exists in every ABI directory given.
#
# Exit codes: 0 clean, 1 violations, 2 usage error.
set -u

if [ $# -lt 2 ]; then
  echo "usage: $0 <payload-tree> <sealed-exec.txt> [jniLibsDir ...]" >&2
  exit 2
fi

PAYLOAD="$1"; shift
MANIFEST="$1"; shift
[ -d "$PAYLOAD" ] || { echo "error: payload tree not found: $PAYLOAD" >&2; exit 2; }
[ -f "$MANIFEST" ] || { echo "error: manifest not found: $MANIFEST" >&2; exit 2; }

problems=0
entries=0

# ---- 1. nothing executable may remain in the payload ----------------------
while IFS= read -r file; do
  [ -n "$file" ] || continue
  magic=$(head -c 4 "$file" 2>/dev/null | od -An -tx1 | tr -d ' \n')
  shebang=""
  [ "$(head -c 2 "$file" 2>/dev/null)" = "#!" ] && shebang=1
  case "$magic" in
    7f454c46*) kind=ELF ;;
    *) [ -n "$shebang" ] && kind=script || continue ;;
  esac
  echo "sealed-payload: executable left in payload: ${file#"$PAYLOAD"/} ($kind)" >&2
  problems=$((problems + 1))
done < <(find "$PAYLOAD" -type f -perm -u+x 2>/dev/null)

# ---- 2. manifest shape ----------------------------------------------------
declare -A seen=()
libs=""
while IFS= read -r line; do
  line="${line%%$'\r'}"
  case "$line" in ''|'#'*) continue ;; esac
  path="${line%%[[:space:]]*}"
  lib="${line#*[[:space:]]}"
  lib="${lib#"${lib%%[![:space:]]*}"}"
  if [ -z "$path" ] || [ -z "$lib" ] || [ "$path" = "$lib" ]; then
    echo "sealed-manifest: malformed line: $line" >&2
    problems=$((problems + 1)); continue
  fi
  case "$path" in
    /*|*..*) echo "sealed-manifest: entry must be prefix-relative: $path" >&2
             problems=$((problems + 1)); continue ;;
  esac
  if [ -n "${seen[$path]:-}" ]; then
    echo "sealed-manifest: duplicate entry: $path" >&2
    problems=$((problems + 1)); continue
  fi
  seen[$path]=1
  libs+="$lib"$'\n'
  entries=$((entries + 1))
done < "$MANIFEST"

# ---- 3. every library present for every ABI ------------------------------
for abi_dir in "$@"; do
  [ -d "$abi_dir" ] || { echo "sealed-manifest: missing ABI directory: $abi_dir" >&2
                         problems=$((problems + 1)); continue; }
  while IFS= read -r lib; do
    [ -n "$lib" ] || continue
    [ -f "$abi_dir/$lib" ] || {
      echo "sealed-manifest: $(basename "$abi_dir") lacks $lib" >&2
      problems=$((problems + 1))
    }
  done <<< "$libs"
done

if [ "$problems" -ne 0 ]; then
  echo "sealed-payload: FAILED with $problems problem(s); $entries manifest entries checked" >&2
  exit 1
fi
echo "sealed-payload: ok - $entries executables shipped by the APK, none left in the payload"
