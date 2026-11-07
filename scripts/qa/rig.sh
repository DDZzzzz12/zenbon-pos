#!/usr/bin/env bash
# One command per rig state change for a QA pass (docs/testing/qa-rig.md sections 1-4, 8, 11).
#
#   rig.sh up <pass> [<ref>] [--tse v1|v2|none] [--http <port>] [--print] [--cp virtual|run]
#   rig.sh upgrade <pass> <newref>        stop, swap in the build of <newref>, start on the SAME data dir
#   rig.sh reset <pass>                   fresh EMPTY database, same worktree/snapshot/ports (then seed again)
#   rig.sh restart <pass>                 same data dir and ports (the virtual TSE is wiped by this)
#   rig.sh status <pass>
#   rig.sh down <pass> [--purge]          stop everything; --purge also deletes the worktree and scratch dir
#
#   <pass>  short id of this pass, e.g. rt26 - names the worktree, the scratch dir and the server
#   <ref>   commit under test (default: HEAD of the main checkout)
#
# `up` does, and checks, every isolation step a pass needs:
#   - a detached worktree .claude/worktrees/qa-<pass> at <ref> (+ local.properties), clean
#   - QA_DIR=$QA_TMP/qa-<pass> (QA_TMP = /private/tmp on macOS, /tmp elsewhere; outside $HOME)
#   - the runVirtual classpath built once and SNAPSHOTTED (other sessions' builds cannot swap classes)
#   - a FREE embedded-Postgres port (a busy one makes the server migrate another session's database)
#   - the server via rig/qa-server.sh: own data dir, own user.home, JWT dev mode off, virtual TSE
#   - waits for it, then proves isolation: isInitialized:false and "First run detected ... <data dir>"
#   - --print also starts the ESC/POS capture listener (rig/print_listener.py) on a free port
#   - writes $QA_DIR/rig.env with everything later steps need (source it)
#
# --cp run: the plain `run` classpath without the virtual TSE (`printRunCp`), for an OLD release tag that
# predates runVirtual - the starting point of an upgrade rehearsal (`up <pass> <old tag> --cp run`,
# seed or restore, then `upgrade <pass> <ref under test>`).
#
# --http: pos-server takes its port only from the DB config, so a fresh DB binds 8080. Another port
# needs the QA-only patch in Application.kt; it goes into a sibling worktree qa-<pass>-port, so the
# pass worktree stays clean for gate.sh. The adminApp dev server proxies to localhost:8080, so a UI
# pass must use 8080.
set -euo pipefail

# Scratch base outside $HOME: /private/tmp on macOS (its write-deny sandbox protects $HOME), /tmp elsewhere.
# Override with QA_TMP; every later script gets its paths from QA_DIR/rig.env, never from this default.
if [[ -z ${QA_TMP:-} ]]; then
  if [[ -d /private/tmp ]]; then QA_TMP=/private/tmp; else QA_TMP=/tmp; fi
fi

REPO=$(cd "$(dirname "$0")/../.." && pwd -P)
RIG=$REPO/scripts/qa/rig
CMD=${1:-}; PASS=${2:-}
[[ -n $CMD && -n $PASS ]] || { sed -n '2,10p' "$0" >&2; exit 2; }
shift 2
[[ $PASS =~ ^[a-z0-9][a-z0-9-]*$ ]] || { echo "pass id must be lowercase letters, digits, dashes" >&2; exit 2; }

export QA_DIR=${QA_DIR:-$QA_TMP/qa-$PASS}
WT=$REPO/.claude/worktrees/qa-$PASS
ENVF=$QA_DIR/rig.env
APP_KT=pos-server/src/main/kotlin/org/example/project/Application.kt
PORT_ANCHOR='ShopConfigurationManager.getServerPort()'
PORT_PATCH='System.getenv("QA_SERVER_PORT")?.toInt() ?: ShopConfigurationManager.getServerPort()'

busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

