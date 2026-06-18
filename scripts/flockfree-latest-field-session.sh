#!/usr/bin/env bash
set -u

OUT_ROOT="${OUT_ROOT:-logs/flockfree-field-session}"
SESSION_DIR=""
PATH_ONLY=0
COMMANDS_ONLY=0
TODO_ONLY=0
LATEST_PHONE_EVIDENCE=0

usage() {
  cat <<'USAGE'
Show the latest FlockFree field-test session bundle.

Usage:
  scripts/flockfree-latest-field-session.sh [options]

Options:
      --session-dir DIR     Show a specific session directory instead of latest.
      --path-only           Print only the selected session directory.
      --commands-only       Print only manual-result marker commands for the session.
      --todo-only           Print remaining TODO/FAIL manual proof rows for the session.
      --latest-phone-evidence
                            Select the newest session with live phone/package evidence.
      --self-check          Run a local self-check without using real logs.
  -h, --help                Show this help.
USAGE
}

run_self_check() {
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/logs/flockfree-field-session/20260101-010101/post-diagnostics"
  session="$tmp/logs/flockfree-field-session/20260101-010101"
  cat > "$session/session-summary.txt" <<'EOF'
FlockFree field-session summary
Readiness: READY
Crash evidence: none found
EOF
  cat > "$session/post-diagnostics/summary.txt" <<'EOF'
ADB state: device
Package installed: yes
EOF
  echo "Source commit: 1111111111111111111111111111111111111111" > "$session/field-session-report.txt"
  echo "scripts/flockfree-mark-result.py \"\$SESSION_DIR\" route_avoidance PASS --notes checked --summarize" \
    > "$session/manual-result-commands.txt"
  cat > "$session/manual-test-results.tsv" <<'EOF'
check_id	status	notes
camera_data	PASS	Camera data row observed.
route_avoidance	TODO	Needs route check.
cyd	FAIL	CYD did not connect.
EOF
  mkdir -p "$tmp/logs/flockfree-field-session/20260101-020202"
  latest="$tmp/logs/flockfree-field-session/20260101-020202"
  cat > "$latest/session-summary.txt" <<'EOF'
FlockFree field-session summary
Readiness: unknown
ADB/package: unknown
EOF
  echo "Source commit: 0000000000000000000000000000000000000000" > "$latest/field-session-report.txt"
  echo "scripts/flockfree-mark-result.py \"\$SESSION_DIR\" route_avoidance PASS --notes checked --summarize" \
    > "$latest/manual-result-commands.txt"
  cat > "$latest/manual-test-results.tsv" <<'EOF'
check_id	status	notes
route_avoidance	TODO	Needs route check.
nearby_alerts	TODO	Move or navigate near a known camera.
cyd	FAIL	Use local phone-GPS simulation fallback.
EOF
  path_output="$(OUT_ROOT="$tmp/logs/flockfree-field-session" "$0" --path-only)" || return 1
  case "$path_output" in
    *20260101-020202*) ;;
    *)
      echo "self-check failed: --path-only selected unexpected session: $path_output" >&2
      return 1
      ;;
  esac
  phone_path_output="$(OUT_ROOT="$tmp/logs/flockfree-field-session" "$0" --latest-phone-evidence --path-only)" || return 1
  case "$phone_path_output" in
    *20260101-010101*) ;;
    *)
      echo "self-check failed: --latest-phone-evidence selected unexpected session: $phone_path_output" >&2
      return 1
      ;;
  esac
  summary_output="$(OUT_ROOT="$tmp/logs/flockfree-field-session" "$0")" || return 1
  case "$summary_output" in
    *"FlockFree latest field session"*"Session source differs from current HEAD"*"Latest attempt has no live phone evidence"*"manual-result-commands.txt"*) ;;
    *)
      echo "self-check failed: summary output missing expected content" >&2
      return 1
      ;;
  esac
  commands_output="$(OUT_ROOT="$tmp/logs/flockfree-field-session" "$0" --commands-only)" || return 1
  case "$commands_output" in
    *"flockfree-mark-result.py"*"route_avoidance"*) ;;
    *)
      echo "self-check failed: --commands-only output missing marker command" >&2
      return 1
      ;;
  esac
  todo_output="$(OUT_ROOT="$tmp/logs/flockfree-field-session" "$0" --todo-only)" || return 1
  case "$todo_output" in
    *"Session source differs from current HEAD"*"route_avoidance: TODO"*"nearby_alerts: TODO"*"cyd: FAIL"*"flockfree-mark-latest-result.sh"*"Current CYD source note"*"Current alert source note"*) ;;
    *)
      echo "self-check failed: --todo-only output missing remaining proof rows" >&2
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
    --commands-only)
      COMMANDS_ONLY=1
      shift
      ;;
    --todo-only)
      TODO_ONLY=1
      shift
      ;;
    --latest-phone-evidence)
      LATEST_PHONE_EVIDENCE=1
      shift
      ;;
    --self-check)
      run_self_check
      exit $?
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

