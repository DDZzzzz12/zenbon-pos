#!/bin/bash
# POS node provisioning — turn a stock Ubuntu *desktop* install into a POS appliance:
#   * the POS server as a systemd service (via install-service.sh)
#   * optionally a full-screen kiosk browser (cage + Chromium) for the order station / admin app
#   * boot to a text console instead of GNOME, unneeded services masked
#   * TSE / backup USB sticks mounted where the server can use them
#   * laptop settings (lid, battery, Wi-Fi power save, optional static IP), firewall, SSH, logs
#
# Starting from the desktop edition is deliberate: it already carries the hardware drivers
# (e.g. the Broadcom Wi-Fi module of a 2017 MacBook Air). Nothing is purged — GNOME is only
# switched off, so driver packages and their dependencies stay untouched.
#
# Usage (as root):
#   sudo ./provision-node.sh [config-file]     apply (safe to re-run)
#   ./provision-node.sh --check [config-file]  validate the config and inspect this machine; changes nothing
#   sudo ./provision-node.sh --undo            revert what a previous run changed (not the server install —
#                                              use install-service.sh --uninstall for that)
#   ./provision-node.sh --help
#
# install-service.sh offers this script as a question while it runs (only on a standard Ubuntu
# desktop install, only interactively) and then calls it with --no-server. Internal flags:
#   --no-server   skip the server install (the caller already did it)
#   --port N      server port, overrides the config
#   --force       run on a system that is not a standard Ubuntu desktop install
#
# Config lookup: the argument, else ./pos-node.conf next to this script, else /etc/pos-node.conf,
# else built-in defaults. See pos-node.conf.example.
#
# Nothing here stops the running desktop: boot-mode, lid, network and kiosk changes take effect
# on the next reboot, so the script can be run from the desktop's own terminal or over SSH.
# Every change is recorded in /var/lib/pos-node/state, which is what --undo replays.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/usb-mount.sh
source "${SCRIPT_DIR}/lib/usb-mount.sh"
NO_SERVER=0
FORCE=0
PORT_OVERRIDE=""
NODE_DIR="/opt/pos-node"
NODE_ENV="/etc/pos-node/kiosk.env"
STATE_DIR="/var/lib/pos-node"
STATE="${STATE_DIR}/state"

info()  { echo "[INFO]  $*"; }
warn()  { echo "[WARN]  $*"; }
error() { echo "[ERROR] $*" >&2; exit 1; }

