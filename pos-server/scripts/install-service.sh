#!/bin/bash
# POS Server — systemd service installer
# Creates a dedicated 'pos' user and installs pos-server as a system service bound to
# 127.0.0.1, plus a Caddy reverse proxy (unit 'pos-caddy', user 'pos-caddy') that terminates
# HTTPS on ports 443/80. Re-run it to upgrade; secrets and certificates are preserved.
#
# Usage (run as root or with sudo):
#   sudo ./install-service.sh [install-dir] [port] [--docker-postgres]
#        [--local-name NAME]... [--clear-local-names]
#
# Default install directory: /opt/pos-server
# Default server port: 8080 (must match the port chosen in the setup wizard)
# HTTPS: Caddy's local CA signs the certificate for https://localhost, this host's IPv4 addresses,
# https://<hostname>.local and every local DNS name given with --local-name (e.g. kasse.fritz.box,
# pos.lan). Its root is exported to /etc/pos-caddy/pos-root-ca.crt and must be trusted on every device.
# Local names are stored in /etc/pos-caddy/local-names: a re-run without --local-name keeps them,
# --local-name (repeatable) replaces them, --clear-local-names removes them.
# The deploy folder contents (pos-server.jar, jre/, native/, run.sh, caddy/) must be
# in the same directory as this script, or specify the install directory.
#
# On a standard Ubuntu desktop install the script also asks whether to slim the machine down into
# a dedicated POS device (provision-node.sh, in this directory). Answer no to install the server only.
#
# Maintainer account (optional, off by default): a login that only opens /maintenance/, the
# server-log search. The installer offers it on install/update; --maintainer sets or replaces it
# later, --maintainer-off removes it. Never a default password; see --help.
#
# To uninstall:
#   sudo ./install-service.sh --uninstall

set -euo pipefail

SERVICE_NAME="pos-server"
SERVICE_USER="pos"
SERVICE_GROUP="pos"
DEFAULT_INSTALL_DIR="/opt/pos-server"
DEFAULT_SERVER_PORT=8080
APP_NAME="Zenbon POS"
LEGACY_DATA_DIR="/home/${SERVICE_USER}/${APP_NAME}"
DATA_DIR="${LEGACY_DATA_DIR}"
OLD_DATA_DIR="/home/${SERVICE_USER}/POS Server"
# Standard Linux layout, used by FRESH installs (an existing install keeps its home-folder layout until
# --migrate-data-dir moves it): state in /var/lib, logs in /var/log, configuration in /etc.
STD_DATA_DIR="/var/lib/zenbon"
STD_LOG_DIR="/var/log/zenbon"
STD_CONF_DIR="/etc/zenbon"
STD_ENV_FILE="${STD_CONF_DIR}/server.env"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
# "standard" or "legacy"; settled by detect_install_layout at the start of install().
LAYOUT=""

# Docker PostgreSQL defaults (used when --docker-postgres flag is set)
PG_CONTAINER="pos-postgres"
PG_IMAGE="postgres:16"
PG_DB="pos_db"
PG_USER="pos"
PG_PASSWORD="pos"
PG_PORT=5432
PG_VOLUME="pos-postgres-data"

# Database mode for the systemd unit. Set by --docker-postgres flag.
DB_MODE="embedded"
# Set when recreating the PostgreSQL container stopped a running pos-server (reported at "Start now?").
POS_SERVER_STOPPED=0

# HTTPS (Caddy, local CA). --local-name NAME (repeatable) replaces the stored local names,
# --clear-local-names removes them; neither keeps them.
LOCAL_NAMES_ARG=()
CLEAR_LOCAL_NAMES=0
# The local names the certificate is written for: settled by preflight_local_names, stored by install_caddy.
LOCAL_NAMES=()
CADDY_SERVICE="pos-caddy"
CADDY_USER="pos-caddy"
CADDY_DATA_DIR="/var/lib/${CADDY_SERVICE}"
# Throwaway XDG home of 'caddy validate'; removed by the EXIT trap.
VALIDATE_HOME=""

# --- Helper functions ---

info()  { echo "[INFO]  $*"; }
warn()  { echo "[WARN]  $*"; }
error() {
    echo "[ERROR] $*" >&2
    [ "${POS_SERVER_STOPPED:-0}" != 1 ] \
        || echo "[WARN]  pos-server was stopped to recreate the PostgreSQL container - start it again: sudo systemctl start ${SERVICE_NAME}" >&2
    exit 1
}

# --- Layout (standard Linux paths vs. the home-folder layout of older installs) ---

# detect_layout <legacy-data-dir> <unit-file>: prints "legacy" or "standard".
# The installer rewrites the unit on every deploy, so an old install has to be recognised each time:
#   - a unit that sets ZENBON_DATA_DIR is standard (also after --migrate-data-dir)
#   - an existing unit without it is legacy
#   - no unit yet: legacy when the old data folder exists, otherwise a fresh install = standard
detect_layout() {
    local legacy_dir="$1" unit="$2"
    if [ -f "${unit}" ]; then
        if grep -q '^Environment=ZENBON_DATA_DIR=' "${unit}"; then echo standard; else echo legacy; fi
        return 0
    fi
    if [ -d "${legacy_dir}" ]; then echo legacy; else echo standard; fi
}

# The layout of this machine, with the pre-rename "POS Server" folder counted as legacy as well.
detect_install_layout() {
    local layout
    layout="$(detect_layout "${LEGACY_DATA_DIR}" "${UNIT_FILE}")"
    if [ "${layout}" = "standard" ] && [ ! -f "${UNIT_FILE}" ] && [ -d "${OLD_DATA_DIR}" ]; then
        layout=legacy
    fi
    echo "${layout}"
}

# env_file_for <layout> <install-dir>
env_file_for() {
    if [ "$1" = "standard" ]; then echo "${STD_ENV_FILE}"; else echo "$2/pos-server.env"; fi
}

# Applies a layout to the globals the install steps use.
apply_layout() {
    LAYOUT="$1"
    if [ "${LAYOUT}" = "standard" ]; then DATA_DIR="${STD_DATA_DIR}"; else DATA_DIR="${LEGACY_DATA_DIR}"; fi
    ENV_FILE="$(env_file_for "${LAYOUT}" "${INSTALL_DIR}")"
}

# The unit template. Lines that differ between the layouts are @@MARKER@@ lines, replaced by render_unit.
unit_template() {
    cat <<EOF
[Unit]
Description=POS Server
Documentation=https://github.com/example/pos-kmp
# Ensure tmpfiles has finished setting up /tmp and /dev/shm before we start,
# otherwise systemd-tmpfiles-setup may race with embedded PostgreSQL and
# remove /dev/shm/PostgreSQL.* segments after postgres has created them.
After=systemd-tmpfiles-setup.service
After=network-online.target
After=time-sync.target
Wants=network-online.target
$([ "${DB_MODE}" = "external" ] && echo "After=docker.service")
$([ "${DB_MODE}" = "external" ] && echo "Requires=docker.service")
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=${SERVICE_USER}
Group=${SERVICE_GROUP}
WorkingDirectory=${INSTALL_DIR}

# Auto-rollback guard: increments failure counter; after 3 strikes swaps
# in pos-server.jar.bak so the next start uses the last known-good JAR.
ExecStartPre=${INSTALL_DIR}/restart-guard.sh

# Use bundled JRE if available
ExecStart=${INSTALL_DIR}/run.sh

# Health probe: clear the failure counter once /health responds. If the
# server never reaches a healthy state in ~60s the post step exits 1 and
# systemd treats this start as failed (the guard increments next round).
ExecStartPost=/bin/bash -c 'for i in \$\$(seq 1 30); do [ "\$\$(systemctl show -p MainPID --value ${SERVICE_NAME})" != 0 ] || exit 1; curl -fsS -o /dev/null http://127.0.0.1:${SERVER_PORT}/health && echo 0 > ${INSTALL_DIR}/.restart-count && exit 0; sleep 2; done; exit 1'

# Restart on failure with increasing delay
Restart=on-failure
RestartSec=5

# Environment
@@ENV@@
Environment=DATABASE_MODE=${DB_MODE}
# JWT signing secret + auth mode (auto-provisioned, file mode 600). systemd reads
# this as root before dropping to User=, so the secret stays out of the unit file.
EnvironmentFile=${ENV_FILE}
$([ "${DB_MODE}" = "external" ] && echo "Environment=DATABASE_URL=jdbc:postgresql://localhost:${PG_PORT}/${PG_DB}")
$([ "${DB_MODE}" = "external" ] && echo "Environment=DATABASE_USERNAME=${PG_USER}")
$([ "${DB_MODE}" = "external" ] && echo "Environment=DATABASE_PASSWORD=${PG_PASSWORD}")

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
@@PROTECT_HOME@@
@@RW_PATHS@@
PrivateTmp=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true

# Resource limits
LimitNOFILE=65536
LimitNPROC=4096

# Logging — stdout/stderr go to journald
StandardOutput=journal
StandardError=journal
SyslogIdentifier=${SERVICE_NAME}

[Install]
WantedBy=multi-user.target
EOF
}

# render_unit <standard|legacy>: the systemd unit text. The legacy output is the pre-ZENBON_DATA_DIR
# unit byte for byte (pinned by install-service-layout-test.sh), so a re-run on an old install changes nothing.
render_unit() {
    local layout="$1" line
    while IFS= read -r line; do
        case "${line}" in
            "@@ENV@@")
                if [ "${layout}" = "standard" ]; then
                    echo "Environment=HOME=${STD_DATA_DIR}"
                    echo "Environment=ZENBON_DATA_DIR=${STD_DATA_DIR}"
                    echo "Environment=LOG_DIR=${STD_LOG_DIR}"
                else
                    echo "Environment=HOME=/home/${SERVICE_USER}"
                fi
                ;;
            "@@PROTECT_HOME@@")
                if [ "${layout}" = "standard" ]; then echo "ProtectHome=true"; else echo "ProtectHome=false"; fi
                ;;
            "@@RW_PATHS@@")
                if [ "${layout}" = "standard" ]; then
                    echo "ReadWritePaths=${INSTALL_DIR} /media/pos"
                    echo "StateDirectory=zenbon"
                    echo "LogsDirectory=zenbon"
                    echo "ConfigurationDirectory=zenbon"
                else
                    echo "ReadWritePaths=\"${DATA_DIR}\" ${INSTALL_DIR} /media/pos"
                fi
                ;;
            *) printf '%s\n' "${line}" ;;
        esac
    done < <(unit_template)
}

require_flag_value() {
    # '--local-name --clear-local-names' must not make '--clear-local-names' the name.
    case "$2" in
        ""|-*) error "$1 needs a value" ;;
    esac
}

check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        error "This script must be run as root (use sudo)"
    fi
}

provision_jwt_secret() {
    # Generate a strong JWT signing secret ONCE and reuse it on every later deploy.
    # Stored in a chmod-600 env file (referenced via EnvironmentFile=) so it never
    # appears in the world-readable systemd unit. The server's startup guard refuses
    # to boot on the built-in default secret, so this must be set for production.
    #
    # IMPORTANT: the secret is preserved across re-installs. Regenerating it would
    # invalidate every active session (force all users to re-login), so we only
    # create it when absent.
    local env_file="$1"

    touch "${env_file}"
    chmod 600 "${env_file}"

    if grep -q '^JWT_SECRET=' "${env_file}"; then
        info "JWT secret already provisioned (${env_file}) — preserving it"
    else
        info "Generating JWT signing secret (first install)..."
        local secret
        if command -v openssl &>/dev/null; then
            secret="$(openssl rand -base64 48)"
        else
            warn "openssl not found — falling back to /dev/urandom"
            secret="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
        fi
        {
            echo "# Auto-generated by install-service.sh on first install. KEEP SECRET."
            echo "# Do NOT commit or share. Rotating JWT_SECRET invalidates all active"
            echo "# sessions (forces every user to re-login)."
            echo "JWT_SECRET=${secret}"
        } >> "${env_file}"
        info "JWT secret written to ${env_file} (chmod 600)"
    fi

    # Ensure auth bypass is off in production (the dev bypass must never run here).
    if ! grep -q '^JWT_DEVELOPMENT_MODE=' "${env_file}"; then
        echo "JWT_DEVELOPMENT_MODE=false" >> "${env_file}"
    fi
}

