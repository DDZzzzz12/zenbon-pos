#!/bin/bash
# Tests for the system-clock warning in install-service.sh (pure function, no system access).
#   bash pos-server/scripts/test/install-service-clock-test.sh
HERE="$(cd "$(dirname "$0")" && pwd)"
source "${HERE}/../install-service.sh"
PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL $1"; }

BUILT=1792800000   # 2026-10-24
NOW_OK=$((BUILT + 3600))
NOW_BEHIND=$((BUILT - 86400 * 160))

out="$(clock_warning "${NOW_OK}" "${BUILT}" yes)"
[ -z "${out}" ] && ok "a clock after the build date with time sync is fine" || bad "fine clock warned: ${out}"

out="$(clock_warning "${NOW_BEHIND}" "${BUILT}" yes)"
[[ "${out}" == *"earlier than this package was built"* ]] && ok "a clock months behind the package warns" || bad "behind clock: '${out}'"

out="$(clock_warning "$((BUILT - 3600))" "${BUILT}" yes)"
[ -z "${out}" ] && ok "a clock an hour before the build (package built elsewhere) does not warn" || bad "hour-early warned: ${out}"

out="$(clock_warning "${NOW_OK}" "${BUILT}" no)"
[[ "${out}" == *"not synchronized"* ]] && ok "an unsynchronized clock warns" || bad "unsynced: '${out}'"

out="$(clock_warning "${NOW_OK}" "${BUILT}" unknown)"
[ -z "${out}" ] && ok "unknown sync state alone does not warn" || bad "unknown warned: ${out}"

out="$(clock_warning "${NOW_BEHIND}" 0 yes)"
[ -z "${out}" ] && ok "no package date, no comparison" || bad "no-package warned: ${out}"

echo "passed: ${PASS}  failed: ${FAIL}"
[ "${FAIL}" -eq 0 ]
