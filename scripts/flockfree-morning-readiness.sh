#!/usr/bin/env bash
set -u

PACKAGE="${PACKAGE:-com.yetiwurks.flockfree}"
ADB="${ADB:-adb}"
SERIAL="${FLOCKFREE_ADB_SERIAL:-192.168.1.139:39183}"
OUT_ROOT="${OUT_ROOT:-logs/flockfree-readiness}"
OUT_DIR="${OUT_DIR:-}"
LOGCAT_LINES="${LOGCAT_LINES:-2000}"
RUN_SOURCE_CHECKS=1
RUN_PERMISSION_PRIMER=1
RUN_DIAGNOSTICS=1
LAUNCH=1
CONNECT=1

usage() {
  cat <<'USAGE'
Run the no-Gradle FlockFree morning readiness pass.

Defaults:
  serial: 192.168.1.139:39183, or $FLOCKFREE_ADB_SERIAL
  package: com.yetiwurks.flockfree, or $PACKAGE
  output: logs/flockfree-readiness/YYYYMMDD-HHMMSS

Usage:
  scripts/flockfree-morning-readiness.sh [options]

Options:
  -s, --serial SERIAL       ADB serial or Wi-Fi endpoint.
      --out-dir DIR         Exact output directory to write.
      --logcat-lines N      Number of filtered logcat lines to keep.
      --skip-source-checks  Do not run scripts/flockfree-source-checks.sh.
      --skip-primer         Do not grant runtime permissions/app-ops first.
      --skip-diagnostics    Do not run the Moto diagnostics collector.
      --no-launch           Do not launch FlockFree during diagnostics.
      --no-connect          Do not run "adb connect" for host:port serials.
  -h, --help                Show this help.

This script does not run Gradle, build an APK, install an APK, upload data, or
delete app data. It collects local evidence and writes a concise readiness report.
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
    --skip-source-checks)
      RUN_SOURCE_CHECKS=0
      shift
      ;;
    --skip-primer)
      RUN_PERMISSION_PRIMER=0
      shift
      ;;
    --skip-diagnostics)
      RUN_DIAGNOSTICS=0
      shift
      ;;
    --no-launch)
      LAUNCH=0
      shift
      ;;
    --no-connect)
      CONNECT=0
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

case "$LOGCAT_LINES" in
  ''|*[!0-9]*)
    echo "--logcat-lines must be a positive integer" >&2
    exit 64
    ;;
esac

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

REPORT="${OUT_DIR}/readiness-report.txt"
SOURCE_LOG="${OUT_DIR}/source-checks.txt"
PRIMER_LOG="${OUT_DIR}/permission-primer.txt"
DIAG_DIR="${OUT_DIR}/diagnostics"
SOURCE_CHANGES_LOG="${OUT_DIR}/source-changes-since-build.txt"
APP_CHANGES_LOG="${OUT_DIR}/app-runtime-changes-since-build.txt"
APP_CHANGE_COUNT="unknown"

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

source_commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
source_state="dirty"
if git diff --quiet && git diff --cached --quiet; then
  source_state="clean"
fi

: > "$REPORT"
append_report "FlockFree morning readiness"
append_report "==========================="
append_report "Output directory: ${OUT_DIR}"
append_report "Source commit: ${source_commit}"
append_report "Source state: ${source_state}"
append_report "ADB serial: ${SERIAL}"
append_report "Package: ${PACKAGE}"
append_report ""

if [ -f build-artifacts/FlockFree-build-info.txt ]; then
  cp build-artifacts/FlockFree-build-info.txt "${OUT_DIR}/FlockFree-build-info.txt"
  build_commit="$(awk -F': ' '/^Source commit:/ {print $2}' build-artifacts/FlockFree-build-info.txt | tail -n 1)"
  build_sha="$(awk -F': ' '/^SHA-256:/ {print $2}' build-artifacts/FlockFree-build-info.txt | tail -n 1)"
  append_report "Last APK build source commit: ${build_commit:-unknown}"
  append_report "Last APK SHA-256: ${build_sha:-unknown}"
  if [ -n "${build_commit:-}" ] && git cat-file -e "${build_commit}^{commit}" 2>/dev/null; then
    git diff --name-only "${build_commit}..HEAD" > "$SOURCE_CHANGES_LOG"
    grep -E '^(OsmAnd/(src|res|assets)/|OsmAnd/AndroidManifest\.xml|OsmAnd/build[^/]*\.gradle|OsmAnd-java/|OsmAnd-api/|OsmAnd-shared/|plugins/|gradle/|build\.gradle|settings\.gradle|gradle\.properties)' \
      "$SOURCE_CHANGES_LOG" > "$APP_CHANGES_LOG" || true
    source_change_count="$(grep -c . "$SOURCE_CHANGES_LOG" 2>/dev/null || true)"
    app_change_count="$(grep -c . "$APP_CHANGES_LOG" 2>/dev/null || true)"
    APP_CHANGE_COUNT="$app_change_count"
    append_report "Source files changed since last APK build: ${source_change_count}"
    if [ "$app_change_count" -eq 0 ]; then
      append_report "Installed APK app-code status: current (no app/runtime changes since last APK build)"
    else
      append_report "Installed APK app-code status: stale (${app_change_count} app/runtime paths changed since last APK build)"
      append_report "Rebuild before testing app behavior; see ${APP_CHANGES_LOG}"
    fi
  else
    append_report "Installed APK app-code status: unknown (build commit not found locally)"
  fi