usage() {
    sed -n '2,/^$/{/^$/!p}' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

# --- Probes -------------------------------------------------------------------------------------
# Never `producer | grep -q`: under pipefail, grep -q exiting early SIGPIPEs the producer and the
# whole pipeline reports failure even when the pattern matched.

module_loaded() { grep -qE "^($1) " /proc/modules; }

unit_exists() {
    local out
    out="$(systemctl list-unit-files "$1" 2>/dev/null || true)"
    grep -q "^$1" <<< "${out}"
}

has_ssh_key() { grep -qs '^[a-z]' /home/*/.ssh/authorized_keys /root/.ssh/authorized_keys; }

# --- State (what --undo replays) ----------------------------------------------------------------

# Appends one line, once. Kinds: file, mask, unit, fstab (replayed in order) and the
# record_once kinds default, gdm, ufw, upower (previous values, captured on the FIRST run only).
record() {
    mkdir -p "${STATE_DIR}"
    grep -qxF -- "$*" "${STATE}" 2>/dev/null || echo "$*" >> "${STATE}"
}

record_once() {
    local kind="$1"
    shift
    grep -q "^${kind} " "${STATE}" 2>/dev/null || record "${kind} $*"
}

# Write stdin to a file and remember it for --undo.
put_file() {
    local path="$1"
    mkdir -p "$(dirname "${path}")"
    cat > "${path}"
    record "file ${path}"
}

mask_unit() {
    local unit="$1"
    [ "$(systemctl is-enabled "${unit}" 2>/dev/null || true)" = "masked" ] && return 0
    systemctl mask "${unit}" >/dev/null 2>&1 || { warn "Could not mask ${unit}"; return 0; }
    case "${unit}" in *.service) systemctl stop "${unit}" >/dev/null 2>&1 || true ;; esac
    record "mask ${unit}"
}

# --- Configuration ------------------------------------------------------------------------------

set_defaults() {
    ROLE="server+kiosk"
    DEPLOY_DIR="${SCRIPT_DIR}"
    INSTALL_DIR="/opt/pos-server"
    PORT="8080"
    DOCKER_POSTGRES="no"
    KIOSK_APPS="pos"
    SERVER_URL=""
    BROWSER_CMD="auto"
    KIOSK_USER="kiosk"
    KIOSK_RESTART_AT="04:30"
    KIOSK_STATUS_TAB="yes"
    KIOSK_WAKE_COMBO="ctrl+alt+u"
    BATTERY_WARN_PERCENT="40"
    BATTERY_CRITICAL_PERCENT="15"
    BATTERY_REWARN_MINUTES="10"
    KEYBOARD_LAYOUT=""
    KEYBOARD_VARIANT=""
    KEYBOARD_MODEL=""
    KEYBOARD_OPTIONS=""
    CUPS="keep"
    PRINTER_QUEUE=""
    PRINTER_URI=""
    TSE_LABEL="SWISSBIT"
    TSE_FS="vfat"
    BACKUP_LABEL="ZEN_BACKUP"
    BACKUP_FS="exfat"
    HOLD_KERNEL="auto"
    FIREWALL="yes"
    SSH_KEY_ONLY="no"
    JOURNAL_MAX="500M"
    BOOT_TO_CONSOLE="yes"
    MASK_SERVICES="bluetooth ModemManager bolt colord switcheroo-control iio-sensor-proxy power-profiles-daemon gnome-remote-desktop cups-browsed apport whoopsie kerneloops"
    LID_IGNORE="yes"
    LID_STOPS_KIOSK="yes"
    WIFI_POWERSAVE_OFF="yes"
    STATIC_IP=""
    GATEWAY=""
    DNS=""
}

load_config() {
    local file="${1:-}"
    if [ -z "${file}" ]; then
        if   [ -f "${SCRIPT_DIR}/pos-node.conf" ]; then file="${SCRIPT_DIR}/pos-node.conf"
        elif [ -f /etc/pos-node.conf ];            then file="/etc/pos-node.conf"
        fi
    fi
    if [ -n "${file}" ]; then
        [ -f "${file}" ] || error "Config file not found: ${file}"
        info "Config: ${file}"
        # shellcheck disable=SC1090
        . "${file}"
    else
        info "No config file found — using defaults"
    fi
    [ -n "${DEPLOY_DIR}" ] || DEPLOY_DIR="${SCRIPT_DIR}"
    [ -z "${PORT_OVERRIDE}" ] || PORT="${PORT_OVERRIDE}"
}

# Slimming keeps the desktop's drivers and switches GNOME off; that only makes sense on a standard
# Ubuntu desktop install. A server edition has nothing to slim, and other distros differ.
is_ubuntu_desktop() {
    [ -r /etc/os-release ] || return 1
    ( . /etc/os-release; [[ " ${ID:-} ${ID_LIKE:-} " == *" ubuntu "* ]] ) || return 1
    dpkg -s ubuntu-desktop >/dev/null 2>&1 || dpkg -s ubuntu-desktop-minimal >/dev/null 2>&1 || return 1
    unit_exists gdm3.service
}

require_desktop() {
    is_ubuntu_desktop && return 0
    if [ "${FORCE}" = "1" ]; then
        warn "Not a standard Ubuntu desktop install — continuing because of --force"
        return 0
    fi
    error "Slimming is built for a standard Ubuntu desktop install; this system is not one. Install the server alone (install-service.sh without --slim), or pass --force at your own risk."
}

wants_server() { [ "${ROLE}" = "server" ] || [ "${ROLE}" = "server+kiosk" ]; }
wants_kiosk()  { [ "${ROLE}" = "kiosk" ]  || [ "${ROLE}" = "server+kiosk" ]; }

validate() {
    case "${ROLE}" in server|kiosk|server+kiosk) ;; *) error "ROLE must be server, kiosk or server+kiosk (got '${ROLE}')" ;; esac
    case "${CUPS}" in keep|disable) ;; *) error "CUPS must be keep or disable (got '${CUPS}')" ;; esac
    case "${HOLD_KERNEL}" in auto|yes|no) ;; *) error "HOLD_KERNEL must be auto, yes or no (got '${HOLD_KERNEL}')" ;; esac
    [ "${ROLE}" != "kiosk" ] || [ -n "${SERVER_URL}" ] || error "ROLE=kiosk needs SERVER_URL (e.g. http://192.168.2.60:${PORT})"
    for app in ${KIOSK_APPS}; do
        case "${app}" in pos|admin) ;; *) error "KIOSK_APPS: unknown app '${app}' (use pos and/or admin)" ;; esac
    done
    [[ -z "${KIOSK_RESTART_AT}" || "${KIOSK_RESTART_AT}" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] \
        || error "KIOSK_RESTART_AT must be HH:MM or empty (got '${KIOSK_RESTART_AT}')"
    [[ "${JOURNAL_MAX}" =~ ^[0-9]+[KMG]$ ]] || error "JOURNAL_MAX must look like 500M (got '${JOURNAL_MAX}')"
    case "${LID_STOPS_KIOSK}" in yes|no) ;; *) error "LID_STOPS_KIOSK must be yes or no (got '${LID_STOPS_KIOSK}')" ;; esac
    case "${KIOSK_STATUS_TAB}" in yes|no) ;; *) error "KIOSK_STATUS_TAB must be yes or no (got '${KIOSK_STATUS_TAB}')" ;; esac
    [[ "${KIOSK_WAKE_COMBO}" =~ ^((ctrl|alt|shift|meta)\+)+([a-z0-9]|f([1-9]|1[0-2])|space|enter)$ ]] \
        || error "KIOSK_WAKE_COMBO must be modifiers plus one key, e.g. ctrl+alt+u (got '${KIOSK_WAKE_COMBO}')"
    [[ "${BATTERY_WARN_PERCENT}" =~ ^[1-9][0-9]?$ && "${BATTERY_CRITICAL_PERCENT}" =~ ^[1-9][0-9]?$ && "${BATTERY_REWARN_MINUTES}" =~ ^[1-9][0-9]{0,2}$ ]] \
        || error "BATTERY_WARN_PERCENT / BATTERY_CRITICAL_PERCENT must be 1-99 and BATTERY_REWARN_MINUTES a whole number"
    [ "${BATTERY_CRITICAL_PERCENT}" -lt "${BATTERY_WARN_PERCENT}" ] || error "BATTERY_CRITICAL_PERCENT must be below BATTERY_WARN_PERCENT"
    if [ -n "${PRINTER_QUEUE}${PRINTER_URI}" ]; then
        [ -n "${PRINTER_QUEUE}" ] && [ -n "${PRINTER_URI}" ] || error "Set both PRINTER_QUEUE and PRINTER_URI, or neither"
        [ "${CUPS}" = "keep" ] || error "PRINTER_QUEUE needs CUPS=keep"
    fi
    local label
    for label in "${TSE_LABEL}" "${BACKUP_LABEL}"; do
        [[ -z "${label}" || "${label}" =~ ^[A-Za-z0-9_-]+$ ]] || error "Volume labels may only contain A-Z a-z 0-9 _ - (got '${label}')"
    done
    if [ -n "${STATIC_IP}" ]; then
        [ -n "${GATEWAY}" ] || error "STATIC_IP needs GATEWAY"
    fi
    if wants_server && [ "${NO_SERVER}" = "0" ]; then
        [ -f "${DEPLOY_DIR}/pos-server.jar" ] && [ -f "${DEPLOY_DIR}/install-service.sh" ] \
            || error "DEPLOY_DIR (${DEPLOY_DIR}) must contain pos-server.jar and install-service.sh — unpack the server package there, or set DEPLOY_DIR"
    fi
    command -v apt-get >/dev/null && command -v systemctl >/dev/null || error "Needs a Debian/Ubuntu system with systemd"
}

# --- Steps --------------------------------------------------------------------------------------

# apt autoremove must never take a driver or its build chain with it, whoever installed it.
protect_drivers() {
    local pkg keep=()
    for pkg in broadcom-sta-dkms bcmwl-kernel-source dkms linux-headers-generic linux-firmware \
               network-manager cups cups-daemon; do
        dpkg -s "${pkg}" >/dev/null 2>&1 && keep+=("${pkg}")
    done
    if [ ${#keep[@]} -gt 0 ]; then
        apt-mark manual "${keep[@]}" >/dev/null
        info "Protected from autoremove: ${keep[*]}"
    fi
    local pci
    pci="$(lspci 2>/dev/null || true)"
    if grep -qi 'BCM43' <<< "${pci}" && ! module_loaded 'wl|brcmfmac|b43'; then
        warn "Broadcom Wi-Fi hardware found but no driver module is loaded — fix the driver before relying on Wi-Fi"
    fi
}

install_packages() {
    local pkgs=(curl ca-certificates)
    wants_kiosk && pkgs+=(cage xdg-desktop-portal-gtk)
    [ "${CUPS}" = "keep" ] && pkgs+=(cups)
    info "Installing packages: ${pkgs[*]}"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${pkgs[@]}"
}

install_server() {
    local args=("${INSTALL_DIR}" "${PORT}")
    [ "${DOCKER_POSTGRES}" = "yes" ] && args+=(--docker-postgres)
    # The service unit whitelists /media/pos as writable; systemd refuses to start it if it is missing.
    mkdir -p /media/pos
    info "Installing the POS server (install-service.sh ${args[*]})"
    # install-service.sh expects to run from the package directory.
    # The installer ends with a "Start now? (y/N)" prompt; answer it so this script never blocks.
    (cd "${DEPLOY_DIR}" && POS_NODE_PROVISIONING=1 bash ./install-service.sh "${args[@]}" <<< "y")
}

setup_printing() {
    if [ "${CUPS}" = "disable" ]; then
        info "CUPS disabled (network printers over raw TCP still work)"
        local u
        for u in cups.service cups.socket cups.path cups-browsed.service; do
            if unit_exists "${u}"; then mask_unit "${u}"; fi
        done
        return 0
    fi
    systemctl enable --now cups.service
    # The server's service account prints through CUPS; it must be allowed to.
    if id pos >/dev/null 2>&1; then
        local g
        for g in lp lpadmin; do getent group "${g}" >/dev/null && usermod -aG "${g}" pos; done
    fi
    if [ -n "${PRINTER_QUEUE}" ]; then
        info "Creating raw CUPS queue '${PRINTER_QUEUE}' -> ${PRINTER_URI}"
        lpadmin -p "${PRINTER_QUEUE}" -E -v "${PRINTER_URI}" -m raw
    else
        info "No printer queue requested. Add one when ready (USB printers need a CUPS queue):"
        info "  lpinfo -v                                  # find the device URI"
        info "  sudo lpadmin -p receipt -E -v <uri> -m raw  # raw queue, ESC/POS passes through untouched"
        info "  or use http://localhost:631 — then select it in the POS printer settings."
    fi
}

setup_mounts() {
    wants_server || return 0
    id pos >/dev/null 2>&1 || { warn "Service user 'pos' missing — skipping USB mounts"; return 0; }
    [ -z "${TSE_LABEL}" ]    || mount_labelled_volume "${TSE_LABEL}" "${TSE_FS}"
    [ -z "${BACKUP_LABEL}" ] || mount_labelled_volume "${BACKUP_LABEL}" "${BACKUP_FS}"
    systemctl daemon-reload
    if [ -n "${BACKUP_LABEL}" ]; then
        info "Backups: in the admin app set the backup path to /media/pos/${BACKUP_LABEL}"
        info "  (FAT32 sticks cannot hold files over 4 GB — format the stick exFAT or ext4)"
    fi
}

# A flat battery must shut the machine down cleanly, not hard-cut the database. UPower's default
# critical action is HybridSleep, which slim_services masks.
setup_power() {
    local conf=/etc/UPower/UPower.conf old
    [ -f "${conf}" ] || return 0
    old="$(sed -n 's/^CriticalPowerAction=//p' "${conf}" | head -1)"
    record_once upower "${old:-HybridSleep}"
    sed -i 's/^CriticalPowerAction=.*/CriticalPowerAction=PowerOff/' "${conf}"
    info "Battery critical action: PowerOff"
}

# The Broadcom `wl` Wi-Fi module is built by DKMS per kernel. An unattended kernel update whose
# build fails leaves the machine without Wi-Fi after the next reboot, so such machines take kernel
# updates only when an operator does them on purpose.
hold_kernel() {
    [ "${HOLD_KERNEL}" != "no" ] || return 0
    if [ "${HOLD_KERNEL}" = "auto" ]; then
        module_loaded 'wl|nvidia|r8168|rtl88' || return 0
    fi
    put_file /etc/apt/apt.conf.d/53pos-node-kernel <<'EOF'
// Out-of-tree (DKMS) driver in use: no unattended kernel upgrades. Upgrade by hand, then check
// `dkms status` shows the module built for the new kernel BEFORE rebooting.
Unattended-Upgrade::Package-Blacklist { "linux-image-"; "linux-headers-"; "linux-modules-"; "linux-generic"; };
EOF
    info "Unattended kernel upgrades blocked (DKMS driver in use) — update kernels by hand and check 'dkms status'"
}

# ufw is inactive on a desktop install. The SSH rule goes in first, so enabling it over SSH is safe.
setup_firewall() {
    [ "${FIREWALL}" = "yes" ] || return 0
    command -v ufw >/dev/null || apt-get install -y -qq ufw
    local ufw_state
    ufw_state="$(ufw status)"
    if grep -q "Status: inactive" <<< "${ufw_state}"; then record_once ufw inactive; else record_once ufw active; fi
    ufw allow 22/tcp comment 'SSH' >/dev/null
    if wants_server; then
        if [ -f /etc/pos-caddy/Caddyfile ]; then
            # install-service.sh binds pos-server to loopback; the LAN reaches it only through Caddy.
            ufw allow 443/tcp comment 'POS HTTPS' >/dev/null
            ufw allow 80/tcp comment 'POS CA download, redirect to HTTPS' >/dev/null
        else
            ufw allow "${PORT}/tcp" comment 'POS Server' >/dev/null
        fi
    fi
    if [ "$(systemctl is-active avahi-daemon 2>/dev/null)" = "active" ]; then
        ufw allow 5353/udp comment 'mDNS' >/dev/null
    fi
    ufw --force default deny incoming >/dev/null
    ufw --force enable >/dev/null
    info "Firewall on: SSH$(wants_server && { [ -f /etc/pos-caddy/Caddyfile ] && echo ", 443/tcp, 80/tcp" || echo ", ${PORT}/tcp"; })"
}

# Key-only SSH. Refuses to proceed unless a key is already installed, so it cannot lock you out,
# and validates the config with `sshd -t` before reloading. 00- sorts before cloud-init's 50- file
# (sshd keeps the FIRST value it sees).
setup_ssh() {
    [ "${SSH_KEY_ONLY}" = "yes" ] || return 0
    if ! has_ssh_key; then
        warn "SSH_KEY_ONLY=yes but no authorized_keys found — skipped (install a key with ssh-copy-id first)"
        return 0
    fi
    local conf=/etc/ssh/sshd_config.d/00-pos-node.conf
    put_file "${conf}" <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
    if sshd -t 2>/dev/null; then
        systemctl reload ssh.service 2>/dev/null || systemctl reload sshd.service 2>/dev/null || true
        info "SSH: password login disabled"
    else
        rm -f "${conf}"
        warn "sshd -t rejected the config — SSH hardening skipped"
    fi
}

setup_journald() {
    put_file /etc/systemd/journald.conf.d/50-pos-node.conf <<EOF
[Journal]
SystemMaxUse=${JOURNAL_MAX}
EOF
    systemctl restart systemd-journald
    info "Journal capped at ${JOURNAL_MAX}"
}

slim_services() {
    local unit
    for unit in ${MASK_SERVICES}; do
        if unit_exists "${unit}.service"; then
            mask_unit "${unit}.service" && info "Masked ${unit}"
        fi
    done
    # A server must never suspend, whatever the lid or an idle timer says.
    for unit in sleep suspend hibernate hybrid-sleep; do mask_unit "${unit}.target"; done
    info "Suspend/hibernate targets masked"

    put_file /etc/apt/apt.conf.d/52pos-node <<'EOF'
// Security updates stay on; never reboot by surprise during opening hours.
Unattended-Upgrade::Automatic-Reboot "false";
EOF
}

setup_boot() {
    [ "${BOOT_TO_CONSOLE}" = "yes" ] || return 0
    record_once default "$(systemctl get-default)"
    local dm
    for dm in gdm3.service gdm.service; do
        if [ "$(systemctl is-enabled "${dm}" 2>/dev/null || true)" = "enabled" ]; then
            record_once gdm "${dm}"
            systemctl disable "${dm}" 2>/dev/null || true
        fi
    done
    systemctl set-default multi-user.target
    info "Boot target: multi-user (GNOME/GDM disabled, not removed) — effective after reboot"
}

setup_laptop() {
    if [ "${LID_IGNORE}" = "yes" ]; then
        put_file /etc/systemd/logind.conf.d/50-pos-node.conf <<'EOF'
[Login]
HandleLidSwitch=ignore
HandleLidSwitchExternalPower=ignore
HandleLidSwitchDocked=ignore
IdleAction=ignore
EOF
        info "Lid switch ignored (effective after reboot)"
    fi
    if [ "${WIFI_POWERSAVE_OFF}" = "yes" ]; then
        put_file /etc/NetworkManager/conf.d/50-pos-node-wifi.conf <<'EOF'
[connection]
wifi.powersave = 2
EOF
        info "Wi-Fi power saving off (effective after reboot)"
    fi
}

setup_static_ip() {
    [ -n "${STATIC_IP}" ] || return 0
    command -v nmcli >/dev/null || { warn "nmcli not found — skipping STATIC_IP"; return 0; }
    local con
    con="$(nmcli -t -f NAME,TYPE con show --active | awk -F: '$2 ~ /wireless|ethernet/ {print $1; exit}')"
    [ -n "${con}" ] || { warn "No active Wi-Fi/Ethernet connection — skipping STATIC_IP"; return 0; }
    nmcli con modify "${con}" ipv4.method manual ipv4.addresses "${STATIC_IP}" ipv4.gateway "${GATEWAY}" \
        ipv4.dns "${DNS:-${GATEWAY}}"
    info "Static ${STATIC_IP} stored on connection '${con}' (effective after reboot; not applied now so this session survives)"
    warn "--undo does not revert the static IP; reset it with: nmcli con modify '${con}' ipv4.method auto"
}

# True when the binary is Ubuntu's chromium/firefox stub that just installs a snap.
is_snap_stub() {
    local p
    p="$(readlink -f "$1")"
    case "${p}" in /snap/*|/usr/bin/snap) return 0 ;; esac
    [ "$(head -c2 "${p}" 2>/dev/null)" = '#!' ] && grep -q 'snap' "${p}"
}

detect_browser() {
    if [ "${BROWSER_CMD}" != "auto" ]; then command -v "${BROWSER_CMD}" || echo "${BROWSER_CMD}"; return 0; fi
    local c p
    for c in chromium google-chrome-stable chromium-browser; do
        p="$(command -v "${c}" 2>/dev/null)" || continue
        is_snap_stub "${p}" && continue
        echo "${p}"; return 0
    done
    return 1
}

# The kiosk's "System" tab: battery, server and network state, power actions, and a low-battery warning
# that pulls the tab forward. The helper files ship in kiosk/ next to this script. Returns 1 (and warns)
# when the tab cannot be set up, so the kiosk itself still comes up without it.
install_status_helper() {
    local src="${SCRIPT_DIR}/kiosk" f
    command -v python3 >/dev/null || { warn "python3 not found — System tab skipped"; return 1; }
    for f in status-helper.py status.html screen-off.py; do
        [ -f "${src}/${f}" ] || { warn "${src}/${f} missing — System tab skipped"; return 1; }
    done
    mkdir -p "${NODE_DIR}"
    install -m 755 "${src}/status-helper.py" "${NODE_DIR}/status-helper.py"
    install -m 644 "${src}/status.html" "${NODE_DIR}/status.html"
    install -m 755 "${src}/screen-off.py" "${NODE_DIR}/screen-off.py"
    record "file ${NODE_DIR}/screen-off.py"
    record "file ${NODE_DIR}/status-helper.py"
    record "file ${NODE_DIR}/status.html"

    # The helper runs inside the kiosk session as the kiosk user. Allow exactly what its buttons do: power
    # off and restart (logind's "multiple sessions" and "inhibit" variants too, or an open SSH login would
    # make polkit ask for a password nobody can type) and starting the screen-off unit, which stops the
    # kiosk. Nothing else.
    put_file /etc/polkit-1/rules.d/50-pos-kiosk.rules <<EOF
polkit.addRule(function(action, subject) {
    if (subject.user !== "${KIOSK_USER}" || !subject.local || !subject.active) {
        return;
    }
    var power = [
        "org.freedesktop.login1.power-off",
        "org.freedesktop.login1.power-off-multiple-sessions",
        "org.freedesktop.login1.power-off-ignore-inhibit",
        "org.freedesktop.login1.reboot",
        "org.freedesktop.login1.reboot-multiple-sessions",
        "org.freedesktop.login1.reboot-ignore-inhibit"
    ];
    if (power.indexOf(action.id) >= 0) {
        return polkit.Result.YES;
    }
    if (action.id === "org.freedesktop.systemd1.manage-units" && action.lookup("unit") === "pos-ui-off.service") {
        return polkit.Result.YES;
    }
});
EOF

    # "Turn the screen off": a root unit that conflicts with the kiosk, so starting it stops cage and the
    # browser (the server keeps running). screen-off.py switches the display off and waits for the wake
    # shortcut on /dev/input -- no compositor is running to deliver key events. Never enabled: a reboot
    # brings the till back. ExecStopPost makes sure the screen is on again however the unit ends.
    put_file /etc/systemd/system/pos-ui-off.service <<EOF
[Unit]
Description=POS: screen off, kiosk stopped (${KIOSK_WAKE_COMBO} brings the till back, the server keeps running)
Conflicts=pos-kiosk.service
After=pos-kiosk.service

[Service]
Type=simple
Environment=WAKE_COMBO=${KIOSK_WAKE_COMBO}
ExecStart=${NODE_DIR}/screen-off.py
ExecStopPost=${NODE_DIR}/screen-off.py --restore
Restart=on-failure
RestartSec=2
EOF
    record "unit pos-ui-off.service"
    info "System tab installed (battery warning below ${BATTERY_WARN_PERCENT}%, critical below ${BATTERY_CRITICAL_PERCENT}%)"
}

setup_kiosk() {
    wants_kiosk || return 0
    local browser base
    browser="$(detect_browser)" || {
        warn "No usable (non-snap) Chromium/Chrome found — kiosk NOT configured."
        warn "Install a deb build (e.g. Google Chrome's .deb or distro chromium), or set BROWSER_CMD, then re-run."
        return 0
    }
    info "Kiosk browser: ${browser}"
    base="${SERVER_URL:-http://localhost:${PORT}}"

    if ! id "${KIOSK_USER}" >/dev/null 2>&1; then
        useradd --create-home --shell /usr/sbin/nologin --comment "POS kiosk" "${KIOSK_USER}"
    fi
    local g
    for g in video render input audio; do getent group "${g}" >/dev/null && usermod -aG "${g}" "${KIOSK_USER}"; done

    local status_tab="no"
    if [ "${KIOSK_STATUS_TAB}" = "yes" ] && install_status_helper; then status_tab="yes"; fi

    put_file "${NODE_ENV}" <<EOF
BROWSER="${browser}"
BASE_URL="${base}"
APPS="${KIOSK_APPS}"
STATUS_TAB="${status_tab}"
WAKE_COMBO="${KIOSK_WAKE_COMBO}"
BATTERY_WARN_PERCENT="${BATTERY_WARN_PERCENT}"
BATTERY_CRITICAL_PERCENT="${BATTERY_CRITICAL_PERCENT}"
BATTERY_REWARN_MINUTES="${BATTERY_REWARN_MINUTES}"
EOF

    put_file "${NODE_DIR}/kiosk-browser.sh" <<'EOF'
#!/bin/bash
# Runs inside cage as the kiosk user. Generated by provision-node.sh — edit /etc/pos-node/kiosk.env instead.
. /etc/pos-node/kiosk.env

# Wait (up to ~4 min) for the server so the browser does not open on an error page.
for _ in $(seq 1 120); do
    curl -fsS -o /dev/null --max-time 3 "${BASE_URL}/health" && break
    sleep 2
done

urls=()
for app in ${APPS}; do urls+=("${BASE_URL}/${app}/"); done

# Optional "System" tab: a local status/power helper (see status-helper.py) on 127.0.0.1:8099. Chromium's
# DevTools endpoint stays on loopback; the helper only uses it to bring that tab forward on low battery.
debug=()
if [ "${STATUS_TAB:-no}" = yes ]; then
    export BATTERY_WARN_PERCENT BATTERY_CRITICAL_PERCENT BATTERY_REWARN_MINUTES WAKE_COMBO
    SERVER_HEALTH_URL="${BASE_URL}/health" python3 /opt/pos-node/status-helper.py &
    urls+=("http://127.0.0.1:8099/")
    debug=(--remote-debugging-port=9222)
fi

# One app: real kiosk. Several: full-screen window with one tab each (Ctrl+Tab switches).
mode=(--kiosk)
[ "${#urls[@]}" -gt 1 ] && mode=(--start-fullscreen)

# Start the browser with no inheritable/ambient capabilities. Processes of the kiosk session carry
# cap_wake_alarm as an ambient capability (the user manager has it permitted but not ambient; where it
# is raised for cage's children was not pinned down). xdg-desktop-portal, started by the user manager,
# has none, and the kernel only lets it read /proc/<browser>/root when the browser's permitted set fits
# inside its own -- otherwise every file dialog (upload buttons, Ctrl+O) is refused with
# "Portal operation not allowed: Unable to open /proc/PID/root" and never appears.
drop=()
command -v setpriv >/dev/null && drop=(setpriv --ambient-caps=-all --inh-caps=-all --)

exec "${drop[@]}" "${BROWSER}" "${mode[@]}" \
    --ozone-platform=wayland \
    --user-data-dir="${HOME}/.config/pos-kiosk" \
    --no-first-run --no-default-browser-check --noerrdialogs --disable-infobars \
    --disable-session-crashed-bubble --disable-translate --password-store=basic \
    "${debug[@]}" "${urls[@]}"
EOF
    chmod 755 "${NODE_DIR}/kiosk-browser.sh"

    # A dedicated, minimal PAM service for the kiosk session instead of "login" (whose stack also
    # logs a pam_lastlog.so "faulty module" warning on every start).
    put_file /etc/pam.d/cage <<'EOF'
auth     required pam_permit.so
account  required pam_permit.so
password required pam_deny.so
session  required pam_loginuid.so
session  required pam_limits.so
session  required pam_env.so
session  required pam_systemd.so
EOF

    # No GNOME Shell here, so the GNOME portal backend (the desktop's default) cannot show a dialog;
    # the GTK backend can, on any Wayland compositor. Only meaningful when it is installed.
    if dpkg -s xdg-desktop-portal-gtk >/dev/null 2>&1; then
        put_file /etc/xdg-desktop-portal/portals.conf <<'EOF'
[preferred]
default=gtk
EOF
    else
        warn "xdg-desktop-portal-gtk is not installed — file pickers in the kiosk will not open (apt install xdg-desktop-portal-gtk)"
    fi

    # cage is a Wayland compositor with its own keymap and ignores the system's console/GNOME setting:
    # without XKB_DEFAULT_* it silently uses US. Default to what the desktop install already chose
    # (/etc/default/keyboard), so the kiosk types like the machine did before; KEYBOARD_* overrides.
    local xkb_env="" kb
    kb_from_system() { sed -n "s/^XKB$1=\"\(.*\)\"/\1/p" /etc/default/keyboard 2>/dev/null | head -1; }
    for kb in LAYOUT VARIANT MODEL OPTIONS; do
        local override_var="KEYBOARD_${kb}" value
        value="${!override_var}"
        [ -n "${value}" ] || value="$(kb_from_system "${kb}")"
        [ -z "${value}" ] || xkb_env+="Environment=XKB_DEFAULT_${kb}=${value}"$'\n'
    done
    [ -n "${xkb_env}" ] && info "Kiosk keyboard: $(printf '%s' "${xkb_env}" | sed 's/Environment=XKB_DEFAULT_//' | paste -sd' ')"

    local after="" wants="" ui_off_conflict=""
    [ "${status_tab}" = yes ] && ui_off_conflict="Conflicts=pos-ui-off.service"
    if wants_server; then after=" pos-server.service"; wants="Wants=pos-server.service"; fi
    put_file /etc/systemd/system/pos-kiosk.service <<EOF
[Unit]
Description=POS kiosk (cage + browser on tty1)
After=systemd-user-sessions.service systemd-logind.service getty@tty1.service${after}
Wants=dbus.socket systemd-logind.service
${wants}
Conflicts=getty@tty1.service
${ui_off_conflict}

[Service]
Type=simple
User=${KIOSK_USER}
PAMName=cage
TTYPath=/dev/tty1
TTYReset=yes
TTYVHangup=yes
TTYVTDisallocate=yes
StandardInput=tty-fail
StandardOutput=journal
StandardError=journal
UtmpIdentifier=tty1
UtmpMode=user
${xkb_env}ExecStart=/usr/bin/cage -s -- ${NODE_DIR}/kiosk-browser.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
    record "unit pos-kiosk.service"

    # Lid closed: stop the kiosk (cage and the browser), the server keeps running -- logind is told to ignore
    # the lid (LID_IGNORE), so the machine stays awake. Lid open: start the kiosk again, but only if this
    # watcher stopped it, so a UI closed on purpose (System tab) is not brought back by opening the lid.
    # Polls the ACPI lid state every 2 s: no extra package, and it survives a missed event.
    if [ "${LID_STOPS_KIOSK}" = "yes" ]; then
        put_file "${NODE_DIR}/lid-watcher.sh" <<'EOF'
#!/bin/bash
# Generated by provision-node.sh. Stops the kiosk while the lid is closed; the server keeps running.
state_file="${LID_STATE_FILE:-$(ls /proc/acpi/button/lid/*/state 2>/dev/null | head -1)}"
if [ -z "${state_file}" ]; then
    echo "lid-watcher: no ACPI lid switch on this machine, nothing to do"
    exit 0
