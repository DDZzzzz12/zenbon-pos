#!/usr/bin/env bash
# Pre-tag build gate: every module's tests, ktlint, the android compile and the public-artifact TSE
# leak check, run so that a green result cannot be faked by the usual traps.
#
#   usage: gate.sh <worktree> [--only a,b,...] [--skip a,b,...] [--list]
#
#   <worktree>  a CLEAN checkout at the commit under test (rig.sh up creates one). Never the shared
#               main checkout: other sessions build there and overwrite each other's test XMLs.
#
# What it guards against (each one once produced a "green" gate that had not run):
#   - build-cache hits     every step runs with --no-build-cache and clean<Task> first, then checks that
#                          the result XMLs are NEWER than the step's start (a cached run leaves old ones)
#   - masked exit codes    one Gradle call per step, never piped; the step's own exit code is recorded
#   - adminApp disconnect  the browser suite can stop mid-run with 0 failures; missing suites (from the
#                          @Test sources) are re-run by name, and any still missing FAIL the step
#   - ktlint aborting      ktlintCheck runs with --continue, in its own call, after the tests
#   - real documents dir   tests render PDFs into ~/Zenbon POS/documents unless user.home is redirected;
#                          an init script redirects it and the file count is compared before/after
#   - TSE SDK in public    packageForDeployment -PincludeTseRuntime=false must leave no native/ and no
#     artifacts            com/secureflashcard/ class (CLAUDE.md "Release Flow")
#
# Output: $QA_DIR/gate/<step>.log per step, $QA_DIR/gate/summary.md, exit 0 only if every step passed.
# Takes hours (pos-server alone is the bulk): run it in the background and wait for it to finish.
set -uo pipefail

STEPS=(
  "pos-server|:pos-server:cleanTest :pos-server:test|pos-server/build/test-results/test"
  "platform-server|:platform-server:cleanTest :platform-server:test|platform/server/build/test-results/test"
  "ecr-server|:ecr-server:cleanTest :ecr-server:test|ecr/server/build/test-results/test"
  "fiscal-server|:fiscal-server:cleanTest :fiscal-server:test|fiscal/server/build/test-results/test"
  "accounting-server|:accounting-server:cleanTest :accounting-server:test|accounting/server/build/test-results/test"
  "backoffice-server|:backoffice-server:cleanTest :backoffice-server:test|backoffice/server/build/test-results/test"
  "pos-server-postgres|:pos-server:cleanPostgresTest :pos-server:postgresTest|pos-server/build/test-results/postgresTest"
  "pos-server-fatjar|:pos-server:cleanFatjarTest :pos-server:fatjarTest|pos-server/build/test-results/fatjarTest"
  "pos-tse|:pos-tse:cleanTest :pos-tse:test|pos-tse/build/test-results/test"
  "pos-export|:fiscal-export:cleanTest :fiscal-export:test|fiscal/export/build/test-results/test"
  "server-common|:platform-server-common:cleanTest :platform-server-common:test|platform/server-common/build/test-results/test"
  "feature-document|:accounting-document:cleanTest :accounting-document:test|accounting/document/build/test-results/test"
  "feature-printer|:ecr-printer:cleanTest :ecr-printer:test|ecr/printer/build/test-results/test"
  "ecr-printer-core|:ecr-printer-core:cleanTest :ecr-printer-core:test|ecr/printer-core/build/test-results/test"
  "ecr-receipt|:ecr-receipt:cleanTest :ecr-receipt:test|ecr/receipt/build/test-results/test"
  "einvoice|:accounting-einvoice:cleanTest :accounting-einvoice:test|accounting/einvoice/build/test-results/test"
  "secure-xml|:platform-secure-xml:cleanTest :platform-secure-xml:test|platform/secure-xml/build/test-results/test"
  "banking|:accounting-banking:cleanTest :accounting-banking:test|accounting/banking/build/test-results/test"
  "platform-api|:platform-api:cleanJvmTest :platform-api:jvmTest|platform/api/build/test-results/jvmTest"
  "ecr-api|:ecr-api:cleanJvmTest :ecr-api:jvmTest|ecr/api/build/test-results/jvmTest"
  "fiscal-api|:fiscal-api:cleanJvmTest :fiscal-api:jvmTest|fiscal/api/build/test-results/jvmTest"
  "accounting-api|:accounting-api:cleanJvmTest :accounting-api:jvmTest|accounting/api/build/test-results/jvmTest"
  "backoffice-api|:backoffice-api:cleanJvmTest :backoffice-api:jvmTest|backoffice/api/build/test-results/jvmTest"
  "money|:platform-money:cleanJvmTest :platform-money:jvmTest|platform/money/build/test-results/jvmTest"
  "shared-ui|:ui-shared:cleanDesktopTest :ui-shared:desktopTest|ui/shared/build/test-results/desktopTest"
  "composeApp|:ui-till:cleanDesktopTest :ui-till:desktopTest|ui/till/build/test-results/desktopTest"
  "adminApp|:ui-backoffice:cleanWasmJsBrowserTest :ui-backoffice:wasmJsBrowserTest|ui/backoffice/build/test-results/wasmJsBrowserTest"
  "android-compile|:ui-till-android:compileDebugKotlin|"
  "ktlint|ktlintCheck --continue|"
  "layering|checkLayering|"
  "public-artifacts|:pos-server:packageForDeployment -PbundleClients=false -PincludeTseRuntime=false|"
)

