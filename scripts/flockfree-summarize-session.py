#!/usr/bin/env python3
"""Summarize a captured FlockFree field-test session directory."""

from __future__ import annotations

import argparse
import re
import tempfile
from pathlib import Path
from typing import NamedTuple


EVIDENCE_PATTERNS = {
    "camera data": re.compile(r"CameraData|Camera data|camera cache|cameras?\.geojson", re.I),
    "route avoidance": re.compile(r"Avoidance|Last route check|route camera|camera-adjacent|reroute", re.I),
    "nearby alerts": re.compile(r"nearby camera|Nearby camera alerts|Alert distance", re.I),
    "OSM reporting": re.compile(r"Add ALPR Camera|ALPR|surveillance|EditPoi", re.I),
}
EVIDENCE_ORDER = list(EVIDENCE_PATTERNS.keys()) + ["CYD"]
CYD_EVIDENCE_PATTERN = re.compile(
    r"\b(FYSTATUS|FYGPS|FYSIM|pair_status|CYD detection received|Phone GPS sent|"
    r"Connected to CYD|CYD status|Simulate CYD detection)\b",
    re.I,
)
FATAL_CRASH_PATTERN = re.compile(r"FATAL EXCEPTION|AndroidRuntime.*FATAL EXCEPTION", re.I)
MANUAL_CHECK_LABELS = {
    "camera_data": "camera data",
    "route_avoidance": "route avoidance",
    "nearby_alerts": "nearby alerts",
    "osm_reporting": "OSM reporting",
    "cyd": "CYD",
}


class ManualResult(NamedTuple):
    check_id: str
    label: str
    status: str
    notes: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Summarize a FlockFree field-test session.")
    parser.add_argument("session_dir", nargs="?", help="logs/flockfree-field-session/... directory.")
    parser.add_argument("--self-check", action="store_true", help="Run a tiny built-in parser self-check.")
    return parser.parse_args()


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return ""


def first_match(pattern: str, text: str, default: str = "unknown") -> str:
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else default


def extract_verdict(readiness: str, field_report: str) -> str:
    for text in (field_report, readiness):
        match = re.search(r"Readiness verdict:\n\s*(READY|ATTENTION):?([^\n]*)", text, re.I)
        if match:
            detail = match.group(2).strip()
            return f"{match.group(1).upper()}{': ' + detail if detail else ''}"
    return "unknown"


def clean_log_text(text: str) -> str:
    lines = []
    for line in text.splitlines():
        if not line or line.startswith("$") or line.startswith("["):
            continue
        lines.append(line)
    return "\n".join(lines)


def present(pattern: re.Pattern[str], *texts: str) -> bool:
    return any(pattern.search(text) for text in texts if text)


def has_fatal_crash_evidence(timed_fatal_lines: str, *texts: str) -> bool:
    try:
        if int(timed_fatal_lines.strip()) > 0:
            return True
    except ValueError:
        pass
    return present(FATAL_CRASH_PATTERN, *texts)


def has_cyd_evidence(post_summary: str, ui_summary: str, session_log: str) -> bool:
    if re.search(r"^CYD detection store: present", post_summary, re.I | re.MULTILINE):
        return True
    return present(CYD_EVIDENCE_PATTERN, ui_summary, session_log)


def parse_manual_results(path: Path) -> list[ManualResult]:
    rows = []
    for line in read_text(path).splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("check_id\t"):
            continue
        parts = line.split("\t", 2)
        if len(parts) < 2:
            continue
        check_id = parts[0].strip()
        status = parts[1].strip().upper()
        notes = parts[2].strip() if len(parts) > 2 else ""
        if check_id not in MANUAL_CHECK_LABELS:
            continue
        rows.append(ManualResult(check_id, MANUAL_CHECK_LABELS[check_id], status, notes))
    return rows


def evidence_order_index(name: str) -> int:
    try:
        return EVIDENCE_ORDER.index(name)
    except ValueError:
        return len(EVIDENCE_ORDER)