fi
stopped_by_us=0
last=""
while sleep 2; do
    now="$(awk '{print $2}' "${state_file}" 2>/dev/null)"
    [ -n "${now}" ] && [ "${now}" != "${last}" ] || continue
    last="${now}"
    case "${now}" in
        closed)
            if systemctl is-active --quiet pos-kiosk.service; then
                echo "lid-watcher: lid closed — stopping the kiosk (server keeps running)"
                systemctl stop pos-kiosk.service && stopped_by_us=1
            fi
            ;;
        open)
            if [ "${stopped_by_us}" = 1 ]; then
                echo "lid-watcher: lid open — starting the kiosk"
                systemctl start pos-kiosk.service
                stopped_by_us=0
            fi
            ;;
    esac
done
EOF
        chmod 755 "${NODE_DIR}/lid-watcher.sh"
        put_file /etc/systemd/system/pos-lid-watcher.service <<EOF
[Unit]
Description=POS: stop the kiosk screen while the lid is closed (the server keeps running)
After=pos-kiosk.service

[Service]
Type=simple
ExecStart=${NODE_DIR}/lid-watcher.sh
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
        record "unit pos-lid-watcher.service"
    fi

    # A wasm app in a browser that never closes grows; restart it at closing time.
    if [ -n "${KIOSK_RESTART_AT}" ]; then
        put_file /etc/systemd/system/pos-kiosk-restart.service <<'EOF'