provision_update_token_key() {
    # Generate the AES-256-GCM key that seals the GitHub update token ONCE and reuse
    # it on every later deploy. It lives on the host (chmod-600 env file, referenced
    # via EnvironmentFile=) and NEVER in the database beside the ciphertext, so a
    # stolen or restored database backup carries a token nobody can open.
    #
    # IMPORTANT: the key is preserved across re-installs. Regenerating it would make
    # the stored GitHub token permanently undecryptable -- the server falls back to
    # "no token" and the operator has to re-enter it in the update settings.
    #
    # Exactly 32 raw bytes, base64-encoded: TokenCipher rejects any other length and
    # then silently disables the private update channel.
    local env_file="$1"

    touch "${env_file}"
    chmod 600 "${env_file}"

    if grep -q '^UPDATE_TOKEN_KEY=' "${env_file}"; then
        info "Update-token key already provisioned (${env_file}) -- preserving it"
        return 0
    fi

    info "Generating update-token encryption key (first install)..."
    local key
    if command -v openssl &>/dev/null; then
        key="$(openssl rand -base64 32 | tr -d '\n')"
    else
        warn "openssl not found -- falling back to /dev/urandom"
        key="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
    fi
    {
        echo "# Auto-generated by install-service.sh on first install. KEEP SECRET."
        echo "# AES-256 key (32 bytes, base64) for the stored GitHub update token."
        echo "# Replacing UPDATE_TOKEN_KEY makes the stored token undecryptable and the"
        echo "# operator must re-enter it in the update settings."
        echo "UPDATE_TOKEN_KEY=${key}"
    } >> "${env_file}"
    info "Update-token key written to ${env_file} (chmod 600)"
}

provision_bind_address() {
    # pos-server listens on loopback only; Caddy is the one process the LAN reaches. Rewritten on
    # every install rather than preserved: HTTPS-only is not an operator preference.
    local env_file="$1"
    sed -i '/^HOST=/d' "${env_file}"
    echo "HOST=127.0.0.1" >> "${env_file}"
    info "pos-server bound to 127.0.0.1 (${env_file})"
}

provision_tls_root_file() {
    # Tells pos-server where Caddy's root certificate is exported (export_local_root_ca writes it
    # once Caddy has started), so the pairing QR can carry its check code and tills can pin it.
    # The server reads the file lazily; it need not exist yet.
    local env_file="$1"
    sed -i '/^POS_TLS_ROOT_CA_FILE=/d' "${env_file}"
    echo "POS_TLS_ROOT_CA_FILE=/etc/${CADDY_SERVICE}/pos-root-ca.crt" >> "${env_file}"
}

check_local_name() {
    # check_local_name <name> <origin>. A local name is written verbatim into the Caddyfile as a site
    # address: a scheme, port, path, wildcard, space or brace would break it or change what Caddy
    # serves, and would only surface at 'caddy validate', after the install has started.
    # Letters spelled out rather than [a-z]: a range can match more than ASCII in some locales.
    local name="${1,,}" origin="$2" hint=""
    local an='abcdefghijklmnopqrstuvwxyz0123456789'
    local label="[${an}]([${an}-]{0,61}[${an}])?"
    local re="^${label}(\\.${label})*\$"
    [ "${origin}" = "--local-name" ] || hint=" - fix ${origin}, or re-run with --local-name or --clear-local-names"
    [ "${#name}" -le 253 ] && [[ "${name}" =~ ${re} ]] \
        || error "${origin}: '$1' is not a plain host name like kasse.fritz.box or pos.lan (letters, digits, '-' and '.'; no https://, port, path, wildcard or spaces)${hint}"
    [[ ! "${name}" =~ ^[0-9.]+$ ]] \
        || error "${origin}: '$1' is only digits and dots (an IP address?) - this server's IPv4 addresses are always in the certificate; local names are DNS names${hint}"
    [ "${name}" != localhost ] \
        || error "${origin}: 'localhost' is always in the certificate${hint}"
    # Java (the tills' JVM and Android) sends no SNI for a single-label host, so Caddy could not pick the
    # certificate and every HTTPS connection to https://<name>/ would fail.
    [[ "${name}" == *.* ]] \
        || error "${origin}: '$1' has no dot - tills cannot use single-label names over HTTPS; use e.g. pos.lan or kasse.fritz.box${hint}"
    [[ "${name}" != *.local ]] \
        || error "${origin}: '$1' ends in .local - routers do not resolve mDNS names, and https://<hostname>.local is added automatically${hint}"
}

preflight_local_names() {
    # Read-only, before install() changes anything (same contract as preflight_package). Settles
    # LOCAL_NAMES: the --local-name list, none with --clear-local-names, otherwise the stored list - so a
    # plain re-run (an upgrade) keeps every name the tills may have been configured with. install_caddy
    # stores the result only once Caddy has validated the Caddyfile written for it.
    local stored="/etc/${CADDY_SERVICE}/local-names"
    local origin="--local-name"
    local name
    local candidates=()
    LOCAL_NAMES=()
    if [ "${CLEAR_LOCAL_NAMES}" = 1 ]; then
        [ "${#LOCAL_NAMES_ARG[@]}" -eq 0 ] || error "--clear-local-names cannot be combined with --local-name"
        info "Local names : none (--clear-local-names)"
        return 0
    fi
    if [ "${#LOCAL_NAMES_ARG[@]}" -gt 0 ]; then
        candidates=("${LOCAL_NAMES_ARG[@]}")
    elif [ -f "${stored}" ]; then
        origin="${stored}"
        # A leading UTF-8 BOM (a file saved by a Windows editor) would make the first name invalid.
        while IFS= read -r name || [ -n "${name}" ]; do
            # Trim surrounding whitespace (incl. a CR); skip blank lines and comments.
            name="${name#"${name%%[![:space:]]*}"}"
            name="${name%"${name##*[![:space:]]}"}"
            case "${name}" in
                ""|"#"*) continue ;;
            esac
            candidates+=("${name}")
        done < <(LC_ALL=C sed '1s/^\xEF\xBB\xBF//' "${stored}")
    fi
    for name in "${candidates[@]}"; do
        check_local_name "${name}" "${origin}"
        name="${name,,}"
        case " ${LOCAL_NAMES[*]} " in
            *" ${name} "*) ;;
            *) LOCAL_NAMES+=("${name}") ;;
        esac
    done
    if [ "${#LOCAL_NAMES[@]}" -gt 0 ]; then
        if [ "${origin}" = "--local-name" ]; then
            info "Local names : ${LOCAL_NAMES[*]}"
        else
            info "Local names : ${LOCAL_NAMES[*]} (kept from ${stored})"
        fi
    fi
}

clock_warning() {
    # clock_warning <now-epoch> <package-built-epoch> <ntp-synchronized: yes|no|unknown>
    # Prints the warning, or nothing when the clock looks sane. Pure, so it can be tested.
    local now="$1" built="$2" ntp="$3"
    local out=""
    if [ "${built}" -gt 0 ] && [ "${now}" -lt $((built - 86400)) ]; then
        out="The system clock ($(date -u -d "@${now}" '+%Y-%m-%d %H:%M UTC' 2>/dev/null || echo "${now}")) is earlier than this package was built ($(date -u -d "@${built}" '+%Y-%m-%d' 2>/dev/null || echo "${built}"))."
    elif [ "${ntp}" = "no" ]; then
        out="The system clock is not synchronized with a time server."
    fi
    [ -z "${out}" ] || printf '%s' "${out}"
}

check_system_clock() {
    # Caddy dates every certificate (and its 10-year root CA) from this clock. Clients with a correct clock
    # reject a certificate dated in their future or already expired, and a root created on a wrong clock
    # can only be replaced by re-pairing every till. A warning only, never a refusal: a deliberately
    # wrong clock (a test machine) must still install.
    local built=0 jar="${SCRIPT_DIR}/pos-server.jar" ntp="unknown" msg
    [ -f "${jar}" ] && built="$(stat -c %Y "${jar}" 2>/dev/null || echo 0)"
    if command -v timedatectl &>/dev/null; then
        ntp="$(timedatectl show -p NTPSynchronized --value 2>/dev/null || echo unknown)"
    fi
    msg="$(clock_warning "$(date +%s)" "${built}" "${ntp}")"
    [ -n "${msg}" ] || return 0
    warn "${msg}"
    warn "  Certificates created now carry wrong dates: devices with a correct clock will refuse them."
    warn "  Fix the time first (sudo timedatectl set-ntp true), then run this installer again."
    warn "  If Caddy already created its root CA on the wrong clock, remove /var/lib/${CADDY_SERVICE} and re-run;"
    warn "  the tills then have to be paired again with the new check code."
}

root_ca_fingerprint() {
    # SHA-256 of the certificate's DER bytes, upper-case and colon-separated (openssl's format).
    local cert="$1"
    local fp=""
    if command -v openssl &>/dev/null; then
        fp="$(openssl x509 -in "${cert}" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2)" || fp=""
    fi
    if [ -z "${fp}" ]; then
        # No openssl on a minimal install: PEM body is base64 of the DER, which is what gets hashed.
        fp="$(sed '/-----/d' "${cert}" | base64 -d 2>/dev/null | sha256sum | cut -d' ' -f1 \
            | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')" || fp=""
    fi
    echo "${fp:-unavailable}"
}

write_caddyfile_internal() {
    # Caddy's own CA signs for every address and name clients use. Re-run the installer if the
    # server's IP address changes.
    local candidates=("https://localhost")
    local ip name site short
    for ip in $(hostname -I); do
        case "${ip}" in
            *:*) ;; # skip IPv6 (link-local addresses are not reachable by URL)
            *) candidates+=("https://${ip}") ;;
        esac
    done
    # Lower-cased like the local names, so the comparison below sees one spelling.
    short="$(hostname -s)"
    short="$(printf '%s' "${short}" | tr '[:upper:]' '[:lower:]')"
    [ "${#candidates[@]}" -gt 1 ] \
        || warn "No IPv4 address found - tills can only use https://${short}.local or a local name"
    candidates+=("https://${short}.local")
    for name in "${LOCAL_NAMES[@]}"; do
        candidates+=("https://${name}")
    done
    # The same address twice in one site block is a Caddyfile error (e.g. an address 'hostname -I' lists twice).
    local sites=""
    for site in "${candidates[@]}"; do
        case ", ${sites}, " in
            *", ${site}, "*) ;;
            *) sites="${sites:+${sites}, }${site}" ;;
        esac
    done
    local http_sites="${sites//https:\/\//http://}"
    local www_dir="/etc/${CADDY_SERVICE}/www"
    cat > "$1" <<EOF
# Generated by install-service.sh — re-run the installer instead of editing this file.
{
    # No admin API: unauthenticated on localhost:2019, it would let any local process load a config
    # that serves the local CA's private key. Config changes = re-run the installer.
    admin off
    local_certs
    # Caddy runs unprivileged; the root is exported by the installer instead.
    skip_install_trust
    # HTTP/3 would need UDP 443, which the firewall does not open.
    servers {
        protocols h1 h2
    }
}

${sites} {
    reverse_proxy 127.0.0.1:${SERVER_PORT}
}

# Plain HTTP exists only to hand out the root certificate before a device trusts it: a browser that does
# not trust the CA cannot load the HTTPS pages at all. Everything else on port 80 goes to HTTPS.
${http_sites} {
    handle /ca {
        root * ${www_dir}
        rewrite * /ca.html
        file_server
    }
    handle /ca.crt {
        root * ${www_dir}
        header Content-Disposition "attachment; filename=pos-root-ca.crt"
        file_server
    }
    handle {
        redir https://{host}{uri} 308
    }
}
EOF
    chmod 644 "$1"
    info "Local-CA sites: ${sites}"
    if [ "${#LOCAL_NAMES[@]}" -gt 0 ]; then
        info "The router's DNS (e.g. the FRITZ!Box) must resolve each local name (${LOCAL_NAMES[*]}) to this"
        info "  server's IP address - otherwise tills using https://<name>/ cannot connect."
    fi
    info "Give this server a fixed IP address (or a DHCP reservation): certificates name the current"
    info "  addresses, so an IP change breaks HTTPS for the tills until the installer is re-run."
}

preflight_package() {
    # Same "read-only, before any mutation" contract as preflight_local_names: install_caddy
    # (step 7b) and step 4's file copy both assume the deploy package is complete. Without this check,
    # a missing binary or JAR is only discovered after step 4 has already deleted the old JAR/run.sh/
    # jre and step 7 has rewritten the systemd unit — leaving a broken install behind. SCRIPT_DIR and
    # INSTALL_DIR are set by install() before this runs.
    [ "$(uname -m)" = x86_64 ] || error "Only x86_64 is supported (the bundled Caddy is linux-amd64)"
    local caddy_bin="/usr/local/lib/${CADDY_SERVICE}/caddy"
    if [ ! -f "${SCRIPT_DIR}/caddy/caddy-linux-amd64" ] && [ ! -x "${caddy_bin}" ]; then
        error "Caddy binary missing (caddy/caddy-linux-amd64). Use a release package — HTTPS cannot be set up without it."
    fi

    # Mirrors step 4's own condition exactly: when SCRIPT_DIR == INSTALL_DIR (installer run from
    # inside the install directory) step 4 copies nothing and the files must already be there;
    # otherwise step 4 copies them FROM SCRIPT_DIR.
    local package_dir="${SCRIPT_DIR}"
    [ "${SCRIPT_DIR}" != "${INSTALL_DIR}" ] || package_dir="${INSTALL_DIR}"
    [ -f "${package_dir}/pos-server.jar" ] || error "pos-server.jar not found in ${package_dir}"
    [ -f "${package_dir}/run.sh" ] || error "run.sh not found in ${package_dir}"
}

