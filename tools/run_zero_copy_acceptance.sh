#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 [-s adb-serial] [--package app.id] [--duration seconds] [--cycles count] [--restart-duration seconds]"
  echo "Example: $0 -s 192.168.119.246:5555 --duration 600 --cycles 10"
}

serial=""
package_name="com.stasao.gcam.dev"
duration=600
cycles=1
restart_duration=10
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial) serial="${2:?missing serial}"; shift 2 ;;
    --package) package_name="${2:?missing package}"; shift 2 ;;
    --duration) duration="${2:?missing duration}"; shift 2 ;;
    --cycles) cycles="${2:?missing cycles}"; shift 2 ;;
    --restart-duration) restart_duration="${2:?missing restart duration}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$duration" =~ ^[1-9][0-9]*$ ]] || { echo "duration must be a positive integer" >&2; exit 2; }
[[ "$cycles" =~ ^[1-9][0-9]*$ ]] || { echo "cycles must be a positive integer" >&2; exit 2; }
[[ "$restart_duration" =~ ^[1-9][0-9]*$ ]] || { echo "restart-duration must be a positive integer" >&2; exit 2; }

adb_args=()
if [[ -n "$serial" ]]; then
  adb_args=(-s "$serial")
else
  device_count="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
  [[ "$device_count" -eq 1 ]] || { echo "Specify -s: connected devices = $device_count" >&2; exit 2; }
fi

adb_cmd() { adb "${adb_args[@]}" "$@"; }
component="$package_name/.RecorderDiagnosticReceiver"
action="$package_name.DIAGNOSTIC"
output_dir="$(pwd)/build/zero-copy-acceptance-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$output_dir"

adb_cmd shell pm path "$package_name" >/dev/null || {
  echo "Package $package_name is not installed" >&2
  exit 1
}

adb_cmd logcat -c
adb_cmd shell find /sdcard/Movies/gCam -type f -name '*.mp4' 2>/dev/null | tr -d '\r' | sort > "$output_dir/files-before.txt" || true

stop_recorder() {
  adb_cmd shell am broadcast -n "$component" -a "$action" --es command stop >/dev/null || true
}
trap stop_recorder EXIT INT TERM

for ((cycle=1; cycle<=cycles; cycle++)); do
  cycle_duration="$restart_duration"
  if (( cycle == 1 )); then
    cycle_duration="$duration"
  fi
  echo "Cycle $cycle/$cycles: starting 4 cameras for ${cycle_duration}s"
  adb_cmd shell am broadcast -n "$component" -a "$action" \
    --es command start --es cameras 0,1,2,3 --ei width 960 --ei fps 20 \
    --ei bitrate 3 --ei segmentMinutes 2 >/dev/null

  elapsed=0
  while (( elapsed < cycle_duration )); do
    adb_cmd shell dumpsys cpuinfo | grep -E "$package_name:(qcarcam|recorder)" \
      >> "$output_dir/cpu.txt" || true
    step=5
    (( cycle_duration - elapsed < step )) && step=$((cycle_duration - elapsed))
    sleep "$step"
    elapsed=$((elapsed + step))
  done

  stop_recorder
  sleep 3
done
trap - EXIT INT TERM

adb_cmd logcat -d -v threadtime GCAM_PERF:I GCAM_DIAGNOSTIC:I gcam_qcarcam:I '*:S' > "$output_dir/logcat.txt"
adb_cmd shell find /sdcard/Movies/gCam -type f -name '*.mp4' 2>/dev/null | tr -d '\r' | sort > "$output_dir/files-after.txt" || true
comm -13 "$output_dir/files-before.txt" "$output_dir/files-after.txt" > "$output_dir/new-files.txt"

while IFS= read -r remote_file; do
  [[ -n "$remote_file" ]] || continue
  local_file="$output_dir/$(echo "$remote_file" | sed 's#^/sdcard/Movies/gCam/##; s#/#_#g')"
  adb_cmd pull "$remote_file" "$local_file" >/dev/null
  if command -v ffprobe >/dev/null 2>&1; then
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=width,height,avg_frame_rate,nb_frames,duration:format=duration,size \
      -of default=noprint_wrappers=1 "$local_file" >> "$output_dir/ffprobe.txt" || true
    echo "file=$local_file" >> "$output_dir/ffprobe.txt"
  fi
done < "$output_dir/new-files.txt"

awk '
  /GCAM_PERF/ {
    input=""; mode=""; fps=""; gap=""
    for (i=1; i<=NF; i++) {
      if ($i ~ /^input=/) { split($i,a,"="); input=a[2] }
      if ($i ~ /^mode=/) { split($i,a,"="); mode=a[2] }
      if ($i ~ /^fps=/) { split($i,a,"="); fps=a[2]+0 }
      if ($i ~ /^maxGapUs=/) { split($i,a,"="); gap=a[2]+0 }
    }
    if (input != "") {
      seen[input]++
      if (!(input in minFps) || fps < minFps[input]) minFps[input]=fps
      if (gap > maxGap[input]) maxGap[input]=gap
      if (mode != "zero-copy") fallback[input]++
    }
  }
  END {
    for (input=0; input<4; input++) {
      printf "input=%d samples=%d minLoggedFps=%.2f maxGapMs=%.3f zeroCopy=%s\n", input,
        seen[input]+0, minFps[input]+0, (maxGap[input]+0)/1000,
        fallback[input] ? "no" : (seen[input] ? "yes" : "unknown")
    }
  }
' "$output_dir/logcat.txt" > "$output_dir/summary.txt"

echo "Results: $output_dir"
cat "$output_dir/summary.txt"
echo "Performance log: $output_dir/logcat.txt"
echo "CPU samples: $output_dir/cpu.txt"
echo "New MP4 files: $output_dir/new-files.txt"
