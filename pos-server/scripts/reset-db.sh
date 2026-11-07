#!/usr/bin/env bash
# Reset pos_db on the local dev machine using a throwaway docker postgres client.
# Drops and recreates the database so real data can be imported manually afterwards.
#
# Usage:
#   ./reset-db.sh                       # use defaults below
#   PGPORT=5436 ./reset-db.sh           # target embedded postgres
#   PGHOST=192.168.1.50 ./reset-db.sh   # remote host
#
# Defaults match pos-server/src/main/resources/application.yaml.

set -euo pipefail

PGHOST="${PGHOST:-host.docker.internal}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-pos}"
PGPASSWORD="${PGPASSWORD:-pos}"
PGDATABASE="${PGDATABASE:-pos_db}"
PG_IMAGE="${PG_IMAGE:-postgres:16-alpine}"

case "$(uname -s)" in
    Linux*) DOCKER_HOST_FLAG="--add-host=host.docker.internal:host-gateway" ;;
    *)      DOCKER_HOST_FLAG="" ;;
esac

echo "Resetting database '${PGDATABASE}' on ${PGHOST}:${PGPORT} as user '${PGUSER}'."
read -r -p "This will DROP all data in '${PGDATABASE}'. Continue? [y/N] " confirm
[[ "${confirm}" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 1; }

run_psql() {
    local db="$1"; shift
    docker run --rm -i \
        ${DOCKER_HOST_FLAG} \
        -e PGPASSWORD="${PGPASSWORD}" \
        "${PG_IMAGE}" \
        psql -v ON_ERROR_STOP=1 \
             -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${db}" \
             "$@"
}

echo "Terminating active connections to '${PGDATABASE}'..."
run_psql postgres -c "
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = '${PGDATABASE}' AND pid <> pg_backend_pid();
" >/dev/null

echo "Dropping database '${PGDATABASE}'..."
run_psql postgres -c "DROP DATABASE IF EXISTS \"${PGDATABASE}\";"

echo "Creating database '${PGDATABASE}'..."
run_psql postgres -c "CREATE DATABASE \"${PGDATABASE}\" OWNER \"${PGUSER}\";"

echo "Done. '${PGDATABASE}' is empty and ready for a manual data import."
echo "Tip: pipe a dump file through the same image, e.g.:"
echo "  docker run --rm -i ${DOCKER_HOST_FLAG} -e PGPASSWORD='${PGPASSWORD}' ${PG_IMAGE} \\"
echo "      psql -h ${PGHOST} -p ${PGPORT} -U ${PGUSER} -d ${PGDATABASE} < your_data.sql"
