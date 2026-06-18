# FlockFree Morning Test Plan

Goal: prove the debug APK installs over Wi-Fi ADB, launches as FlockFree, and exposes the current camera-awareness MVP without chasing unfinished features.

Current status: APK packaging is working for the `gplayFreeLegacyFatDebug` flavor. The last verified APK installed successfully on the Moto G Stylus and launched to the map. Current source includes SQLite-backed camera persistence, bundled first-use camera seed fallback, camera-data source/freshness diagnostics, experimental two-pass camera avoidance, profile-persisted applied/fallback/skipped route diagnostics, movement/navigation camera alerts with profile-persisted last-check status, OSM editor tag-prefill reporting with map-center draft action and profile-persisted report-draft status, cache/database-backed route startup, a settings-driven CYD BLE scan/status/simulation path, CYD auto-reconnect on map resume, a CYD foreground service source path with permission-gated background scan restart and Android `connectedDevice` service type, phone GPS streaming to CYD, local CYD simulation from phone/OsmAnd GPS or current map center when hardware is absent, and persisted CYD detection map/review candidates. Run `scripts/flockfree-user-build-install.sh` before morning feature testing so the installed APK matches the latest source.

## Setup

- Phone and workstation are on the same Wi-Fi network.
- USB debugging is already authorized.
- `../resources` exists beside this repo.
- Android SDK is available at `$HOME/Android/Sdk`.

## Build

If Wi-Fi ADB is currently unreachable, recover the phone connection first:

```bash
cd /home/yetisoldier/projects/FlockFree-Navigation
scripts/flockfree-adb-recover.sh
```

If recovery still reports `No route to host` or an unknown ADB state, wake the phone, confirm it is still on the same Wi-Fi network, confirm Wireless debugging is enabled, copy the current IP:port from the phone, and rerun with `--serial PHONE_IP:PORT`.
Review `adb-mdns-after.txt` and `ip-neigh-after.txt` in the recovery bundle if the phone may have changed Wireless debugging port or IP.

For the easiest manual path, run:

```bash
cd /home/yetisoldier/projects/FlockFree-Navigation
scripts/flockfree-user-build-install.sh
```

That script builds the current APK, copies it to `build-artifacts/`, writes `build-artifacts/FlockFree-build-info.txt` with source commit, clean/dirty state, SHA-256, and signature status, installs it on the default Moto Wi-Fi ADB endpoint, launches it, and captures diagnostics.

Source-only checks that do not run Gradle:

```bash
scripts/flockfree-source-checks.sh
```

Full no-Gradle readiness pass against the already installed APK:

```bash
scripts/flockfree-morning-readiness.sh
```

Start with the generated `logs/flockfree-readiness/.../readiness-report.txt`. The report compares `build-artifacts/FlockFree-build-info.txt` against the current repo, states whether the installed APK is app-code current or whether app/runtime paths changed after the last APK build, and ends with a `Readiness verdict`.

Timed no-Gradle evidence capture while performing the manual checklist:

```bash
scripts/flockfree-field-test-session.sh
```

Start with the generated `logs/flockfree-field-session/.../field-session-report.txt`. It includes a readiness gate, manual test prompts, `test-area-suggestions.txt` map anchors from the bundled seed, `manual-test-results.tsv` for PASS/FAIL/SKIP notes, ready-made `manual-result-commands.txt` marker commands for that session, filtered logcat from the test window, post-session diagnostics, and a `session-summary.txt` evidence summary with explicit crash status, manual results, and missing manual checks.

To reopen the newest field-session report path, summary, and marker-command file:

```bash
scripts/flockfree-latest-field-session.sh
```

If the newest run stopped before reaching the phone, reopen the latest field session with live ADB/package evidence:

```bash
scripts/flockfree-latest-field-session.sh --latest-phone-evidence
```

To print only the ready-made marker commands for the newest field session:

