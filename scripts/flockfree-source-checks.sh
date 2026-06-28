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
cyd_manager = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydHardwareManager.java").read_text()
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
    "flockfree_camera_nearest_map_center",
    "flockfree_camera_nearest_last_check",
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
    "CAMERA_NEAREST_MAP_CENTER_KEY",
    "CAMERA_NEAREST_LAST_CHECK_KEY",
    "refreshCameraData()",
    "setupNearestCameraLastCheckPreference()",
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
    "CAMERA_NEAREST_LAST_CHECK_SUMMARY",
    "DEFAULT_STATUS_SUMMARY",
]:
    if key not in prefs:
        raise SystemExit(f"missing preference constant {key}")
required_plugin_tokens = [
    "registerStringPreference(",
    "CAMERA_ROUTE_LAST_CHECK_SUMMARY.set(summary)",
    "CAMERA_ALERT_LAST_CHECK_SUMMARY.set(summary)",
    "checkCameraAlertAtMapCenter(",
    "showNearestCameraAtMapCenter(",
    "setLastNearestCameraSummary(",
    "checkCameraAlertAt(latitude, longitude",
    "getMapView().getLatitude()",
    "forceAlert",
    "flockfree_alert_last_check_map_unavailable",
    "new CameraReporter(app, CAMERA_REPORT_LAST_DRAFT_SUMMARY)",
]
missing_plugin = [item for item in required_plugin_tokens if item not in plugin]
if missing_plugin:
    raise SystemExit("missing persisted status preference wiring:\n" + "\n".join(missing_plugin))
required_fragment_tokens = [
    "ALERT_CHECK_MAP_CENTER_KEY",
    "plugin.showNearestCameraAtMapCenter(getMapActivity())",
    "plugin.checkCameraAlertAtMapCenter(getMapActivity())",
    "REPORT_MAP_CENTER_KEY",
    "showAddCameraDialogAtMapCenter(getMapActivity())",
    "AndroidUtils.requestBLEPermissions(mapActivity)",
    "AndroidUtils.requestNotificationPermissionIfNeeded(mapActivity)",
    "return false;",
]
missing_fragment = [item for item in required_fragment_tokens if item not in fragment]
if missing_fragment:
    raise SystemExit("missing map-center settings wiring:\n" + "\n".join(missing_fragment))
required_reporter_tokens = [
    "CommonPreference<String> lastReportDraftSummaryPreference",
    "lastReportDraftSummaryPreference.get()",
    "lastReportDraftSummaryPreference.set(summary)",
    "showAddCameraDialogAtMapCenter(",
    "flockfree_report_last_draft_map_unavailable",
    "mapActivity.getMapView().getLatitude()",
]
missing_reporter = [item for item in required_reporter_tokens if item not in reporter]
if missing_reporter:
    raise SystemExit("missing persisted report draft wiring:\n" + "\n".join(missing_reporter))
required_cyd_tokens = [
    "startScanAndConnectFromService(",
    "startScanAndConnectWithGrantedPermissions(",
    "createScanFilters()",
    "ScanFilter.Builder()",
    "setServiceUuid(new ParcelUuid(CydBleUartClient.UART_SERVICE_UUID))",
    "setDeviceName(CydBleUartClient.DEFAULT_DEVICE_NAME_PREFIX)",
    "scanTimeoutRunnable",
    "handler.removeCallbacks(scanTimeoutRunnable)",
    "handler.postDelayed(scanTimeoutRunnable",
    "simulateLocalDetection(",
    "getLocalSimulationFix(",
    "map-center-local-test",
    "rememberLastKnownLocationIfAvailable()",
    "getLastStaleKnownLocation()",
    "activity.getMapView().getLatitude()",
    "flockfree_cyd_local_simulated_detection",
    "flockfree_cyd_local_location_unavailable",
    "flockfree_cyd_phone_gps_ready",
]
missing_cyd = [item for item in required_cyd_tokens if item not in cyd_manager]
if missing_cyd:
    raise SystemExit("missing local CYD simulation wiring:\n" + "\n".join(missing_cyd))
if "simulateDetection(getMapActivity())" not in fragment:
    raise SystemExit("settings simulate button does not pass map activity for map-center fallback")
