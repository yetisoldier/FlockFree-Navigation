#!/usr/bin/env bash
set -euo pipefail

# Source-only checks for agents and humans. This script intentionally does not run Gradle.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

log() {
	printf '\n==> %s\n' "$1"
}

find_first_jar() {
	local pattern="$1"
	find "$HOME/.gradle/caches/modules-2/files-2.1" -path "$pattern" -name '*.jar' 2>/dev/null \
		| sort -V \
		| tail -n 1
}

log "Git whitespace check"
git diff --check

log "XML parse check"
python3 - <<'PY'
import xml.etree.ElementTree as ET
for path in ["OsmAnd/res/values/strings.xml", "OsmAnd/res/xml/flockfree_preferences.xml"]:
    ET.parse(path)
print("xml ok")
PY

log "FlockFree string checks"
python3 - <<'PY'
from pathlib import Path
import re

strings_xml = Path("OsmAnd/res/values/strings.xml").read_text()
names = re.findall(r'<string name="([^"]+)"', strings_xml)
seen = set()
duplicates = []
for name in names:
    if name in seen and name.startswith("flockfree"):
        duplicates.append(name)
    seen.add(name)
if duplicates:
    raise SystemExit("duplicate FlockFree strings:\n" + "\n".join(duplicates))

strings = set(names)
missing = []
for path in Path("OsmAnd/src/net/osmand/plus/plugins/flockfree").rglob("*.java"):
    text = path.read_text(errors="ignore")
    for ref in re.findall(r'R\.string\.(flockfree_[A-Za-z0-9_]+)', text):
        if ref not in strings:
            missing.append(f"{path}:{ref}")
if missing:
    raise SystemExit("missing FlockFree string refs:\n" + "\n".join(missing))
print("flockfree string refs ok")
PY

log "Preference wiring checks"
python3 - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET

prefs_xml = Path("OsmAnd/res/xml/flockfree_preferences.xml")
fragment = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/FlockFreeSettingsFragment.java").read_text()
prefs = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/FlockFreePreferences.java").read_text()
plugin = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/FlockFreePlugin.java").read_text()
reporter = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/CameraReporter.java").read_text()
camera_data = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/CameraData.java").read_text()

root = ET.parse(prefs_xml).getroot()
android_ns = "{http://schemas.android.com/apk/res/android}"
xml_keys = {
    elem.attrib.get(android_ns + "key")
    for elem in root.iter()
    if elem.attrib.get(android_ns + "key")
}
required_xml_keys = {
    "camera_show_layer",
    "camera_avoidance_enabled",
    "camera_avoidance_radius",
    "camera_alerts_enabled",
    "camera_alert_distance",
    "flockfree_alert_last_check",
    "flockfree_reporting",
    "flockfree_report_last_draft",
    "cyd_ble_enabled",
    "flockfree_camera_data_refresh",
    "flockfree_route_last_check",
    "flockfree_cyd_status",
    "flockfree_cyd_connect",
    "flockfree_cyd_request_status",
    "flockfree_cyd_simulate_detection",
    "flockfree_cyd_clear_detections",
}
missing_xml = sorted(required_xml_keys - xml_keys)
if missing_xml:
    raise SystemExit("missing preference XML keys:\n" + "\n".join(missing_xml))

required_fragments = [
    "CAMERA_DATA_REFRESH_KEY",
    "refreshCameraData()",
    "flockfree_camera_data_loaded_source_age_summary",
    "getLastLoadedSourceLabel()",
    "getLastLoadedFreshnessLabel()",
    "startDynamicStatusRefresh()",
    "ROUTE_LAST_CHECK_KEY",
    "setupRouteLastCheckPreference()",
    "ALERT_LAST_CHECK_KEY",
    "setupAlertLastCheckPreference()",
    "REPORT_LAST_DRAFT_KEY",
    "setupReportLastDraftPreference()",
    "CYD_CLEAR_DETECTIONS_KEY",
    "setupCydStatusPreference()",
]
missing_fragments = [item for item in required_fragments if item not in fragment]
if missing_fragments:
    raise SystemExit("missing settings fragment wiring:\n" + "\n".join(missing_fragments))