```bash
scripts/flockfree-latest-field-session.sh --commands-only
```

To print only the remaining TODO/FAIL proof rows for the newest field session:

```bash
scripts/flockfree-latest-field-session.sh --todo-only
```

If that session predates the current source, the helper prints the session commit, current `HEAD`, and a rebuild/install warning. For older nearby-alert and CYD result sheets, it also notes that current source can use `Check map center alert` and phone/OsmAnd GPS or map-center local simulation after the latest APK is installed.

To mark the newest field session directly after a visual/manual check:

```bash
scripts/flockfree-mark-latest-result.sh route_avoidance PASS --notes "Avoidance applied toast observed"
```

Offline camera-dense route-test anchors:

```bash
scripts/flockfree-suggest-test-areas.py --limit 10 --radius-km 80
```

Use the printed `Map anchor` coordinates to pick areas for camera marker, nearby-alert, and route-avoidance checks.

Manual equivalent:

```bash
cd /home/yetisoldier/projects/FlockFree-Navigation

ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK=$HOME/Android/Sdk \
  ./gradlew :OsmAnd:assembleGplayFreeLegacyFatDebug \
  -x test --no-daemon --max-workers=1
```

Find the APK:

```bash
find OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug -type f -name '*.apk' -print
```

Expected current APK:

```text
OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk
```

## Install

```bash
adb devices
adb connect PHONE_IP:5555
adb install -r PATH_FROM_FIND_COMMAND.apk
adb shell monkey -p com.yetiwurks.flockfree 1
```

## Checklist