if "refreshData()" not in camera_data:
    raise SystemExit("missing CameraData.refreshData()")
if '"flockfree/cameras.geojson"' not in camera_data:
    raise SystemExit("missing packaged bundled camera seed asset path")
print("preference wiring ok")
PY

log "Camera database checks"
python3 - <<'PY'
from pathlib import Path

camera_data = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/CameraData.java").read_text()
camera_db_path = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/CameraDatabaseHelper.java")
camera_db = camera_db_path.read_text()
strings = Path("OsmAnd/res/values/strings.xml").read_text()
readme = Path("README.md").read_text()
handoff = Path("docs/OVERNIGHT-HANDOFF.md").read_text()
data_notes = Path("docs/DATA-LAYER-NOTES.md").read_text()

required_db = [
    "extends SQLiteOpenHelper",
    "flockfree_cameras.db",
    "idx_cameras_lat_lon",
    "replaceAllCameras(",
    "getCamerasInBoundingBox(",
    "queryCamerasInBoundingBox(",
    "getCamerasNear(",
    "getAllCameras()",
    "if (left > right)",
    "COL_LAT + \" >= ?",
    "COL_LON + \" >= ?",
]
missing_db = [item for item in required_db if item not in camera_db]
if missing_db:
    raise SystemExit("missing camera database helper wiring:\n" + "\n".join(missing_db))

required_data = [
    "private final CameraDatabaseHelper databaseHelper",
    "private volatile boolean databaseReady",
    "loadFromDatabase()",
    "databaseHelper.getAllCameras()",
    "persistParsedCameras(parsed, source)",
    "databaseHelper.replaceAllCameras(parsed)",
    "DataSource.DATABASE",
    "R.string.flockfree_camera_data_source_database",
    "databaseReady = true",
    "databaseReady = false",
    "databaseHelper.getCamerasInBoundingBox(top, left, bottom, right)",
    "databaseHelper.getCamerasNear(lat, lon, radiusMeters)",
    "file.getName() + \".tmp\"",
    "Unable to replace camera cache file",
]
missing_data = [item for item in required_data if item not in camera_data]
if missing_data:
    raise SystemExit("missing CameraData SQLite persistence wiring:\n" + "\n".join(missing_data))

if "flockfree_camera_data_source_database" not in strings:
    raise SystemExit("missing database camera-data source string")

stale_phrases = [
    "not a persisted SQLite/geohash database",
    "persisted SQLite/geohash indexing is a later optimization",
    "Move camera storage to SQLite",
]
for phrase in stale_phrases:
    for path, text in {
        "README.md": readme,
        "docs/OVERNIGHT-HANDOFF.md": handoff,
        "docs/DATA-LAYER-NOTES.md": data_notes,
    }.items():
        if phrase in text:
            raise SystemExit(f"stale camera database wording in {path}: {phrase}")
print("camera database wiring ok")
PY

log "FlockFree route avoidance checks"
python3 - <<'PY'
from pathlib import Path

route_provider = Path("OsmAnd/src/net/osmand/plus/routing/RouteProvider.java").read_text()
avoidance_helper = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/CameraAvoidanceHelper.java").read_text()
plugin = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/FlockFreePlugin.java").read_text()
route_menu = Path("OsmAnd/src/net/osmand/plus/routepreparationmenu/MapRouteInfoMenu.java").read_text()
route_status_card = Path("OsmAnd/src/net/osmand/plus/routepreparationmenu/cards/FlockFreeRouteStatusCard.java").read_text()
route_status_layout = Path("OsmAnd/res/layout/flockfree_route_status_card.xml").read_text()
readme = Path("README.md").read_text()
avoidance_doc = Path("docs/FLOCK-CAMERA-AVOIDANCE-ROUTING.md").read_text()

