# FlockFree Morning Test Plan

Goal: prove the debug APK installs over Wi-Fi ADB, launches as FlockFree, and exposes the current camera-awareness MVP without chasing unfinished features.

Current status: APK packaging is working for the `gplayFreeLegacyFatDebug` flavor. The first verified APK installed successfully on the Moto G Stylus and launched into the FlockFree first-run screen.

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
- [ ] Open plugin/settings surfaces and confirm FlockFree settings are visible where exposed by the current OsmAnd UI.
- [ ] Toggle the camera layer preference if reachable, then return to the map and confirm the layer hides/shows after refresh.

## Known Non-Goals For This Morning

- Do not expect camera avoidance to change generated routes yet.
- Do not expect CYD BLE hardware to connect from this repo state.
- Do not expect offline first-run camera data before the GeoJSON download succeeds.
- Do not expect polished widgets, quick actions, or a final settings UI.

## Useful Diagnostics

```bash
adb logcat -c
adb shell monkey -p com.yetiwurks.flockfree 1
adb logcat -d | rg -i 'flockfree|CameraData|FlockFreePlugin|AndroidRuntime|FATAL EXCEPTION'
adb shell pidof com.yetiwurks.flockfree
```

Pass condition: the app launches, reaches the map, does not crash, and at least one FlockFree camera-layer/reporting path can be observed. Treat routing avoidance and CYD BLE as documented stubs unless separate code lands before the test.
