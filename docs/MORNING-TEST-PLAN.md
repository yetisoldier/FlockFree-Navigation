# FlockFree Morning Test Plan

Goal: prove the debug APK installs over Wi-Fi ADB, launches as FlockFree, and exposes the current camera-awareness MVP without chasing unfinished features.

Current status: APK packaging is working for the `gplayFreeLegacyFatDebug` flavor. The current verified APK installed successfully on the Moto G Stylus, launched to the map, and includes camera indexing, bundled first-use camera seed fallback, camera-data source/freshness diagnostics, experimental two-pass camera avoidance, visible applied/fallback/skipped route diagnostics, movement/navigation camera alerts, OSM editor tag-prefill reporting, cache-backed route startup, a settings-driven CYD BLE scan/status/simulation path, CYD auto-reconnect on map resume, phone GPS streaming to CYD, and persisted CYD detection map/review candidates.

## Setup

- Phone and workstation are on the same Wi-Fi network.
- USB debugging is already authorized.
- `../resources` exists beside this repo.
- Android SDK is available at `$HOME/Android/Sdk`.

## Build

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
- [ ] Long-press or use map context at a location and confirm `Add ALPR Camera` is present.
- [ ] Select an ALPR brand preset and confirm OsmAnd's POI editor opens with the surveillance/ALPR tags present in the advanced tag view.
- [ ] Open plugin/settings surfaces and confirm the FlockFree settings screen is visible.
- [ ] Confirm the FlockFree settings screen shows a `Camera data` status row after camera data loads, including source `cache`, `bundled seed`, or `network`, plus last-refresh freshness or `Refresh due`.
- [ ] Tap `Refresh camera data` once on Wi-Fi and confirm the status switches to loading, then refreshes in place or returns to the indexed camera count.
- [ ] Toggle the camera layer preference if reachable, then return to the map and confirm the layer hides/shows after refresh.
- [ ] Confirm `Nearby camera alerts` and `Alert distance` are present, then while navigating or moving near a known camera confirm FlockFree shows a nearby-camera toast no more than once per cooldown window.
- [ ] Enable camera avoidance, calculate a route, and confirm FlockFree shows a route camera summary toast with `Avoidance applied`, `Avoidance fallback`, or an explicit skipped reason.
- [ ] Reopen FlockFree settings and confirm `Last route check` preserves the same route summary/status after the toast disappears.
- [ ] On an offline OsmAnd route through a known camera corridor, compare the route with camera avoidance off versus on and look for a one-pass reroute around camera-adjacent road objects.
- [ ] Open the FlockFree CYD hardware settings, enable `CYD BLE`, and tap `Scan and connect CYD` with the CYD powered and advertising `CYD-Flock-You`.
- [ ] If the CYD connects, tap `Request CYD status` and confirm the `CYD status` row refreshes in place with device, GPS, SD, detection, and radio scan details.
- [ ] Relaunch or leave/return to the map with `CYD BLE` still enabled and confirm FlockFree starts scanning again without revisiting the CYD settings screen.
- [ ] After FlockFree has a valid phone GPS fix, wait roughly one second and confirm the `CYD status` row says `Phone GPS sent ... seconds ago`.
- [ ] Tap `Request CYD status` and confirm the CYD itself reports phone GPS as available.
- [ ] Tap `Simulate CYD detection` and confirm the status row updates with a detection summary or the app shows `CYD detection received`.
- [ ] Return to the map and confirm a GPS-backed CYD detection appears as a cyan diamond `CYD` marker.
- [ ] Tap the CYD marker and choose `Review as ALPR camera`; confirm the normal ALPR report dialog opens at the detection location.
- [ ] Relaunch the app and confirm recent CYD detection markers are restored before clearing them.
- [ ] Use `Clear CYD detections` before a real drive if old bench-test markers are still on the map.

## Known Non-Goals For This Morning

- Do not expect camera avoidance to work for BRouter or online routing. The experimental reroute path only applies to OsmAnd offline vector routing.
- Do not expect camera avoidance to be subtle. It blocks whole route road objects and falls back to the original route if the avoided route fails.
- Do not expect a polished CYD foreground service or sync. Current source can scan/connect/request status/simulate from settings, can scan again from the map when CYD BLE is enabled and idle, and can review recent GPS-backed detections from the map; those candidates are persisted only in app-private local storage.
- Offline first-run camera data should now be available from the bundled seed snapshot, but `Refresh camera data` is still needed to prove live network update behavior.
- Do not expect polished widgets, quick actions, or a final settings UI beyond the exposed MVP preferences.

## Useful Diagnostics

For a repeatable no-Gradle snapshot from the Moto over Wi-Fi ADB:

```bash
cd /home/yetisoldier/projects/FlockFree-Navigation
scripts/flockfree-moto-diagnostics.sh
```

The script defaults to the last verified Moto Wi-Fi ADB endpoint, `192.168.1.139:5555`, and writes local-only artifacts under `logs/flockfree-diagnostics/YYYYMMDD-HHMMSS/`. It checks `adb` device state, whether `com.yetiwurks.flockfree` is installed, current focused activity, app PID/process state, package metadata, UI screenshot/hierarchy, app-private camera cache/CYD candidate file state, and a filtered logcat snapshot for `FlockFree`, `CameraData`, `FATAL`, `AndroidRuntime`, and `com.yetiwurks.flockfree`. Start with `summary.txt`, then inspect `screenshot.png`, `ui-summary.txt`, `app-data-state.txt`, `package-state.txt`, `current-activity.txt`, `process-state.txt`, and `logcat-flockfree-camera-fatal.txt`.

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

Pass condition: the app launches, reaches the map, does not crash, camera data indexes and can be manually refreshed, camera proximity alerts fire while navigating or moving near a known camera, the add-camera flow pre-fills the OSM editor with ALPR tags, the CYD settings path can connect or fail cleanly with a clear status, the status row shows recent phone GPS sends, phone GPS reaches the CYD after connection, GPS-backed CYD detections become reviewable map candidates, and the experimental offline reroute visibly reports and preserves whether it applied, fell back, or skipped for a specific reason.
