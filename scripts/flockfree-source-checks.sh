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
    "cyd_ble_enabled",
    "flockfree_camera_data_refresh",
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
    "CYD_CLEAR_DETECTIONS_KEY",
    "setupCydStatusPreference()",
]
missing_fragments = [item for item in required_fragments if item not in fragment]
if missing_fragments:
    raise SystemExit("missing settings fragment wiring:\n" + "\n".join(missing_fragments))

for key in ["CAMERA_ALERTS_ENABLED", "CAMERA_ALERT_DISTANCE", "CYD_BLE_ENABLED"]:
    if key not in prefs:
        raise SystemExit(f"missing preference constant {key}")
if "refreshData()" not in camera_data:
    raise SystemExit("missing CameraData.refreshData()")
print("preference wiring ok")
PY

log "Script syntax checks"
bash -n scripts/flockfree-moto-diagnostics.sh scripts/flockfree-user-build-install.sh

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
