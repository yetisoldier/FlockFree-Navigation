#!/usr/bin/env bash

set -u

PACKAGE="${PACKAGE:-com.yetiwurks.flockfree}"
ADB="${ADB:-adb}"
SERIAL="${FLOCKFREE_ADB_SERIAL:-192.168.1.139:5555}"
OUT_ROOT="${OUT_ROOT:-logs/flockfree-diagnostics}"
OUT_DIR="${OUT_DIR:-}"
LOGCAT_LINES="${LOGCAT_LINES:-2000}"
CONNECT=1
LAUNCH=0
CLEAR_LOGCAT=0

usage() {
  cat <<'USAGE'
Collect repeatable FlockFree phone diagnostics over ADB.

Defaults:
  serial: 192.168.1.139:5555, or $FLOCKFREE_ADB_SERIAL
  package: com.yetiwurks.flockfree, or $PACKAGE
  output: logs/flockfree-diagnostics/YYYYMMDD-HHMMSS

Usage:
  scripts/flockfree-moto-diagnostics.sh [options]

Options:
  -s, --serial SERIAL      ADB serial or Wi-Fi endpoint.
      --out-dir DIR        Exact output directory to write.
      --logcat-lines N     Number of filtered logcat lines to keep.
      --no-connect         Do not run "adb connect" for host:port serials.
      --launch             Launch the package with monkey before collecting.
      --clear-logcat       Clear logcat before optional launch/collection.
  -h, --help               Show this help.

This script writes local files only. It does not upload or send diagnostics.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -s|--serial)
      [ "$#" -ge 2 ] || { echo "missing value for $1" >&2; exit 64; }
      SERIAL="$2"
      shift 2
      ;;
    --out-dir)
      [ "$#" -ge 2 ] || { echo "missing value for $1" >&2; exit 64; }
      OUT_DIR="$2"
      shift 2
      ;;
    --logcat-lines)
      [ "$#" -ge 2 ] || { echo "missing value for $1" >&2; exit 64; }
      LOGCAT_LINES="$2"
      shift 2
      ;;
    --no-connect)
      CONNECT=0
      shift
      ;;
    --launch)
      LAUNCH=1
      shift
      ;;
    --clear-logcat)
      CLEAR_LOGCAT=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "adb not found. Set ADB=/path/to/adb or add adb to PATH." >&2
  exit 127
fi

case "$LOGCAT_LINES" in
  ''|*[!0-9]*)
    echo "--logcat-lines must be a positive integer" >&2
    exit 64
    ;;
esac

if [ -z "$OUT_DIR" ]; then
  RUN_ID="$(date +%Y%m%d-%H%M%S)"
  OUT_DIR="${OUT_ROOT}/${RUN_ID}"
fi

mkdir -p "$OUT_DIR" || {
  echo "failed to create output directory: $OUT_DIR" >&2
  exit 1
}

RUN_LOG="${OUT_DIR}/run.log"
: > "$RUN_LOG"

log() {
  printf '%s\n' "$*" | tee -a "$RUN_LOG"
}

capture_host() {
  name="$1"
  shift
  file="${OUT_DIR}/${name}"
  {
    printf '$'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
    status="$?"
    printf '\n[exit_status=%s]\n' "$status"
  } > "$file" 2>&1
  log "wrote ${file}"
}

capture_adb() {
  name="$1"
  shift
  capture_host "$name" "$ADB" -s "$SERIAL" "$@"
}

capture_shell() {
  name="$1"
  shift
  capture_adb "$name" shell "$*"
}

capture_logcat_filter() {
  file="${OUT_DIR}/logcat-flockfree-camera-fatal.txt"
  {
    printf '$ %q -s %q logcat -d -v threadtime | grep -Ei %q | tail -n %q\n\n' \
      "$ADB" "$SERIAL" 'FlockFree|CameraData|FATAL|AndroidRuntime|com.yetiwurks.flockfree' "$LOGCAT_LINES"
  } > "$file"

  "$ADB" -s "$SERIAL" logcat -d -v threadtime 2>&1 \
    | grep -Ei 'FlockFree|CameraData|FATAL|AndroidRuntime|com.yetiwurks.flockfree' \
    | tail -n "$LOGCAT_LINES" >> "$file"
  statuses=("${PIPESTATUS[@]}")

  if [ "${statuses[1]}" -eq 1 ]; then
    printf '\n[no matching FlockFree/CameraData/FATAL logcat lines]\n' >> "$file"
  fi
  printf '\n[exit_status adb=%s grep=%s tail=%s]\n' \
    "${statuses[0]}" "${statuses[1]}" "${statuses[2]}" >> "$file"
  log "wrote ${file}"
}