find_sessions() {
  find "$OUT_ROOT" -mindepth 1 -maxdepth 1 -type d -exec test -f '{}/field-session-report.txt' ';' -print \
    | sort
}

has_phone_evidence() {
  summary_file="${1}/post-diagnostics/summary.txt"
  [ -f "$summary_file" ] || return 1
  grep -q '^ADB state: device' "$summary_file" || return 1
  grep -q '^Package installed: yes' "$summary_file" || return 1
}

extract_source_commit() {
  report_file="${1}/field-session-report.txt"
  [ -f "$report_file" ] || return 0
  sed -n 's/^Source commit: //p' "$report_file" | head -n 1
}

current_source_commit() {
  git rev-parse HEAD 2>/dev/null || true
}

commits_match() {
  session_commit="$1"
  current_commit="$2"
  [ -n "$session_commit" ] || return 1
  [ -n "$current_commit" ] || return 1
  case "$current_commit" in
    "$session_commit"*) return 0 ;;
  esac
  case "$session_commit" in
    "$current_commit"*) return 0 ;;
  esac
  return 1
}

print_source_context() {
  session_commit="$(extract_source_commit "$SESSION_DIR")"
  current_commit="$(current_source_commit)"
  if [ -n "$session_commit" ]; then
    echo "Session source: ${session_commit:0:10}"
  fi
  if [ -n "$current_commit" ]; then
    echo "Current HEAD: ${current_commit:0:10}"
  fi
  if [ -n "$session_commit" ] && [ -n "$current_commit" ] \
      && ! commits_match "$session_commit" "$current_commit"; then
    echo "Session source differs from current HEAD; rerun after the latest build/install for current-source evidence."
  fi
}

find_latest_phone_evidence_session() {
  selected=""
  while IFS= read -r candidate; do
    if has_phone_evidence "$candidate"; then
      selected="$candidate"
    fi
  done <<EOF
$(find_sessions)
EOF
  printf '%s\n' "$selected"
}

if [ -z "$SESSION_DIR" ]; then
  if [ ! -d "$OUT_ROOT" ]; then
    echo "No FlockFree field-session directory found: $OUT_ROOT" >&2
    exit 1
  fi
  if [ "$LATEST_PHONE_EVIDENCE" -eq 1 ]; then
    SESSION_DIR="$(find_latest_phone_evidence_session)"
  else
    SESSION_DIR="$(find_sessions | tail -n 1)"
  fi
fi

if [ -z "$SESSION_DIR" ] || [ ! -d "$SESSION_DIR" ]; then
  if [ "$LATEST_PHONE_EVIDENCE" -eq 1 ]; then
    echo "No FlockFree field session with live phone/package evidence found." >&2
  else
    echo "No FlockFree field session found." >&2
  fi
  exit 1
fi

REPORT="${SESSION_DIR}/field-session-report.txt"
SUMMARY="${SESSION_DIR}/session-summary.txt"
COMMANDS="${SESSION_DIR}/manual-result-commands.txt"
PROMPTS="${SESSION_DIR}/manual-test-prompts.txt"
RESULTS="${SESSION_DIR}/manual-test-results.tsv"
AREAS="${SESSION_DIR}/test-area-suggestions.txt"
LAST_PHONE_EVIDENCE_SESSION=""
if [ "$LATEST_PHONE_EVIDENCE" -eq 0 ]; then
  LAST_PHONE_EVIDENCE_SESSION="$(find_latest_phone_evidence_session)"