for key in [
    "CAMERA_ALERTS_ENABLED",
    "CAMERA_ALERT_DISTANCE",
    "CYD_BLE_ENABLED",
    "CAMERA_ROUTE_LAST_CHECK_SUMMARY",
    "CAMERA_ALERT_LAST_CHECK_SUMMARY",
    "CAMERA_REPORT_LAST_DRAFT_SUMMARY",
    "DEFAULT_STATUS_SUMMARY",
]:
    if key not in prefs:
        raise SystemExit(f"missing preference constant {key}")
required_plugin_tokens = [
    "registerStringPreference(",
    "CAMERA_ROUTE_LAST_CHECK_SUMMARY.set(summary)",
    "CAMERA_ALERT_LAST_CHECK_SUMMARY.set(summary)",
    "new CameraReporter(app, CAMERA_REPORT_LAST_DRAFT_SUMMARY)",
]
missing_plugin = [item for item in required_plugin_tokens if item not in plugin]
if missing_plugin:
    raise SystemExit("missing persisted status preference wiring:\n" + "\n".join(missing_plugin))
required_reporter_tokens = [
    "CommonPreference<String> lastReportDraftSummaryPreference",
    "lastReportDraftSummaryPreference.get()",
    "lastReportDraftSummaryPreference.set(summary)",
]
missing_reporter = [item for item in required_reporter_tokens if item not in reporter]
if missing_reporter:
    raise SystemExit("missing persisted report draft wiring:\n" + "\n".join(missing_reporter))
if "refreshData()" not in camera_data:
    raise SystemExit("missing CameraData.refreshData()")
if '"flockfree/cameras.geojson"' not in camera_data:
    raise SystemExit("missing packaged bundled camera seed asset path")
print("preference wiring ok")
PY

log "Diagnostics script checks"
python3 - <<'PY'
from pathlib import Path

diagnostics = Path("scripts/flockfree-moto-diagnostics.sh").read_text()
required = [
    "capture_ui_snapshot()",
    "capture_app_data_state()",
    "app-data-state.txt",
    "cache/cameras.geojson",
    "flockfree-cyd-detections.json",
    "Camera cache:",
    "CYD detection store:",
    "camera_cache_state",
    "cyd_store_state",
    "capture_permission_state()",
    "permission-state.txt",
    "Location permissions:",
    "Bluetooth permissions:",
    "Notifications permission:",
    "ACCESS_FINE_LOCATION",
    "BLUETOOTH_SCAN",
]
missing = [item for item in required if item not in diagnostics]
if missing:
    raise SystemExit("missing diagnostics script wiring:\n" + "\n".join(missing))
print("diagnostics script wiring ok")
PY

log "Permission primer checks"
python3 - <<'PY'
from pathlib import Path

manifest = Path("OsmAnd/AndroidManifest.xml").read_text()
primer = Path("scripts/flockfree-moto-permission-primer.sh").read_text()
required_permissions = [
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.POST_NOTIFICATIONS",
]
missing_manifest = [permission for permission in required_permissions if permission not in manifest]
if missing_manifest:
    raise SystemExit("permission primer references permissions missing from manifest:\n" + "\n".join(missing_manifest))
missing_primer = [permission for permission in required_permissions if permission not in primer]
if missing_primer:
    raise SystemExit("permission primer missing grant commands:\n" + "\n".join(missing_primer))
required_primer_tokens = [
    "cmd appops set",
    "flockfree-moto-diagnostics.sh",
    "--no-diagnostics",
    "--no-launch",
]
missing_tokens = [token for token in required_primer_tokens if token not in primer]
if missing_tokens:
    raise SystemExit("permission primer missing expected behavior:\n" + "\n".join(missing_tokens))
print("permission primer wiring ok")
PY

log "Morning readiness wrapper checks"
python3 - <<'PY'
from pathlib import Path

