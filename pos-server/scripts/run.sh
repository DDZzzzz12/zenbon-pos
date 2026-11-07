#!/bin/bash
# POS Server startup script
# Run from the deployment directory containing pos-server.jar and native/ folder
#
# The native/ folder contains JNI libraries for TSE hardware access (LOCAL mode).
# The TSE runs in this server process; there is no separate connector any more. Without these
# libraries the server runs normally but cannot sign, so it cannot be used for fiscal operation.
#
# Database mode (default: embedded — no external PostgreSQL required):
#   DATABASE_MODE=embedded  ./run.sh   # Bundled PostgreSQL (default)
#   DATABASE_MODE=external  ./run.sh   # Requires external PostgreSQL server

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Detect architecture for native library path
ARCH="$(uname -m)"
case "$ARCH" in
    x86_64|amd64)
        NATIVE_DIR="linux64"
        ;;
    i386|i486|i586|i686)
        NATIVE_DIR="linux32"
        ;;
    aarch64|arm64|armv7l|armv6l)
        # Named explicitly rather than falling through to linux32. The TSE SDK ships x86 JNI
        # libraries only (linux32/linux64/windows32/windows64), so there is nothing to point at
        # on ARM -- and pointing at the 32-bit x86 directory produced a "TSE LOCAL mode will not
        # work" warning that blamed the TSE instead of the architecture.
        NATIVE_DIR=""
        echo "Warning: unsupported architecture '$ARCH'."
        echo "  The TSE driver ships x86 libraries only, and it runs in the server process, so"
        echo "  this host cannot sign receipts. Embedded PostgreSQL is x86_64-only as well."
        echo "  Install the server on an x86_64 host for a fiscal deployment."
        ;;
    *)
        NATIVE_DIR=""
        echo "Warning: unrecognised architecture '$ARCH' — TSE support is x86 only."
        ;;
esac

if [ -n "$NATIVE_DIR" ]; then NATIVE_PATH="$SCRIPT_DIR/native/$NATIVE_DIR"; else NATIVE_PATH=""; fi

# Verify native libraries exist. Skipped when the architecture has none to look for -- the
# warning above already said why, and repeating it with an empty path reads like a second fault.
if [ -n "$NATIVE_PATH" ] && [ ! -d "$NATIVE_PATH" ]; then
    echo "Warning: Native library directory not found: $NATIVE_PATH"
    echo "The TSE cannot be used. Everything else runs normally."
fi

# Verify the Swissbit SDK jar exists. It is not bundled into pos-server.jar —
# pos-server.jar references it through a Class-Path manifest entry, so it must sit
# at native/WormAPI.jar. Open-source builds ship without it; the operator supplies
# their own copy obtained from Swissbit.
if [ ! -f "$SCRIPT_DIR/native/WormAPI.jar" ]; then
    echo "Warning: TSE SDK not found: $SCRIPT_DIR/native/WormAPI.jar"
    echo "The TSE cannot be used. Everything else runs normally."
    echo "Place the Swissbit WormAPI.jar there to enable local TSE hardware access."
fi

if [ -n "$NATIVE_PATH" ]; then
    export LD_LIBRARY_PATH="$NATIVE_PATH:$LD_LIBRARY_PATH"
fi

# Database mode: "embedded" (default, no external PG) or "external" (requires PG server)
DATABASE_MODE="${DATABASE_MODE:-embedded}"
export DATABASE_MODE

# Use bundled JRE if available, otherwise fall back to system Java
if [ -d "$SCRIPT_DIR/jre" ]; then
    JAVA_CMD="$SCRIPT_DIR/jre/bin/java"
else
    JAVA_CMD="java"
fi

# JVM options. The default heap is 1 GB: a DSFinV-K export of one busy day (about 3,700 bills)
# peaked at ~800 MB of heap and died with OutOfMemoryError at the former 512 MB cap. Override by
# setting JAVA_OPTS, which REPLACES the default, so include -Xmx when you set it, e.g.
#   JAVA_OPTS="-Xms256m -Xmx1g -XX:+UseSerialGC" ./run.sh
# Under systemd, put the line in the service's EnvironmentFile.
JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"

# JAVA_OPTS is deliberately unquoted so it splits into separate JVM arguments.
if [ -n "$NATIVE_PATH" ]; then
    "$JAVA_CMD" $JAVA_OPTS \
         -Djava.library.path="$NATIVE_PATH" \
         -jar "$SCRIPT_DIR/pos-server.jar" \
         "$@"
else
    "$JAVA_CMD" $JAVA_OPTS \
         -jar "$SCRIPT_DIR/pos-server.jar" \
         "$@"
fi