capture_ui_snapshot() {
  screenshot_file="${OUT_DIR}/screenshot.png"
  screenshot_err="${OUT_DIR}/screenshot.err"
  if "$ADB" -s "$SERIAL" exec-out screencap -p > "$screenshot_file" 2> "$screenshot_err"; then
    log "wrote ${screenshot_file}"
    if [ ! -s "$screenshot_err" ]; then
      rm -f "$screenshot_err"
    fi
  else
    log "failed to capture screenshot; see ${screenshot_err}"
  fi

  remote_xml="/sdcard/flockfree-window.xml"
  dump_file="${OUT_DIR}/window-dump.txt"
  pull_file="${OUT_DIR}/window-pull.txt"
  {
    printf '$ %q -s %q shell uiautomator dump %q\n\n' "$ADB" "$SERIAL" "$remote_xml"
    "$ADB" -s "$SERIAL" shell uiautomator dump "$remote_xml"
    status="$?"
    printf '\n[exit_status=%s]\n' "$status"
  } > "$dump_file" 2>&1
  log "wrote ${dump_file}"

  if grep -q '\[exit_status=0\]' "$dump_file"; then
    {
      printf '$ %q -s %q pull %q %q\n\n' "$ADB" "$SERIAL" "$remote_xml" "${OUT_DIR}/window.xml"
      "$ADB" -s "$SERIAL" pull "$remote_xml" "${OUT_DIR}/window.xml"
      status="$?"
      printf '\n[exit_status=%s]\n' "$status"
      "$ADB" -s "$SERIAL" shell rm -f "$remote_xml" >/dev/null 2>&1 || true
    } > "$pull_file" 2>&1
    log "wrote ${pull_file}"
  fi

  ui_summary="${OUT_DIR}/ui-summary.txt"
  python3 - "$OUT_DIR/window.xml" > "$ui_summary" 2>&1 <<'PY'
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
patterns = [
    "FlockFree",
    "Camera data",
    "Refresh camera data",
    "Last route check",
    "CYD status",
    "Phone GPS",
    "Refresh due",
    "Avoidance",
]

print("FlockFree UI text summary")
print("=========================")
try:
    root = ET.parse(path).getroot()
except Exception as exc:
    print(f"window.xml unavailable or unreadable: {exc}")
    raise SystemExit(0)

matches = []
for node in root.iter("node"):
    values = []
    for key in ("text", "content-desc", "resource-id"):
        value = node.attrib.get(key)
        if value:
            values.append(value)
    joined = " | ".join(values)
    if joined and any(pattern.lower() in joined.lower() for pattern in patterns):
        matches.append(joined)

if matches:
    for value in matches[:100]:
        print(f"- {value}")
else:
    print("No target UI text matched.")
PY
  log "wrote ${ui_summary}"
}

write_summary() {
  adb_state="${1:-unknown}"
  package_installed="${2:-unknown}"
  pid="${3:-}"
  activity="${4:-}"
  log_file="${OUT_DIR}/logcat-flockfree-camera-fatal.txt"
  log_lines="0"
  if [ -f "$log_file" ]; then
    log_lines="$(grep -Evc '^(\$|\[|$)' "$log_file" 2>/dev/null || true)"
  fi

  {
    printf 'FlockFree Moto diagnostics\n'
    printf '==========================\n\n'
    printf 'Output directory: %s\n' "$OUT_DIR"
    printf 'ADB serial: %s\n' "$SERIAL"
    printf 'Package: %s\n' "$PACKAGE"
    printf 'ADB state: %s\n' "$adb_state"
    printf 'Package installed: %s\n' "$package_installed"
    printf 'Current activity: %s\n' "${activity:-not detected}"
    printf 'PID: %s\n' "${pid:-not running}"
    printf 'Filtered logcat lines: %s\n\n' "$log_lines"
    printf 'Key files:\n'
    printf '%s\n' '- adb-devices-after.txt'
    printf '%s\n' '- adb-state.txt'
    printf '%s\n' '- package-state.txt'
    printf '%s\n' '- current-activity.txt'
    printf '%s\n' '- process-state.txt'
    printf '%s\n' '- screenshot.png'
    printf '%s\n' '- window.xml'
    printf '%s\n' '- ui-summary.txt'
    printf '%s\n' '- logcat-flockfree-camera-fatal.txt'
  } > "${OUT_DIR}/summary.txt"
  log "wrote ${OUT_DIR}/summary.txt"
}

