# FlockFree Morning Test Plan

Goal: prove the debug APK installs over Wi-Fi ADB, launches as FlockFree, and exposes the current camera-awareness MVP without chasing unfinished features.

Current status: APK packaging is working for the `gplayFreeLegacyFatDebug` flavor. The current verified APK installed successfully on the Moto G Stylus and launched into the FlockFree first-run screen. It includes the route-summary hook and exposed FlockFree plugin settings screen. Source now includes newer camera indexing, experimental two-pass camera avoidance, and a CYD BLE scaffold after that APK, so rebuild before testing those newer features.

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
- [ ] Open plugin/settings surfaces and confirm the FlockFree settings screen is visible.
- [ ] Confirm the FlockFree settings screen shows a `Camera data` status row after camera data loads.
- [ ] Toggle the camera layer preference if reachable, then return to the map and confirm the layer hides/shows after refresh.
- [ ] Enable camera avoidance, calculate a route, and confirm FlockFree shows a route camera summary toast.
- [ ] On an offline OsmAnd route through a known camera corridor, compare the route with camera avoidance off versus on and look for a one-pass reroute around camera-adjacent road objects.

## Known Non-Goals For This Morning

- Do not expect camera avoidance to work for BRouter or online routing. The experimental reroute path only applies to OsmAnd offline vector routing.
- Do not expect camera avoidance to be subtle. It blocks whole route road objects and falls back to the original route if the avoided route fails.
- Do not expect CYD BLE hardware to connect from the UI yet. Current source has the low-level UART client/parser scaffold, not a visible pairing flow.
- Do not expect offline first-run camera data before the GeoJSON download succeeds.
- Do not expect polished widgets, quick actions, or a final settings UI beyond the exposed MVP preferences.

## Useful Diagnostics

```bash
adb logcat -c
adb shell monkey -p com.yetiwurks.flockfree 1
adb logcat -d | rg -i 'flockfree|CameraData|FlockFreePlugin|AndroidRuntime|FATAL EXCEPTION'
adb shell pidof com.yetiwurks.flockfree
```

Pass condition: the app launches, reaches the map, does not crash, camera data indexes, at least one FlockFree camera-layer/reporting path can be observed, and the experimental offline reroute either avoids a camera-adjacent road or safely falls back to the original route.