# Ports are CLAIMED, not just found free: two rigs started together both saw 5450 free, and the second
# server ran against the first one's Postgres (2026-10-09, "duplicate key ... rolname=pos"). mkdir is
# atomic, so exactly one claimant wins. A claim is stale when its rig.sh is gone AND its pass is down.
CLAIMS=$QA_TMP/qa-port-claims
claim_port() {  # $1 first, $2 last -> prints the claimed port
  local p owner pid
  mkdir -p "$CLAIMS"
  for p in $(seq "$1" "$2"); do
    busy "$p" && continue
    if ! mkdir "$CLAIMS/$p" 2>/dev/null; then
      read -r owner pid <"$CLAIMS/$p/owner" 2>/dev/null || true
      if [[ -n ${owner:-} ]] && ! kill -0 "${pid:-0}" 2>/dev/null && [[ ! -f $QA_TMP/qa-$owner/rig.env ]]; then
        rm -rf "$CLAIMS/$p"
        mkdir "$CLAIMS/$p" 2>/dev/null || continue
      else
        continue
      fi
    fi
    echo "$PASS $$" >"$CLAIMS/$p/owner"
    echo "$p"
    return 0
  done
  return 1
}
release_port() {  # $1 port - only a claim this pass owns
  local owner _pid
  [[ -n ${1:-} && -f $CLAIMS/$1/owner ]] || return 0
  read -r owner _pid <"$CLAIMS/$1/owner" || true
  [[ $owner == "$PASS" ]] && rm -rf "${CLAIMS:?}/$1"
  return 0
}
die() { echo "rig: $*" >&2; exit 1; }

up_failed() {  # a failed `up` must not leave a half-started rig behind (stray server, listener, rig.env)
  trap - ERR EXIT
  pkill -f "home-$PASS" 2>/dev/null || true
  pkill -f "qa-server.sh $PASS " 2>/dev/null || true
  [[ -n ${PRINT_PORT:-} ]] && pkill -f "print_listener.py --port $PRINT_PORT" 2>/dev/null || true
  release_port "${PG:-}"; release_port "${PRINT_PORT:-}"
  rm -f "$ENVF"
  echo "rig: up FAILED - stopped what it had started; fix the cause, then 'rig.sh down $PASS --purge' and retry" >&2
  exit 1
}

alive() { pgrep -f "home-$PASS" >/dev/null || pgrep -f "qa-server.sh $PASS " >/dev/null; }

wait_up() {  # $1 base url, $2 seconds
  local i
  for ((i = 0; i < $2; i += 3)); do
    curl -fsS -m 3 "$1/api/setup/status" >/dev/null 2>&1 && return 0
    ((i > 15)) && ! alive && return 1
    sleep 3
  done
  return 1
}

# The console log ($QA_DIR/server-<pass>.log) and the server's own application.log under user.home -
# only what was written since the last start_server (application.log survives restarts, and an old
# "Reusing ..." line must not pass for the current start).
APP_LOG_REL="Zenbon POS/.data/log/application.log"
LOG_MARK_CONSOLE=0 LOG_MARK_APP=0
fsize() { [[ -f $1 ]] && wc -c <"$1" | tr -d ' ' || echo 0; }
server_logs() {
  tail -c +$((LOG_MARK_CONSOLE + 1)) "$QA_DIR/server-$PASS.log" 2>/dev/null
  tail -c +$((LOG_MARK_APP + 1)) "$QA_DIR/home-$PASS/$APP_LOG_REL" 2>/dev/null
}

# Never `server_logs | grep -q`: under pipefail, grep -q exiting at the first match SIGPIPEs tail, and
# the pipeline FAILS once the log outgrows the 64 KB pipe buffer. grep -c reads everything.
logs_match() {  # $1 extended regex
  local n
  n=$(server_logs | grep -cE -- "$1" || true)
  [[ ${n:-0} -gt 0 ]]
}

# The server answers HTTP before its async log appender has flushed the startup lines: poll for them.
wait_log() {  # $1 extended regex, $2 seconds
  local i
  for ((i = 0; i < $2; i += 2)); do
    logs_match "$1" && return 0
    sleep 2
  done
  return 1
}