required_route_tokens = [
    "int originalRouteCameraCount = avoidanceHelper.findCamerasNearRouteLocations(",
    "int originalRoadAssociationCount = 0;",
    "originalRoadAssociationCount += rwc.cameraCount;",
    "getFlockFreeAvoidanceRejectionReason(",
    "FLOCKFREE_MAX_AVOIDANCE_EXTRA_TIME_SECONDS = 10 * 60",
    "FLOCKFREE_MAX_AVOIDANCE_TIME_MULTIPLIER = 1.20d",
    "FLOCKFREE_MAX_AVOIDANCE_DISTANCE_MULTIPLIER = 1.25d",
    "getFlockFreeMaxAvoidanceExtraTimeSeconds(",
    "Math.max(FLOCKFREE_MAX_AVOIDANCE_EXTRA_TIME_SECONDS, percentageAllowanceSeconds)",
    "candidateCameraCount >= originalCameraCount",
    "candidateCameraCount == 0",
    "recordAvoidanceApplied(blockedIds.size(), originalRouteCameraCount,",
    "recordAvoidancePartial(blockedIds.size(),",
    "originalRouteCameraCount, originalRouteTimeSeconds",
]
missing_route = [item for item in required_route_tokens if item not in route_provider]
if missing_route:
    raise SystemExit("missing route avoidance acceptance wiring:\n" + "\n".join(missing_route))

stale_route_tokens = [
    "totalCameraCount += rwc.cameraCount",
    "avoidedCameraCount < totalCameraCount",
    "relaxedCameraCount >= totalCameraCount",
]
stale_route = [item for item in stale_route_tokens if item in route_provider]
if stale_route:
    raise SystemExit("stale route avoidance camera-count comparison tokens:\n" + "\n".join(stale_route))

required_helper_tokens = [
    "recordAvoidanceApplied(int roadCount, int originalCameraCount, int routeCameraCount",
    "lastAvoidanceCameraCount = Math.max(0, originalCameraCount - routeCameraCount);",
    "lastAvoidanceOriginalCameraCount = Math.max(0, originalCameraCount);",
    "lastAvoidanceCameraCount = Math.max(0, originalCameraCount - remainingCameraCount);",
    "lastPartialRemainingCameraCount = Math.max(0, remainingCameraCount);",
    "MapUtils.getProjectionPoint31(cameraX31, cameraY31",
    "isCameraNearRoadGeometry(",
]
missing_helper = [item for item in required_helper_tokens if item not in avoidance_helper]
if missing_helper:
    raise SystemExit("missing route avoidance helper wiring:\n" + "\n".join(missing_helper))

required_status_card_tokens = [
    "hasRouteCheckSummaryForRouteMenu()",
    "getRouteCheckSummaryForRouteMenu(",
    "getAvoidanceHelper().getRouteCameraSummaryFromLocations(routeLocations)",
    "refreshRouteInfoMenuIfVisible()",
    "mapActivity.getMapRouteInfoMenu().updateMenu()",
    "FlockFreeRouteStatusCard",
    "plugin.getRouteCheckSummaryForRouteMenu(routingHelper.getRoute())",
    "flockfree_route_status_card_title",
    "flockfree_route_status_summary",
    "ic_action_privacy_and_security",
]
status_card_text = "\n".join([plugin, route_menu, route_status_card, route_status_layout])
missing_status_card = [item for item in required_status_card_tokens if item not in status_card_text]
if missing_status_card:
    raise SystemExit("missing route status card wiring:\n" + "\n".join(missing_status_card))

required_doc_tokens = [
    "actual route exposure",
    "road association",
    "10 minutes",
    "20 percent",
    "25 percent",
    "route-check status card",
    "source metadata identifies them as Flock-related",
]
docs_text = "\n".join([readme, avoidance_doc])
missing_doc_tokens = [item for item in required_doc_tokens if item not in docs_text]
if missing_doc_tokens:
    raise SystemExit("missing route avoidance documentation updates:\n" + "\n".join(missing_doc_tokens))

print("route avoidance wiring ok")
PY

log "Diagnostics script checks"
python3 - <<'PY'
from pathlib import Path

