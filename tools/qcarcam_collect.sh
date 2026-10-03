#!/bin/zsh
set -u

SCRIPT_DIR=${0:A:h}
PROJECT_DIR=${SCRIPT_DIR:h}
SDK_ADB="$HOME/Library/Android/sdk/platform-tools/adb"
ADB_BIN=${ADB:-$SDK_ADB}

if [[ ! -x "$ADB_BIN" ]]; then
  ADB_BIN=$(command -v adb 2>/dev/null || true)
fi
if [[ -z "$ADB_BIN" || ! -x "$ADB_BIN" ]]; then
  print -u2 "adb not found; set ADB=/path/to/adb"
  exit 1
fi

DEVICE_LINES=$($ADB_BIN devices | awk 'NR > 1 && $2 == "device" { print $1 }')
DEVICE_COUNT=$(print -r -- "$DEVICE_LINES" | awk 'NF { n++ } END { print n+0 }')
if (( DEVICE_COUNT == 0 )); then
  print -u2 "No authorized Android device. Enable USB debugging and accept the RSA prompt."
  $ADB_BIN devices -l
  exit 2
fi
if (( DEVICE_COUNT > 1 )) && [[ -z ${ANDROID_SERIAL:-} ]]; then
  print -u2 "More than one device; set ANDROID_SERIAL first:"
  print -r -- "$DEVICE_LINES" >&2
  exit 2
fi

STAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR="$PROJECT_DIR/qcarcam-capture-$STAMP"
mkdir -p "$OUT_DIR"

run_shell() {
  local name=$1
  shift
  print "Collecting $name"
  $ADB_BIN shell "$@" >"$OUT_DIR/$name.txt" 2>&1 || true
}

$ADB_BIN devices -l >"$OUT_DIR/devices.txt"
run_shell identity id
run_shell props getprop
run_shell selinux getenforce
run_shell services service list
run_shell hidl "lshal 2>&1 | grep -Ei 'qcar|camera|avm|evs|ais'"
run_shell processes "ps -AZ 2>&1 | grep -Ei 'qcar|camera|avm|parking|ais'"
run_shell binaries "ls -lZ /system/bin/qcarcam_hidl_test /vendor/bin/qcarcam_test /vendor/bin/qcarcam_edrm_rvc 2>&1"
run_shell vendor_files "find /vendor/etc /system/etc -maxdepth 4 -type f 2>/dev/null | grep -Ei 'qcarcam|qcar_cam|(^|/)ais|avm'"
run_shell vendor_libs "find /vendor/lib64 /vendor/lib -maxdepth 2 -type f 2>/dev/null | grep -Ei 'qcarcam|ais_client|qcarcam-interface'"
run_shell hidl_test_help "timeout 5 /system/bin/qcarcam_hidl_test -h"
run_shell qcarcam_test_help "timeout 5 /vendor/bin/qcarcam_test -h"
run_shell rvc_help "timeout 5 /vendor/bin/qcarcam_edrm_rvc -h"

for remote in \
  /system/bin/qcarcam_hidl_test \
  /vendor/bin/qcarcam_test \
  /vendor/bin/qcarcam_edrm_rvc \
  /vendor/lib64/qcarcam-interface.so \
  /vendor/lib64/libais_client.so; do
  $ADB_BIN pull "$remote" "$OUT_DIR/" >/dev/null 2>&1 || true
done

print "Done: $OUT_DIR"
print "No camera stream was started; only discovery and '-h' calls were made."
