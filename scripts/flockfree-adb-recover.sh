#!/usr/bin/env bash
set -u

ADB="${ADB:-adb}"
SERIAL="${FLOCKFREE_ADB_SERIAL:-192.168.1.139:5555}"
OUT_ROOT="${OUT_ROOT:-logs/flockfree-adb-recover}"
OUT_DIR="${OUT_DIR:-}"
KILL_SERVER=1
RUN_DIAGNOSTICS=1

usage() {
  cat <<'USAGE'
Retry the FlockFree Moto Wi-Fi ADB endpoint and capture a local recovery log.

Defaults:
  serial: 192.168.1.139:5555, or $FLOCKFREE_ADB_SERIAL
  output: logs/flockfree-adb-recover/YYYYMMDD-HHMMSS

Usage:
  scripts/flockfree-adb-recover.sh [options]

Options:
  -s, --serial SERIAL      ADB serial or Wi-Fi endpoint.
      --out-dir DIR        Exact output directory to write.
      --no-kill-server     Do not restart the local ADB server.
      --no-diagnostics     Do not run FlockFree diagnostics after recovery.
  -h, --help               Show this help.

This script does not build, install, upload, clear logcat, launch the app, or
delete app data. It only operates on the local ADB connection state.
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
    --no-kill-server)
      KILL_SERVER=0
      shift
      ;;
    --no-diagnostics)
      RUN_DIAGNOSTICS=0
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

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ -z "$OUT_DIR" ]; then
  RUN_ID="$(date +%Y%m%d-%H%M%S)"
  OUT_DIR="${OUT_ROOT}/${RUN_ID}"
fi
mkdir -p "$OUT_DIR" || {
  echo "failed to create output directory: $OUT_DIR" >&2
  exit 1
}

REPORT="${OUT_DIR}/adb-recovery-report.txt"
: > "$REPORT"

log() {
  printf '%s\n' "$*" | tee -a "$REPORT"
}

capture() {
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

get_state() {
  "$ADB" -s "$SERIAL" get-state 2>/dev/null | tr -d '\r' || true
}

host_from_serial() {
  case "$SERIAL" in
    *:*) printf '%s\n' "${SERIAL%:*}" ;;
    *) printf '' ;;
  esac
}

log "FlockFree Wi-Fi ADB recovery"
log "==========================="
log "Output directory: ${OUT_DIR}"
log "ADB serial: ${SERIAL}"
log ""

capture adb-devices-before.txt "$ADB" devices -l
capture adb-mdns-before.txt "$ADB" mdns services

host="$(host_from_serial)"
if [ -n "$host" ] && command -v ip >/dev/null 2>&1; then
  capture ip-route-host-before.txt ip route get "$host"
  capture ip-neigh-before.txt ip neigh show
else
  log "Skipped route/neighbor capture: serial is not host:port or ip is unavailable."
fi

if [ -n "$host" ] && command -v ping >/dev/null 2>&1; then
  capture ping-host.txt ping -c 1 -W 2 "$host"
else
  log "Skipped ping: serial is not host:port or ping is unavailable."
fi

if [[ "$SERIAL" == *:* ]]; then
  capture adb-disconnect.txt "$ADB" disconnect "$SERIAL"
fi

if [ "$KILL_SERVER" -eq 1 ]; then
  capture adb-kill-server.txt "$ADB" kill-server
  capture adb-start-server.txt "$ADB" start-server
else
  log "Skipped local ADB server restart."
fi

if [[ "$SERIAL" == *:* ]]; then
  capture adb-connect.txt "$ADB" connect "$SERIAL"
fi

capture adb-devices-after.txt "$ADB" devices -l
capture adb-mdns-after.txt "$ADB" mdns services
if [ -n "$host" ] && command -v ip >/dev/null 2>&1; then
  capture ip-route-host-after.txt ip route get "$host"
  capture ip-neigh-after.txt ip neigh show
fi

state="$(get_state)"
log ""
log "ADB state after recovery: ${state:-unknown}"

if [ "$state" != "device" ]; then
  log ""
  log "Recovery did not reach device state."
  log "Likely causes: phone asleep, phone off Wi-Fi, Wireless debugging off, IP/port changed, or host cannot route to the phone."
  log "Review adb-mdns-after.txt for a changed Wireless debugging port and ip-neigh-after.txt for whether the old host is FAILED."
  log "On the phone, confirm Wireless debugging is enabled and note the current IP:port, then rerun with:"
  log "  scripts/flockfree-adb-recover.sh --serial PHONE_IP:PORT"
  exit 2
fi

if [ "$RUN_DIAGNOSTICS" -eq 1 ]; then
  diagnostics_dir="${OUT_DIR}/diagnostics"
  "$ROOT_DIR/scripts/flockfree-moto-diagnostics.sh" \
    --serial "$SERIAL" \
    --out-dir "$diagnostics_dir" \
    --no-connect \
    --logcat-lines 1000
  log ""
  log "Diagnostics: ${diagnostics_dir}"
fi

log "Recovery reached ADB device state."