install_caddy() {
    # Outside ${INSTALL_DIR}: that tree is owned by and writable for pos-server's user, who could
    # otherwise swap the proxy binary or edit the Caddyfile. Both directories belong to root; the
    # proxy user reads the binary, Caddyfile and local-names (755/644).
    local caddy_dir="/etc/${CADDY_SERVICE}"
    local caddy_bin="/usr/local/lib/${CADDY_SERVICE}/caddy"
    local local_names_file="${caddy_dir}/local-names"

    # Staged files (Caddyfile.new, local-names.new, caddy.new) and the validation home must not outlive an
    # interrupted run (Ctrl-C, SIGHUP, I/O error). Only '.new' paths: committed files stay.
    trap '[ -z "${VALIDATE_HOME:-}" ] || rm -rf "${VALIDATE_HOME}"; rm -f "/etc/${CADDY_SERVICE}/Caddyfile.new" "/etc/${CADDY_SERVICE}/local-names.new" "/usr/local/lib/${CADDY_SERVICE}/caddy.new"' EXIT

    mkdir -p "${caddy_dir}" "${caddy_bin%/*}" "${CADDY_DATA_DIR}"
    chown -R root:root "${caddy_dir}" "${caddy_bin%/*}"
    chmod 755 "${caddy_dir}" "${caddy_bin%/*}"

    # The package's binary is staged as caddy.new and replaces the installed one only after it has
    # validated the new Caddyfile. A re-run without a package binary validates with the installed one.
    # 'command install': this script defines its own install() function, which a bare 'install'
    # would call recursively instead of /usr/bin/install.
    local caddy_bin_new=""
    local validate_bin="${caddy_bin}"
    if [ -f "${SCRIPT_DIR}/caddy/caddy-linux-amd64" ]; then
        caddy_bin_new="${caddy_bin}.new"
        command install -m 0755 -o root -g root "${SCRIPT_DIR}/caddy/caddy-linux-amd64" "${caddy_bin_new}"
        validate_bin="${caddy_bin_new}"
    fi
    [ -x "${validate_bin}" ] || error "Caddy binary missing (caddy/caddy-linux-amd64). Use a release package — HTTPS cannot be set up without it."

    if ! id "${CADDY_USER}" &>/dev/null; then
        # useradd creates a same-name group itself, but exits 9 if a leftover one exists; then name it.
        local gid_opt=()
        if getent group "${CADDY_USER}" >/dev/null; then
            gid_opt=(--gid "${CADDY_USER}")
        fi
        useradd --system --shell /usr/sbin/nologin --home-dir "${CADDY_DATA_DIR}" \
            --comment "POS HTTPS proxy" "${gid_opt[@]}" "${CADDY_USER}"
    fi

    # The local names were settled (and validated) by preflight_local_names.
    # The running proxy's configuration changes only once the new one validates: the new Caddyfile goes
    # to Caddyfile.new, and the local-names list is stored only after validation.
    local caddyfile_new="${caddy_dir}/Caddyfile.new"
    write_caddyfile_internal "${caddyfile_new}"
    chown root:root "${caddyfile_new}"

    # Certificates and the local CA's private key live here; only the proxy user may read them.
    chown -R "${CADDY_USER}:${CADDY_USER}" "${CADDY_DATA_DIR}"
    chmod 700 "${CADDY_DATA_DIR}"

    # Validate against throwaway storage: validation provisions the TLS app (in local_certs mode it can
    # initialise the PKI), and running it as root on the real data dir would leave root-owned files
    # that the unprivileged service cannot read or replace.
    info "Validating Caddyfile..."
    VALIDATE_HOME="$(mktemp -d)"
    if ! XDG_DATA_HOME="${VALIDATE_HOME}" XDG_CONFIG_HOME="${VALIDATE_HOME}" \
            "${validate_bin}" validate --config "${caddyfile_new}" --adapter caddyfile; then
        rm -rf "${VALIDATE_HOME}"
        VALIDATE_HOME=""
        rm -f "${caddyfile_new}"
        [ -z "${caddy_bin_new}" ] || rm -f "${caddy_bin_new}"
        error "Caddyfile did not validate - see the output above (the proxy configuration was not changed)"
    fi
    rm -rf "${VALIDATE_HOME}"
    VALIDATE_HOME=""

    # Validated: apply it. Binary first - the new Caddyfile may need the new binary's modules.
    if [ -n "${caddy_bin_new}" ]; then
        mv -f "${caddy_bin_new}" "${caddy_bin}"
        if [ -f "${SCRIPT_DIR}/caddy/LICENSE" ]; then
            command install -m 0644 "${SCRIPT_DIR}/caddy/LICENSE" "${caddy_bin%/*}/LICENSE"
        fi
        local buildinfo
        for buildinfo in "${SCRIPT_DIR}"/caddy/*.buildinfo.txt; do
            [ -f "${buildinfo}" ] || continue
            command install -m 0644 "${buildinfo}" "${caddy_bin%/*}/"
        done
    fi
    # The stored list first: should the run die between the two renames, the next plain re-run rebuilds
    # the Caddyfile from it.
    if [ "${#LOCAL_NAMES[@]}" -gt 0 ]; then
        command install -m 0644 -o root -g root /dev/null "${local_names_file}.new"
        printf '%s\n' "${LOCAL_NAMES[@]}" > "${local_names_file}.new"
        mv -f "${local_names_file}.new" "${local_names_file}"
        info "Local names stored in ${local_names_file}"
    elif [ "${CLEAR_LOCAL_NAMES}" = 1 ] && [ -e "${local_names_file}" ]; then
        rm -f "${local_names_file}"
        info "Removed the stored local names (${local_names_file})"
    fi
    mv -f "${caddyfile_new}" "${caddy_dir}/Caddyfile"

    cat > "/etc/systemd/system/${CADDY_SERVICE}.service" <<EOF
[Unit]
Description=POS HTTPS reverse proxy (Caddy)
After=network-online.target ${SERVICE_NAME}.service
Wants=network-online.target

[Service]
Type=notify
User=${CADDY_USER}
Group=${CADDY_USER}
ExecStart=${caddy_bin} run --config ${caddy_dir}/Caddyfile --adapter caddyfile
# No ExecReload: 'caddy reload' needs the admin API, which the Caddyfile turns off. Restart instead.
Environment=XDG_DATA_HOME=${CADDY_DATA_DIR}
Environment=XDG_CONFIG_HOME=${CADDY_DATA_DIR}
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=${CADDY_DATA_DIR}
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
LockPersonality=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
Restart=on-failure
RestartSec=5
TimeoutStopSec=5s
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF

    # A pre-release test build kept the upstream (and, with its since-removed --domain mode, a DNS API
    # token) in caddy.env. Removed only now: until the unit above replaced the old one, that unit's
    # EnvironmentFile= and the old Caddyfile still needed it. systemd keeps the old unit loaded until a
    # daemon-reload, so reload first: should a later step fail before step 10's reload, a restart of the
    # still-loaded old unit would fail on the missing file.
    if [ -e "${caddy_dir}/caddy.env" ]; then
        systemctl daemon-reload
        rm -f "${caddy_dir}/caddy.env"
        info "Removed ${caddy_dir}/caddy.env left by an earlier install (no longer used). If it held a DNS API"
        info "  token, revoke that token at the DNS provider."
    fi
}

export_local_root_ca() {
    # Caddy creates its root on first start.
    local root="${CADDY_DATA_DIR}/caddy/pki/authorities/local/root.crt"
    local target="/etc/${CADDY_SERVICE}/pos-root-ca.crt"
    local _
    # The data dir belongs to the proxy user, so root only tests for the file here (a test on a planted
    # symlink reveals nothing) and never reads it.
    for _ in $(seq 1 30); do
        [ -f "${root}" ] && break
        sleep 1
    done
    if [ ! -f "${root}" ]; then
        warn "Caddy has not created its local CA yet — check 'journalctl -u ${CADDY_SERVICE}'"
        return 0
    fi
    # Read as the proxy user: a symlink planted in its data dir then grants nothing beyond its own access.
    # Bounded in size and time, so a swap to /dev/zero or a FIFO cannot fill /etc or hang the installer.
    if ! runuser -u "${CADDY_USER}" -- timeout 10 head -c 65536 "${root}" > "${target}.new" 2>/dev/null \
            || ! grep '^-----BEGIN CERTIFICATE-----$' "${target}.new" >/dev/null; then
        rm -f "${target}.new"
        warn "Could not read Caddy's local root CA (${root}) - check 'journalctl -u ${CADDY_SERVICE}'"
        return 0
    fi
    chmod 644 "${target}.new"
    mv -f "${target}.new" "${target}"
    info "Local root CA : ${target}"
    info "  SHA-256     : $(root_ca_fingerprint "${target}")"
    info "  Install it as a trusted CA on every admin PC and browser (manual, chapter 17); tills pair with the check code instead."
    info "  Compare the SHA-256 on each device before trusting it."
    write_ca_download_page "${target}"
}

write_ca_download_page() {
    # The certificate and a small page with its SHA-256, served over plain HTTP at /ca and /ca.crt (see the
    # Caddyfile). Written once the root exists; Caddy reads the files per request, so no restart is needed.
    local root_file="$1"
    local www_dir="/etc/${CADDY_SERVICE}/www"
    local fp host_hint
    fp="$(root_ca_fingerprint "${root_file}")"
    host_hint="$(hostname -I | awk '{print $1}')"
    command install -d -m 755 -o root -g root "${www_dir}"
    command install -m 644 -o root -g root "${root_file}" "${www_dir}/ca.crt.new"
    mv -f "${www_dir}/ca.crt.new" "${www_dir}/ca.crt"
    cat > "${www_dir}/ca.html.new" <<HTML
<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>POS Zertifikat / certificate</title>
<style>body{font-family:system-ui,sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem;line-height:1.5}
code{background:#eee;padding:.1rem .3rem;word-break:break-all}a.b{display:inline-block;background:#007a69;color:#fff;padding:.7rem 1.2rem;text-decoration:none;margin:1rem 0}</style></head>
<body>
<h1>POS-Server: Zertifikat installieren</h1>
<p><a class="b" href="/ca.crt">pos-root-ca.crt herunterladen</a></p>
<p>Vergleichen Sie vor dem Installieren den SHA-256-Fingerabdruck mit dem, den das Installationsskript am Server anzeigt:</p>
<p><code>${fp}</code></p>
<p>Danach <code>https://${host_hint}/admin/</code> neu laden. Anleitung je Gerät: Handbuch, Kapitel 17.
Kassen (Android/Linux) brauchen das Zertifikat nicht: beim Koppeln den Pr&uuml;fcode aus dem Back Office eingeben oder den QR-Code scannen.</p>
<hr>
<h2>English</h2>
<p>Download the certificate and compare its SHA-256 fingerprint (above) with the one the installer printed on the server before you trust it. Tills (Android/Linux) do not need it: pair them with the check code or the QR code from the back office.</p>
</body></html>
HTML
    chmod 644 "${www_dir}/ca.html.new"
    mv -f "${www_dir}/ca.html.new" "${www_dir}/ca.html"
    info "  Certificate download page for browsers: http://${host_hint}/ca"
}

# --- Maintainer account (optional log-search login at /maintenance/) ---
#
# Off unless BOTH MAINTAINER_USERNAME and MAINTAINER_PASSWORD_HASH are in the env file. The hash is
# made on this machine by the installed jar (--hash-maintainer-password), which reads the password
# from stdin when no console is attached -- so the password only ever travels through a pipe made by
# the shell builtin printf, never through a command line or the process list. It is not stored, not
# echoed, and there is deliberately no default account or default password.

# True when a person can answer prompts (tests override this).
is_interactive() { [ -t 0 ]; }

MAINTAINER_NAME_ARG=""         # --maintainer-user
MAINTAINER_PASSWORD_STDIN=0    # --maintainer-password-stdin
MAINTAINER_CHANGED=0           # set when this run wrote or removed the account
MAINTAINER_PASSWORD=""
MAINTAINER_HASH=""
MAINTAINER_LAST_BACKUP=""
DEFAULT_MAINTAINER_USER="wartung"
MAINTAINER_MIN_LENGTH=12
MAINTAINER_MAX_BYTES=72
MAINTAINER_OFFERED_MARKER="# maintainer-setup-offered (install-service.sh asks only once; use --maintainer to set an account)"
# Same shape the server accepts at startup: $2a$/$2b$/$2y$, two-digit cost, 53 chars of BCrypt base64.
BCRYPT_SHAPE='^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'

# Value of KEY in an env file, one pair of surrounding quotes removed. Empty when absent.
env_get() {
    local file="$1" key="$2" line value
    line="$(grep -m1 "^${key}=" "${file}" 2>/dev/null || true)"
    [ -n "${line}" ] || return 0
    value="${line#"${key}="}"
    case "${value}" in
        \'*\') value="${value#\'}"; value="${value%\'}" ;;
        \"*\") value="${value#\"}"; value="${value%\"}" ;;
    esac
    printf '%s' "${value}"
}

# Rewrite an env file in ONE write: drop every line that sets one of the space-separated KEYS (and the
# "offered" marker), then append the given lines. Writing through `cat >` keeps the file's inode, mode
# and owner; the mode is re-asserted because this file holds the JWT secret. A timestamped backup is
# taken first and only the newest three are kept.
env_rewrite() {
    local file="$1" drop_keys="$2"
    shift 2
    local tmp line backup
    backup="${file}.maintainer-bak-$(date +%Y%m%d%H%M%S)"
    cp -p "${file}" "${backup}" || { warn "Could not back up ${file}"; return 1; }
    MAINTAINER_LAST_BACKUP="${backup}"
    tmp="$(umask 077 && mktemp "${file}.XXXXXX")" || return 1
    awk -v keys="${drop_keys}" -v marker="${MAINTAINER_OFFERED_MARKER}" '
        BEGIN { n = split(keys, a, " "); for (i = 1; i <= n; i++) drop[a[i]] = 1 }
        $0 == marker { next }
        { i = index($0, "="); if (i > 1 && (substr($0, 1, i - 1) in drop)) next; print }
    ' "${file}" > "${tmp}"
    for line in "$@"; do
        printf '%s\n' "${line}" >> "${tmp}"
    done
    cat "${tmp}" > "${file}"
    rm -f "${tmp}"
    chmod 600 "${file}"
    local old=()
    for line in "${file}".maintainer-bak-*; do
        [ -e "${line}" ] && old+=("${line}")
    done
    while [ "${#old[@]}" -gt 3 ]; do
        rm -f "${old[0]}"
        old=("${old[@]:1}")
    done
    return 0
}

# Restore the env file from the backup env_rewrite just took.
env_rollback() {
    local file="$1"
    [ -n "${MAINTAINER_LAST_BACKUP}" ] || return 1
    cat "${MAINTAINER_LAST_BACKUP}" > "${file}"
    chmod 600 "${file}"
}

# Prints the reason and returns 0 when the username is unusable; returns 1 when it is fine.
maintainer_user_problem() {
    if ! [[ "$1" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
        echo "The username may only contain letters, digits, '.', '_' and '-' (1 to 64 characters)."
        return 0
    fi
    return 1
}

# Prints the reason and returns 0 when the password is unusable; returns 1 when it is fine.
# The jar re-checks the same rules and stays the authority; this saves a round trip.
maintainer_password_problem() {
    local password="$1" bytes
    if [ "${#password}" -lt "${MAINTAINER_MIN_LENGTH}" ]; then
        echo "The password must be at least ${MAINTAINER_MIN_LENGTH} characters long."
        return 0
    fi
    bytes="$(printf '%s' "${password}" | LC_ALL=C wc -c | tr -d ' ')"
    if [ "${bytes}" -gt "${MAINTAINER_MAX_BYTES}" ]; then
        echo "The password must be at most ${MAINTAINER_MAX_BYTES} bytes long (UTF-8; umlauts and special characters count more than once)."
        return 0
    fi
    return 1
}

# Read the password twice from the terminal without echo. Result in MAINTAINER_PASSWORD.
prompt_maintainer_password() {
    local attempt first second problem
    for attempt in 1 2 3; do
        printf 'Password (at least %s characters, at most %s bytes): ' "${MAINTAINER_MIN_LENGTH}" "${MAINTAINER_MAX_BYTES}" >&2
        IFS= read -rs first || return 1
        printf '\n' >&2
        printf 'Repeat password: ' >&2
        IFS= read -rs second || return 1
        printf '\n' >&2
        if [ "${first}" != "${second}" ]; then
            warn "The two entries differ."
            continue
        fi
        if problem="$(maintainer_password_problem "${first}")"; then
            warn "${problem}"
            continue
        fi
        MAINTAINER_PASSWORD="${first}"
        return 0
    done
    warn "Giving up after 3 attempts."
    return 1
}

# One line from standard input, for scripted use. Result in MAINTAINER_PASSWORD.
read_maintainer_password_stdin() {
    local line="" problem
    IFS= read -r line || [ -n "${line}" ] || { warn "No password was provided on standard input."; return 1; }
    line="${line%$'\r'}"
    if problem="$(maintainer_password_problem "${line}")"; then
        warn "${problem}"
        return 1
    fi
    MAINTAINER_PASSWORD="${line}"
}

# Hash the password with the installed jar. Result (the bare hash) in MAINTAINER_HASH.
make_maintainer_hash() {
    local install_dir="$1" java_bin out errfile hash
    if [ -x "${install_dir}/jre/bin/java" ]; then
        java_bin="${install_dir}/jre/bin/java"
    else
        java_bin="$(command -v java || true)"
    fi
    [ -n "${java_bin}" ] || { warn "No Java runtime found to create the password hash."; return 1; }
    [ -f "${install_dir}/pos-server.jar" ] || { warn "${install_dir}/pos-server.jar not found."; return 1; }

    errfile="$(umask 077 && mktemp)"
    # -Djdk.console=java.base: on newer JDKs System.console() is non-null even when stdin is a pipe,
    # which would make the jar try the (echo-less) console path instead of reading the pipe.
    if ! out="$(printf '%s\n%s\n' "${MAINTAINER_PASSWORD}" "${MAINTAINER_PASSWORD}" |
        "${java_bin}" -Djdk.console=java.base -jar "${install_dir}/pos-server.jar" --hash-maintainer-password 2>"${errfile}")"; then
        warn "Creating the password hash failed:"
        sed 's/^/        /' "${errfile}" >&2
        rm -f "${errfile}"
        return 1
    fi
    rm -f "${errfile}"
    hash="${out#MAINTAINER_PASSWORD_HASH=\'}"
    hash="${hash%\'}"
    if ! [[ "${hash}" =~ ${BCRYPT_SHAPE} ]]; then
        warn "The jar did not return a BCrypt hash -- not writing anything."
        return 1
    fi
    MAINTAINER_HASH="${hash}"
}

# After a write: both keys present once, username valid, hash intact (the `$` survived the quoting).
verify_maintainer_env() {
    local file="$1" want_user="$2" want_hash="$3" got_hash
    got_hash="$(env_get "${file}" MAINTAINER_PASSWORD_HASH)"
    [ "$(env_get "${file}" MAINTAINER_USERNAME)" = "${want_user}" ] || return 1
    [ "${got_hash}" = "${want_hash}" ] || return 1
    [[ "${got_hash}" =~ ${BCRYPT_SHAPE} ]] || return 1
    [ "$(grep -c '^MAINTAINER_PASSWORD_HASH=' "${file}")" = "1" ] || return 1
    [ "$(grep -c '^MAINTAINER_USERNAME=' "${file}")" = "1" ] || return 1
}

# Collect username and password, hash, write, verify. Never leaves a half-written account.
write_maintainer_account() {
    local env_file="$1" install_dir="$2" name problem
    MAINTAINER_PASSWORD=""
    name="${MAINTAINER_NAME_ARG}"

    if [ "${MAINTAINER_PASSWORD_STDIN}" = "1" ]; then
        [ -n "${name}" ] || { warn "--maintainer-password-stdin needs --maintainer-user NAME."; return 1; }
        if problem="$(maintainer_user_problem "${name}")"; then warn "${problem}"; return 1; fi
        read_maintainer_password_stdin || return 1
    else
        is_interactive || { warn "No terminal for the password prompt. Use --maintainer-user NAME --maintainer-password-stdin."; return 1; }
        if [ -z "${name}" ]; then
            printf 'Username [%s]: ' "${DEFAULT_MAINTAINER_USER}" >&2
            IFS= read -r name || return 1
            name="${name:-${DEFAULT_MAINTAINER_USER}}"
        fi
        if problem="$(maintainer_user_problem "${name}")"; then warn "${problem}"; return 1; fi
        prompt_maintainer_password || return 1
    fi

    if ! make_maintainer_hash "${install_dir}"; then
        MAINTAINER_PASSWORD=""
        return 1
    fi
    MAINTAINER_PASSWORD=""

    env_rewrite "${env_file}" "MAINTAINER_USERNAME MAINTAINER_PASSWORD_HASH" \
        "MAINTAINER_USERNAME=${name}" \
        "MAINTAINER_PASSWORD_HASH='${MAINTAINER_HASH}'" || return 1
    if ! verify_maintainer_env "${env_file}" "${name}" "${MAINTAINER_HASH}"; then
        env_rollback "${env_file}"
        warn "The written account did not read back correctly -- ${env_file} was restored from ${MAINTAINER_LAST_BACKUP}."
        return 1
    fi
    MAINTAINER_CHANGED=1
    info "Maintainer account '${name}' written to ${env_file} (chmod 600). Backup: ${MAINTAINER_LAST_BACKUP}"
}

# Used by install(): leave an existing account alone, otherwise offer the setup (once).
configure_maintainer_account() {
    local env_file="$1" install_dir="$2" user hash answer
    user="$(env_get "${env_file}" MAINTAINER_USERNAME)"
    hash="$(env_get "${env_file}" MAINTAINER_PASSWORD_HASH)"

    if [ -n "${user}" ] && [ -n "${hash}" ]; then
        info "Maintainer account '${user}' already configured -- leaving it untouched."
        info "  Change or remove it with: sudo $0 --maintainer   |   sudo $0 --maintainer-off"
        return 0
    fi
    if [ -n "${user}" ] || [ -n "${hash}" ]; then
        warn "Only one of MAINTAINER_USERNAME / MAINTAINER_PASSWORD_HASH is set in ${env_file}; the maintainer account is OFF."
        warn "  Fix it with: sudo $0 --maintainer"
        return 0
    fi

    if [ "${MAINTAINER_PASSWORD_STDIN}" = "1" ]; then
        write_maintainer_account "${env_file}" "${install_dir}"
        return $?
    fi
    is_interactive || return 0                                                  # no terminal, no flags: stays off, silently
    ! grep -qxF "${MAINTAINER_OFFERED_MARKER}" "${env_file}" || return 0  # asked on an earlier run

    info ""
    info "Optional: a maintainer account lets support search the server logs by traceId at"
    info "  /maintenance/ without SSH access. It is not an employee and opens nothing else."
    info "Set up a maintainer account for log search? [y/N]"
    IFS= read -r answer || answer=""
    if [ "${answer}" = "y" ] || [ "${answer}" = "Y" ]; then
        write_maintainer_account "${env_file}" "${install_dir}"
        return $?
    fi
    printf '%s\n' "${MAINTAINER_OFFERED_MARKER}" >> "${env_file}"
    info "Skipped. You can add it later with: sudo $0 --maintainer"
}

maintainer_address_note() {
    local host
    host="$(hostname -I 2>/dev/null | awk '{print $1}')"
    info "Maintainer log search: http://${host:-<server>}:${SERVER_PORT:-${DEFAULT_SERVER_PORT}}/maintenance/"
    info "  Use it only over HTTPS -- the maintainer password must not cross the network in clear text."
}

# `--maintainer` / `--maintainer-off`: change only the account, then restart the service.
maintainer_command() {
    local action="$1" env_file user answer
    INSTALL_DIR="${2:-$DEFAULT_INSTALL_DIR}"
    SERVER_PORT="${3:-$DEFAULT_SERVER_PORT}"
    env_file="$(env_file_for "$(detect_install_layout)" "${INSTALL_DIR}")"
    # The layout is read from the unit; if the file is not there, the other layout's file is the one.
    [ -f "${env_file}" ] || env_file="$(env_file_for "$([ "${env_file}" = "${STD_ENV_FILE}" ] && echo legacy || echo standard)" "${INSTALL_DIR}")"
    [ -f "${env_file}" ] || error "${env_file} not found -- is pos-server installed in ${INSTALL_DIR}?"

    if [ "${action}" = "off" ]; then
        if [ -z "$(env_get "${env_file}" MAINTAINER_USERNAME)" ] && [ -z "$(env_get "${env_file}" MAINTAINER_PASSWORD_HASH)" ]; then
            info "No maintainer account is configured -- nothing to remove."
            return 0
        fi
        env_rewrite "${env_file}" "MAINTAINER_USERNAME MAINTAINER_PASSWORD_HASH" "${MAINTAINER_OFFERED_MARKER}" ||
            error "Could not update ${env_file}"
        if [ -n "$(env_get "${env_file}" MAINTAINER_USERNAME)" ] || [ -n "$(env_get "${env_file}" MAINTAINER_PASSWORD_HASH)" ]; then
            env_rollback "${env_file}"
            error "The account was still present after removal -- ${env_file} was restored."
        fi
        info "Maintainer account removed. Backup: ${MAINTAINER_LAST_BACKUP}"
    else
        user="$(env_get "${env_file}" MAINTAINER_USERNAME)"
        if [ -n "${user}" ] && [ -n "$(env_get "${env_file}" MAINTAINER_PASSWORD_HASH)" ] && [ "${MAINTAINER_PASSWORD_STDIN}" != "1" ]; then
            is_interactive || error "An account '${user}' exists; replacing it needs a terminal (or --maintainer-password-stdin)."
            info "A maintainer account '${user}' already exists. Replace it? [y/N]"
            IFS= read -r answer || answer=""
            if [ "${answer}" != "y" ] && [ "${answer}" != "Y" ]; then
                info "Left untouched."
                return 0
            fi
        fi
        write_maintainer_account "${env_file}" "${INSTALL_DIR}" || error "The maintainer account was NOT changed."
        maintainer_address_note
    fi

    if systemctl is-active --quiet "${SERVICE_NAME}" 2>/dev/null; then
        info "Restarting ${SERVICE_NAME}..."
        systemctl restart "${SERVICE_NAME}"
    else
        info "${SERVICE_NAME} is not running; the change applies at its next start."
    fi
}

install_docker_postgres() {
    # Ensure Docker is present
    if ! command -v docker &>/dev/null; then
        info "Docker not found — installing..."
        apt-get update -qq
        apt-get install -y -qq docker.io
        systemctl enable docker
        systemctl start docker
        info "Docker installed"
    else
        info "Docker already installed: $(docker --version)"
    fi

    # Always ensure Docker is enabled at boot, even if it was pre-installed.
    if ! systemctl is-enabled --quiet docker 2>/dev/null; then
        info "Enabling Docker daemon at boot..."
        systemctl enable docker
    fi
    if ! systemctl is-active --quiet docker 2>/dev/null; then
        info "Starting Docker daemon..."
        systemctl start docker
    fi

    # Create the data volume if it doesn't exist
    if ! docker volume inspect "${PG_VOLUME}" &>/dev/null; then
        info "Creating Docker volume '${PG_VOLUME}'..."
        docker volume create "${PG_VOLUME}" >/dev/null
    else
        info "Docker volume '${PG_VOLUME}' already exists (preserving data)"
    fi

    # Pull image if not present
    if ! docker image inspect "${PG_IMAGE}" &>/dev/null; then
        info "Pulling ${PG_IMAGE}..."
        docker pull "${PG_IMAGE}"
    fi

    # A container created by an older installer published 5432 on every interface — with the
    # default password, and past ufw (Docker writes its own iptables rules). Port bindings cannot
    # be changed in place, so recreate it; the data lives in the volume and survives.
    # (grep >/dev/null, not grep -q: -q exits at the first match, and under pipefail the writer's
    # SIGPIPE would turn a match into a false "not found".)
    if docker ps -a --format '{{.Names}}' | grep "^${PG_CONTAINER}$" >/dev/null; then
        local bind_ips
        bind_ips="$(docker inspect -f '{{range $p, $c := .HostConfig.PortBindings}}{{range $c}}[{{.HostIp}}]{{end}}{{end}}' "${PG_CONTAINER}")"
        if [ "${bind_ips}" != "[127.0.0.1]" ]; then
            info "Container '${PG_CONTAINER}' publishes PostgreSQL beyond loopback — recreating (volume '${PG_VOLUME}' kept)..."
            # Stop the only client first, then give PostgreSQL a clean shutdown.
            if systemctl is-active --quiet "${SERVICE_NAME}" 2>/dev/null; then
                POS_SERVER_STOPPED=1
            fi
            systemctl stop "${SERVICE_NAME}" 2>/dev/null || true
            docker stop -t 60 "${PG_CONTAINER}" >/dev/null || true
            docker rm "${PG_CONTAINER}" >/dev/null
        fi
    fi

    # Reuse existing container if present, otherwise create
    if docker ps -a --format '{{.Names}}' | grep "^${PG_CONTAINER}$" >/dev/null; then
        info "Container '${PG_CONTAINER}' already exists"
        if ! docker ps --format '{{.Names}}' | grep "^${PG_CONTAINER}$" >/dev/null; then
            info "Starting existing container..."
            docker start "${PG_CONTAINER}" >/dev/null
        fi
    else
        info "Creating PostgreSQL container '${PG_CONTAINER}'..."
        docker run -d \
            --name "${PG_CONTAINER}" \
            --restart unless-stopped \
            -e POSTGRES_DB="${PG_DB}" \
            -e POSTGRES_USER="${PG_USER}" \
            -e POSTGRES_PASSWORD="${PG_PASSWORD}" \
            -p "127.0.0.1:${PG_PORT}:5432" \
            -v "${PG_VOLUME}:/var/lib/postgresql/data" \
            "${PG_IMAGE}" >/dev/null
        info "Container created and started"
    fi

    # Wait for postgres to accept connections
    info "Waiting for PostgreSQL to be ready..."
    local retries=0
    while ! docker exec "${PG_CONTAINER}" pg_isready -U "${PG_USER}" -d "${PG_DB}" &>/dev/null; do
        retries=$((retries + 1))
        if [ $retries -ge 30 ]; then
            error "PostgreSQL did not become ready after 30s — check 'docker logs ${PG_CONTAINER}'"
        fi
        sleep 1
    done
    info "PostgreSQL is ready on localhost:${PG_PORT}"
}

# --- Uninstall ---

uninstall() {
    check_root
    info "Uninstalling ${SERVICE_NAME}..."

    if systemctl is-active --quiet "${SERVICE_NAME}" 2>/dev/null; then
        info "Stopping service..."
        systemctl stop "${SERVICE_NAME}"
    fi

    if systemctl is-enabled --quiet "${SERVICE_NAME}" 2>/dev/null; then
        info "Disabling service..."
        systemctl disable "${SERVICE_NAME}"
    fi

    if [ -f "/etc/systemd/system/${SERVICE_NAME}.service" ]; then
        rm "/etc/systemd/system/${SERVICE_NAME}.service"
        systemctl daemon-reload
        info "Service file removed"
    fi

    if [ -f "/etc/systemd/system/${CADDY_SERVICE}.service" ]; then
        systemctl stop "${CADDY_SERVICE}" 2>/dev/null || true
        systemctl disable "${CADDY_SERVICE}" 2>/dev/null || true
        rm -f "/etc/systemd/system/${CADDY_SERVICE}.service"
        systemctl daemon-reload
        info "HTTPS proxy service removed (config kept in /etc/${CADDY_SERVICE}, certificates in ${CADDY_DATA_DIR})"
    fi

    # Clean up restart-guard artifacts (the install dir itself stays;
    # operator removes it manually as documented at the end of uninstall).
    if [ -d "${DEFAULT_INSTALL_DIR}" ]; then
        rm -f "${DEFAULT_INSTALL_DIR}/restart-guard.sh"
        rm -f "${DEFAULT_INSTALL_DIR}/.restart-count"
        rm -f "${DEFAULT_INSTALL_DIR}/pos-server.jar.bak"
        rm -f "${DEFAULT_INSTALL_DIR}"/pos-server.jar.failed-* 2>/dev/null || true
    fi

    # Remove tmpfiles exclusion
    if [ -f /etc/tmpfiles.d/pos-embedded-pg.conf ]; then
        rm /etc/tmpfiles.d/pos-embedded-pg.conf
        info "Removed tmpfiles exclusion for embedded PostgreSQL"
    fi

    # Remove firewall rules (both the pre-HTTPS port rule and the HTTPS rules)
    # Only rules this installer added (recognised by their comments) are deleted.
    if command -v ufw &>/dev/null && ufw status | grep "Status: active" >/dev/null; then
        if ufw status | grep "POS Server" >/dev/null; then
            ufw delete allow "${DEFAULT_SERVER_PORT}/tcp" 2>/dev/null || true
            info "Removed firewall rule for port ${DEFAULT_SERVER_PORT}/tcp"
        fi
        if ufw status | grep "POS HTTPS" >/dev/null; then
            ufw delete allow 443/tcp 2>/dev/null || true
            ufw delete allow 80/tcp 2>/dev/null || true
            info "Removed firewall rules for ports 443/tcp and 80/tcp"
        fi
    fi

    # Stop Docker PostgreSQL container if requested
    if [ "${REMOVE_DOCKER_PG:-0}" = "1" ] && command -v docker &>/dev/null; then
        if docker ps -a --format '{{.Names}}' | grep "^${PG_CONTAINER}$" >/dev/null; then
            info "Stopping and removing Docker container '${PG_CONTAINER}'..."
            docker stop "${PG_CONTAINER}" 2>/dev/null || true
            docker rm "${PG_CONTAINER}" 2>/dev/null || true
            info "Container removed (volume '${PG_VOLUME}' preserved — run 'docker volume rm ${PG_VOLUME}' to delete data)"
        fi
    fi

    info "Uninstall complete."
    info "The '${SERVICE_USER}' and '${CADDY_USER}' users, install directory, data, HTTPS config and"
    info "certificates were NOT removed (a reinstall keeps its certificates and local CA)."
    info "To fully remove: userdel -r ${SERVICE_USER} && rm -rf ${DEFAULT_INSTALL_DIR}"
    info "  and: userdel ${CADDY_USER} && rm -rf /etc/${CADDY_SERVICE} /usr/local/lib/${CADDY_SERVICE} ${CADDY_DATA_DIR}"
}

# --- Install ---

# The unit's ExecStartPost health probe is a curl loop. Without curl it can never pass, so systemd
# treats every start as failed even though the server is running (a stock Ubuntu desktop has none).
ensure_curl() {
    command -v curl >/dev/null 2>&1 && return 0
    command -v apt-get >/dev/null 2>&1 || error "curl is required (the service's start-up health probe uses it) — install it and re-run"
    info "curl not found — installing it (needed by the service's start-up health probe)"
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl
}

# --migrate-data-dir copies the data folder with rsync. A minimal server image has none, and finding that out
# after the service is stopped leaves the shop down for no reason, so it is checked (and installed) up front.
ensure_rsync() {
    command -v rsync >/dev/null 2>&1 && return 0
    command -v apt-get >/dev/null 2>&1 || error "rsync is required to copy the data folder — install it and re-run"
    info "rsync not found — installing it (needed to copy the data folder)"
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq rsync || error "rsync could not be installed — install it and re-run"
}

# On a standard Ubuntu desktop install, offer to also turn the machine into a dedicated POS
# appliance (provision-node.sh). Asked only interactively and only where slimming makes sense;
# everywhere else this is silently "no" and the server is installed on its own. Sets SLIM.
offer_slim() {
    SLIM=0
    [ -z "${POS_NODE_PROVISIONING:-}" ] || return 0   # provision-node.sh is already driving us
    [ -t 0 ] || return 0
    [ -f "${SCRIPT_DIR}/provision-node.sh" ] || return 0
    bash "${SCRIPT_DIR}/provision-node.sh" --desktop-check >/dev/null 2>&1 || return 0

    info ""
    info "This looks like a standard Ubuntu desktop install."
    info "Besides the server, this machine can be turned into a dedicated POS device:"
    info "  - boot to a text console instead of GNOME (GNOME stays installed, so drivers are kept)"
    info "  - full-screen kiosk browser for the order station / admin app"
    info "  - TSE / backup USB mounts, firewall, no suspend, unneeded services switched off"
    info "Settings: ${SCRIPT_DIR}/pos-node.conf if present (template: pos-node.conf.example), else defaults."
    info "Nothing is removed, and 'sudo /opt/pos-node/provision-node.sh --undo' reverts it."
    info "Slim down this system as well? (y/N)"
    local answer
    read -r answer
    if [ "${answer}" = "y" ] || [ "${answer}" = "Y" ]; then SLIM=1; fi
}

install() {
    check_root
    ensure_curl

    # A stray second name ('--local-name a.lan b.lan') would otherwise become the install directory,
    # resolved against the current directory.
    case "${1:-}" in
        ""|/*) ;;
        *) error "install-dir must be an absolute path; got '$1'" ;;
    esac

    # Both physical and canonical: step 4 deletes the old files unless SCRIPT_DIR == INSTALL_DIR, so a
    # trailing slash, '..' or symlink naming the package directory must compare equal.
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
    INSTALL_DIR="$(realpath -m -- "${1:-$DEFAULT_INSTALL_DIR}")"
    SERVER_PORT="${2:-$DEFAULT_SERVER_PORT}"
    # No leading zeros: '0443' would slip past the 80/443 comparison.
    [[ "${SERVER_PORT}" =~ ^[1-9][0-9]{0,4}$ ]] && [ "${SERVER_PORT}" -ge 1 ] && [ "${SERVER_PORT}" -le 65535 ] \
        && [ "${SERVER_PORT}" != 80 ] && [ "${SERVER_PORT}" != 443 ] \
        || error "Port must be 1-65535 and not 80/443 (Caddy uses those); got '${SERVER_PORT}'"
    offer_slim
    # Persistent (survives re-installs — not in the file-removal list at step 5) and
    # space-free path, so it is safe to reference from EnvironmentFile=.
    # A fresh install gets the standard Linux paths; an install that already runs from the home-folder
    # layout keeps it (the unit is rewritten on every deploy, so this is decided each time).
    apply_layout "$(detect_install_layout)"

    info "Installing POS Server as systemd service"
    info "  Layout      : ${LAYOUT}"
    info "  Install dir : ${INSTALL_DIR}"
    info "  Server port : ${SERVER_PORT}"
    info "  Service user: ${SERVICE_USER}"
    info "  Data dir    : ${DATA_DIR}"
    [ "${LAYOUT}" = "standard" ] && info "  Log dir     : ${STD_LOG_DIR}   Config: ${STD_CONF_DIR}"
    info "  DB mode     : ${DB_MODE}"

    # 0. Validate the deploy package is complete — read-only, before anything changes
    preflight_package

    # 0b. Settle and validate the local DNS names for the certificate — read-only, before anything changes
    preflight_local_names
    check_system_clock

    # 1. Create dedicated service user (no login shell, system account)
    if id "${SERVICE_USER}" &>/dev/null; then
        info "User '${SERVICE_USER}' already exists"
    else
        info "Creating service user '${SERVICE_USER}'..."
        # useradd creates a same-name group itself, but exits 9 if a leftover one exists; then name it.
        local pos_gid_opt=()
        if getent group "${SERVICE_USER}" >/dev/null; then
            pos_gid_opt=(--gid "${SERVICE_USER}")
        fi
        # The standard layout keeps nothing under /home: no home folder is created there.
        local pos_home_opt=(--create-home --home-dir "/home/${SERVICE_USER}")
        if [ "${LAYOUT}" = "standard" ]; then
            pos_home_opt=(--no-create-home --home-dir "${STD_DATA_DIR}")
        fi
        useradd \
            --system \
            --shell /usr/sbin/nologin \
            "${pos_home_opt[@]}" \
            --comment "POS Server service account" \
            "${pos_gid_opt[@]}" \
            "${SERVICE_USER}"
        info "User '${SERVICE_USER}' created"
    fi

    # 2. Migrate old data directory to new app name
    if [ "${LAYOUT}" = "legacy" ] && [ -d "${OLD_DATA_DIR}" ] && [ ! -d "${DATA_DIR}" ]; then
        info "Migrating data directory from '${OLD_DATA_DIR}' to '${DATA_DIR}'..."
        mv "${OLD_DATA_DIR}" "${DATA_DIR}"
        info "Data directory migrated successfully"
    fi

    # 3. Install Docker PostgreSQL if requested
    if [ "${DB_MODE}" = "external" ]; then
        install_docker_postgres
    fi

    # 4. Remove old installation and copy new files
    if [ "${SCRIPT_DIR}" != "${INSTALL_DIR}" ]; then
        # Back up the previous JAR before overwriting so the restart-guard
        # can roll back to a known-good binary if the new one fails to start.
        #
        # On a FIRST install there is no previous JAR and we deliberately do NOT
        # seed pos-server.jar.bak from the JAR we are about to place. A .bak that
        # is byte-identical to the running JAR is not a rollback target: the guard
        # would archive the failing JAR as .failed-<ts>, restore the same bytes,
        # log "rolling back" and change nothing -- an operator reading the journal
        # would believe a rollback was attempted and had no effect. No .bak means
        # the guard stays quiet and the real failure stays visible. The first
        # update or re-install creates the first genuine .bak.
        if [ -f "${INSTALL_DIR}/pos-server.jar" ]; then
            info "Backing up previous JAR -> pos-server.jar.bak"
            cp -a "${INSTALL_DIR}/pos-server.jar" "${INSTALL_DIR}/pos-server.jar.bak"
        fi

        if [ -d "${INSTALL_DIR}" ]; then
            info "Removing old installation from ${INSTALL_DIR}..."
            rm -f "${INSTALL_DIR}/pos-server.jar"
            rm -f "${INSTALL_DIR}/run.sh"
            # No longer shipped (release notes live in the release-marker migration); removing the
            # copy an older package left behind keeps stale notes off the install.
            rm -f "${INSTALL_DIR}/CHANGELOG.md"
            rm -f "${INSTALL_DIR}/README.txt"
            rm -f "${INSTALL_DIR}/VERSION"
            rm -rf "${INSTALL_DIR}/jre"
            rm -rf "${INSTALL_DIR}/native"
        fi

        info "Copying files to ${INSTALL_DIR}..."
        mkdir -p "${INSTALL_DIR}"
        cp -a "${SCRIPT_DIR}/pos-server.jar" "${INSTALL_DIR}/" 2>/dev/null || true
        cp -a "${SCRIPT_DIR}/run.sh" "${INSTALL_DIR}/" 2>/dev/null || true
        [ -d "${SCRIPT_DIR}/jre" ] && cp -a "${SCRIPT_DIR}/jre" "${INSTALL_DIR}/"
        [ -d "${SCRIPT_DIR}/native" ] && cp -a "${SCRIPT_DIR}/native" "${INSTALL_DIR}/"
        [ -f "${SCRIPT_DIR}/README.txt" ] && cp -f "${SCRIPT_DIR}/README.txt" "${INSTALL_DIR}/README.txt"
        [ -f "${SCRIPT_DIR}/VERSION" ] && cp -f "${SCRIPT_DIR}/VERSION" "${INSTALL_DIR}/VERSION"

        # Older packages also copied CHANGELOG.md into the data directory; nothing reads it.
        rm -f "${DATA_DIR}/CHANGELOG.md"
    fi

    # 4b. Write the restart-guard helper and reset the failure counter.
    info "Writing restart-guard.sh..."
    cat > "${INSTALL_DIR}/restart-guard.sh" <<'GUARDEOF'
#!/bin/bash
# Auto-rollback guard for pos-server.
# Invoked via systemd ExecStartPre. Counts consecutive failed starts;
# after THRESHOLD failures, swaps in pos-server.jar.bak so the next
# start uses the last known-good binary. Always exits 0 — never blocks
# the start sequence.
#
# The counter counts START ATTEMPTS (ExecStartPre), not process exits, and
# ExecStartPost zeroes it as soon as /health answers. An update therefore
# costs the new build nothing: the applier's deliberate exit 42 happens in a
# process whose own start already reported healthy, so the counter is 0 and
# the new JAR gets the full THRESHOLD budget. Three genuinely failed starts
# still roll back.
#
# THRESHOLD must stay BELOW the unit's StartLimitBurst (5): rollback happens
# on the (THRESHOLD+1)th start, so raising it past 4 would let systemd hit the
# start limit and give up before the guard ever swaps the JAR back.
set -u
INSTALL_DIR="$(dirname "$(readlink -f "$0")")"
COUNTER="${INSTALL_DIR}/.restart-count"
JAR="${INSTALL_DIR}/pos-server.jar"
BACKUP="${INSTALL_DIR}/pos-server.jar.bak"
THRESHOLD=3

count=$(cat "$COUNTER" 2>/dev/null || echo 0)
count=$((count + 1))
echo "$count" > "$COUNTER"

if [ "$count" -gt "$THRESHOLD" ] && [ -f "$BACKUP" ]; then
    ts=$(date +%s)
    logger -t pos-server-rollback \
        "rolling back after $count failed starts; archiving as pos-server.jar.failed-$ts"
    mv "$JAR" "${JAR}.failed-${ts}" 2>/dev/null || true
    mv "$BACKUP" "$JAR"
    echo 0 > "$COUNTER"
fi
exit 0
GUARDEOF
    chmod +x "${INSTALL_DIR}/restart-guard.sh"
    echo 0 > "${INSTALL_DIR}/.restart-count"

    # Verify required files
    [ -f "${INSTALL_DIR}/pos-server.jar" ] || error "pos-server.jar not found in ${INSTALL_DIR}"
    [ -f "${INSTALL_DIR}/run.sh" ] || error "run.sh not found in ${INSTALL_DIR}"

    # 5. Create data and log directories
    mkdir -p "${DATA_DIR}/.data/postgresql"
    if [ "${LAYOUT}" = "standard" ]; then
        mkdir -p "${STD_LOG_DIR}"
        # Holds the env file (secrets): systemd reads it as root; the service user never needs it.
        mkdir -p "${STD_CONF_DIR}"
    else
        mkdir -p "${DATA_DIR}/.data/log"
    fi
    mkdir -p "${INSTALL_DIR}/logs"
    # The unit lists /media/pos under ReadWritePaths (TSE / backup sticks mounted there). systemd
    # refuses to start the service (226/NAMESPACE) when a ReadWritePaths entry does not exist.
    mkdir -p /media/pos

    # 5b. Provision the JWT signing secret (generate once, reuse on later deploys)
    provision_jwt_secret "${ENV_FILE}"

    # 5c. Provision the update-token encryption key (generate once, reuse on later deploys)
    provision_update_token_key "${ENV_FILE}"

    # 5d. Optional maintainer account for log search (asked once; an existing account is never touched).
    #     A failure here must not abort the install: the env file is left exactly as it was.
    configure_maintainer_account "${ENV_FILE}" "${INSTALL_DIR}" ||
        warn "Maintainer account NOT set up (see above). Add it later with: sudo $0 --maintainer"

    # 6. Set ownership and permissions
    chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${INSTALL_DIR}"
    chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${DATA_DIR}"
    [ "${LAYOUT}" != "standard" ] || chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${STD_LOG_DIR}"
    chmod +x "${INSTALL_DIR}/run.sh"

    # 7. Create systemd service file
    info "Creating systemd service..."
    render_unit "${LAYOUT}" > "${UNIT_FILE}"

    # 7b. HTTPS reverse proxy in front of pos-server
    install_caddy

    # 7c. Bind pos-server to loopback only now that Caddy is installed and its config validated. Done
    # after install_caddy on purpose: if that step fails, the next pos-server start (updater exit 42,
    # crash, reboot) must still be reachable on the LAN rather than loopback-only with no proxy.
    provision_bind_address "${ENV_FILE}"
    provision_tls_root_file "${ENV_FILE}"

    # 8. Protect embedded PostgreSQL shared memory from systemd-tmpfiles-clean
    info "Creating tmpfiles exclusion for embedded PostgreSQL..."
    cat > /etc/tmpfiles.d/pos-embedded-pg.conf <<TMPEOF
# Preserve embedded PostgreSQL shared memory and binaries used by pos-server.
# Without this, systemd-tmpfiles-clean removes /dev/shm/PostgreSQL.* and
# breaks database connectivity until a full service restart.
x /dev/shm/PostgreSQL.*
x /tmp/embedded-pg
x /tmp/embedded-pg/**
TMPEOF

    # 9. Firewall: only Caddy is reachable from the LAN.
    if command -v ufw &>/dev/null && ufw status | grep "Status: active" >/dev/null; then
        # Open the HTTPS ports first: if that fails, the old port rule is still in place.
        ufw allow 443/tcp comment "POS HTTPS"
        ufw allow 80/tcp comment "POS HTTPS redirect"
        info "Firewall: 80/tcp and 443/tcp open"
        # Only the rule the pre-HTTPS installer added (comment "POS Server"); an operator's own rule stays.
        if ufw status | grep "^${SERVER_PORT}/tcp .*# POS Server[[:space:]]*$" >/dev/null; then
            ufw delete allow "${SERVER_PORT}/tcp" || true
            info "Closed port ${SERVER_PORT}/tcp — pos-server is loopback-only now"
        elif ufw status | grep "^${SERVER_PORT}/tcp " >/dev/null; then
            info "Port ${SERVER_PORT}/tcp stays open in ufw (a rule this installer did not add); pos-server listens on 127.0.0.1 only, so the LAN cannot reach it there"
        fi
    else
        warn "ufw is not active — make sure only ports 80 and 443 are reachable from the LAN"
    fi

    # 10. Reload systemd and enable service
    systemctl daemon-reload
    systemctl enable "${SERVICE_NAME}" "${CADDY_SERVICE}"

    info ""
    info "Installation complete!"
    info ""
    info "JWT secret  : ${ENV_FILE} (chmod 600, generated once, reused on re-deploy)"
    info "  To rotate it (logs everyone out): edit that file, set a new JWT_SECRET,"
    info "  then 'sudo systemctl restart ${SERVICE_NAME}'."
    info ""
    info "Update key  : ${ENV_FILE} (chmod 600, generated once, reused on re-deploy)"
    info "  UPDATE_TOKEN_KEY encrypts the stored GitHub update token. Replacing it"
    info "  makes that token undecryptable -- you must re-enter it in the settings."
    info ""
    if [ -n "$(env_get "${ENV_FILE}" MAINTAINER_USERNAME)" ] && [ -n "$(env_get "${ENV_FILE}" MAINTAINER_PASSWORD_HASH)" ]; then
        maintainer_address_note
        if [ "${MAINTAINER_CHANGED}" = "1" ] && systemctl is-active --quiet "${SERVICE_NAME}" 2>/dev/null; then
            info "  The server is running: restart it to apply the account: sudo systemctl restart ${SERVICE_NAME}"
        fi
        info ""
    fi
    info "Commands:"
    info "  sudo systemctl start ${SERVICE_NAME}     # Start the server"
    info "  sudo systemctl stop ${SERVICE_NAME}      # Stop the server"
    info "  sudo systemctl status ${SERVICE_NAME}    # Check status"
    info "  sudo journalctl -u ${SERVICE_NAME} -f    # Follow logs"
    info "  sudo journalctl -u ${CADDY_SERVICE} -f      # Follow HTTPS proxy logs"
    info "  Server address for tills and browsers: https://<IP address of this server>/$([ "${#LOCAL_NAMES[@]}" -eq 0 ] || printf ' or https://%s/' "${LOCAL_NAMES[@]}")"
    info ""
    info "Start now? (y/N)"
    # No terminal (EOF) counts as "N": the install itself succeeded.
    read -r answer || answer=""
    # (stdin may be consumed by --maintainer-password-stdin: EOF means no)
    if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
        if systemctl restart "${SERVICE_NAME}" "${CADDY_SERVICE}"; then
            info "Services started"
        else
            warn "A service failed to start - check 'journalctl -u ${SERVICE_NAME} -u ${CADDY_SERVICE}'. If pos-server is healthy but HTTPS fails, the port argument (${SERVER_PORT}) may not match the port chosen in the setup wizard."
        fi
        # 'status' exits non-zero when a unit is not active; report it, don't abort under set -e.
        systemctl status "${SERVICE_NAME}" "${CADDY_SERVICE}" --no-pager || true
        export_local_root_ca
    else
        if [ "${POS_SERVER_STOPPED}" = "1" ]; then
            warn "pos-server was stopped to recreate the PostgreSQL container and is NOT running now."
        fi
        info "Start later with: sudo systemctl start ${SERVICE_NAME} ${CADDY_SERVICE}. Tills cannot connect until both services are running."
    fi

    if [ "${SLIM}" = "1" ]; then
        info ""
        info "Slimming this system (provision-node.sh)..."
        POS_NODE_PROVISIONING=1 bash "${SCRIPT_DIR}/provision-node.sh" --no-server --port "${SERVER_PORT}"
    fi
}

# --- --migrate-data-dir: move an old home-folder install to the standard Linux paths ---

MIGRATE_ASSUME_YES=0     # --yes: stop a running service without asking
MIGRATE_HEALTH_TRIES=30  # x 2 s

# Size in KiB of the tree at $1, without the log folder (logs are copied separately, best effort).
migrate_size_kb() { du -sk --exclude=.data/log "$1" | awk '{print $1}'; }

# Free KiB on the filesystem that will hold $1 (the nearest existing parent).
migrate_free_kb() {
    local dir="$1"
    while [ ! -d "${dir}" ] && [ "${dir}" != "/" ]; do dir="$(dirname "${dir}")"; done
    df -Pk "${dir}" | awk 'NR==2 {print $4}'
}

# File count and total bytes of the tree at $1 without the log folder: "<count> <bytes>".
migrate_fingerprint() {
    (cd "$1" && find . -path ./.data/log -prune -o -type f -printf '%s\n' | awk '{n++; b+=$1} END {print n+0, b+0}')
}

# Tells whether the service may be stopped: it is not running, or the operator agreed (--yes or a "y").
migrate_confirm_stop() {
    systemctl is-active --quiet "${SERVICE_NAME}" 2>/dev/null || return 0
    [ "${MIGRATE_ASSUME_YES}" = "1" ] && return 0
    if is_interactive; then
        local answer
        read -r -p "${SERVICE_NAME} is running. Stop it for the migration? [y/N] " answer || answer=""
        [ "${answer}" = "y" ] || [ "${answer}" = "Y" ] && return 0
    fi
    return 1
}

migrate_own() {
    [ "$(id -u)" -eq 0 ] || return 0
    id "${SERVICE_USER}" >/dev/null 2>&1 || return 0
    chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "$@"
}

# Copies $1 to $2 and checks the copy. Returns non-zero (with a message) on any mismatch.
migrate_copy_and_verify() {
    local old="$1" new="$2" before after
    mkdir -p "${new}"
    rsync -a --exclude='/.data/log/' "${old}/" "${new}/" || { warn "rsync failed"; return 1; }
    before="$(migrate_fingerprint "${old}")"
    after="$(migrate_fingerprint "${new}")"
    if [ "${before}" != "${after}" ]; then
        warn "Copy verification failed: old has '${before}' (files bytes), new has '${after}'"
        return 1
    fi
    if [ -d "${old}/.data/postgresql" ]; then
        local f
        for f in PG_VERSION postgresql.conf; do
            if [ -e "${old}/.data/postgresql/${f}" ] && [ ! -e "${new}/.data/postgresql/${f}" ]; then
                warn "Copy verification failed: ${f} is missing in the new database folder"
                return 1
            fi
        done
    fi
}

migrate_health_check() {
    local port="$1" i
    for i in $(seq 1 "${MIGRATE_HEALTH_TRIES}"); do
        curl -fsS -o /dev/null "http://127.0.0.1:${port}/health" && return 0
        sleep 2
    done
    return 1
}

# Waits until the server has consumed the rebase marker, i.e. rewritten the stored document, backup
# and DATEV paths. /health alone does not prove it: a failed rebase leaves the marker behind.
migrate_wait_rebase() {
    local new="$1" i
    for i in $(seq 1 "${MIGRATE_HEALTH_TRIES}"); do
        [ -e "${new}/${MARKER_RELATIVE}" ] || return 0
        sleep 2
    done
    return 1
}

# Port in the installed unit's health probe, or the default.
migrate_port() {
    local p
    p="$(grep -o '127\.0\.0\.1:[0-9]*/health' "${UNIT_FILE}" 2>/dev/null | head -1 | sed 's/.*://; s/\/health//')"
    echo "${p:-${DEFAULT_SERVER_PORT}}"
}

