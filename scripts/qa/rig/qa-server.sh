#!/usr/bin/env bash
# Starts one isolated pos-server for a QA pass from a SNAPSHOTTED classpath (see snapcp.sh).
#
#   usage: qa-server.sh <name> <pg-port> <http-port> [v1|v2|none]
#
# Isolation it gives you:
#   - its own embedded Postgres:  $QA_DIR/data-<name> on <pg-port> (port must be free)
#   - its own user.home:          $QA_DIR/home-<name>  (documents, logs, backups, DATEV default here)
#   - JWT dev mode OFF            (dev mode skips owner creation and impersonates; never use it for QA)
#   - a virtual TSE (v1 = TSE 1.0, v2 = TSE 2.0) unless "none"
#   - macOS only: sandbox-exec denying every file write under $QA_SANDBOX_DENY (default $HOME), so a
#     restored backup/DATEV job that points at the shop's real folders fails instead of writing there.
#     QA_DIR must therefore live OUTSIDE that subtree (e.g. under /private/tmp).
#
# Environment:
#   QA_DIR          scratch root (required)
#   QA_CP_FILE      snapshotted classpath file (default $QA_DIR/cp.snap.txt)
#   QA_JWT_SECRET   JWT secret (default: a random one per start, so old tokens die with the process)
#   QA_SANDBOX      1 = use sandbox-exec when available (default 1), 0 = off
#   QA_SANDBOX_DENY subtree to write-protect (default $HOME)
#
# HTTP port caveat: pos-server takes its port ONLY from the DB config (SERVER_PORT); a fresh DB binds
# 8080 and a restored DB binds the shop's configured port. QA_SERVER_PORT is exported below but is
# honoured only by a build carrying the uncommitted QA patch described in docs/testing/qa-rig.md.
# <http-port> is otherwise only checked for being free.
#
# Logs go to $QA_DIR/server-<name>.log. Stop with:  pkill -f "home-<name>"   (never `lsof -ti :8080 | kill`:
# that also kills an Android emulator holding a client connection to 8080).
set -euo pipefail

NAME=${1:-}; PG=${2:-}; HTTP=${3:-}; GEN=${4:-v1}
if [[ -z $NAME || -z $PG || -z $HTTP ]]; then
  echo "usage: qa-server.sh <name> <pg-port> <http-port> [v1|v2|none]" >&2; exit 2
fi
: "${QA_DIR:?set QA_DIR to the scratch root}"
CP_FILE=${QA_CP_FILE:-$QA_DIR/cp.snap.txt}
[[ -f $CP_FILE ]] || { echo "classpath file $CP_FILE missing - run snapcp.sh first" >&2; exit 2; }

busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
if busy "$PG"; then
  echo "PG port $PG is busy. Another session's Postgres would be MIGRATED by this server. Free ports:" >&2
  for p in $(seq 5432 5499); do busy "$p" || printf '%s ' "$p"; done; echo >&2
  exit 3
fi
busy "$HTTP" && { echo "HTTP port $HTTP is busy" >&2; exit 3; }

DENY=${QA_SANDBOX_DENY:-$HOME}
case "$QA_DIR/" in "$DENY"/*) echo "QA_DIR ($QA_DIR) is inside the write-denied subtree $DENY" >&2; exit 2;; esac

mkdir -p "$QA_DIR/home-$NAME"
export JWT_SECRET=${QA_JWT_SECRET:-$(openssl rand -hex 32)}
export JWT_DEVELOPMENT_MODE=false
export DATABASE_MODE=embedded
export EMBEDDED_PG_PORT=$PG
export EMBEDDED_PG_DATA_DIR=$QA_DIR/data-$NAME
export QA_SERVER_PORT=$HTTP

JAVA_ARGS=(-Duser.home="$QA_DIR/home-$NAME")
if [[ $GEN != none ]]; then
  JAVA_ARGS+=(-Dtse.virtual.device=present -Dtse.virtual.generation="$GEN")
fi
JAVA_ARGS+=(-cp "$(head -1 "$CP_FILE")" org.example.project.ApplicationKt)

cd "$QA_DIR/home-$NAME"
LOG=$QA_DIR/server-$NAME.log
echo "starting $NAME: pg=$PG data=$EMBEDDED_PG_DATA_DIR home=$QA_DIR/home-$NAME tse=$GEN log=$LOG"
if [[ ${QA_SANDBOX:-1} == 1 ]] && command -v sandbox-exec >/dev/null; then
  SBX="(version 1)(allow default)(deny file-write* (subpath \"$DENY\"))"
  exec sandbox-exec -p "$SBX" java "${JAVA_ARGS[@]}" >"$LOG" 2>&1
fi
exec java "${JAVA_ARGS[@]}" >"$LOG" 2>&1