[Unit]
Description=Restart the POS kiosk browser (releases leaked memory)

[Service]
Type=oneshot
ExecStart=/bin/systemctl try-restart pos-kiosk.service
EOF
        put_file /etc/systemd/system/pos-kiosk-restart.timer <<EOF
[Unit]
Description=Nightly POS kiosk browser restart

[Timer]
OnCalendar=*-*-* ${KIOSK_RESTART_AT}:00
Persistent=false

[Install]
WantedBy=timers.target
EOF
        record "unit pos-kiosk-restart.timer"
    fi
    systemctl daemon-reload
    systemctl enable pos-kiosk.service
    [ -z "${KIOSK_RESTART_AT}" ] || systemctl enable pos-kiosk-restart.timer
    if [ "${LID_STOPS_KIOSK}" = "yes" ]; then systemctl enable --now pos-lid-watcher.service; fi
    info "Kiosk service enabled (starts on next boot; apps: ${KIOSK_APPS}${KIOSK_RESTART_AT:+; browser restarts nightly at ${KIOSK_RESTART_AT}})"
}

# Keep a copy of this script on the machine so --undo is available there.
install_self() {
    mkdir -p "${NODE_DIR}"
    if [ "$(readlink -f "$0")" != "${NODE_DIR}/provision-node.sh" ]; then
        install -m 755 "$0" "${NODE_DIR}/provision-node.sh"
        record "file ${NODE_DIR}/provision-node.sh"
    fi
}

