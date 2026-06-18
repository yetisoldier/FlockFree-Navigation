#!/usr/bin/env bash
set -u

PACKAGE="${PACKAGE:-com.yetiwurks.flockfree}"
ADB="${ADB:-adb}"
SERIAL="${FLOCKFREE_ADB_SERIAL:-192.168.1.139:5555}"
OUT_ROOT="${OUT_ROOT:-logs/flockfree-field-session}"
OUT_DIR="${OUT_DIR:-}"
DURATION_SECONDS="${DURATION_SECONDS:-900}"
LOGCAT_LINES="${LOGCAT_LINES:-3000}"
RUN_READINESS=1
CONNECT=1
LAUNCH=1
CLEAR_LOGCAT=1

usage() {
  cat <<'USAGE'
Capture a no-Gradle FlockFree manual field-test session over ADB.

Defaults:
  serial: 192.168.1.139:5555, or $FLOCKFREE_ADB_SERIAL
  package: com.yetiwurks.flockfree, or $PACKAGE
  duration: 900 seconds
  output: logs/flockfree-field-session/YYYYMMDD-HHMMSS

Usage:
  scripts/flockfree-field-test-session.sh [options]

Options:
  -s, --serial SERIAL       ADB serial or Wi-Fi endpoint.
      --out-dir DIR         Exact output directory to write.
      --duration SECONDS    Timed filtered logcat capture duration.
      --logcat-lines N      Number of filtered logcat lines for final diagnostics.
      --skip-readiness      Do not run the morning readiness gate first.
      --no-connect          Do not run "adb connect" for host:port serials.
      --no-launch           Do not launch FlockFree before timed capture.
      --keep-logcat         Do not clear logcat before timed capture.
  -h, --help                Show this help.

This script does not run Gradle, install an APK, delete app data, upload data,
or submit OSM edits. It captures local evidence while a human performs tests.
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
    --duration)
      [ "$#" -ge 2 ] || { echo "missing value for $1" >&2; exit 64; }
      DURATION_SECONDS="$2"
      shift 2
      ;;
    --logcat-lines)
      [ "$#" -ge 2 ] || { echo "missing value for $1" >&2; exit 64; }
      LOGCAT_LINES="$2"
      shift 2
      ;;
    --skip-readiness)
      RUN_READINESS=0
      shift
      ;;
    --no-connect)
      CONNECT=0
      shift
      ;;
    --no-launch)
      LAUNCH=0
      shift
      ;;
    --keep-logcat)
      CLEAR_LOGCAT=0
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

case "$DURATION_SECONDS" in
  ''|*[!0-9]*)
    echo "--duration must be a positive integer" >&2
    exit 64
    ;;
esac

case "$LOGCAT_LINES" in
  ''|*[!0-9]*)
    echo "--logcat-lines must be a positive integer" >&2
    exit 64
    ;;
esac

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "adb not found. Set ADB=/path/to/adb or add adb to PATH." >&2
  exit 127
fi

if ! command -v timeout >/dev/null 2>&1; then
  echo "timeout command not found; install coreutils or use a shell that provides timeout." >&2
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

REPORT="${OUT_DIR}/field-session-report.txt"
SESSION_LOGCAT="${OUT_DIR}/session-logcat-filtered.txt"
PROMPTS="${OUT_DIR}/manual-test-prompts.txt"
READINESS_DIR="${OUT_DIR}/readiness"
POST_DIAG_DIR="${OUT_DIR}/post-diagnostics"

append_report() {
  printf '%s\n' "$*" | tee -a "$REPORT"
}

run_step() {
  label="$1"
  output="$2"
  shift 2
  append_report "Running: ${label}"
  {
    printf '$'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } > "$output" 2>&1
  status="$?"
  printf '\n[exit_status=%s]\n' "$status" >> "$output"
  if [ "$status" -eq 0 ]; then
    append_report "OK: ${label}"
  else
    append_report "FAIL: ${label} (see ${output})"
    return "$status"
  fi
}

write_prompts() {
  cat > "$PROMPTS" <<'PROMPTS'
FlockFree manual test prompts
=============================

Use these while the timed logcat capture is running:

1. Open FlockFree settings and confirm the readiness-relevant rows:
   Camera data, Last route check, Nearby camera alerts, CYD status.
2. Tap Refresh camera data on Wi-Fi and wait for the row to settle.
3. Calculate one camera-dense offline route with avoidance enabled.
4. Reopen settings and confirm Last route check preserves applied/fallback/skipped status.
5. Long-press a map location, choose Add ALPR Camera, and confirm OsmAnd's editor opens with ALPR/surveillance tags.
6. Enable CYD BLE, scan/connect, request CYD status, and run Simulate CYD detection if hardware is available.
7. Return to the map and review any CYD marker as an ALPR camera.

This script only records evidence. It does not submit OSM edits.
PROMPTS
}

source_commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
source_state="dirty"
if git diff --quiet && git diff --cached --quiet; then
  source_state="clean"
fi

