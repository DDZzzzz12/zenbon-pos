#!/bin/bash
# Tests for scripts/lib/usb-mount.sh with stubbed lsblk/blkid/findmnt; nothing touches the system.
#   bash pos-server/scripts/test/usb-mount-test.sh
HERE="$(cd "$(dirname "$0")" && pwd)"
PASS=0
FAIL=0
ok()    { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad()   { FAIL=$((FAIL + 1)); echo "  FAIL $1"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
mkdir -p "${TMP}/bin"
cat > "${TMP}/bin/findmnt" <<'STUB'
#!/bin/bash
echo "${STUB_ROOT_SRC:-/dev/sda2}"
STUB
cat > "${TMP}/bin/lsblk" <<'STUB'
#!/bin/bash
case "$*" in
    *-nsr*/dev/mapper/vg-root*) printf 'vg-root lvm\nsda3 part\nsda disk\n' ;;
    *-nsr*/dev/sda2*) printf 'sda2 part\nsda disk\n' ;;
    *-P*)
        echo 'NAME="sda" SIZE="238G" LABEL="" FSTYPE="" MODEL="SSD" RM="0" TRAN="sata" TYPE="disk" PKNAME=""'
        echo 'NAME="sda1" SIZE="512M" LABEL="" FSTYPE="vfat" MODEL="" RM="0" TRAN="" TYPE="part" PKNAME="sda"'
        echo 'NAME="sda2" SIZE="237G" LABEL="" FSTYPE="ext4" MODEL="" RM="0" TRAN="" TYPE="part" PKNAME="sda"'
        echo 'NAME="sdb" SIZE="58G" LABEL="" FSTYPE="" MODEL="Cruzer Blade" RM="1" TRAN="usb" TYPE="disk" PKNAME=""'
        echo 'NAME="sdb1" SIZE="58G" LABEL="MY STICK" FSTYPE="exfat" MODEL="" RM="1" TRAN="" TYPE="part" PKNAME="sdb"'
        echo 'NAME="sdc" SIZE="16G" LABEL="" FSTYPE="" MODEL="Empty Stick" RM="1" TRAN="usb" TYPE="disk" PKNAME=""'
        echo 'NAME="sdd" SIZE="32G" LABEL="" FSTYPE="" MODEL="Plain Stick" RM="1" TRAN="usb" TYPE="disk" PKNAME=""'
        echo 'NAME="sdd1" SIZE="32G" LABEL="" FSTYPE="ext4" MODEL="" RM="1" TRAN="" TYPE="part" PKNAME="sdd"'
        ;;
esac
STUB
cat > "${TMP}/bin/blkid" <<'STUB'
#!/bin/bash
case "$*" in
    "-L ZEN_BACKUP") echo /dev/sdb1 ;;
    "-s TYPE -o value /dev/sdb1") echo exfat ;;
    "-s TYPE -o value /dev/sdd1") echo vfat ;;
    "-s TYPE -o value /dev/sdc") : ;;
esac
STUB
chmod +x "${TMP}/bin/"*
export PATH="${TMP}/bin:${PATH}"

source "${HERE}/../install-service.sh"
export USB_MOUNT_ROOT="${TMP}/media/pos" FSTAB_FILE="${TMP}/fstab" SYSTEMD_UNIT_DIR="${TMP}/units"
: > "${FSTAB_FILE}"
record() { :; }
put_file() { mkdir -p "$(dirname "$1")"; cat > "$1"; }
source "${HERE}/../lib/usb-mount.sh"

echo "candidates"
list="$(list_candidate_devices)"
check "the boot disk and its partitions are never listed" '! grep -q "/dev/sda" <<<"${list}"'
check "a partition of a USB disk is listed with its label" 'grep -qP "^/dev/sdb1\t58G\tMY STICK\texfat" <<<"${list}"'
check "an empty USB disk is listed" 'grep -q "^/dev/sdc" <<<"${list}"'
check "a USB disk that has partitions is not listed itself" '! grep -q "^/dev/sdb	" <<<"${list}"'

