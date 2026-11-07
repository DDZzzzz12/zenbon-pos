#!/bin/bash
# Shared USB-volume helpers for provision-node.sh and install-service.sh (sourced, never run).
#
# The callers provide: info, warn, error, record <kind...> and put_file <path> (stdin -> file).
# Paths can be redirected for tests: USB_MOUNT_ROOT, FSTAB_FILE, SYSTEMD_UNIT_DIR.

USB_MOUNT_ROOT="${USB_MOUNT_ROOT:-/media/pos}"
FSTAB_FILE="${FSTAB_FILE:-/etc/fstab}"
SYSTEMD_UNIT_DIR="${SYSTEMD_UNIT_DIR:-/etc/systemd/system}"

# Mount a labelled USB volume at /media/pos/<label>, owned by the server's service account.
# The desktop's automount would put it under /media/<login-user>/ where the server cannot write,
# and with no desktop session nothing would mount it at all. /media/pos/<label> is both on the
# service unit's writable-path list and where the server's TSE locator looks (/media/<user>/<device>).
# `nofail` keeps the machine booting with the stick absent; x-systemd.automount mounts it on first
# access, so plugging it in later (or swapping it) needs no reboot. Applied from the next boot.
mount_labelled_volume() {
    local label="$1" fstype="$2" opts present_fs dev
    dev="$(blkid -L "${label}" 2>/dev/null || true)"   # -L alone yields the device path
    present_fs=""
    [ -z "${dev}" ] || present_fs="$(blkid -s TYPE -o value "${dev}" 2>/dev/null || true)"
    [ -z "${present_fs}" ] || fstype="${present_fs}"
    case "${fstype}" in
        vfat|exfat|ntfs) opts="uid=pos,gid=pos,umask=0027" ;;
        *)               opts="" ;;   # ext4 etc. take no uid= option; ownership is fixed by hand
    esac
    mkdir -p "${USB_MOUNT_ROOT}/${label}"
    sed -i "/^LABEL=${label}[[:space:]].*# pos-node\$/d" "${FSTAB_FILE}"
    echo "LABEL=${label} ${USB_MOUNT_ROOT}/${label} auto nofail,x-systemd.automount,x-systemd.device-bound,x-systemd.device-timeout=1s${opts:+,${opts}} 0 0 # pos-node" >> "${FSTAB_FILE}"
    record "fstab ${label}"

    # The automount only MOUNTS. Without the next two lines a pulled stick leaves a dead mount behind (the
    # kernel logs "FAT-fs: Directory bread failed") and a process reading it hangs -- the server did exactly
    # that while validating the TSE. device-bound ties the mount to the device so systemd unmounts it when
    # the stick disappears; LazyUnmount detaches it even while a process still has a file open. The
    # automount unit stays, so the next stick with this label mounts on first access.
    local unit
    unit="$(systemd-escape -p --suffix=mount "${USB_MOUNT_ROOT}/${label}")"
    put_file "${SYSTEMD_UNIT_DIR}/${unit}.d/10-pos-node.conf" <<EOF
[Mount]
LazyUnmount=yes
EOF
    if [ -z "${opts}" ] && [ -n "${present_fs}" ]; then
        warn "Volume '${label}' is ${present_fs}: after its first mount run  sudo chown pos:pos ${USB_MOUNT_ROOT}/${label}"
    fi
    info "fstab: LABEL=${label} -> ${USB_MOUNT_ROOT}/${label} (${fstype}${present_fs:+, detected})"
}

# start_labelled_automount LABEL: starts the automount unit now instead of at the next boot.
start_labelled_automount() {
    local unit
    unit="$(systemd-escape -p --suffix=automount "${USB_MOUNT_ROOT}/$1")"
    systemctl start "${unit}" 2>/dev/null || warn "Could not start ${unit}; the volume mounts after the next boot."
}

# volume_is_mounted PATH: a real filesystem is mounted at PATH (touching it first lets an automount fire).
volume_is_mounted() {
    local fs
    ls "$1" >/dev/null 2>&1 || true
    fs="$(findmnt -no FSTYPE --mountpoint "$1" 2>/dev/null | tail -1 || true)"
    [ -n "${fs}" ] && [ "${fs}" != "autofs" ]
}

