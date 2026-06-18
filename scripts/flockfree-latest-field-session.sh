#!/usr/bin/env bash
set -u

OUT_ROOT="${OUT_ROOT:-logs/flockfree-field-session}"
SESSION_DIR=""
PATH_ONLY=0

usage() {
  cat <<'USAGE'
Show the latest FlockFree field-test session bundle.

Usage:
  scripts/flockfree-latest-field-session.sh [options]

Options:
      --session-dir DIR     Show a specific session directory instead of latest.
      --path-only           Print only the selected session directory.
      --self-check          Run a local self-check without using real logs.
  -h, --help                Show this help.
USAGE
}

run_self_check() {
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/logs/flockfree-field-session/20260101-010101"
  session="$tmp/logs/flockfree-field-session/20260101-010101"
  cat > "$session/session-summary.txt" <<'EOF'
FlockFree field-session summary
Readiness: READY
Crash evidence: none found
EOF
  echo "report" > "$session/field-session-report.txt"
  echo "commands" > "$session/manual-result-commands.txt"
  path_output="$(OUT_ROOT="$tmp/logs/flockfree-field-session" "$0" --path-only)" || return 1
  case "$path_output" in
    *20260101-010101*) ;;
    *)
      echo "self-check failed: --path-only selected unexpected session: $path_output" >&2
      return 1
      ;;
  esac
  summary_output="$(OUT_ROOT="$tmp/logs/flockfree-field-session" "$0")" || return 1
  case "$summary_output" in
    *"FlockFree latest field session"*READY*"manual-result-commands.txt"*) ;;
    *)
      echo "self-check failed: summary output missing expected content" >&2
      return 1
      ;;
  esac
  echo "FlockFree latest field-session self-check passed."
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --session-dir)
      [ "$#" -ge 2 ] || { echo "missing value for $1" >&2; exit 64; }
      SESSION_DIR="$2"
      shift 2
      ;;
    --path-only)
      PATH_ONLY=1
      shift
      ;;
    --self-check)
      run_self_check
      exit 0
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

if [ -z "$SESSION_DIR" ]; then
  if [ ! -d "$OUT_ROOT" ]; then
    echo "No FlockFree field-session directory found: $OUT_ROOT" >&2
    exit 1
  fi
  SESSION_DIR="$(find "$OUT_ROOT" -mindepth 1 -maxdepth 1 -type d -exec test -f '{}/field-session-report.txt' ';' -print \
    | sort | tail -n 1)"
fi

if [ -z "$SESSION_DIR" ] || [ ! -d "$SESSION_DIR" ]; then
  echo "No FlockFree field session found." >&2
  exit 1
fi

REPORT="${SESSION_DIR}/field-session-report.txt"
SUMMARY="${SESSION_DIR}/session-summary.txt"
COMMANDS="${SESSION_DIR}/manual-result-commands.txt"
PROMPTS="${SESSION_DIR}/manual-test-prompts.txt"
RESULTS="${SESSION_DIR}/manual-test-results.tsv"
AREAS="${SESSION_DIR}/test-area-suggestions.txt"

if [ "$PATH_ONLY" -eq 1 ]; then
  printf '%s\n' "$SESSION_DIR"
  exit 0
fi

echo "FlockFree latest field session"
echo "=============================="
echo "Session: $SESSION_DIR"
echo "Report: $REPORT"
[ -f "$SUMMARY" ] && echo "Summary: $SUMMARY"
[ -f "$COMMANDS" ] && echo "Manual marker commands: $COMMANDS"
[ -f "$PROMPTS" ] && echo "Manual prompts: $PROMPTS"
[ -f "$RESULTS" ] && echo "Manual result sheet: $RESULTS"
[ -f "$AREAS" ] && echo "Test-area suggestions: $AREAS"
echo

if [ -f "$SUMMARY" ]; then
  cat "$SUMMARY"
else
  echo "No session-summary.txt found. Start with $REPORT"
fi

if [ -f "$COMMANDS" ]; then
  echo
  echo "To mark a visual result, open or run commands from:"
  echo "  $COMMANDS"
fi