def summarize(session_dir: Path) -> str:
    field_report = read_text(session_dir / "field-session-report.txt")
    readiness = read_text(session_dir / "readiness" / "readiness-report.txt")
    post_summary = read_text(session_dir / "post-diagnostics" / "summary.txt")
    ui_summary = read_text(session_dir / "post-diagnostics" / "ui-summary.txt")
    app_data = read_text(session_dir / "post-diagnostics" / "app-data-state.txt")
    session_log = clean_log_text(read_text(session_dir / "session-logcat-filtered.txt"))
    manual_results = parse_manual_results(session_dir / "manual-test-results.tsv")

    source_commit = first_match(r"^Source commit: (.+)$", field_report)
    source_state = first_match(r"^Source state: (.+)$", field_report)
    readiness_verdict = extract_verdict(readiness, field_report)
    apk_status = first_match(r"^Installed APK app-code status: (.+)$", readiness)
    adb_state = first_match(r"^ADB state: (.+)$", post_summary)
    package_state = first_match(r"^Package installed: (.+)$", post_summary)
    activity = first_match(r"^Current activity: (.+)$", post_summary)
    pid = first_match(r"^PID: (.+)$", post_summary)
    camera_cache = first_match(r"^Camera cache: (.+)$", post_summary)
    cyd_store = first_match(r"^CYD detection store: (.+)$", post_summary)
    location_permissions = first_match(r"^Location permissions: (.+)$", post_summary)
    bluetooth_permissions = first_match(r"^Bluetooth permissions: (.+)$", post_summary)
    notification_permission = first_match(r"^Notifications permission: (.+)$", post_summary)
    timed_log_lines = first_match(r"^Timed filtered logcat lines: (.+)$", field_report)
    timed_fatal_lines = first_match(r"^Timed fatal crash evidence lines: (.+)$", field_report)

    evidence_texts = (post_summary, ui_summary, app_data, session_log)
    fatal_crash_found = has_fatal_crash_evidence(timed_fatal_lines, field_report, readiness, post_summary, session_log)
    observed = []
    not_observed = []
    for name, pattern in EVIDENCE_PATTERNS.items():
        if present(pattern, *evidence_texts):
            observed.append(name)
        else:
            not_observed.append(name)
    if has_cyd_evidence(post_summary, ui_summary, session_log):
        observed.append("CYD")
    else:
        not_observed.append("CYD")
    for result in manual_results:
        if result.status == "PASS" and result.label not in observed:
            observed.append(result.label)
        elif result.status in {"FAIL", "TODO", ""} and result.label not in observed and result.label not in not_observed:
            not_observed.append(result.label)

    observed = sorted(set(observed), key=evidence_order_index)
    not_observed = [name for name in sorted(set(not_observed), key=evidence_order_index) if name not in observed]

    output = [
        "FlockFree field-session summary",
        "===============================",
        f"Session directory: {session_dir}",
        f"Source: {source_commit} ({source_state})",
        f"Readiness: {readiness_verdict}",
        f"Installed APK app-code: {apk_status}",
        f"ADB/package: {adb_state}; installed {package_state}; PID {pid}",
        f"Activity: {activity}",
        f"Camera cache: {camera_cache}",
        f"CYD store: {cyd_store}",
        f"Permissions: location {location_permissions}; Bluetooth {bluetooth_permissions}; notifications {notification_permission}",
        f"Timed logcat lines: {timed_log_lines}",
        f"Timed fatal crash evidence lines: {timed_fatal_lines}",
        f"Crash evidence: {'FOUND' if fatal_crash_found else 'none found'}",
        "",
        "Observed evidence buckets:",
    ]
    output.extend(f"- {name}" for name in observed)
    if not observed:
        output.append("- none")
    output.append("")
    output.append("Not observed in captured artifacts:")
    output.extend(f"- {name}" for name in not_observed)
    if not not_observed:
        output.append("- none")

    if fatal_crash_found:
        output.append("")
        output.append("ATTENTION: fatal crash evidence was found in the captured artifacts.")

    if manual_results:
        output.append("")
        output.append("Manual result sheet:")
        for result in manual_results:
            detail = f" - {result.notes}" if result.notes else ""
            output.append(f"- {result.label}: {result.status}{detail}")

    next_checks = []
    if "route avoidance" in not_observed:
        next_checks.append("calculate a route with camera avoidance enabled and capture the toast/settings row")
    if "OSM reporting" in not_observed:
        next_checks.append("open Add ALPR Camera and capture the editor/tag prefill")
    if "CYD" in not_observed:
        next_checks.append("connect/simulate CYD or confirm hardware was intentionally skipped")
    if "nearby alerts" in not_observed:
        next_checks.append("move/navigate near a known camera to exercise nearby alerts")
    if next_checks:
        output.append("")
        output.append("Suggested next manual checks:")
        output.extend(f"- {item}" for item in next_checks)

    return "\n".join(output) + "\n"


def self_check() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "readiness").mkdir()
        (root / "post-diagnostics").mkdir()
        (root / "field-session-report.txt").write_text(
            "Source commit: abc123\n"
            "Source state: clean\n"
            "Readiness verdict:\n"
            "  READY: source checks passed\n"
            "Timed filtered logcat lines: 4\n"
            "Timed fatal crash evidence lines: 0\n",
            encoding="utf-8",
        )
        (root / "readiness" / "readiness-report.txt").write_text(
            "Installed APK app-code status: current (no app/runtime changes since last APK build)\n",
            encoding="utf-8",
        )
        (root / "post-diagnostics" / "summary.txt").write_text(
            "ADB state: device\n"
            "Package installed: yes\n"
            "Current activity: MapActivity\n"
            "PID: 123\n"
            "Camera cache: present (10 bytes)\n"
            "CYD detection store: missing\n"
            "Location permissions: fine granted, coarse granted; device location on\n"
            "Bluetooth permissions: scan granted, connect granted; Bluetooth on\n"
            "Notifications permission: granted\n",
            encoding="utf-8",
        )
        (root / "session-logcat-filtered.txt").write_text(
            "01-01 I CameraData: loaded\n01-01 I FlockFree: Avoidance applied\n",
            encoding="utf-8",
        )
        (root / "manual-test-results.tsv").write_text(
            "check_id\tstatus\tnotes\n"
            "osm_reporting\tPASS\tEditor showed ALPR tags\n"
            "cyd\tTODO\tHardware not connected\n",
            encoding="utf-8",
        )
        result = summarize(root)
        required = [
            "Readiness: READY",
            "Installed APK app-code: current",
            "camera data",
            "route avoidance",
            "OSM reporting",
            "Manual result sheet:",
            "Timed fatal crash evidence lines: 0",
            "Crash evidence: none found",
            "connect/simulate CYD",
        ]
        missing = [item for item in required if item not in result]
        if missing:
            raise SystemExit("self-check failed; missing: " + ", ".join(missing))
    print("FlockFree session summarizer self-check passed.")
    return 0


def main() -> int:
    args = parse_args()
    if args.self_check:
        return self_check()
    if not args.session_dir:
        raise SystemExit("session_dir is required unless --self-check is used")
    session_dir = Path(args.session_dir)
    if not session_dir.exists():
        raise SystemExit(f"Session directory not found: {session_dir}")
    print(summarize(session_dir), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
