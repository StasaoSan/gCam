#!/bin/sh
set -eu

PACKAGE="${1:-com.stasao.gcam.dev}"
UID_VALUE="$(adb shell cmd package list packages -U "$PACKAGE" | tr -d '\r' | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p')"

if [ -z "$UID_VALUE" ]; then
    echo "Package $PACKAGE is not installed" >&2
    exit 1
fi

# /dev/ion is recreated on every boot. Keep system:system ownership: Codec2 runs
# as mediacodec and loses ION access if the group is changed to the app UID.
# SELinux remains the actual access boundary on production builds.
adb shell su 0 chown system:system /dev/ion
adb shell su 0 chmod 666 /dev/ion
adb shell ls -lZ /dev/ion
echo "ION access prepared for $PACKAGE (uid $UID_VALUE); Codec2 system access preserved"