: > "$REPORT"
append_report "FlockFree field-test session"
append_report "============================"
append_report "Output directory: ${OUT_DIR}"
append_report "Source commit: ${source_commit}"
append_report "Source state: ${source_state}"
append_report "ADB serial: ${SERIAL}"
append_report "Package: ${PACKAGE}"
append_report "Timed capture duration: ${DURATION_SECONDS} seconds"
append_report ""

write_prompts
append_report "Manual prompts: ${PROMPTS}"
append_report ""

if [ "$RUN_READINESS" -eq 1 ]; then
  readiness_args=(--serial "$SERIAL" --out-dir "$READINESS_DIR" --logcat-lines "$LOGCAT_LINES")
  if [ "$CONNECT" -eq 0 ]; then
    readiness_args+=(--no-connect)
  fi
  run_step "morning readiness gate" "${OUT_DIR}/readiness-run.txt" \
    "$ROOT_DIR/scripts/flockfree-morning-readiness.sh" "${readiness_args[@]}" || exit $?
  append_report ""
  if [ -f "${READINESS_DIR}/readiness-report.txt" ]; then
    append_report "Readiness gate verdict:"
    awk '/^Readiness verdict:/{flag=1} flag{print "  " $0}' \
      "${READINESS_DIR}/readiness-report.txt" | tee -a "$REPORT"
    append_report ""
  fi
else
  append_report "Skipped: morning readiness gate"
  append_report ""
fi

if [ "$CONNECT" -eq 1 ] && [[ "$SERIAL" == *:* ]]; then
  run_step "adb connect" "${OUT_DIR}/adb-connect.txt" "$ADB" connect "$SERIAL" || exit $?
fi

ADB_STATE="$("$ADB" -s "$SERIAL" get-state 2>/dev/null | tr -d '\r' || true)"
append_report "ADB state before timed capture: ${ADB_STATE:-unknown}"
if [ "$ADB_STATE" != "device" ]; then
  append_report "ATTENTION: ADB state is not 'device'; reconnect or authorize the Moto, then rerun."
  exit 2
fi

if [ "$CLEAR_LOGCAT" -eq 1 ]; then
  run_step "clear logcat" "${OUT_DIR}/logcat-clear.txt" "$ADB" -s "$SERIAL" logcat -c || exit $?
fi

if [ "$LAUNCH" -eq 1 ]; then
  run_step "launch FlockFree" "${OUT_DIR}/launch-monkey.txt" "$ADB" -s "$SERIAL" shell monkey -p "$PACKAGE" 1 || exit $?
  sleep 2
fi

append_report ""
append_report "Timed filtered logcat capture is starting."
append_report "While it runs, perform the manual prompts in ${PROMPTS}."
append_report ""

{
  printf '$ timeout %ss %q -s %q logcat -v threadtime | grep -Ei %q\n\n' \
    "$DURATION_SECONDS" "$ADB" "$SERIAL" 'FlockFree|CameraData|CYD|Avoidance|AndroidRuntime|FATAL|com.yetiwurks.flockfree'
  timeout "${DURATION_SECONDS}s" "$ADB" -s "$SERIAL" logcat -v threadtime 2>&1 \
    | grep -Ei 'FlockFree|CameraData|CYD|Avoidance|AndroidRuntime|FATAL|com.yetiwurks.flockfree'
  statuses=("${PIPESTATUS[@]}")
  if [ "${statuses[1]}" -eq 1 ]; then
    printf '\n[no matching field-test logcat lines]\n'
  fi
  printf '\n[exit_status timeout/adb=%s grep=%s]\n' "${statuses[0]}" "${statuses[1]}"
} > "$SESSION_LOGCAT"

log_line_count="$(grep -Evc '^(\$|\[|$)' "$SESSION_LOGCAT" 2>/dev/null || true)"
fatal_line_count="$(grep -Ev '^(\$|\[|$)' "$SESSION_LOGCAT" 2>/dev/null \
  | grep -Eic 'FATAL EXCEPTION|AndroidRuntime.*FATAL EXCEPTION' || true)"
append_report "Timed filtered logcat: ${SESSION_LOGCAT}"
append_report "Timed filtered logcat lines: ${log_line_count}"
append_report "Timed fatal crash evidence lines: ${fatal_line_count}"
append_report ""

diag_args=(--serial "$SERIAL" --out-dir "$POST_DIAG_DIR" --logcat-lines "$LOGCAT_LINES")
if [ "$CONNECT" -eq 0 ]; then
  diag_args+=(--no-connect)
fi
run_step "post-session diagnostics" "${OUT_DIR}/post-diagnostics-run.txt" \
  "$ROOT_DIR/scripts/flockfree-moto-diagnostics.sh" "${diag_args[@]}" || exit $?

append_report ""
append_report "Post-session diagnostic summary:"
if [ -f "${POST_DIAG_DIR}/summary.txt" ]; then
  sed 's/^/  /' "${POST_DIAG_DIR}/summary.txt" | tee -a "$REPORT"
else
  append_report "  missing post-session diagnostics summary"
fi

append_report ""
append_report "Done. Start with ${REPORT}"