- [ ] `adb devices` shows the phone as `device`, not `unauthorized` or `offline`.
- [ ] APK installs without `INSTALL_FAILED_*`.
- [ ] App launches and appears as FlockFree.
- [ ] First-run permissions/wizard do not block reaching the map.
- [ ] Download or open a local map area if OsmAnd asks for one.
- [ ] Wait up to 2 minutes for camera data to load from cache, bundled seed, or network.
- [ ] Zoom to 10+ in an area expected to have ALPR cameras.
- [ ] Camera dots appear on the map.
- [ ] At zoom 15+, short vendor labels appear where camera data has a known brand.
- [ ] Tapping a camera opens the `ALPR Camera` details dialog.
- [ ] Move the map to a suggested camera-dense anchor, tap `Draft report at map center`, or long-press/use map context at a location and confirm `Add ALPR Camera` is present.
- [ ] Select an ALPR brand preset and confirm OsmAnd's POI editor opens with the surveillance/ALPR tags present in the advanced tag view.
- [ ] Reopen FlockFree settings and confirm `Last report draft` reports the editor-opened or manual-tag-fallback path for that report attempt; restart the app once and confirm the row still holds the result.
- [ ] Open plugin/settings surfaces and confirm the FlockFree settings screen is visible.
- [ ] Confirm the FlockFree settings screen shows a `Camera data` status row after camera data loads, including source `database`, `cache`, `bundled seed`, or `network`, plus last-refresh freshness or `Refresh due`.
- [ ] After one successful data load, restart the app and confirm the `Camera data` status row can report source `database`.
- [ ] Tap `Refresh camera data` once on Wi-Fi and confirm the status switches to loading, then refreshes in place or returns to the indexed camera count.
- [ ] Toggle the camera layer preference if reachable, then return to the map and confirm the layer hides/shows after refresh.
- [ ] Confirm `Nearby camera alerts`, `Alert distance`, `Last alert check`, and `Check map center alert` are present.
- [ ] Move the map to a suggested camera-dense anchor, tap `Check map center alert`, and confirm FlockFree updates `Last alert check` with triggered/no-camera/loading/disabled status. A real navigation or movement check can still confirm the live GPS path later.
- [ ] Restart the app once and confirm `Last alert check` still holds the result.
- [ ] Enable camera avoidance, calculate a route, and confirm FlockFree shows a route camera summary toast with `Avoidance applied`, `Avoidance fallback`, or an explicit skipped reason.
- [ ] Reopen FlockFree settings and confirm `Last route check` preserves the same route summary/status after the toast disappears; restart the app once and confirm the row still holds the result.
- [ ] On an offline OsmAnd route through a known camera corridor, compare the route with camera avoidance off versus on and look for a one-pass reroute around camera-adjacent road objects.
- [ ] If a route corridor is not obvious, run `scripts/flockfree-suggest-test-areas.py --limit 10 --radius-km 80` and use one of the printed `Map anchor` coordinates as the route/map test area.
- [ ] Run `scripts/flockfree-moto-permission-primer.sh` before CYD/GPS testing, then confirm the generated `summary.txt` shows location and Bluetooth permissions granted.
- [ ] Open the FlockFree CYD hardware settings, enable `CYD BLE`, and tap `Scan and connect CYD` with the CYD powered and advertising `CYD-Flock-You`.
- [ ] If the CYD connects, tap `Request CYD status` and confirm the `CYD status` row refreshes in place with device, GPS, SD, detection, and radio scan details.
- [ ] Relaunch or leave/return to the map with `CYD BLE` still enabled and confirm FlockFree starts scanning again without revisiting the CYD settings screen.
- [ ] Background FlockFree with `CYD BLE` enabled and confirm Android shows the FlockFree CYD foreground notification. If Bluetooth permissions are already granted, confirm the CYD status eventually shows scanning/ready behavior without reopening settings. The previous APK proved the foreground-service path, but current source now uses `connectedDevice`, so rebuild before treating this latest service type as proven.
- [ ] After FlockFree has a valid phone GPS fix, wait roughly one second and confirm the `CYD status` row says `Phone GPS sent ... seconds ago` when connected or `Phone GPS ready ... seconds ago` before hardware is connected.
- [ ] Tap `Request CYD status` and confirm the CYD itself reports phone GPS as available.
- [ ] Tap `Simulate CYD detection`; with hardware connected it should request `FYSIM`, and without hardware it should create a local test marker from phone/OsmAnd GPS or the current map center.
- [ ] Return to the map and confirm a GPS-backed CYD detection appears as a cyan diamond `CYD` marker.
- [ ] Tap the CYD marker and choose `Review as ALPR camera`; confirm the normal ALPR report dialog opens at the detection location.
- [ ] Relaunch the app and confirm recent CYD detection markers are restored before clearing them.
- [ ] Use `Clear CYD detections` before a real drive if old bench-test markers are still on the map.

## Known Non-Goals For This Morning

- Do not expect camera avoidance to work for BRouter or online routing. The experimental reroute path only applies to OsmAnd offline vector routing.
- Do not expect camera avoidance to be subtle. It blocks whole route road objects and falls back to the original route if the avoided route fails.
- Do not expect sync. Current source has a foreground service source path with permission-gated background scan restart plus scan/connect/request status/simulate from settings, can create a local phone/map-center test marker when hardware is absent, can scan again from the map when CYD BLE is enabled and idle, and can review recent GPS-backed detections from the map; those candidates are persisted only in app-private local storage. The background service was proven on-device at an earlier source commit, but the current `connectedDevice` service-type change still needs a fresh APK proof.
- Do not expect the camera database to be the final spatial architecture. Current source has app-private SQLite persistence plus an in-memory fallback mirror; a geohash/tile store is still later work, and this source path needs fresh APK proof.
- Offline first-run camera data should now be available from the bundled seed snapshot, but `Refresh camera data` is still needed to prove live network update behavior.
- Do not expect polished widgets, quick actions, or a final settings UI beyond the exposed MVP preferences.

## Useful Diagnostics

For a repeatable no-Gradle snapshot from the Moto over Wi-Fi ADB:

```bash
cd /home/yetisoldier/projects/FlockFree-Navigation
scripts/flockfree-moto-diagnostics.sh
```

