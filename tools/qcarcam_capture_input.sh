#!/bin/zsh
set -eu

if (( $# < 1 || $# > 2 )); then
  print -u2 "usage: $0 INPUT_ID [SECONDS]"
  exit 2
fi

INPUT_ID=$1
SECONDS=${2:-1}
[[ "$INPUT_ID" == <-> ]] || { print -u2 "INPUT_ID must be numeric"; exit 2; }
[[ "$SECONDS" == <-> ]] || { print -u2 "SECONDS must be numeric"; exit 2; }

SCRIPT_DIR=${0:A:h}
PROJECT_DIR=${SCRIPT_DIR:h}
ADB_BIN=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
SERIAL=${ANDROID_SERIAL:-192.168.209.33:5555}
BASE_CONFIG="$SCRIPT_DIR/qcarcam_config_probe.xml"
STAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="$PROJECT_DIR/qcarcam-frame-$INPUT_ID-$STAMP"
REMOTE_CONFIG="/data/local/tmp/qcarcam_config_input$INPUT_ID.xml"

mkdir -p "$OUTPUT_DIR"
"$ADB_BIN" -s "$SERIAL" get-state >/dev/null
"$ADB_BIN" -s "$SERIAL" push "$BASE_CONFIG" /data/local/tmp/qcarcam_config_base.xml >/dev/null
"$ADB_BIN" -s "$SERIAL" shell \
  "sed 's/input_id=\"0\"/input_id=\"$INPUT_ID\"/' /data/local/tmp/qcarcam_config_base.xml > '$REMOTE_CONFIG'"

"$ADB_BIN" -s "$SERIAL" shell \
  "rm -f /data/vendor/camera/frame_*.raw; cd /data/local/tmp; /system/bin/qcarcam_hidl_test -config='$REMOTE_CONFIG' -noDisplay -nonInteractive -nomenu -seconds='$SECONDS' -dumpFrame=1 >qcarcam_capture.out 2>&1; cat qcarcam_capture.out"

REMOTE_FRAME=$("$ADB_BIN" -s "$SERIAL" shell \
  'ls /data/vendor/camera/frame_*.raw 2>/dev/null | head -1' | tr -d '\r')
if [[ -z "$REMOTE_FRAME" ]]; then
  print -u2 "No frame received from input $INPUT_ID"
  exit 1
fi

"$ADB_BIN" -s "$SERIAL" pull "$REMOTE_FRAME" "$OUTPUT_DIR/frame.raw" >/dev/null
"$ADB_BIN" -s "$SERIAL" shell 'rm -f /data/vendor/camera/frame_*.raw'

BUNDLED_PY="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"
if [[ -x "$BUNDLED_PY" ]]; then
  "$BUNDLED_PY" "$SCRIPT_DIR/uyvy_to_png.py" \
    "$OUTPUT_DIR/frame.raw" "$OUTPUT_DIR/frame.png" --width 1280 --height 800
fi

print "Saved: $OUTPUT_DIR"