WT=${1:-}
[[ $WT == --list ]] && { for s in "${STEPS[@]}"; do echo "${s%%|*}"; done; exit 0; }
[[ -n $WT && -d $WT ]] || { echo "usage: gate.sh <worktree> [--only a,b] [--skip a,b] [--list]" >&2; exit 2; }
shift
ONLY="" SKIP=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --only) ONLY=",$2,"; shift 2 ;;
    --skip) SKIP=",$2,"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done

WT=$(cd "$WT" && pwd -P)
: "${QA_DIR:?set QA_DIR to the scratch dir of this pass, outside HOME, e.g. /private/tmp/qa-PASS on macOS, /tmp/qa-PASS on Linux}"
OUT=$QA_DIR/gate
mkdir -p "$OUT" "$QA_DIR/gate-home"
# One gate per scratch dir: two runs would overwrite each other's summary and logs.
LOCK=$OUT/.lock
mkdir "$LOCK" 2>/dev/null || { echo "another gate.sh is running for $QA_DIR (remove $LOCK if it is not)" >&2; exit 2; }
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

if [[ -n $(git -C "$WT" status --porcelain --untracked-files=no) ]]; then
  echo "worktree $WT has uncommitted changes to tracked files - a gate must run on a clean commit" >&2
  git -C "$WT" status --short --untracked-files=no >&2
  exit 2
fi
[[ -f $WT/local.properties ]] || { echo "$WT/local.properties missing (android compile needs it): cp local.properties $WT/" >&2; exit 2; }
HEAD_SHA=$(git -C "$WT" rev-parse --short HEAD)

INIT=$OUT/isolate-home.gradle
cat >"$INIT" <<EOF
allprojects { tasks.withType(Test).configureEach { systemProperty 'user.home', '$QA_DIR/gate-home' } }
EOF

DOCS="$HOME/Zenbon POS/documents"
docs_count() { if [[ -d $DOCS ]]; then find "$DOCS" -type f | wc -l | tr -d ' '; else echo 0; fi; }
DOCS_BEFORE=$(docs_count)

# Sums tests/failures/errors/skipped over the XMLs in $1 that are newer than epoch $2.
# Prints "<fresh files> <stale files> <tests> <failures> <errors> <skipped>".
xml_totals() {
  python3 - "$1" "$2" <<'PY'
import os, sys, xml.etree.ElementTree as ET
d, start = sys.argv[1], float(sys.argv[2])
fresh = stale = t = f = e = s = 0
if os.path.isdir(d):
    for n in os.listdir(d):
        if not n.endswith(".xml"):
            continue
        p = os.path.join(d, n)
        if os.path.getmtime(p) < start:
            stale += 1
            continue
        fresh += 1
        root = ET.parse(p).getroot()
        suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
        for su in suites:
            t += int(su.get("tests", 0)); f += int(su.get("failures", 0))
            e += int(su.get("errors", 0)); s += int(su.get("skipped", 0))
print(fresh, stale, t, f, e, s)
PY
}

# adminApp: classes declared in the @Test sources that have no result XML.
admin_missing() {
  python3 - "$WT" <<'PY'
import os, re, sys
wt = sys.argv[1]
src = os.path.join(wt, "ui/backoffice/src/wasmJsTest/kotlin")
res = os.path.join(wt, "ui/backoffice/build/test-results/wasmJsBrowserTest")
want = set()
for root, _, files in os.walk(src):
    for n in files:
        if not n.endswith(".kt"):
            continue
        text = open(os.path.join(root, n), encoding="utf-8").read()
        if "@Test" not in text:
            continue
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        want.add((pkg.group(1) + "." if pkg else "") + n[:-3])
have = set()
if os.path.isdir(res):
    for n in os.listdir(res):
        m = re.match(r"TEST-(?:wasmJsBrowserTest\.)?(.+)\.xml$", n)
        if m:
            have.add(m.group(1))
print("\n".join(sorted(want - have)))
PY
}