# Removes the database copy this run made in $1. migrate_data_dir refuses to start when $1 already holds a
# database, so none existed before; leaving the partial one behind would make every retry refuse.
migrate_discard_copy() {
    rm -rf "${1}/.data/postgresql"
}

# Puts the old unit and env file back and starts the old layout again.
migrate_rollback() {
    local unit_backup="$1" was_running="$2" new="$3"
    warn "Rolling back: the old data folder was never modified."
    systemctl stop "${SERVICE_NAME}" 2>/dev/null || true
    [ -f "${unit_backup}" ] && cp -p "${unit_backup}" "${UNIT_FILE}"
    rm -f "${new}/${MARKER_RELATIVE}"
    migrate_discard_copy "${new}"
    systemctl daemon-reload
    if [ "${was_running}" = "1" ]; then systemctl start "${SERVICE_NAME}" || warn "The old service did not start; check 'journalctl -u ${SERVICE_NAME}'"; fi
}

MARKER_RELATIVE=".data/previous-data-dir"

# migrate_data_dir: seven steps, stopping at the first failure (see the design: zenbon-server-standard-paths).
migrate_data_dir() {
    local old="${LEGACY_DATA_DIR}" new="${STD_DATA_DIR}" was_running=0 unit_backup port env_old
    INSTALL_DIR="$(realpath -m -- "${1:-$DEFAULT_INSTALL_DIR}")"
    env_old="${INSTALL_DIR}/pos-server.env"
    [ "$(detect_install_layout)" = "legacy" ] || error "Nothing to migrate: this machine already uses ${STD_DATA_DIR}."
    [ -d "${old}" ] || error "Old data folder ${old} not found."
    [ -f "${UNIT_FILE}" ] || error "${UNIT_FILE} not found: is pos-server installed?"
    [ ! -e "${new}/.data/postgresql" ] || error "${new} already holds a database; refusing to overwrite it."

    # 1+2. Decide and check space — read-only, before the service is touched
    migrate_confirm_stop || error "${SERVICE_NAME} is running. Stop it first, or pass --yes to let this script stop it."
    ensure_rsync
    local need free
    need="$(migrate_size_kb "${old}")"
    free="$(migrate_free_kb "${new}")"
    # 10 % headroom for the database growing while it is copied
    [ "${free}" -gt $((need + need / 10)) ] || error "Not enough free space for ${new}: need about ${need} KiB, ${free} KiB free."

    if systemctl is-active --quiet "${SERVICE_NAME}" 2>/dev/null; then
        was_running=1
        info "Stopping ${SERVICE_NAME}..."
        systemctl stop "${SERVICE_NAME}"
    fi

    # 3+4. Copy (source untouched) and verify
    info "Copying ${old} -> ${new} (this can take a while)..."
    if ! migrate_copy_and_verify "${old}" "${new}"; then
        rm -f "${new}/${MARKER_RELATIVE}"
        migrate_discard_copy "${new}"
        if [ "${was_running}" = "1" ]; then systemctl start "${SERVICE_NAME}" || true; fi
        error "Migration stopped: the copy could not be verified. Nothing was changed; ${old} is untouched and the incomplete copy was removed, so the migration can be run again."
    fi

    # 5. Marker (the server rebases the stored paths on its next start), unit and env file
    mkdir -p "${new}/.data" "${STD_LOG_DIR}" "${STD_CONF_DIR}"
    printf '%s\n' "${old}" > "${new}/${MARKER_RELATIVE}"
    [ -d "${old}/.data/log" ] && cp -a "${old}/.data/log/." "${STD_LOG_DIR}/" 2>/dev/null || true
    migrate_own "${new}" "${STD_LOG_DIR}"
    unit_backup="${UNIT_FILE}.pre-migrate"
    cp -p "${UNIT_FILE}" "${unit_backup}"
    if [ -f "${env_old}" ]; then
        cp -p "${env_old}" "${STD_ENV_FILE}"
        chmod 600 "${STD_ENV_FILE}"
    fi
    ENV_FILE="${STD_ENV_FILE}"
    DATA_DIR="${new}"
    SERVER_PORT="$(migrate_port)"
    DB_MODE="$(grep -o '^Environment=DATABASE_MODE=.*' "${UNIT_FILE}" | head -1 | sed 's/.*=//')"
    DB_MODE="${DB_MODE:-embedded}"
    render_unit standard > "${UNIT_FILE}"
    systemctl daemon-reload
    info "Starting ${SERVICE_NAME} on the new data folder..."
    if ! systemctl start "${SERVICE_NAME}"; then
        migrate_rollback "${unit_backup}" "${was_running}" "${new}"
        rm -f "${STD_ENV_FILE}"
        error "Migration rolled back: ${SERVICE_NAME} did not start on ${new}."
    fi

    # 6+7. Health check; then retire the old folder, or roll back
    if ! migrate_health_check "${SERVER_PORT}"; then
        migrate_rollback "${unit_backup}" "${was_running}" "${new}"
        rm -f "${STD_ENV_FILE}"
        error "Migration rolled back: ${SERVICE_NAME} was not healthy on ${new}."
    fi
    if ! migrate_wait_rebase "${new}"; then
        migrate_rollback "${unit_backup}" "${was_running}" "${new}"
        rm -f "${STD_ENV_FILE}"
        error "Migration rolled back: ${SERVICE_NAME} did not rebase its stored paths onto ${new}."
    fi
    local retired="${old}.migrated-$(date +%Y%m%d)"
    mv "${old}" "${retired}"
    rm -f "${env_old}"
    info "Migration complete."
    info "  Data   : ${new}"
    info "  Logs   : ${STD_LOG_DIR}"
    info "  Config : ${STD_ENV_FILE}"
    info "The old folder was renamed to '${retired}'. Delete it after a good backup; it is no longer used."
}

