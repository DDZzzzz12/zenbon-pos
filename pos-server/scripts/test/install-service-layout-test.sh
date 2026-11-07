#!/bin/bash
# Tests for the layout detection and the systemd unit text in install-service.sh (no system access).
#   bash pos-server/scripts/test/install-service-layout-test.sh
HERE="$(cd "$(dirname "$0")" && pwd)"
source "${HERE}/../install-service.sh"
PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL $1"; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# --- detect_layout ---
[ "$(detect_layout "${TMP}/absent" "${TMP}/no.service")" = standard ] && ok "fresh machine (no old folder, no unit) is standard" || bad "fresh machine"
mkdir -p "${TMP}/Zenbon POS"
[ "$(detect_layout "${TMP}/Zenbon POS" "${TMP}/no.service")" = legacy ] && ok "an existing old data folder is legacy" || bad "old folder"
printf '[Service]\nEnvironment=HOME=/home/pos\n' > "${TMP}/old.service"
[ "$(detect_layout "${TMP}/absent" "${TMP}/old.service")" = legacy ] && ok "a unit without ZENBON_DATA_DIR is legacy" || bad "old unit"
printf '[Service]\nEnvironment=ZENBON_DATA_DIR=/var/lib/zenbon\n' > "${TMP}/new.service"
[ "$(detect_layout "${TMP}/absent" "${TMP}/new.service")" = standard ] && ok "a unit with ZENBON_DATA_DIR is standard" || bad "new unit"
[ "$(detect_layout "${TMP}/Zenbon POS" "${TMP}/new.service")" = standard ] && ok "a migrated install stays standard although the old folder is still there" || bad "migrated"

# --- env file location ---
[ "$(env_file_for standard /opt/pos-server)" = /etc/zenbon/server.env ] && ok "standard env file is /etc/zenbon/server.env" || bad "std env"
[ "$(env_file_for legacy /opt/pos-server)" = /opt/pos-server/pos-server.env ] && ok "legacy env file stays in the install dir" || bad "legacy env"

# --- unit text ---
SERVICE_NAME=pos-server; SERVICE_USER=pos; SERVICE_GROUP=pos; INSTALL_DIR=/opt/pos-server
SERVER_PORT=8080; PG_PORT=5432; PG_DB=pos_db; PG_USER=pos; PG_PASSWORD=pos
for mode in embedded external; do
    DB_MODE="${mode}"
    DATA_DIR="/home/pos/Zenbon POS"; ENV_FILE=/opt/pos-server/pos-server.env
    if diff <(render_unit legacy) "${HERE}/fixtures/unit-legacy-${mode}.golden" >/dev/null; then
        ok "legacy unit (${mode}) is byte-identical to the pre-change output"
    else
        bad "legacy unit (${mode}) differs: $(diff <(render_unit legacy) "${HERE}/fixtures/unit-legacy-${mode}.golden" | head -5)"
    fi
done

DB_MODE=embedded; DATA_DIR="${STD_DATA_DIR}"; ENV_FILE="${STD_ENV_FILE}"
unit="$(render_unit standard)"
for needle in StateDirectory=zenbon LogsDirectory=zenbon ConfigurationDirectory=zenbon \
    Environment=ZENBON_DATA_DIR=/var/lib/zenbon Environment=LOG_DIR=/var/log/zenbon ProtectHome=true \
    "EnvironmentFile=/etc/zenbon/server.env" "ReadWritePaths=/opt/pos-server /media/pos"; do
    grep -qxF -- "${needle}" <<<"${unit}" && ok "standard unit has: ${needle}" || bad "standard unit lacks: ${needle}"
done
grep -q 'ProtectHome=false\|/home/pos' <<<"${unit}" && bad "standard unit still mentions /home" || ok "standard unit does not mention /home"
[ "$(grep -c '^ReadWritePaths=' <<<"${unit}")" -eq 1 ] && ok "standard unit has exactly one ReadWritePaths" || bad "ReadWritePaths count"

echo "passed: ${PASS}  failed: ${FAIL}"
[ "${FAIL}" -eq 0 ]