summary() {
    echo
    info "Done. Reboot to switch over:  sudo reboot"
    wants_server && info "Server:  systemctl status pos-server   |   http://<this-ip>:${PORT}/"
    wants_kiosk  && info "Kiosk:   systemctl status pos-kiosk    |   logs: journalctl -u pos-kiosk"
    info "Revert this script's changes:  sudo ${NODE_DIR}/provision-node.sh --undo"
    if wants_kiosk && wants_server && [[ " ${KIOSK_APPS} " == *" pos "* ]]; then
        info "Note: /pos/ answers 404 until a browser-station role is enabled in the admin app (Settings -> Browser-Stationen)."
    fi
}

# --- --check -------------------------------------------------------------------------------------

# Read-only: validates the config (validate() already ran) and reports what this machine looks like
# against what an apply would do. Exit status 1 when something would be skipped or is wrong.
preflight() {
    local problems=0
    ok()   { echo "  [ok]   $*"; }
    bad()  { echo "  [FIX]  $*"; problems=$((problems + 1)); }
    note() { echo "  [info] $*"; }

    echo "Preflight for ROLE=${ROLE}"
    [ ! -f "${STATE}" ] || note "previously provisioned ($(wc -l < "${STATE}") recorded changes)"
    is_ubuntu_desktop && ok "standard Ubuntu desktop install" || bad "not a standard Ubuntu desktop install (apply needs --force)"
    if wants_server && [ "${NO_SERVER}" = "0" ]; then ok "server package found in ${DEPLOY_DIR}"; fi
    if wants_kiosk; then
        if b="$(detect_browser)"; then ok "kiosk browser: ${b}"; else bad "no usable non-snap Chromium/Chrome (set BROWSER_CMD)"; fi
        dpkg -s cage >/dev/null 2>&1 && ok "cage installed" || note "cage will be installed"
    fi
    if wants_kiosk && [ "${KIOSK_STATUS_TAB}" = "yes" ]; then
        if command -v python3 >/dev/null && [ -f "${SCRIPT_DIR}/kiosk/status-helper.py" ] && [ -f "${SCRIPT_DIR}/kiosk/status.html" ] && [ -f "${SCRIPT_DIR}/kiosk/screen-off.py" ]; then
            ok "System tab: helper files and python3 present"
        else
            bad "System tab needs python3 and kiosk/status-helper.py + kiosk/status.html + kiosk/screen-off.py next to this script"
        fi
    fi
    if wants_server && [ -n "${TSE_LABEL}" ]; then
        if dev="$(blkid -L "${TSE_LABEL}" 2>/dev/null)"; then ok "TSE '${TSE_LABEL}' present at ${dev}"; else note "TSE '${TSE_LABEL}' not plugged in (mount is set up anyway, nofail)"; fi
    fi
    if wants_server && [ -n "${BACKUP_LABEL}" ]; then
        blkid -L "${BACKUP_LABEL}" >/dev/null 2>&1 && ok "backup volume '${BACKUP_LABEL}' present" || note "backup volume '${BACKUP_LABEL}' not plugged in"
    fi
    if module_loaded wl; then
        note "Broadcom 'wl' module loaded — kernel hold: ${HOLD_KERNEL}"
        if grep -q installed <<< "$(dkms status 2>/dev/null || true)"; then ok "dkms: wl built"; else bad "dkms status shows no built module"; fi
    fi
    if [ "${SSH_KEY_ONLY}" = "yes" ]; then
        if has_ssh_key; then ok "an SSH key is installed"; else bad "SSH_KEY_ONLY=yes but no authorized_keys found"; fi
    fi
    if [ "${CUPS}" = "keep" ] && ! systemctl is-active --quiet cups.service; then note "cups is not running (it will be enabled)"; fi
    note "boot target now: $(systemctl get-default)$([ "${BOOT_TO_CONSOLE}" = yes ] && echo ' -> multi-user.target')"
    command -v ufw >/dev/null && note "ufw: $(ufw status 2>/dev/null | head -1 || echo 'needs root to read')" || note "ufw will be installed"
    echo
    if [ "${problems}" -eq 0 ]; then info "Check passed — nothing was changed."; else warn "${problems} item(s) need attention — nothing was changed."; return 1; fi
}

