# FlockFree Morning Test Plan

Goal: prove the debug APK installs over Wi-Fi ADB, launches as FlockFree, and exposes the current camera-awareness MVP without chasing unfinished features.

Current status: APK packaging is working for the `gplayFreeLegacyFatDebug` flavor. The current verified APK installed successfully on the Moto G Stylus and launched into the FlockFree first-run screen. It includes the route-summary hook and exposed FlockFree plugin settings screen. Source now includes newer camera indexing, experimental two-pass camera avoidance, OSM editor tag-prefill reporting, cache-backed route startup, and a settings-driven CYD BLE scan/status/simulation path after that APK, so rebuild before testing those newer features.

## Setup

- Phone and workstation are on the same Wi-Fi network.
- USB debugging is already authorized.
- `../resources` exists beside this repo.
- Android SDK is available at `$HOME/Android/Sdk`.

## Build

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
- [ ] Wait up to 2 minutes on Wi-Fi for camera data cache/download.
- [ ] Zoom to 10+ in an area expected to have ALPR cameras.
- [ ] Camera dots appear on the map.
- [ ] At zoom 15+, short vendor labels appear where camera data has a known brand.
- [ ] Tapping a camera opens the `ALPR Camera` details dialog.
- [ ] Long-press or use map context at a location and confirm `Add ALPR Camera` is present.
- [ ] Select an ALPR brand preset and confirm OsmAnd's POI editor opens with the surveillance/ALPR tags present in the advanced tag view.
- [ ] Open plugin/settings surfaces and confirm the FlockFree settings screen is visible.
- [ ] Confirm the FlockFree settings screen shows a `Camera data` status row after camera data loads.
- [ ] Toggle the camera layer preference if reachable, then return to the map and confirm the layer hides/shows after refresh.
- [ ] Enable camera avoidance, calculate a route, and confirm FlockFree shows a route camera summary toast.
- [ ] On an offline OsmAnd route through a known camera corridor, compare the route with camera avoidance off versus on and look for a one-pass reroute around camera-adjacent road objects.
- [ ] Open the FlockFree CYD hardware settings, enable `CYD BLE`, and tap `Scan and connect CYD` with the CYD powered and advertising `CYD-Flock-You`.
- [ ] If the CYD connects, tap `Request CYD status` and confirm the `CYD status` row updates with device, GPS, SD, detection, and radio scan details.
- [ ] Tap `Simulate CYD detection` and confirm the status row updates with a detection summary or the app shows `CYD detection received`.

## Known Non-Goals For This Morning

- Do not expect camera avoidance to work for BRouter or online routing. The experimental reroute path only applies to OsmAnd offline vector routing.
- Do not expect camera avoidance to be subtle. It blocks whole route road objects and falls back to the original route if the avoided route fails.
- Do not expect a polished CYD foreground service or pending-review workflow yet. Current source can scan/connect/request status/simulate from settings, but detections are surfaced as status/toast only.
- Do not expect offline first-run camera data before the GeoJSON download succeeds.
- Do not expect polished widgets, quick actions, or a final settings UI beyond the exposed MVP preferences.

## Useful Diagnostics

For a repeatable no-Gradle snapshot from the Moto over Wi-Fi ADB:

```bash
cd /home/yetisoldier/projects/FlockFree-Navigation
scripts/flockfree-moto-diagnostics.sh
```

The script defaults to the last verified Moto Wi-Fi ADB endpoint, `192.168.1.139:5555`, and writes local-only artifacts under `logs/flockfree-diagnostics/YYYYMMDD-HHMMSS/`. It checks `adb` device state, whether `com.yetiwurks.flockfree` is installed, current focused activity, app PID/process state, package metadata, and a filtered logcat snapshot for `FlockFree`, `CameraData`, `FATAL`, `AndroidRuntime`, and `com.yetiwurks.flockfree`. Start with `summary.txt`, then inspect `package-state.txt`, `current-activity.txt`, `process-state.txt`, and `logcat-flockfree-camera-fatal.txt`.

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

Pass condition: the app launches, reaches the map, does not crash, camera data indexes, the add-camera flow pre-fills the OSM editor with ALPR tags, the CYD settings path can connect or fail cleanly with a clear status, and the experimental offline reroute either avoids a camera-adjacent road or safely falls back to the original route.