readiness = Path("scripts/flockfree-morning-readiness.sh").read_text()
required = [
    "flockfree-source-checks.sh",
    "flockfree-moto-permission-primer.sh",
    "flockfree-moto-diagnostics.sh",
    "readiness-report.txt",
    "FlockFree-build-info.txt",
    "source-changes-since-build.txt",
    "app-runtime-changes-since-build.txt",
    "Installed APK app-code status:",
    "Readiness verdict:",
    "READY: source checks",
    "ATTENTION: review",
    "--skip-source-checks",
    "--skip-primer",
    "--skip-diagnostics",
]
missing = [item for item in required if item not in readiness]
if missing:
    raise SystemExit("morning readiness wrapper missing expected behavior:\n" + "\n".join(missing))
print("morning readiness wrapper wiring ok")
PY

log "Field-test session collector checks"
python3 - <<'PY'
from pathlib import Path

session = Path("scripts/flockfree-field-test-session.sh").read_text()
required = [
    "flockfree-morning-readiness.sh",
    "flockfree-moto-diagnostics.sh",
    "flockfree-suggest-test-areas.py",
    "field-session-report.txt",
    "session-summary.txt",
    "test-area-suggestions.txt",
    "manual-test-prompts.txt",
    "manual-test-results.tsv",
    "manual-result-commands.txt",
    "session-logcat-filtered.txt",
    "post-diagnostics",
    "flockfree-summarize-session.py",
    "flockfree-mark-result.py",
    "Last alert check",
    "Last report draft",
    "Nearby camera alert behavior and Last alert check status observed",
    "ALPR/surveillance tag prefill and Last report draft status observed",
    "Map anchor coordinates",
    "Suggested map anchors:",
    "--duration",
    "--skip-readiness",
    "FlockFree|CameraData|CYD|Avoidance",
    "Timed fatal crash evidence lines:",
    "grep -Ev '^(\\$|\\[|$)'",
    ]
missing = [item for item in required if item not in session]
if missing:
    raise SystemExit("field-test session collector missing expected behavior:\n" + "\n".join(missing))
print("field-test session collector wiring ok")
PY

log "Latest field-session helper checks"
python3 - <<'PY'
from pathlib import Path

helper = Path("scripts/flockfree-latest-field-session.sh").read_text()
required = [
    "FlockFree latest field session",
    "manual-result-commands.txt",
    "session-summary.txt",
    "field-session-report.txt",
    "--path-only",
    "--commands-only",
    "--todo-only",
    "--session-dir",
    "--self-check",
]
missing = [item for item in required if item not in helper]
if missing:
    raise SystemExit("latest field-session helper missing expected behavior:\n" + "\n".join(missing))
print("latest field-session helper wiring ok")
PY

log "Field-session summarizer checks"
python3 - <<'PY'
from pathlib import Path

summarizer = Path("scripts/flockfree-summarize-session.py").read_text()
required = [
    "FlockFree field-session summary",
    "Observed evidence buckets:",
    "Not observed in captured artifacts:",
    "Suggested next manual checks:",
    "Manual result sheet:",
    "Crash evidence:",
    "parse_manual_results(",
    "has_cyd_evidence(",
    "--self-check",
    "Timed fatal crash evidence lines:",
]
missing = [item for item in required if item not in summarizer]
if missing:
    raise SystemExit("field-session summarizer missing expected behavior:\n" + "\n".join(missing))
print("field-session summarizer wiring ok")
PY

log "Manual result marker checks"
python3 - <<'PY'
from pathlib import Path

marker = Path("scripts/flockfree-mark-result.py").read_text()
latest_marker = Path("scripts/flockfree-mark-latest-result.sh").read_text()
required = [
    "manual-test-results.tsv",
    "VALID_CHECKS",
    "VALID_STATUSES",
    "PASS",
    "FAIL",
    "SKIP",
    "TODO",
    "--summarize",
    "--self-check",
    "flockfree-summarize-session.py",
    "REPORT_BLOCK_BEGIN",
    "update_field_report(",
]
missing = [item for item in required if item not in marker]
latest_required = [
    "flockfree-latest-field-session.sh",
    "flockfree-mark-result.py",
    "--no-summarize",
    "--self-check",
    "Updated latest session:",
]
missing.extend(
    f"latest marker: {item}" for item in latest_required if item not in latest_marker
)
if missing:
    raise SystemExit("manual result marker missing expected behavior:\n" + "\n".join(missing))