fi

if [ "$PATH_ONLY" -eq 1 ]; then
  printf '%s\n' "$SESSION_DIR"
  exit 0
fi

if [ "$COMMANDS_ONLY" -eq 1 ]; then
  if [ -f "$COMMANDS" ]; then
    cat "$COMMANDS"
    exit 0
  fi
  echo "No manual-result command file found: $COMMANDS" >&2
  exit 1
fi

if [ "$TODO_ONLY" -eq 1 ]; then
  if [ ! -f "$RESULTS" ]; then
    echo "No manual result sheet found: $RESULTS" >&2
    exit 1
  fi
  echo "FlockFree remaining manual proof"
  echo "=============================="
  echo "Session: $SESSION_DIR"
  print_source_context
  if [ "$LATEST_PHONE_EVIDENCE" -eq 1 ]; then
    echo "Selection: latest session with live phone/package evidence"
  elif ! has_phone_evidence "$SESSION_DIR" && [ -n "$LAST_PHONE_EVIDENCE_SESSION" ]; then
    echo "Latest attempt has no live phone evidence."
    echo "Last phone-evidence session: $LAST_PHONE_EVIDENCE_SESSION"
    echo "View it with: scripts/flockfree-latest-field-session.sh --latest-phone-evidence"
  fi
  echo "Manual result sheet: $RESULTS"
  echo
  awk '
    BEGIN {
      FS = "\t"
      found = 0
    }
    /^#/ || NF < 3 || $1 == "check_id" {
      next
    }
    $2 == "TODO" || $2 == "FAIL" {
      notes = $3
      for (i = 4; i <= NF; i++) {
        notes = notes "\t" $i
      }
      found = 1
      printf "- %s: %s - %s\n", $1, $2, notes
      printf "  Mark when proved: scripts/flockfree-mark-latest-result.sh %s PASS --notes \"<what you observed>\"\n", $1
    }
    END {
      if (!found) {
        print "No TODO or FAIL manual proof rows remain."
      }
    }
  ' "$RESULTS"
  if awk '
    BEGIN { FS = "\t"; found = 0 }
    $1 == "cyd" && ($2 == "TODO" || $2 == "FAIL") && $3 ~ /local phone-GPS/ {
      found = 1
    }
    END { exit found ? 0 : 1 }
  ' "$RESULTS"; then
    echo
    echo "Current CYD source note: after rebuilding current HEAD, local simulation can use phone/OsmAnd GPS or the current map center."
  fi
  if awk '
    BEGIN { FS = "\t"; found = 0 }
    $1 == "nearby_alerts" && ($2 == "TODO" || $2 == "FAIL") && $3 !~ /Check map center alert/ {
      found = 1
    }
    END { exit found ? 0 : 1 }
  ' "$RESULTS"; then
    echo
    echo "Current alert source note: after rebuilding current HEAD, use Check map center alert from a suggested camera-dense anchor, or verify the live GPS path during movement."
  fi
  exit 0
fi

echo "FlockFree latest field session"
echo "=============================="
echo "Session: $SESSION_DIR"
print_source_context
if [ "$LATEST_PHONE_EVIDENCE" -eq 1 ]; then
  echo "Selection: latest session with live phone/package evidence"
elif ! has_phone_evidence "$SESSION_DIR" && [ -n "$LAST_PHONE_EVIDENCE_SESSION" ]; then
  echo "Latest attempt has no live phone evidence."
  echo "Last phone-evidence session: $LAST_PHONE_EVIDENCE_SESSION"
  echo "View it with: scripts/flockfree-latest-field-session.sh --latest-phone-evidence"
fi
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
  echo
  echo "To print those commands directly:"
  echo "  scripts/flockfree-latest-field-session.sh --commands-only"
fi