echo "a volume without a label"
shown="$(backup_device_command 2>&1 < /dev/null)" || true
check "its filesystem is shown in the fs column, not shifted into the label" 'grep -q "/dev/sdd1  32G  label='"''"'  fs='"'ext4'"'" <<<"${shown}"'

echo "boot disk behind LVM or a btrfs subvolume"
for src in /dev/mapper/vg-root "/dev/sda2[/@]"; do
    list="$(STUB_ROOT_SRC="${src}" list_candidate_devices)"
    check "boot disk resolved from '${src}': sda and its partitions are not offered" '! grep -q "/dev/sda" <<<"${list}" && grep -q "^/dev/sdb1" <<<"${list}"'
done

echo "automount"
cat > "${TMP}/bin/systemctl" <<'STUB'
#!/bin/bash
echo "$*" >> "${STUB_SYSTEMCTL_LOG}"
STUB
chmod +x "${TMP}/bin/systemctl"
export STUB_SYSTEMCTL_LOG="${TMP}/systemctl.log"; : > "${STUB_SYSTEMCTL_LOG}"
start_labelled_automount ZEN_BACKUP
check "the automount unit is started now" 'grep -q "^start .*ZEN_BACKUP.automount$" "${STUB_SYSTEMCTL_LOG}"'
cat > "${TMP}/bin/findmnt" <<'STUB'
#!/bin/bash
case "$*" in
    *--mountpoint*) printf '%s' "${STUB_MNT_FS}"; [ -z "${STUB_MNT_FS}" ] || echo ;;
    *) echo "${STUB_ROOT_SRC:-/dev/sda2}" ;;
esac
STUB
chmod +x "${TMP}/bin/findmnt"
mkdir -p "${USB_MOUNT_ROOT}/ZEN_BACKUP"
check "an autofs entry alone is not mounted" '! STUB_MNT_FS=autofs volume_is_mounted "${USB_MOUNT_ROOT}/ZEN_BACKUP"'
check "nothing at the path is not mounted" '! STUB_MNT_FS= volume_is_mounted "${USB_MOUNT_ROOT}/ZEN_BACKUP"'
check "a real filesystem is mounted" 'STUB_MNT_FS=ext4 volume_is_mounted "${USB_MOUNT_ROOT}/ZEN_BACKUP"'

echo "a device without a filesystem"
code=0; out="$(check_backup_device /dev/sdc 2>&1)" || code=$?
check "refused with the mkfs command printed" '[ "${code}" -ne 0 ] && grep -q "mkfs" <<<"${out}"'
check "no write to fstab" '[ ! -s "${FSTAB_FILE}" ]'

echo "FAT32"
code=0; out="$(check_backup_device /dev/sdd1 2>&1)" || code=$?
check "allowed with the 4 GB warning" '[ "${code}" -eq 0 ] && grep -q "4 GB" <<<"${out}"'

echo "fstab line"
mount_labelled_volume ZEN_BACKUP exfat >/dev/null 2>&1
expected="LABEL=ZEN_BACKUP ${USB_MOUNT_ROOT}/ZEN_BACKUP auto nofail,x-systemd.automount,x-systemd.device-bound,x-systemd.device-timeout=1s,uid=pos,gid=pos,umask=0027 0 0 # pos-node"
check "equals what provision-node.sh wrote before the move" '[ "$(cat "${FSTAB_FILE}")" = "${expected}" ]'
check "a second run replaces the line instead of adding one" 'mount_labelled_volume ZEN_BACKUP exfat >/dev/null 2>&1; [ "$(grep -c "^LABEL=ZEN_BACKUP" "${FSTAB_FILE}")" = 1 ]'
check "the LazyUnmount drop-in is written" 'grep -rq "LazyUnmount=yes" "${SYSTEMD_UNIT_DIR}"'

echo "passed: ${PASS}  failed: ${FAIL}"
[ "${FAIL}" -eq 0 ]
