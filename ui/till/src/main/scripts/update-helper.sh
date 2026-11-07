#!/bin/sh
# Usage: update-helper.sh <client-pid> <staging-dir> <install-dir>
set -e
CLIENT_PID="$1"
STAGING="$2"
INSTALL="$3"

# Wait up to 30s for the client to exit
i=0
while kill -0 "$CLIENT_PID" 2>/dev/null; do
    i=$((i+1))
    [ "$i" -gt 60 ] && break
    sleep 0.5
done

TS=$(date +%s)
BACKUP="$INSTALL/.backup-$TS"
mkdir -p "$BACKUP"

# Move existing dirs to backup
for d in bin lib share; do
    [ -d "$INSTALL/$d" ] && mv "$INSTALL/$d" "$BACKUP/$d"
done

# Install staging contents
if cp -r "$STAGING"/* "$INSTALL/" && rm -rf "$STAGING"; then
    rm -rf "$BACKUP"
    nohup setsid "$INSTALL/bin/zenbon-pos" >/dev/null 2>&1 &
    exit 0
else
    # Rollback
    for d in bin lib share; do
        [ -d "$BACKUP/$d" ] && { rm -rf "$INSTALL/$d"; mv "$BACKUP/$d" "$INSTALL/$d"; }
    done
    rm -rf "$BACKUP"
    nohup setsid "$INSTALL/bin/zenbon-pos" --update-failed >/dev/null 2>&1 &
    exit 1
fi