# --- --backup-device: mount a USB volume for the backup copy ---

BACKUP_DEVICE_DEFAULT_LABEL="ZEN_BACKUP"

# The shared helper lives next to this script (scripts/lib/usb-mount.sh in the deploy package).
load_usb_helper() {
    local lib
    lib="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/usb-mount.sh"
    [ -f "${lib}" ] || error "lib/usb-mount.sh not found next to this script; the deploy package is incomplete."
    # The helper records changes for provision-node's --undo; this script keeps no such state.
    record() { :; }
    put_file() { mkdir -p "$(dirname "$1")"; cat > "$1"; }
    # shellcheck source=lib/usb-mount.sh
    source "${lib}"
}

# Sets a label on an existing filesystem (never formats). Returns 1 for a filesystem it cannot label.
label_device() {
    local dev="$1" label="$2" fstype
    fstype="$(blkid -s TYPE -o value "${dev}")"
    case "${fstype}" in
        ext2|ext3|ext4) e2label "${dev}" "${label}" ;;
        vfat)           fatlabel "${dev}" "${label}" ;;
        exfat)          exfatlabel "${dev}" "${label}" ;;
        ntfs)           ntfslabel "${dev}" "${label}" ;;
        xfs)            xfs_admin -L "${label}" "${dev}" ;;
        *)              return 1 ;;
    esac
}