start_server() {  # uses PG HTTP TSE; waits; prints the setup status
  local log=$QA_DIR/server-$PASS.log
  LOG_MARK_CONSOLE=0   # qa-server.sh truncates the console log on every start
  LOG_MARK_APP=$(fsize "$QA_DIR/home-$PASS/$APP_LOG_REL")
  QA_CP_FILE=${CP_FILE:-$QA_DIR/cp.snap.txt} nohup "$RIG/qa-server.sh" "$PASS" "$PG" "$HTTP" "$TSE" \
    >"$QA_DIR/launcher-$PASS.log" 2>&1 &
  echo "rig: server starting (log $log)"
  if ! wait_up "http://localhost:$HTTP" 240; then
    cat "$QA_DIR/launcher-$PASS.log" >&2; tail -30 "$log" >&2 2>/dev/null
    if logs_match "could not create any TCP/IP sockets|pg_authid_rolname_index"; then
      pkill -f "home-$PASS" || true
      die "Postgres port $PG COLLIDED with another cluster (another rig or session). Nothing of yours ran against it; 'down --purge' and retry once"
    fi
    die "server did not come up - see $QA_DIR/launcher-$PASS.log and $log"
  fi
  if logs_match "could not create any TCP/IP sockets"; then
    pkill -f "home-$PASS" || true
    die "Postgres port $PG collided with another postmaster - STOPPED; nothing else may run against it"
  fi
}

write_env() {
  local jar
  jar=$(find "$HOME/.gradle/caches" -name 'postgresql-42.*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort | tail -1)
  cat >"$ENVF" <<EOF
# written by rig.sh up $PASS - source this file; it holds no credential of a real shop
export QA_PASS=$PASS
export QA_DIR=$QA_DIR
export QA_WT=$WT
export QA_SHA=$SHA
export QA_BASE=http://localhost:$HTTP
export QA_HTTP_PORT=$HTTP
export QA_PG_PORT=$PG
export QA_PG_USER=pos
export QA_PG_PASSWORD=pos
export QA_PG_DB=pos_db
export QA_PGJDBC_JAR=$jar
export QA_TSE=$TSE
export QA_PRINT_PORT=${PRINT_PORT:-}
export QA_CP_FILE=${CP_FILE:-$QA_DIR/cp.snap.txt}
# Every Bash tool call is a fresh shell: this one file brings back the rig AND the fixture logins.
[ -f "$QA_DIR/creds.env" ] && . "$QA_DIR/creds.env"
true
EOF
}

cmd_up() {
  local ref="" PRINT=0 CPKIND=virtual
  TSE=v1 HTTP=8080
  while [[ $# -gt 0 ]]; do
    case $1 in
      --tse) TSE=$2; shift 2 ;;
      --http) HTTP=$2; shift 2 ;;
      --print) PRINT=1; shift ;;
      --cp) CPKIND=$2; shift 2 ;;
      -*) die "unknown option $1" ;;
      *) ref=$1; shift ;;
    esac
  done
  ref=${ref:-$(git -C "$REPO" rev-parse HEAD)}
  git -C "$REPO" rev-parse --verify -q "$ref^{commit}" >/dev/null || die "unknown ref $ref"
  [[ -f $ENVF ]] && die "$ENVF exists - this pass is already up (rig.sh status $PASS / down $PASS)"
  busy "$HTTP" && die "HTTP port $HTTP is busy ($(lsof -nP -iTCP:"$HTTP" -sTCP:LISTEN | awk 'NR==2{print $1" pid "$2}')). Do not kill it unless it is yours; use --http <other> for an API-only pass"
  [[ -e $QA_DIR/data-$PASS ]] && die "$QA_DIR/data-$PASS exists from an earlier run - 'rig.sh restart $PASS' to reuse it, or 'rig.sh down $PASS --purge' first"
  mkdir -p "$QA_DIR"
  trap 'up_failed' ERR EXIT

  # 1. worktree
  if [[ ! -d $WT ]]; then
    git -C "$REPO" worktree add --detach "$WT" "$ref" >/dev/null
  fi
  cp "$REPO/local.properties" "$WT/" 2>/dev/null || echo "rig: no local.properties in the main checkout (android builds will fail)"
  SHA=$(git -C "$WT" rev-parse --short HEAD)
  [[ -z $(git -C "$WT" status --porcelain --untracked-files=no) ]] || die "worktree $WT is not clean"
  [[ $(git -C "$WT" rev-parse HEAD) == $(git -C "$REPO" rev-parse "$ref^{commit}") ]] || die "worktree $WT is at $SHA, not at $ref - remove it or pick another pass id"
  echo "rig: worktree $WT at $SHA"

  # 2. classpath snapshot (from a patched sibling worktree when --http is not 8080, so $WT stays clean
  #    for gate.sh, the APK and the adminApp build)
  [[ $CPKIND == run ]] && TSE=none
  CP_FILE=$QA_DIR/cp.snap.txt
  snapshot_classpath "$(server_worktree "$WT" "$ref")" "$CPKIND" "$QA_DIR/cp" "$QA_DIR/cpsnap"

  # 4. ports + server
  PG=$(claim_port 5450 5499) || die "no free Postgres port in 5450-5499"
  if [[ $PRINT == 1 ]]; then
    PRINT_PORT=$(claim_port 9107 9199) || die "no free print-capture port"
    mkdir -p "$QA_DIR/prn"
    nohup python3 "$RIG/print_listener.py" --port "$PRINT_PORT" --out "$QA_DIR/prn" >"$QA_DIR/print-listener.log" 2>&1 &
    echo "rig: ESC/POS capture on 127.0.0.1:$PRINT_PORT -> $QA_DIR/prn"
  fi
  write_env
  start_server

  # 5. isolation proof
  prove_fresh
  trap - ERR EXIT
  echo "rig: UP  pass=$PASS sha=$SHA base=http://localhost:$HTTP pg=$PG tse=$TSE"
  echo "rig: next: source $ENVF"
}