# --- --undo --------------------------------------------------------------------------------------

undo() {
    [ -f "${STATE}" ] || error "Nothing recorded in ${STATE} — nothing to undo"
    local kind rest
    while read -r kind rest; do
        case "${kind}" in
            file)    rm -f -- "${rest}" ;;
            mask)    systemctl unmask "${rest}" >/dev/null 2>&1 || true ;;
            unit)    systemctl disable --now "${rest}" >/dev/null 2>&1 || true ;;
            fstab)   sed -i "/^LABEL=${rest}[[:space:]].*# pos-node\$/d" /etc/fstab ;;
            default) systemctl set-default "${rest}" >/dev/null ;;
            gdm)     systemctl enable "${rest}" >/dev/null 2>&1 || true ;;
            ufw)     [ "${rest}" != "inactive" ] || ufw --force disable >/dev/null ;;
            upower)  sed -i "s/^CriticalPowerAction=.*/CriticalPowerAction=${rest}/" /etc/UPower/UPower.conf ;;
            *)       warn "Unknown state line: ${kind} ${rest}" ;;
        esac
    done < "${STATE}"
    systemctl daemon-reload
    systemctl try-reload-or-restart ssh.service >/dev/null 2>&1 || true
    mv "${STATE}" "${STATE}.undone.$(date +%Y%m%d%H%M%S)"
    info "Reverted. Not touched: installed packages, the 'pos' and '${KIOSK_USER}' users, the server install"
    info "(remove that with install-service.sh --uninstall), and any static IP. Reboot to complete."
}