# backup_device_command [DEVICE] [LABEL]: pick a USB volume, mount it at /media/pos/<label>, print the path.
backup_device_command() {
    local dev="${1:-}" label="${2:-}" list n=0 choice fstype current
    load_usb_helper
    if [ -z "${dev}" ]; then
        list="$(list_candidate_devices)"
        [ -n "${list}" ] || error "No removable or USB volume found. Plug the backup stick in and run this again."
        info "Removable / USB volumes (the system disk is never listed):"
        # Tab is IFS whitespace, so `read` would merge the two tabs around an empty label and shift every column
        # after it: the fields are re-separated by the unit separator, which is not whitespace.
        while IFS=$'\037' read -r d size lbl fs model; do
            n=$((n + 1))
            info "  ${n}) ${d}  ${size}  label='${lbl}'  fs='${fs}'  ${model}"
        done < <(tr '\t' '\037' <<< "${list}")
        is_interactive || error "No terminal: name the device, e.g.  sudo $0 --backup-device /dev/sdb1"
        read -r -p "Which one holds the backups? [1-${n}] " choice || choice=""
        [[ "${choice}" =~ ^[0-9]+$ ]] && [ "${choice}" -ge 1 ] && [ "${choice}" -le "${n}" ] || error "Not a valid choice."
        dev="$(sed -n "${choice}p" <<< "${list}" | cut -f1)"
    else
        # A named device must still be one of the candidates: never the system disk, never an internal drive.
        cut -f1 <<< "$(list_candidate_devices)" | grep -qxF -- "${dev}" || error "${dev} is not a removable or USB volume (or it is the system disk)."
    fi
    check_backup_device "${dev}" || exit 1
    current="$(blkid -s LABEL -o value "${dev}" 2>/dev/null || true)"
    if [ -z "${label}" ]; then
        label="${current}"
        if [ -z "${label}" ]; then
            label="${BACKUP_DEVICE_DEFAULT_LABEL}"
            if is_interactive; then
                read -r -p "The volume has no label. Label to give it [${label}]: " choice || choice=""
                [ -z "${choice}" ] || label="${choice}"
            fi
        fi
    fi
    [[ "${label}" =~ ^[A-Za-z0-9_-]{1,11}$ ]] || error "Label '${label}' is not usable: letters, digits, _ and - only, at most 11 characters."
    if [ "${label}" != "${current}" ]; then
        label_device "${dev}" "${label}" || error "Cannot set a label on this filesystem type; label it yourself and run this again."
    fi
    fstype="$(blkid -s TYPE -o value "${dev}")"
    mount_labelled_volume "${label}" "${fstype}"
    systemctl daemon-reload
    start_labelled_automount "${label}"
    if ! volume_is_mounted "${USB_MOUNT_ROOT}/${label}"; then
        warn "The volume is not mounted yet, so nothing was set up on it. Plug it in (or reboot) and run this again."
        error "Backup volume ${USB_MOUNT_ROOT}/${label} is not mounted."
    fi
    case "${fstype}" in
        ext2|ext3|ext4|xfs) chown "${SERVICE_USER}:${SERVICE_GROUP}" "${USB_MOUNT_ROOT}/${label}" || warn "Could not give ${SERVICE_USER} the volume; run  sudo chown ${SERVICE_USER}:${SERVICE_GROUP} ${USB_MOUNT_ROOT}/${label}" ;;
    esac
    info ""
    info "Backup volume ready. In the back office (Settings -> Datenbank-Backup) enter as 'Kopie speichern in':"
    info "  ${USB_MOUNT_ROOT}/${label}"
}