diagnostics = Path("scripts/flockfree-moto-diagnostics.sh").read_text()
required = [
    "capture_ui_snapshot()",
    "UI (hierarchy|hierchary) dumped to",
    "window hierarchy was not dumped",
    "capture_app_data_state()",
    "capture_camera_database_summary()",
    "app-data-state.txt",
    "camera-database-summary.txt",
    "camera-database.sqlite",
    "cache/cameras.geojson",
    "databases/flockfree_cameras.db",
    "flockfree-cyd-detections.json",
    "Camera cache:",
    "Camera database:",
    "SELECT COUNT(*) FROM cameras;",
    "camera row count:",
    "CYD detection store:",
    "camera_cache_state",
    "camera_database_state",
    "cyd_store_state",
    "capture_permission_state()",
    "permission-state.txt",
    "capture_service_state()",
    "service-state.txt",
    "CYD foreground service:",
    "dumpsys activity services",
    "dumpsys notification --noredact",
    "CydBleService",
    "Location permissions:",
    "Bluetooth permissions:",
    "Notifications permission:",
    "ACCESS_FINE_LOCATION",
    "BLUETOOTH_SCAN",
    "192.168.1.139:39183",
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
    "reconnect_serial()",
    "retrying Wi-Fi ADB reconnect",
    "disconnect \"$SERIAL\"",
    "flockfree-moto-diagnostics.sh",
    "--no-diagnostics",
    "--no-launch",
]
missing_tokens = [token for token in required_primer_tokens if token not in primer]
if missing_tokens:
    raise SystemExit("permission primer missing expected behavior:\n" + "\n".join(missing_tokens))
print("permission primer wiring ok")
PY

log "CYD foreground service checks"
python3 - <<'PY'
from pathlib import Path

manifest = Path("OsmAnd/AndroidManifest.xml").read_text()
strings = Path("OsmAnd/res/values/strings.xml").read_text()
service_path = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydBleService.java")
service = service_path.read_text()
plugin = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/FlockFreePlugin.java").read_text()
fragment = Path("OsmAnd/src/net/osmand/plus/plugins/flockfree/FlockFreeSettingsFragment.java").read_text()
readme = Path("README.md").read_text()
handoff = Path("docs/OVERNIGHT-HANDOFF.md").read_text()
morning = Path("docs/MORNING-TEST-PLAN.md").read_text()

required_manifest = [
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android.permission.POST_NOTIFICATIONS",
    "net.osmand.plus.plugins.flockfree.cyd.CydBleService",
    'android:foregroundServiceType="connectedDevice"',
]
missing_manifest = [item for item in required_manifest if item not in manifest]
if missing_manifest:
    raise SystemExit("missing CYD foreground service manifest wiring:\n" + "\n".join(missing_manifest))

required_strings = [
    "flockfree_cyd_service_channel_name",
    "flockfree_cyd_service_channel_desc",
    "flockfree_cyd_service_notification_title",
    "flockfree_cyd_service_notification_text",
]
missing_strings = [item for item in required_strings if item not in strings]
if missing_strings:
    raise SystemExit("missing CYD foreground service strings:\n" + "\n".join(missing_strings))

required_service = [
    "extends Service",
    "startForeground(",
    "NotificationChannel",
    "FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE",
    "CydHardwareManager",
    "getHardwareManager()",
    "maybeStartBackgroundScan()",
    "intent != null ? intent.getStringExtra(EXTRA_ACTION) : ACTION_START",
    "maybeStartBackgroundScan();\n\t\t\treturn START_STICKY;",
    "state == CydHardwareManager.State.IDLE || state == CydHardwareManager.State.ERROR",
    "startScanAndConnectFromService(this)",
    "startForegroundService(intent)",
    "NotificationCompat.Builder",
    "R.drawable.ic_action_bluetooth",
]
missing_service = [item for item in required_service if item not in service]
if missing_service:
    raise SystemExit("missing CYD foreground service implementation tokens:\n" + "\n".join(missing_service))

required_callers = [
    "CydBleService.start(",
    "CydBleService.stop(",
]
for token in required_callers:
    if token not in plugin and token not in fragment:
        raise SystemExit(f"missing CYD foreground service caller token: {token}")

stale_doc_phrases = [
    "no foreground service yet",
    "no foreground service or sync",
    "fresh APK proof",
    "current `connectedDevice` service-type change still needs",
]
for phrase in stale_doc_phrases:
    for path, text in {
        "README.md": readme,
        "docs/OVERNIGHT-HANDOFF.md": handoff,
        "docs/MORNING-TEST-PLAN.md": morning,
    }.items():
        if phrase in text:
            raise SystemExit(f"stale foreground service wording in {path}: {phrase}")
required_doc_tokens = [
    "foreground service source path",
    "connectedDevice",
    "latest verified APK proved",
    "validated on-device",
]
docs_text = "\n".join([readme, handoff, morning])
missing_doc_tokens = [item for item in required_doc_tokens if item not in docs_text]
if missing_doc_tokens:
    raise SystemExit("missing CYD foreground service documentation tokens:\n" + "\n".join(missing_doc_tokens))