# The whole disk(s) the system boots from, one name per line ("sda"), empty when it cannot be told.
# "/" may sit on a partition, an LVM/RAID volume or a btrfs subvolume ("/dev/sda2[/@]"): walk down to the
# physical disks so none of the boot disk's partitions is ever offered.
usb_boot_disks() {
    local src disks
    src="$(findmnt -no SOURCE / 2>/dev/null | head -1 || true)"
    src="${src%%\[*}"
    [ -n "${src}" ] || return 0
    disks="$(lsblk -nsr -o NAME,TYPE "${src}" 2>/dev/null | awk '$2 == "disk" { print $1 }' || true)"
    if [ -n "${disks}" ]; then echo "${disks}"; else basename "${src}"; fi
}

# list_candidate_devices: removable or USB block devices, one per line, tab separated:
#   /dev/NAME  SIZE  LABEL  FSTYPE  MODEL
# Only partitions, and disks without partitions. Never the disk that holds "/".
list_candidate_devices() {
    local boot rows line name size label fstype model rm tran type pkname
    boot="$(usb_boot_disks)"
    rows="$(lsblk -P -o NAME,SIZE,LABEL,FSTYPE,MODEL,RM,TRAN,TYPE,PKNAME 2>/dev/null || true)"
    local -A has_children=()
    while IFS= read -r line; do
        [[ "${line}" =~ PKNAME=\"([^\"]*)\" ]] && [ -n "${BASH_REMATCH[1]}" ] && has_children["${BASH_REMATCH[1]}"]=1
    done <<< "${rows}"
    while IFS= read -r line; do
        [ -n "${line}" ] || continue
        name="" size="" label="" fstype="" model="" rm="" tran="" type="" pkname=""
        [[ "${line}" =~ ^NAME=\"([^\"]*)\" ]] && name="${BASH_REMATCH[1]}"
        [[ "${line}" =~ SIZE=\"([^\"]*)\" ]] && size="${BASH_REMATCH[1]}"
        [[ "${line}" =~ LABEL=\"([^\"]*)\" ]] && label="${BASH_REMATCH[1]}"
        [[ "${line}" =~ FSTYPE=\"([^\"]*)\" ]] && fstype="${BASH_REMATCH[1]}"
        [[ "${line}" =~ MODEL=\"([^\"]*)\" ]] && model="${BASH_REMATCH[1]}"
        [[ "${line}" =~ [[:space:]]RM=\"([^\"]*)\" ]] && rm="${BASH_REMATCH[1]}"
        [[ "${line}" =~ TRAN=\"([^\"]*)\" ]] && tran="${BASH_REMATCH[1]}"
        [[ "${line}" =~ [[:space:]]TYPE=\"([^\"]*)\" ]] && type="${BASH_REMATCH[1]}"
        [[ "${line}" =~ PKNAME=\"([^\"]*)\" ]] && pkname="${BASH_REMATCH[1]}"
        case "${type}" in part|disk) ;; *) continue ;; esac
        [ "${rm}" = "1" ] || [ "${tran}" = "usb" ] || [ -n "${pkname}" ] || continue
        grep -qxF -e "${name}" -e "${pkname:-/}" <<< "${boot}" && continue
        # A partition is only a candidate when its disk is removable/USB: look the disk up in the rows.
        if [ "${type}" = "part" ]; then
            grep -E "^NAME=\"${pkname}\" .*(RM=\"1\"|TRAN=\"usb\")" <<< "${rows}" >/dev/null || continue
        fi
        [ "${type}" = "disk" ] && [ -n "${has_children[${name}]:-}" ] && continue
        printf '/dev/%s\t%s\t%s\t%s\t%s\n' "${name}" "${size}" "${label}" "${fstype}" "${model}"
    done <<< "${rows}"
}

# check_backup_device DEVICE: returns 0 when DEVICE carries a filesystem that can be mounted.
# Never formats: with no filesystem it prints the command for the operator and returns 1, touching nothing.
# A FAT32 volume works but cannot hold a file over 4 GB, which a backup can exceed.
check_backup_device() {
    local dev="$1" fstype
    fstype="$(blkid -s TYPE -o value "${dev}" 2>/dev/null || true)"
    if [ -z "${fstype}" ]; then
        warn "${dev} has no filesystem. This script never formats a device. To format it yourself (this ERASES it):"
        warn "  sudo mkfs.exfat -L ZEN_BACKUP ${dev}      # or: sudo mkfs.ext4 -L ZEN_BACKUP ${dev}"
        warn "then run this command again."
        return 1
    fi
    if [ "${fstype}" = "vfat" ]; then
        warn "${dev} is FAT32: it cannot hold a file larger than 4 GB, and a backup can grow past that. exFAT or ext4 is better."
    fi
    return 0
}