# The worktree the server is built from: $1 itself on 8080; otherwise a sibling "$1-port" at the same
# ref carrying the QA-only port patch (pos-server reads its port only from the DB config, and a fresh
# DB says 8080). Never commit the patch; `down --purge` removes the sibling.
server_worktree() {  # $1 clean worktree, $2 ref
  if [[ $HTTP == 8080 ]]; then echo "$1"; return; fi
  local pwt=$1-port
  [[ -d $pwt ]] || git -C "$REPO" worktree add --detach "$pwt" "$2" >/dev/null
  cp "$REPO/local.properties" "$pwt/" 2>/dev/null || true
  if ! grep -qF "$PORT_PATCH" "$pwt/$APP_KT"; then
    grep -qF "$PORT_ANCHOR" "$pwt/$APP_KT" || die "port anchor not found in $pwt/$APP_KT"
    sed -i.qabak "s|$PORT_ANCHOR|$PORT_PATCH|" "$pwt/$APP_KT" && rm -f "$pwt/$APP_KT.qabak"
  fi
  echo "rig: server for port $HTTP is built from $pwt (QA-only port patch; never commit it)" >&2
  echo "$pwt"
}

snapshot_classpath() {  # $1 worktree, $2 virtual|run, $3 output prefix, $4 snapshot dir
  local task=printRvCp
  [[ $2 == run ]] && task=printRunCp
  echo "rig: building the $2 classpath of $(git -C "$1" rev-parse --short HEAD) (minutes on a cold worktree)"
  (cd "$1" && ./gradlew -q --no-configuration-cache -I "$RIG/printcp.gradle" \
      -Dqa.cpout="$3.raw.txt" ":pos-server:$task") >"$3-build.log" 2>&1 \
    || { tail -30 "$3-build.log" >&2; die "classpath build failed - see $3-build.log"; }
  "$RIG/snapcp.sh" "$3.raw.txt" "$4" "$3.snap.txt" "$1" >/dev/null
}