print("manual result marker wiring ok")
PY

log "Test-area suggestion helper checks"
python3 - <<'PY'
from pathlib import Path

helper = Path("scripts/flockfree-suggest-test-areas.py").read_text()
required = [
    "OsmAnd/assets/flockfree/cameras.geojson.gz",
    "FlockFree camera-dense test areas",
    "--radius-km",
    "--cell-km",
    "--format",
    "Map anchor:",
    "FeatureCollection",
]
missing = [item for item in required if item not in helper]
if missing:
    raise SystemExit("test-area suggestion helper missing expected behavior:\n" + "\n".join(missing))
print("test-area suggestion helper wiring ok")
PY

log "Bundled camera seed check"
python3 - <<'PY'
from pathlib import Path
import gzip
import json

asset = Path("OsmAnd/assets/flockfree/cameras.geojson.gz")
if not asset.exists():
    raise SystemExit(f"missing bundled camera seed: {asset}")
with gzip.open(asset, "rt", encoding="utf-8") as f:
    data = json.load(f)
features = data.get("features")
if data.get("type") != "FeatureCollection" or not isinstance(features, list) or not features:
    raise SystemExit("bundled camera seed is not a non-empty FeatureCollection")
print(f"bundled camera seed ok: {len(features)} features, {asset.stat().st_size} compressed bytes")
PY

log "Script syntax checks"
bash -n scripts/flockfree-moto-diagnostics.sh \
	scripts/flockfree-field-test-session.sh \
	scripts/flockfree-latest-field-session.sh \
	scripts/flockfree-mark-latest-result.sh \
	scripts/flockfree-morning-readiness.sh \
	scripts/flockfree-moto-permission-primer.sh \
	scripts/flockfree-user-build-install.sh \
	scripts/flockfree-source-checks.sh

log "Python helper syntax checks"
python3 -m py_compile \
	scripts/flockfree-suggest-test-areas.py \
	scripts/flockfree-summarize-session.py \
	scripts/flockfree-mark-result.py

log "Test-area helper smoke check"
scripts/flockfree-suggest-test-areas.py --limit 2 --radius-km 80 >/dev/null

log "Field-session summarizer self-check"
scripts/flockfree-summarize-session.py --self-check >/dev/null

log "Latest field-session helper self-check"
scripts/flockfree-latest-field-session.sh --self-check >/dev/null

log "Manual result marker self-check"
scripts/flockfree-mark-result.py --self-check >/dev/null
scripts/flockfree-mark-latest-result.sh --self-check >/dev/null

log "CYD parser/store self-check"
ANNOTATION_JAR="${ANNOTATION_JAR:-$(find_first_jar '*androidx.annotation/annotation-jvm*')}"
JSON_JAR="${JSON_JAR:-$(find_first_jar '*org.json/json*')}"
if [[ -z "${ANNOTATION_JAR:-}" || -z "${JSON_JAR:-}" ]]; then
	echo "Skipping CYD parser/store self-check: annotation/json jars not found." >&2
else
	TMP_DIR="$(mktemp -d)"
	trap 'rm -rf "$TMP_DIR"' EXIT
	javac -cp "$ANNOTATION_JAR:$JSON_JAR" -d "$TMP_DIR" \
		OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydJsonUtils.java \
		OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydPairStatus.java \
		OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydDetectionCandidate.java \
		OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydMessageParser.java \
		OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydDetectionStore.java \
		OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydParserSelfCheck.java
	java -cp "$TMP_DIR:$ANNOTATION_JAR:$JSON_JAR" net.osmand.plus.plugins.flockfree.cyd.CydParserSelfCheck
fi

printf '\nAll source-only FlockFree checks passed.\n'