run_gradle() {  # $1 = log file, rest = gradle args; returns gradle's own exit code
  local log=$1; shift
  (cd "$WT" && ./gradlew --no-build-cache --no-configuration-cache -I "$INIT" "$@") >>"$log" 2>&1
}

SUMMARY=$OUT/summary.md
{
  echo "# Gate $(date '+%F %T') on \`$HEAD_SHA\` ($WT)"
  echo
  echo "| Step | Exit | Result | Tests | Fail | Err | Skip | Minutes |"
  echo "|---|---|---|---|---|---|---|---|"
} >"$SUMMARY"
OVERALL=0

for spec in "${STEPS[@]}"; do
  IFS='|' read -r NAME TASKS RESDIR <<<"$spec"
  [[ -n $ONLY && $ONLY != *",$NAME,"* ]] && continue
  [[ -n $SKIP && $SKIP == *",$NAME,"* ]] && continue
  LOG=$OUT/$NAME.log
  : >"$LOG"
  START=$(date +%s)
  echo "[$(date +%T)] $NAME: ./gradlew $TASKS"
  read -r -a ARGS <<<"$TASKS"
  run_gradle "$LOG" "${ARGS[@]}"
  RC=$?
  VERDICT=PASS NOTE=""
  T=- F=- E=- S=-

  if [[ $NAME == adminApp ]]; then
    MISSING=$(admin_missing)
    if [[ -n $MISSING ]]; then
      echo "  adminApp: $(wc -l <<<"$MISSING" | tr -d ' ') suite(s) without results - re-running them by name" | tee -a "$LOG"
      RERUN=()
      while read -r cls; do RERUN+=(--tests "$cls"); done <<<"$MISSING"
      run_gradle "$LOG" :ui-backoffice:wasmJsBrowserTest "${RERUN[@]}"
      RC2=$?
      MISSING=$(admin_missing)
      [[ $RC2 -ne 0 ]] && RC=$RC2
      if [[ -n $MISSING ]]; then
        VERDICT=FAIL NOTE="still no results for: $(tr '\n' ' ' <<<"$MISSING")"
      elif [[ $RC -ne 0 ]] && grep -q "Disconnected" "$LOG"; then
        RC=0 NOTE="browser disconnected; every suite re-run by name"
      fi
    fi
  fi

  if [[ -n $RESDIR ]]; then
    read -r FRESH STALE T F E S <<<"$(xml_totals "$WT/$RESDIR" "$START")"
    if [[ $FRESH -eq 0 ]]; then
      VERDICT=FAIL NOTE="no result XML written by this run (cache hit or nothing executed)"
    elif [[ $STALE -gt 0 && $NAME != adminApp ]]; then
      NOTE="$STALE stale XML(s) from an earlier run ignored"
    fi
    [[ $F != 0 || $E != 0 ]] && VERDICT=FAIL
  fi
  [[ $RC -ne 0 ]] && VERDICT=FAIL

  if [[ $NAME == public-artifacts && $RC -eq 0 ]]; then
    if NOTE=$(python3 "$(dirname "$0")/rig/leak_scan.py" "$WT/pos-server/build/deploy"); then :; else VERDICT=FAIL; fi
  fi

  MIN=$(( ($(date +%s) - START + 59) / 60 ))
  echo "| $NAME | $RC | $VERDICT | $T | $F | $E | $S | $MIN |" >>"$SUMMARY"
  [[ -n $NOTE ]] && echo "|  ↳ | | $NOTE | | | | | |" >>"$SUMMARY"
  echo "[$(date +%T)] $NAME: $VERDICT (exit $RC, tests $T, failures $F, errors $E) ${NOTE}"
  [[ $VERDICT == FAIL ]] && OVERALL=1
done

DOCS_AFTER=$(docs_count)
{
  echo
  echo "Real documents dir ($DOCS): $DOCS_BEFORE files before, $DOCS_AFTER after."
} >>"$SUMMARY"
if [[ $DOCS_BEFORE != "$DOCS_AFTER" ]]; then
  echo "**FAIL: the gate wrote into the real documents dir** - a test escaped the user.home redirect." >>"$SUMMARY"
  OVERALL=1
fi
echo
cat "$SUMMARY"
exit $OVERALL