prove_fresh() {
  local st
  st=$(curl -fsS "http://localhost:$HTTP/api/setup/status")
  wait_log "initdb will create a new data directory at: $QA_DIR/data-$PASS" 60 \
    || { echo "$st"; die "no 'First run detected ... $QA_DIR/data-$PASS' in the logs - NOT this pass's fresh database; stop and investigate"; }
  echo "$st" | grep -q '"isInitialized":false' || die "setup status is not isInitialized:false - NOT an isolated fresh server: $st"
}

stop_server() {
  pkill -f "home-$PASS" || true
  for _ in $(seq 1 40); do busy "$HTTP" || busy "$PG" || break; sleep 1; done
  busy "$PG" && die "Postgres port $PG still busy after stop"
  return 0
}

cmd_reset() {  # fresh empty database, same worktree, snapshot and ports
  [[ -f $ENVF ]] || die "no $ENVF - run rig.sh up first"
  # shellcheck disable=SC1090
  source "$ENVF"
  PG=$QA_PG_PORT HTTP=$QA_HTTP_PORT TSE=$QA_TSE CP_FILE=$QA_CP_FILE
  stop_server
  rm -rf "$QA_DIR/data-$PASS" "$QA_DIR/home-$PASS" "$QA_DIR/creds.env" "$QA_DIR/fixture.json" "$QA_DIR/pairing.txt"
  mv "$QA_DIR/server-$PASS.log" "$QA_DIR/server-$PASS.$(date +%H%M%S).log" 2>/dev/null || true
  start_server
  prove_fresh
  echo "rig: RESET $PASS - fresh empty database on $QA_BASE (seed it again)"
}

cmd_restart() {
  [[ -f $ENVF ]] || die "no $ENVF - run rig.sh up first"
  # shellcheck disable=SC1090
  source "$ENVF"
  PG=$QA_PG_PORT HTTP=$QA_HTTP_PORT TSE=$QA_TSE CP_FILE=$QA_CP_FILE
  stop_server
  start_server
  wait_log "Reusing existing PostgreSQL data directory: $QA_DIR/data-$PASS" 60 \
    || die "restart did not reuse $QA_DIR/data-$PASS"
  echo "rig: restarted. The virtual TSE is UNINITIALIZED now; re-commissioning reuses its serial and restarts TANR at 1."
}

cmd_upgrade() {  # the in-place upgrade of qa-rig.md section 5.1, on whatever data the pass holds
  local newref=${1:-}
  [[ -n $newref ]] || die "usage: rig.sh upgrade <pass> <newref>"
  [[ -f $ENVF ]] || die "no $ENVF - run rig.sh up first"
  # shellcheck disable=SC1090
  source "$ENVF"
  PG=$QA_PG_PORT HTTP=$QA_HTTP_PORT
  local nwt=$REPO/.claude/worktrees/qa-$PASS-new
  [[ -d $nwt ]] || git -C "$REPO" worktree add --detach "$nwt" "$newref" >/dev/null
  cp "$REPO/local.properties" "$nwt/" 2>/dev/null || true
  [[ $(git -C "$nwt" rev-parse HEAD) == $(git -C "$REPO" rev-parse "$newref^{commit}") ]] || die "$nwt is not at $newref"
  [[ -z $(git -C "$nwt" status --porcelain --untracked-files=no) ]] || die "worktree $nwt is not clean"
  snapshot_classpath "$(server_worktree "$nwt" "$newref")" virtual "$QA_DIR/cp-new" "$QA_DIR/cpsnap-new"
  stop_server
  rm -rf "$QA_DIR/data-$PASS-before-upgrade"
  cp -R "$QA_DIR/data-$PASS" "$QA_DIR/data-$PASS-before-upgrade"   # kept for re-runs of the upgrade
  echo "rig: data dir copied to data-$PASS-before-upgrade"
  : >"$QA_DIR/server-$PASS.log"
  TSE=v1 CP_FILE=$QA_DIR/cp-new.snap.txt SHA=$(git -C "$nwt" rev-parse --short HEAD) WT=$nwt
  PRINT_PORT=${QA_PRINT_PORT:-}
  start_server
  wait_log "Reusing existing PostgreSQL data directory: $QA_DIR/data-$PASS" 60 \
    || die "the new build did not reuse $QA_DIR/data-$PASS - this is not an in-place upgrade"
  write_env
  echo "rig: Flyway on the new build:"
  server_logs | grep -oE 'Current version of schema [^,]{0,40}: [0-9]+|Migrating schema [^,]{0,80}|Successfully applied [0-9]+ migrations?[^,]{0,60}|up to date\. No migration necessary|FlywayException[^,]{0,120}' \
    | sed 's/\\"/"/g' | sort -u
  echo "rig: UPGRADED $PASS to $SHA (virtual TSE uninitialised after the restart - commission again if the cases sign)"
}