# --- Entry point ---

main() {
    # Parse flags: --docker-postgres switches DB_MODE to external before install runs.
    POSITIONAL_ARGS=()
    SUBCOMMAND=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --uninstall|-u)
                SUBCOMMAND="uninstall"
                shift
                ;;
            --help|-h)
                SUBCOMMAND="help"
                shift
                ;;
            --docker-postgres)
                DB_MODE="external"
                shift
                ;;
            --remove-docker-postgres)
                REMOVE_DOCKER_PG=1
                shift
                ;;
            --migrate-data-dir)
                SUBCOMMAND="migrate"
                shift
                ;;
            --backup-device)
                SUBCOMMAND="backup-device"
                shift
                ;;
            --yes|-y)
                MIGRATE_ASSUME_YES=1
                shift
                ;;
            --maintainer)
                SUBCOMMAND="maintainer"
                shift
                ;;
            --maintainer-off)
                SUBCOMMAND="maintainer-off"
                shift
                ;;
            --maintainer-user)
                [ $# -ge 2 ] || error "--maintainer-user needs a NAME"
                MAINTAINER_NAME_ARG="$2"
                shift 2
                ;;
            --maintainer-password-stdin)
                MAINTAINER_PASSWORD_STDIN=1
                shift
                ;;
            --local-name)
                require_flag_value "$1" "${2-}"
                LOCAL_NAMES_ARG+=("$2")
                shift 2
                ;;
            --clear-local-names)
                CLEAR_LOCAL_NAMES=1
                shift
                ;;
            # Anything else starting with '-' is a typo or '--name=value'; as a positional it would become
            # the install directory.
            -*) error "Unknown option: $1 (use '--name value', not '--name=value')" ;;
            *)
                POSITIONAL_ARGS+=("$1")
                shift
                ;;
        esac
    done

    case "${SUBCOMMAND}" in
        uninstall)
            uninstall
            ;;
        migrate)
            check_root
            migrate_data_dir "${POSITIONAL_ARGS[@]+"${POSITIONAL_ARGS[@]}"}"
            ;;
        backup-device)
            check_root
            backup_device_command "${POSITIONAL_ARGS[@]+"${POSITIONAL_ARGS[@]}"}"
            ;;
        maintainer)
            check_root
            maintainer_command set "${POSITIONAL_ARGS[@]+"${POSITIONAL_ARGS[@]}"}"
            ;;
        maintainer-off)
            check_root
            maintainer_command off "${POSITIONAL_ARGS[@]+"${POSITIONAL_ARGS[@]}"}"
            ;;
        help)
            echo "Usage: sudo $0 [install-dir] [port] [--docker-postgres] [--local-name NAME]... [--clear-local-names] [maintainer options]"
            echo "       sudo $0 --maintainer [install-dir] [maintainer options]"
            echo "       sudo $0 --maintainer-off [install-dir]"
            echo "       sudo $0 --migrate-data-dir [install-dir] [--yes]"
            echo "       sudo $0 --backup-device [/dev/sdXN [LABEL]]"
            echo "       sudo $0 --uninstall [--remove-docker-postgres]"
            echo ""
            echo "  install-dir              Installation directory (default: /opt/pos-server)"
            echo "  port                     Port pos-server listens on — the one chosen in the setup wizard"
            echo "                           (default: 8080). Caddy forwards HTTPS to 127.0.0.1:<port>, so a"
            echo "                           mismatch makes every request fail."
            echo "  --docker-postgres        Install + run PostgreSQL in Docker (container: ${PG_CONTAINER})"
            echo "                           and configure pos-server to use external mode."
            echo "  --local-name NAME        Also put the local DNS name NAME in the HTTPS certificate (e.g."
            echo "                           kasse.fritz.box or pos.lan: at least one dot, not ending in .local)."
            echo "                           Repeat it for several names. The router's DNS must resolve NAME to"
            echo "                           this server. Replaces the stored list"
            echo "                           (/etc/${CADDY_SERVICE}/local-names); a re-run without --local-name keeps it."
            echo "  --clear-local-names      Remove the stored local names (cannot be combined with --local-name)."
            echo "  --migrate-data-dir       Move an install that keeps its data in /home/${SERVICE_USER}/${APP_NAME} to the standard"
            echo "                           Linux paths: data ${STD_DATA_DIR}, logs ${STD_LOG_DIR}, configuration"
            echo "                           ${STD_CONF_DIR}. Checks the free space, stops the service (after asking, or with"
            echo "                           --yes), copies and verifies the data, switches the service over and checks"
            echo "                           that it is healthy; on any failure the old folder and unit are kept. The old"
            echo "                           folder is renamed '${APP_NAME}.migrated-<date>' on success. A plain install or"
            echo "                           update never moves an existing install."
            echo "  --backup-device          List the removable / USB volumes (never the system disk), let you pick one,"
            echo "                           and mount it at /media/pos/<label> (fstab with nofail + automount, so a missing"
            echo "                           stick never blocks boot). It never formats: a device without a filesystem is"
            echo "                           refused and the mkfs command is printed for you to run yourself. Then enter"
            echo "                           /media/pos/<label> as 'Kopie speichern in' in the back office."
            echo "  --uninstall, -u          Remove the services (config, certificates, data and users are kept)."
            echo "  --remove-docker-postgres During uninstall, also stop + remove the Docker container."
            echo "                           Volume '${PG_VOLUME}' is preserved unless removed manually:"
            echo "                             docker volume rm ${PG_VOLUME}"
            echo ""
            echo "Maintainer account (optional; lets support search server logs at /maintenance/):"
            echo "  On install or update the installer asks 'Set up a maintainer account? [y/N]' once"
            echo "  (default no). An account that already exists is never changed by an install."
            echo "  --maintainer             Set or replace the account only, then restart the service."
            echo "  --maintainer-off         Remove the account (and restart the service)."
            echo "  --maintainer-user NAME   Username (letters, digits, . _ -); default suggestion: ${DEFAULT_MAINTAINER_USER}"
            echo "  --maintainer-password-stdin"
            echo "                           Read the password as ONE line from standard input instead of"
            echo "                           prompting (scripted use; needs --maintainer-user). Never pass a"
            echo "                           password as an argument -- it would show in the process list."
            echo "                           e.g.  sudo $0 --maintainer --maintainer-user ${DEFAULT_MAINTAINER_USER} --maintainer-password-stdin < pw.txt"
            echo "  Password: at least ${MAINTAINER_MIN_LENGTH} characters, at most ${MAINTAINER_MAX_BYTES} bytes (UTF-8). Without a terminal and"
            echo "  without --maintainer-password-stdin the question is skipped and the account stays off."
            echo "  /maintenance/ is served over HTTPS only (Caddy)."
            echo ""
            echo "HTTPS: Caddy's local CA signs the certificate for https://localhost, this server's IPv4 addresses,"
            echo "https://<hostname>.local and the local names. Every till, tablet and admin PC must trust its root,"
            echo "exported to /etc/${CADDY_SERVICE}/pos-root-ca.crt when the services are started by the installer."
            ;;
        *)
            install "${POSITIONAL_ARGS[@]+"${POSITIONAL_ARGS[@]}"}"
            ;;
    esac
}

# Run only when executed; tests source this file to call the functions directly.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    main "$@"
fi
