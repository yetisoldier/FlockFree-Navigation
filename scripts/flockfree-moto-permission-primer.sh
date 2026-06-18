#!/usr/bin/env bash
set -u

PACKAGE="${PACKAGE:-com.yetiwurks.flockfree}"
ADB="${ADB:-adb}"
SERIAL="${FLOCKFREE_ADB_SERIAL:-192.168.1.139:5555}"
CONNECT=1
RUN_DIAGNOSTICS=1
LAUNCH=1

usage() {
  cat <<'USAGE'
Grant FlockFree runtime permissions needed for morning CYD/GPS testing.

Defaults:
  serial: 192.168.1.139:5555, or $FLOCKFREE_ADB_SERIAL
  package: com.yetiwurks.flockfree, or $PACKAGE

Usage:
  scripts/flockfree-moto-permission-primer.sh [options]

Options:
  -s, --serial SERIAL      ADB serial or Wi-Fi endpoint.
      --no-connect         Do not run "adb connect" for host:port serials.
      --no-diagnostics     Do not run the diagnostics collector afterward.
      --no-launch          Do not launch FlockFree during diagnostics.
  -h, --help               Show this help.

This script changes only runtime permission/app-op state for the target package
on the connected test phone. It does not build, install, upload, or delete data.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -s|--serial)
      [ "$#" -ge 2 ] || { echo "missing value for $1" >&2; exit 64; }
      SERIAL="$2"
      shift 2
      ;;
    --no-connect)
      CONNECT=0
      shift
      ;;
    --no-diagnostics)
      RUN_DIAGNOSTICS=0
      shift
      ;;
    --no-launch)
      LAUNCH=0
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

run_adb() {
  printf '$'
  printf ' %q' "$ADB" -s "$SERIAL" "$@"
  printf '\n'
  "$ADB" -s "$SERIAL" "$@"
}

try_shell() {
  description="$1"
  shift
  echo
  echo "==> ${description}"
  if run_adb shell "$*"; then
    echo "OK: ${description}"
  else
    echo "WARN: ${description} failed" >&2
  fi
}

echo "Preparing FlockFree permissions on ${SERIAL} for ${PACKAGE}"

run_adb devices -l

if [ "$CONNECT" -eq 1 ] && [[ "$SERIAL" == *:* ]]; then
  "$ADB" connect "$SERIAL" || true
fi

ADB_STATE="$("$ADB" -s "$SERIAL" get-state 2>/dev/null | tr -d '\r' || true)"
if [ "$ADB_STATE" != "device" ]; then
  echo "ADB state is '${ADB_STATE:-unknown}', not 'device'." >&2
  exit 2
fi

PACKAGE_QUERY="$("$ADB" -s "$SERIAL" shell "pm list packages $PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if ! printf '%s\n' "$PACKAGE_QUERY" | grep -Fq "$PACKAGE"; then
  echo "Package is not installed: $PACKAGE" >&2
  exit 3
fi

for permission in \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.ACCESS_COARSE_LOCATION \
  android.permission.BLUETOOTH_SCAN \
  android.permission.BLUETOOTH_CONNECT \
  android.permission.POST_NOTIFICATIONS
do
  try_shell "pm grant ${permission}" "pm grant $PACKAGE $permission"
done

for appop in \
  FINE_LOCATION \
  COARSE_LOCATION \
  BLUETOOTH_SCAN \
  BLUETOOTH_CONNECT \
  POST_NOTIFICATION
do
  try_shell "appop allow ${appop}" "cmd appops set $PACKAGE $appop allow"
done

echo
echo "Permission state after primer:"
run_adb shell "dumpsys package $PACKAGE 2>/dev/null | grep -E 'android.permission.(ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|BLUETOOTH_SCAN|BLUETOOTH_CONNECT|POST_NOTIFICATIONS):' || true"
run_adb shell "cmd appops get $PACKAGE 2>/dev/null | grep -Ei 'COARSE_LOCATION|FINE_LOCATION|POST_NOTIFICATION|BLUETOOTH_SCAN|BLUETOOTH_CONNECT' || true"

if [ "$RUN_DIAGNOSTICS" -eq 1 ]; then
  diagnostics_args=(--serial "$SERIAL" --logcat-lines 2000)
  if [ "$LAUNCH" -eq 1 ]; then
    diagnostics_args+=(--clear-logcat --launch)
  fi
  "$ROOT_DIR/scripts/flockfree-moto-diagnostics.sh" "${diagnostics_args[@]}"
fi
