#!/bin/bash
# Tests for --migrate-data-dir in install-service.sh. Temp directories, stubbed systemctl/curl/df, real rsync.
#   bash pos-server/scripts/test/install-service-migrate-test.sh
HERE="$(cd "$(dirname "$0")" && pwd)"
source "${HERE}/../install-service.sh"
PASS=0
FAIL=0
ok()    { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad()   { FAIL=$((FAIL + 1)); echo "  FAIL $1"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

command -v rsync >/dev/null || { echo "rsync is required for this test"; exit 1; }

new_world() {
    TMP="$(mktemp -d)"
    mkdir -p "${TMP}/bin" "${TMP}/home/Zenbon POS/.data/postgresql" "${TMP}/home/Zenbon POS/.data/log" \
        "${TMP}/home/Zenbon POS/documents" "${TMP}/etc/systemd" "${TMP}/opt"
    echo 16 > "${TMP}/home/Zenbon POS/.data/postgresql/PG_VERSION"
    echo "# conf" > "${TMP}/home/Zenbon POS/.data/postgresql/postgresql.conf"
    head -c 4096 /dev/urandom > "${TMP}/home/Zenbon POS/documents/a.pdf"
    echo log > "${TMP}/home/Zenbon POS/.data/log/application.log"
    printf 'JWT_SECRET=abc\n' > "${TMP}/opt/pos-server.env"
    printf '[Service]\nEnvironment=HOME=/home/pos\nEnvironment=DATABASE_MODE=embedded\nExecStartPost=/bin/bash -c "curl http://127.0.0.1:8080/health"\nReadWritePaths="x"\n' > "${TMP}/etc/systemd/pos-server.service"
    cp "${TMP}/etc/systemd/pos-server.service" "${TMP}/unit.orig"
    # stubs
    cat > "${TMP}/bin/systemctl" <<'STUB'
#!/bin/bash
echo "$*" >> "${STUB_LOG}"
if [ "$1" = "is-active" ]; then [ -f "${STUB_ACTIVE}" ]; exit $?; fi
if [ "$1" = "stop" ]; then rm -f "${STUB_ACTIVE}"; fi
if [ "$1" = "start" ]; then
    touch "${STUB_ACTIVE}"
    # the real server rebases its stored paths on start and then deletes the marker
    if [ -f "${STUB_MARKER}" ]; then cp "${STUB_MARKER}" "${STUB_LOG}.marker"; [ -n "${STUB_KEEP_MARKER:-}" ] || rm -f "${STUB_MARKER}"; fi
fi
exit 0
STUB
    printf '#!/bin/bash\nexit "${STUB_CURL_EXIT:-0}"\n' > "${TMP}/bin/curl"
    printf '#!/bin/bash\nexit 0\n' > "${TMP}/bin/sleep"
    printf '#!/bin/bash\necho "Filesystem 1024-blocks Used Available Capacity Mounted"\necho "x 100 0 ${STUB_FREE_KB:-100000000} 0%% /"\n' > "${TMP}/bin/df"
    # rsync wrapper: the real rsync, then optionally drop a copied file to force a verification mismatch
    REAL_RSYNC="$(command -v rsync)"
    cat > "${TMP}/bin/rsync" <<STUB
#!/bin/bash
"${REAL_RSYNC}" "\$@" || exit \$?
[ -n "\${STUB_DAMAGE:-}" ] && rm -f "\${STUB_DAMAGE}"
exit 0
STUB
    chmod +x "${TMP}/bin/"*
    export PATH="${TMP}/bin:${PATH}"
    export STUB_LOG="${TMP}/systemctl.log" STUB_ACTIVE="${TMP}/active"
    : > "${STUB_LOG}"
    unset STUB_CURL_EXIT STUB_FREE_KB STUB_DAMAGE STUB_KEEP_MARKER
    LEGACY_DATA_DIR="${TMP}/home/Zenbon POS"; OLD_DATA_DIR="${TMP}/home/POS Server"
    STD_DATA_DIR="${TMP}/var/lib/zenbon"; STD_LOG_DIR="${TMP}/var/log/zenbon"; STD_CONF_DIR="${TMP}/etc/zenbon"
    STD_ENV_FILE="${STD_CONF_DIR}/server.env"; UNIT_FILE="${TMP}/etc/systemd/pos-server.service"
    SERVICE_NAME=pos-server; SERVICE_USER=pos; SERVICE_GROUP=pos
    export STUB_MARKER="${STD_DATA_DIR}/.data/previous-data-dir"
    MIGRATE_ASSUME_YES=0; MIGRATE_HEALTH_TRIES=2
}

run_migrate() { local code=0; ( migrate_data_dir "${TMP}/opt" ) > "${TMP}/out.log" 2>&1 || code=$?; echo "${code}" > "${TMP}/rc"; }
rc() { cat "${TMP}/rc"; }

echo "happy path"
new_world; touch "${STUB_ACTIVE}"; MIGRATE_ASSUME_YES=1
run_migrate
check "exit 0" '[ "$(rc)" = 0 ]'
check "new base holds the old tree" '[ -f "${STD_DATA_DIR}/documents/a.pdf" ] && [ -f "${STD_DATA_DIR}/.data/postgresql/PG_VERSION" ]'
check "the server was started with a marker holding the old base" '[ "$(cat "${STUB_LOG}.marker")" = "${LEGACY_DATA_DIR}" ]'
check "old folder renamed to .migrated-<date>" '[ ! -d "${LEGACY_DATA_DIR}" ] && ls -d "${LEGACY_DATA_DIR}.migrated-"* >/dev/null 2>&1'
check "the unit is the standard one" 'grep -q "^Environment=ZENBON_DATA_DIR=" "${UNIT_FILE}" && grep -q "ProtectHome=true" "${UNIT_FILE}"'
check "env file moved to the config folder, mode 600" '[ -f "${STD_ENV_FILE}" ] && [ "$(stat -c %a "${STD_ENV_FILE}")" = 600 ] && [ ! -f "${TMP}/opt/pos-server.env" ]'
check "logs carried over to the log folder" '[ -f "${STD_LOG_DIR}/application.log" ]'

echo "insufficient free space"
new_world; touch "${STUB_ACTIVE}"; MIGRATE_ASSUME_YES=1; export STUB_FREE_KB=1
run_migrate
check "refused" '[ "$(rc)" != 0 ] && grep -q "Not enough free space" "${TMP}/out.log"'
check "service never stopped" '! grep -q "^stop" "${STUB_LOG}"'
check "nothing copied, unit untouched" '[ ! -e "${STD_DATA_DIR}" ] && cmp -s "${UNIT_FILE}" "${TMP}/unit.orig"'

echo "forced verification mismatch"
new_world; touch "${STUB_ACTIVE}"; MIGRATE_ASSUME_YES=1; export STUB_DAMAGE="${STD_DATA_DIR}/documents/a.pdf"
run_migrate
check "stopped with a message" '[ "$(rc)" != 0 ] && grep -q "could not be verified" "${TMP}/out.log"'
check "old unit untouched, old folder untouched" 'cmp -s "${UNIT_FILE}" "${TMP}/unit.orig" && [ -f "${LEGACY_DATA_DIR}/documents/a.pdf" ] && [ ! -e "${TMP}/home/Zenbon POS.migrated-"* ]'
check "no marker" '[ ! -e "${STD_DATA_DIR}/.data/previous-data-dir" ]'
check "the service that was running is started again" '[ -f "${STUB_ACTIVE}" ]'
check "the partial database copy is removed" '[ ! -e "${STD_DATA_DIR}/.data/postgresql" ]'
unset STUB_DAMAGE; run_migrate
check "a retry after the failure succeeds" '[ "$(rc)" = 0 ] && [ -f "${STD_DATA_DIR}/.data/postgresql/PG_VERSION" ]'

echo "running service without confirmation"
new_world; touch "${STUB_ACTIVE}"
run_migrate < /dev/null
check "refused" '[ "$(rc)" != 0 ] && grep -q "is running" "${TMP}/out.log"'
check "service not stopped, nothing copied" '! grep -q "^stop" "${STUB_LOG}" && [ ! -e "${STD_DATA_DIR}" ]'

echo "unhealthy after the switch rolls back"
new_world; touch "${STUB_ACTIVE}"; MIGRATE_ASSUME_YES=1; export STUB_CURL_EXIT=22
run_migrate
check "rolled back" '[ "$(rc)" != 0 ] && grep -q "rolled back" "${TMP}/out.log"'
check "rolled back: the partial database copy is removed" '[ ! -e "${STD_DATA_DIR}/.data/postgresql" ]'
check "old unit restored, old folder kept, marker gone, env kept" 'cmp -s "${UNIT_FILE}" "${TMP}/unit.orig" && [ -d "${LEGACY_DATA_DIR}" ] && [ ! -e "${STD_DATA_DIR}/.data/previous-data-dir" ] && [ -f "${TMP}/opt/pos-server.env" ]'

echo "server healthy but the path rebase never finished"
new_world; touch "${STUB_ACTIVE}"; MIGRATE_ASSUME_YES=1; export STUB_KEEP_MARKER=1
run_migrate
check "rolled back, not retired" '[ "$(rc)" != 0 ] && grep -q "did not rebase" "${TMP}/out.log" && [ -d "${LEGACY_DATA_DIR}" ] && ! ls -d "${LEGACY_DATA_DIR}.migrated-"* >/dev/null 2>&1'
check "old unit restored, marker gone" 'cmp -s "${UNIT_FILE}" "${TMP}/unit.orig" && [ ! -e "${STD_DATA_DIR}/.data/previous-data-dir" ]'

echo "rsync missing and not installable"
new_world; touch "${STUB_ACTIVE}"; MIGRATE_ASSUME_YES=1
printf '#!/bin/bash\nexit 100\n' > "${TMP}/bin/apt-get"; chmod +x "${TMP}/bin/apt-get"
command() { if [ "$1" = "-v" ] && [ "$2" = "rsync" ]; then return 1; fi; builtin command "$@"; }
run_migrate
unset -f command
check "refused before the service is stopped" '[ "$(rc)" != 0 ] && grep -q "rsync" "${TMP}/out.log" && ! grep -q "^stop" "${STUB_LOG}" && [ ! -e "${STD_DATA_DIR}" ]'

echo "already standard"
new_world; printf '[Service]\nEnvironment=ZENBON_DATA_DIR=/var/lib/zenbon\n' > "${UNIT_FILE}"
run_migrate
check "nothing to migrate" '[ "$(rc)" != 0 ] && grep -q "Nothing to migrate" "${TMP}/out.log"'

echo "passed: ${PASS}  failed: ${FAIL}"
[ "${FAIL}" -eq 0 ]