print("CYD foreground service wiring ok")
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
    "camera database is present but empty",
    "camera cache/database",
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
    "finish_early()",
    "Done with attention",
    "morning readiness gate failed",
    "Last alert check",
    "Check map center alert",
    "Last report draft",
    "Draft report at map center",
    "Map-center or live nearby camera alert behavior and Last alert check status observed",
    "Map-center or context-menu ALPR/surveillance tag prefill and Last report draft status observed",
    "local phone/map-center simulation fallback",
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
    "--latest-phone-evidence",
    "--session-dir",
    "--self-check",
    "has_phone_evidence()",
    "extract_source_commit()",
    "current_source_commit()",
    "commits_match()",
    "Session source differs from current HEAD",
    "Current CYD source note",
    "Current alert source note",
    "Check map center alert from a suggested camera-dense anchor",
    "Latest attempt has no live phone evidence",
    "Last phone-evidence session:",
]
missing = [item for item in required if item not in helper]
if missing:
    raise SystemExit("latest field-session helper missing expected behavior:\n" + "\n".join(missing))
print("latest field-session helper wiring ok")
PY

log "ADB recovery helper checks"
python3 - <<'PY'
from pathlib import Path

recover = Path("scripts/flockfree-adb-recover.sh").read_text()
required = [
    "FlockFree Wi-Fi ADB recovery",
    "adb-recovery-report.txt",
    "adb-mdns-before.txt",
    "adb-mdns-after.txt",
    "ping-host.txt",
    "ip-route-host-before.txt",
    "ip-neigh-after.txt",
    "adb-kill-server.txt",
    "adb-start-server.txt",
    "changed Wireless debugging port",
    "Recovery did not reach device state",
    "Wireless debugging",
    "flockfree-moto-diagnostics.sh",
    "--no-kill-server",
    "--no-diagnostics",
]
missing = [item for item in required if item not in recover]
if missing:
    raise SystemExit("ADB recovery helper missing expected behavior:\n" + "\n".join(missing))
print("ADB recovery helper wiring ok")
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
    "Camera database:",
    "Manual result sheet:",
    "Crash evidence:",
    "parse_manual_results(",
    "has_cyd_evidence(",
    "Phone GPS ready",
    "Local CYD test detection created",
    "FlockFree local test",
    "map-center-local-test",
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

log "Manual build/install helper checks"
python3 - <<'PY'
from pathlib import Path

helper = Path("scripts/flockfree-user-build-install.sh").read_text()
readme = Path("README.md").read_text()
morning = Path("docs/MORNING-TEST-PLAN.md").read_text()
handoff = Path("docs/OVERNIGHT-HANDOFF.md").read_text()
required = [
    "Build/install helper for local FlockFree test APKs.",
    ":OsmAnd:assembleGplayFreeOpenglFatDebug",
    "OsmAnd-gplayFree-opengl-fat-debug.apk",
    "builds the OpenGL flavor",
    "RUN_READINESS=1",
    "RUN_FIELD_SESSION=0",
    "--skip-readiness",
    "--field-session",
    "--field-duration",
    "flockfree-morning-readiness.sh",
    "Running no-Gradle readiness gate for the installed APK",
    "flockfree-field-test-session.sh",
    "Starting timed FlockFree field-test evidence session",
    "For the manual field-test evidence window",
    "FlockFree-build-info.txt",
]
missing = [item for item in required if item not in helper]
if missing:
    raise SystemExit("manual build/install helper missing expected behavior:\n" + "\n".join(missing))
doc_text = "\n".join([readme, morning, handoff])
required_doc_tokens = [
    "scripts/flockfree-user-build-install.sh --field-session",
    "no-Gradle readiness gate",
    "installed app-code is current",
]
missing_doc_tokens = [item for item in required_doc_tokens if item not in doc_text]
if missing_doc_tokens:
    raise SystemExit("manual build/install helper docs missing expected wording:\n" + "\n".join(missing_doc_tokens))
print("manual build/install helper wiring ok")
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
	scripts/flockfree-adb-recover.sh \
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