log "Collecting FlockFree diagnostics into ${OUT_DIR}"
log "Using ADB serial ${SERIAL}"

capture_host "adb-devices-before.txt" "$ADB" devices -l

if [ "$CONNECT" -eq 1 ] && [[ "$SERIAL" == *:* ]]; then
  capture_host "adb-connect.txt" "$ADB" connect "$SERIAL"
fi

capture_host "adb-devices-after.txt" "$ADB" devices -l

ADB_STATE="$("$ADB" -s "$SERIAL" get-state 2>/dev/null | tr -d '\r' || true)"
printf '%s\n' "${ADB_STATE:-unknown}" > "${OUT_DIR}/adb-state.txt"
log "wrote ${OUT_DIR}/adb-state.txt"

if [ "$ADB_STATE" != "device" ]; then
  write_summary "${ADB_STATE:-unknown}" "not checked" "" ""
  log "ADB state is not 'device'; authorize or reconnect the Moto, then rerun."
  exit 2
fi

if [ "$CLEAR_LOGCAT" -eq 1 ]; then
  capture_adb "logcat-clear.txt" logcat -c
fi

if [ "$LAUNCH" -eq 1 ]; then
  capture_adb "launch-monkey.txt" shell monkey -p "$PACKAGE" 1
  sleep 2
fi

capture_shell "device-props.txt" \
  "printf 'manufacturer='; getprop ro.product.manufacturer; printf 'model='; getprop ro.product.model; printf 'android='; getprop ro.build.version.release; printf 'sdk='; getprop ro.build.version.sdk; printf '\nwlan0:\n'; ip addr show wlan0 2>/dev/null || true"

capture_shell "package-state.txt" \
  "printf 'pm list packages:\n'; pm list packages $PACKAGE 2>/dev/null || true; printf '\npm path:\n'; pm path $PACKAGE 2>/dev/null || true; printf '\ndumpsys package excerpt:\n'; dumpsys package $PACKAGE 2>/dev/null | grep -E 'Package \\[|versionName|versionCode|firstInstallTime|lastUpdateTime|installerPackageName|userId=' || true"

capture_shell "current-activity.txt" \
  "printf 'window focus:\n'; dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|topResumedActivity' || true; printf '\nactivity stack excerpt:\n'; dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity|Hist #' | head -80 || true"

capture_shell "process-state.txt" \
  "printf 'pidof:\n'; pidof $PACKAGE 2>/dev/null || true; printf '\nps:\n'; ps -A 2>/dev/null | grep -F $PACKAGE || true; printf '\nactivity process excerpt:\n'; dumpsys activity processes 2>/dev/null | grep -F $PACKAGE -A 20 -B 5 || true"

capture_shell "activity-top.txt" \
  "dumpsys activity top 2>/dev/null | head -200 || true"

capture_ui_snapshot

capture_logcat_filter

PACKAGE_QUERY="$("$ADB" -s "$SERIAL" shell "pm list packages $PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if printf '%s\n' "$PACKAGE_QUERY" | grep -Fq "$PACKAGE"; then
  PACKAGE_INSTALLED="yes"
else
  PACKAGE_INSTALLED="no"
fi

PID="$("$ADB" -s "$SERIAL" shell "pidof $PACKAGE" 2>/dev/null | tr -d '\r' | xargs || true)"
CURRENT_ACTIVITY="$("$ADB" -s "$SERIAL" shell "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|topResumedActivity' | head -1" 2>/dev/null | tr -d '\r' || true)"

write_summary "$ADB_STATE" "$PACKAGE_INSTALLED" "$PID" "$CURRENT_ACTIVITY"
log "Done. Start with ${OUT_DIR}/summary.txt"