# --- Entry point --------------------------------------------------------------------------------

MODE="apply"
CONF_ARG=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help)       usage ;;
        --check)         MODE="check" ;;
        --undo)          MODE="undo" ;;
        --desktop-check) MODE="desktop-check" ;;
        --no-server)     NO_SERVER=1 ;;
        --force)         FORCE=1 ;;
        --port)          [ $# -ge 2 ] || error "--port needs a value"; PORT_OVERRIDE="$2"; shift ;;
        -*)              error "Unknown option: $1 (try --help)" ;;
        *)               CONF_ARG="$1" ;;
    esac
    shift
done

set_defaults

if [ "${MODE}" = "desktop-check" ]; then
    require_desktop
    exit 0
fi

if [ "${MODE}" = "check" ]; then
    load_config "${CONF_ARG}"
    validate
    preflight
    exit $?
fi

[ "$(id -u)" -eq 0 ] || error "Run as root (sudo ./provision-node.sh)"

if [ "${MODE}" = "undo" ]; then
    undo
    exit 0
fi

require_desktop
load_config "${CONF_ARG}"
validate

protect_drivers
install_packages
if wants_server && [ "${NO_SERVER}" = "0" ]; then install_server; fi
setup_printing
setup_mounts
slim_services
setup_power
hold_kernel
setup_firewall
setup_ssh
setup_journald
setup_laptop
setup_static_ip
setup_kiosk
setup_boot
install_self
summary