The script defaults to the last verified Moto Wi-Fi ADB endpoint, `192.168.1.139:39183`, and writes local-only artifacts under `logs/flockfree-diagnostics/YYYYMMDD-HHMMSS/`. It checks `adb` device state, whether `com.yetiwurks.flockfree` is installed, current focused activity, app PID/process state, package metadata, UI screenshot/hierarchy, app-private camera cache, camera database, CYD candidate file state, runtime location/Bluetooth/notification permission state, CYD foreground service/notification state, and a filtered logcat snapshot for `FlockFree`, `CameraData`, `FATAL`, `AndroidRuntime`, and `com.yetiwurks.flockfree`. Start with `summary.txt` for package, activity, PID, camera-cache, camera-database, CYD-store, permission readiness, foreground-service state, and logcat counts, then inspect `screenshot.png`, `ui-summary.txt`, `app-data-state.txt`, `permission-state.txt`, `service-state.txt`, `package-state.txt`, `current-activity.txt`, `process-state.txt`, and `logcat-flockfree-camera-fatal.txt`.

To grant only FlockFree's declared runtime permissions before CYD/GPS testing and immediately capture a fresh diagnostic bundle:

```bash
scripts/flockfree-moto-permission-primer.sh
```

To capture evidence while performing route, OSM reporting, and CYD checks:

```bash
scripts/flockfree-field-test-session.sh --duration 900
```

During or immediately after the walk-through, use the generated `manual-result-commands.txt` commands or edit the generated `manual-test-results.tsv` statuses to `PASS`, `FAIL`, or `SKIP` for any checks that were visually confirmed but did not produce distinctive log evidence. Rerun `scripts/flockfree-summarize-session.py logs/flockfree-field-session/...` to fold any hand-edited TSV results into the summary.

You can also update one row and refresh the summary without opening an editor:

```bash
scripts/flockfree-mark-result.py logs/flockfree-field-session/RUN_ID route_avoidance PASS --notes "Avoidance applied toast observed" --summarize
```

With `--summarize`, the helper refreshes `session-summary.txt` and updates a generated manual-result block in `field-session-report.txt`, so the report remains the primary file to read.

For the latest field session, the shorter equivalent is:

```bash
scripts/flockfree-mark-latest-result.sh route_avoidance PASS --notes "Avoidance applied toast observed"
```

To pick data-backed route-test areas from the bundled seed:

```bash
scripts/flockfree-suggest-test-areas.py --limit 10 --radius-km 80
```

If the phone IP changes:

```bash
scripts/flockfree-moto-diagnostics.sh --serial PHONE_IP:5555
```

To collect a fresh launch window without keeping old logcat noise:

```bash
scripts/flockfree-moto-diagnostics.sh --clear-logcat --launch
```

The script does not upload or send anything externally. It is safe to rerun; each run creates a new ignored `logs/` subdirectory.

Manual equivalents:

```bash
adb logcat -c
adb shell monkey -p com.yetiwurks.flockfree 1
adb logcat -d | rg -i 'flockfree|CameraData|FlockFreePlugin|AndroidRuntime|FATAL EXCEPTION'
adb shell pidof com.yetiwurks.flockfree
```

Pass condition: the app launches, reaches the map, does not crash, camera data indexes and can be manually refreshed, camera proximity alerts can be checked at the map center and preserve a `Last alert check` result, the map-center or context-menu add-camera flow pre-fills the OSM editor with ALPR tags and preserves a `Last report draft` result, the CYD settings path can connect or fail cleanly with a clear status, the status row shows recent phone GPS sends or cached GPS readiness, phone GPS reaches the CYD after connection, GPS-backed CYD detections or local phone/map-center CYD test markers become reviewable map candidates, and the experimental offline reroute visibly reports and preserves whether it applied, fell back, or skipped for a specific reason.