else
  append_report "Last APK build info: missing"
  append_report "Installed APK app-code status: unknown (missing build provenance)"
fi
append_report ""

if [ "$RUN_SOURCE_CHECKS" -eq 1 ]; then
  run_step "source-only checks" "$SOURCE_LOG" "$ROOT_DIR/scripts/flockfree-source-checks.sh" || exit $?
else
  append_report "Skipped: source-only checks"
fi
append_report ""

if [ "$RUN_PERMISSION_PRIMER" -eq 1 ]; then
  primer_args=(--serial "$SERIAL" --no-diagnostics)
  if [ "$CONNECT" -eq 0 ]; then
    primer_args+=(--no-connect)
  fi
  run_step "permission primer" "$PRIMER_LOG" "$ROOT_DIR/scripts/flockfree-moto-permission-primer.sh" "${primer_args[@]}" || exit $?
else
  append_report "Skipped: permission primer"
fi
append_report ""

if [ "$RUN_DIAGNOSTICS" -eq 1 ]; then
  diagnostics_args=(--serial "$SERIAL" --out-dir "$DIAG_DIR" --logcat-lines "$LOGCAT_LINES")
  if [ "$CONNECT" -eq 0 ]; then
    diagnostics_args+=(--no-connect)
  fi
  if [ "$LAUNCH" -eq 1 ]; then
    diagnostics_args+=(--clear-logcat --launch)
  fi
  run_step "Moto diagnostics" "${OUT_DIR}/diagnostics-run.txt" "$ROOT_DIR/scripts/flockfree-moto-diagnostics.sh" "${diagnostics_args[@]}" || exit $?
  append_report ""
  append_report "Diagnostic summary:"
  if [ -f "${DIAG_DIR}/summary.txt" ]; then
    sed 's/^/  /' "${DIAG_DIR}/summary.txt" | tee -a "$REPORT"
  else
    append_report "  missing diagnostics summary"
  fi

  append_report ""
  append_report "Readiness verdict:"
  verdict="READY"
  if [ "$source_state" != "clean" ]; then
    append_report "  ATTENTION: source tree is ${source_state}"
    verdict="ATTENTION"
  fi
  if [ "$APP_CHANGE_COUNT" = "unknown" ]; then
    append_report "  ATTENTION: installed APK app-code freshness is unknown"
    verdict="ATTENTION"
  elif [ "$APP_CHANGE_COUNT" -gt 0 ]; then
    append_report "  ATTENTION: installed APK is stale for ${APP_CHANGE_COUNT} app/runtime path(s)"
    verdict="ATTENTION"
  fi
  if [ -f "${DIAG_DIR}/summary.txt" ]; then
    summary_file="${DIAG_DIR}/summary.txt"
    if ! grep -q '^ADB state: device' "$summary_file"; then
      append_report "  ATTENTION: ADB device state is not ready"
      verdict="ATTENTION"
    fi
    if ! grep -q '^Package installed: yes' "$summary_file"; then
      append_report "  ATTENTION: package is not installed"
      verdict="ATTENTION"
    fi
    if grep -q '^PID: not running' "$summary_file"; then
      append_report "  ATTENTION: package process is not running"
      verdict="ATTENTION"
    fi
    if ! grep -q '^Camera cache: present' "$summary_file"; then
      append_report "  ATTENTION: camera cache is not present"
      verdict="ATTENTION"
    fi
    if ! grep -q '^Camera database: present' "$summary_file"; then
      append_report "  ATTENTION: camera database is not present"
      verdict="ATTENTION"
    elif grep -q '^Camera database: present .* 0 rows' "$summary_file"; then
      append_report "  ATTENTION: camera database is present but empty"
      verdict="ATTENTION"
    fi
    if ! grep -q '^Location permissions: fine granted, coarse granted; device location on' "$summary_file"; then
      append_report "  ATTENTION: location permission or device location is not ready"
      verdict="ATTENTION"
    fi
    if ! grep -q '^Bluetooth permissions: scan granted, connect granted; Bluetooth on' "$summary_file"; then
      append_report "  ATTENTION: Bluetooth permission or device Bluetooth is not ready"
      verdict="ATTENTION"
    fi
    if ! grep -q '^Notifications permission: granted' "$summary_file"; then
      append_report "  ATTENTION: notification permission is not ready"
      verdict="ATTENTION"
    fi
  else
    append_report "  ATTENTION: diagnostics summary is missing"
    verdict="ATTENTION"
  fi
  if [ -f "${DIAG_DIR}/logcat-flockfree-camera-fatal.txt" ] \
    && grep -Ev '^(\$|\[|$)' "${DIAG_DIR}/logcat-flockfree-camera-fatal.txt" \
      | grep -Eiq 'FATAL EXCEPTION|AndroidRuntime.*FATAL EXCEPTION'; then
    append_report "  ATTENTION: fatal crash evidence found in filtered logcat"
    verdict="ATTENTION"
  fi
  if [ "$verdict" = "READY" ]; then
    append_report "  READY: source checks, APK freshness, permissions, camera cache/database, launch state, and filtered crash checks are ready for manual feature testing."
  else
    append_report "  ATTENTION: review the item(s) above before judging app behavior."
  fi
else
  append_report "Skipped: Moto diagnostics"
fi

append_report ""
append_report "Done. Start with ${REPORT}"
