#!/usr/bin/env bash
set -u

OUT_ROOT="${OUT_ROOT:-logs/flockfree-field-session}"
NO_SUMMARIZE=0

usage() {
  cat <<'USAGE'
Mark a manual result on the latest FlockFree field-test session.

Usage:
  scripts/flockfree-mark-latest-result.sh CHECK_ID STATUS [--notes TEXT] [--no-summarize]

Checks:
  camera_data, route_avoidance, nearby_alerts, osm_reporting, cyd

Statuses:
  PASS, FAIL, SKIP, TODO

Options:
      --notes TEXT      Replacement notes for the manual result row.
      --no-summarize   Do not refresh session-summary.txt or field-session-report.txt.
      --self-check     Run a local self-check without using real logs.
  -h, --help           Show this help.
USAGE
}

run_self_check() {
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  session="$tmp/logs/flockfree-field-session/20260101-010101"
  mkdir -p "$session/readiness" "$session/post-diagnostics"
  cat > "$session/field-session-report.txt" <<'EOF'
FlockFree field-test session
Source commit: abc123
Source state: clean
Readiness verdict:
  READY: source checks passed
Timed filtered logcat lines: 1
Timed fatal crash evidence lines: 0
EOF
  cat > "$session/readiness/readiness-report.txt" <<'EOF'
Installed APK app-code status: current (no app/runtime changes since last APK build)
EOF
  cat > "$session/post-diagnostics/summary.txt" <<'EOF'
ADB state: device
Package installed: yes
Current activity: MapActivity
PID: 1
Camera cache: present (10 bytes)
CYD detection store: missing
Location permissions: fine granted, coarse granted; device location on
Bluetooth permissions: scan granted, connect granted; Bluetooth on
Notifications permission: granted
EOF
  cat > "$session/session-logcat-filtered.txt" <<'EOF'
01-01 I CameraData: loaded
EOF
  cat > "$session/manual-test-results.tsv" <<'EOF'
check_id	status	notes
route_avoidance	TODO	Needs route check.
EOF
  OUT_ROOT="$tmp/logs/flockfree-field-session" "$0" route_avoidance PASS --notes "Avoidance observed" \
    > "$tmp/self-check-output.txt" || return 1
  grep -q $'route_avoidance\tPASS\tAvoidance observed' "$session/manual-test-results.tsv" || {
    echo "self-check failed: result row was not updated" >&2
    return 1
  }
  grep -q "route avoidance: PASS" "$session/session-summary.txt" || {
    echo "self-check failed: summary was not refreshed" >&2
    return 1
  }
  grep -q "Manual result update" "$session/field-session-report.txt" || {
    echo "self-check failed: report was not updated" >&2
    return 1
  }
  echo "FlockFree latest-result marker self-check passed."
}

if [ "$#" -eq 0 ]; then
  usage >&2
  exit 64
fi

case "${1:-}" in
  --self-check)
    run_self_check
    exit 0
    ;;
  -h|--help)
    usage
    exit 0
    ;;
esac

CHECK_ID="${1:-}"
STATUS="${2:-}"
shift 2 || {
  usage >&2
  exit 64
}

ARGS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --notes)
      [ "$#" -ge 2 ] || { echo "missing value for $1" >&2; exit 64; }
      ARGS+=(--notes "$2")
      shift 2
      ;;
    --no-summarize)
      NO_SUMMARIZE=1
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

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SESSION_DIR="$(OUT_ROOT="$OUT_ROOT" "$ROOT_DIR/scripts/flockfree-latest-field-session.sh" --path-only)" || exit $?
if [ -z "$SESSION_DIR" ]; then
  echo "No latest FlockFree field session found." >&2
  exit 1
fi

MARK_ARGS=("$ROOT_DIR/scripts/flockfree-mark-result.py" "$SESSION_DIR" "$CHECK_ID" "$STATUS")
MARK_ARGS+=("${ARGS[@]}")
if [ "$NO_SUMMARIZE" -eq 0 ]; then
  MARK_ARGS+=(--summarize)
fi

"${MARK_ARGS[@]}"
echo
echo "Updated latest session: $SESSION_DIR"
echo "Report: $SESSION_DIR/field-session-report.txt"
