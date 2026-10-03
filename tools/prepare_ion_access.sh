#!/bin/sh
set -eu

PACKAGE="${1:-com.stasao.gcam.dev}"
UID_VALUE="$(adb shell cmd package list packages -U "$PACKAGE" | tr -d '\r' | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p')"

if [ -z "$UID_VALUE" ]; then
    echo "Package $PACKAGE is not installed" >&2
    exit 1
fi

# /dev/ion is recreated on every boot. Restrict access to the app UID group
# instead of making the device world-writable.
adb shell su 0 chown system:"$UID_VALUE" /dev/ion
adb shell su 0 chmod 660 /dev/ion
adb shell ls -lZ /dev/ion
echo "ION access prepared for $PACKAGE (uid/gid $UID_VALUE)"