cmd_status() {
  [[ -f $ENVF ]] || { echo "rig: $PASS is not up"; return; }
  # shellcheck disable=SC1090
  source "$ENVF"
  echo "pass=$PASS sha=$QA_SHA base=$QA_BASE pg=$QA_PG_PORT tse=$QA_TSE print=${QA_PRINT_PORT:-off}"
  busy "$QA_HTTP_PORT" && echo "http: listening" || echo "http: DOWN"
  busy "$QA_PG_PORT" && echo "postgres: listening" || echo "postgres: DOWN"
  curl -fsS -m 5 "$QA_BASE/api/setup/status" 2>/dev/null || echo "setup/status: no answer"
  echo
}

cmd_down() {
  local purge=0
  [[ ${1:-} == --purge ]] && purge=1
  pkill -f "home-$PASS" || true
  if [[ -f $ENVF ]]; then
    # shellcheck disable=SC1090
    source "$ENVF"
    [[ -n ${QA_PRINT_PORT:-} ]] && pkill -f "print_listener.py --port $QA_PRINT_PORT" || true
    release_port "$QA_PG_PORT"; release_port "${QA_PRINT_PORT:-}"
    for _ in $(seq 1 40); do busy "$QA_HTTP_PORT" || busy "$QA_PG_PORT" || break; sleep 1; done
    busy "$QA_PG_PORT" && echo "rig: WARNING Postgres port $QA_PG_PORT still listening"
    rm -f "$ENVF"
  fi
  if [[ -d $WT ]]; then
    [[ -z $(git -C "$WT" status --porcelain --untracked-files=no) ]] || echo "rig: WARNING $WT has changes"
  fi
  if [[ $purge == 1 && -d $QA_DIR ]]; then
    # Results outlive the scratch dir (Step 8 reports them). Server logs do not: a restored pass's
    # logs carry shop data.
    local keep=$QA_TMP/qa-results-$PASS f
    mkdir -p "$keep"
    for f in "$QA_DIR"/results-*.md "$QA_DIR"/api-smoke.md "$QA_DIR"/gate/summary.md; do
      [[ -f $f ]] && cp "$f" "$keep/"
    done
    echo "rig: kept results in $keep"
  fi
  if [[ $purge == 1 ]]; then
    local w
    for w in "$WT" "$WT-port" "$WT-new" "$WT-new-port"; do
      [[ -d $w ]] && git -C "$REPO" worktree remove --force "$w"
    done
    case $QA_DIR in "$QA_TMP"/qa-*) rm -rf "$QA_DIR" ;; *) echo "rig: not deleting $QA_DIR (not under $QA_TMP/qa-*)" ;; esac
    echo "rig: purged worktree and scratch dir"
  fi
  echo "rig: DOWN $PASS"
}

case $CMD in
  up) cmd_up "$@" ;;
  reset) cmd_reset ;;
  upgrade) cmd_upgrade "$@" ;;
  restart) cmd_restart ;;
  status) cmd_status ;;
  down) cmd_down "$@" ;;
  *) die "unknown command $CMD (up|reset|restart|upgrade|status|down)" ;;
esac
