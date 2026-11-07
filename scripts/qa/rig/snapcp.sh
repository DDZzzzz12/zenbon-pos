#!/usr/bin/env bash
# Snapshots a classpath so later builds cannot change the classes under a running QA server.
#
#   usage: snapcp.sh <raw-cp-file> <snap-dir> <out-cp-file> <worktree-root>
#
# Every classpath entry under <worktree-root> (build/classes, build/libs, module resources) is copied
# into <snap-dir>/NNN-<name>; entries elsewhere (the Gradle cache) are kept as they are. The result is
# written as one colon-separated line to <out-cp-file>, which qa-server.sh reads via QA_CP_FILE.
#
# Why: several sessions/agents build in the same checkout. Without a snapshot, a `gradle build`
# elsewhere silently swaps the classes of a server that is mid-pass.
set -euo pipefail
IN=${1:-}; SNAP=${2:-}; OUT=${3:-}; ROOT=${4:-}
if [[ -z $IN || -z $SNAP || -z $OUT || -z $ROOT ]]; then
  echo "usage: snapcp.sh <raw-cp-file> <snap-dir> <out-cp-file> <worktree-root>" >&2; exit 2
fi
ROOT=$(cd "$ROOT" && pwd -P)
rm -rf "$SNAP"; mkdir -p "$SNAP"
IFS=':' read -r -a ENTRIES <<<"$(head -1 "$IN")"
parts=(); i=0; snapped=0
for e in "${ENTRIES[@]}"; do
  i=$((i + 1))
  [[ -e $e ]] || { echo "skip missing: $e"; continue; }
  case "$e" in
    "$ROOT"/*)
      d=$SNAP/$(printf %03d "$i")-$(basename "$e")
      cp -R "$e" "$d"
      parts+=("$d"); snapped=$((snapped + 1)) ;;
    *) parts+=("$e") ;;
  esac
done
(IFS=':'; printf '%s' "${parts[*]}") >"$OUT"
echo "entries=$i snapped=$snapped -> $OUT"
