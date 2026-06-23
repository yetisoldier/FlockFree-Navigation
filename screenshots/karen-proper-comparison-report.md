# FlockFree Proper Device Validation and Google Maps Comparison

Date: 2026-06-23, Moto g stylus 2023 over ADB `192.168.1.139:38865`.

## Scope And Build Evidence

- Repo: `/home/yetisoldier/projects/FlockFree-Navigation`
- Commit under test: `b54a01c0c5e58a1c678527b98a5284dffff15ccd` on `master` (`feat: polish improvements from Google Maps comparison report`).
- Changes under test from `git show --stat`: FlockFree renderer, speed limit drawable polish, traffic legend layout, `map_hud_top.xml`, `MapActivity`, `FlockFreeIncidentLayer`, `FlockFreeNavigationAssistant`, `FlockFreePlugin`, and `TrafficRoutingHelper`.
- `git diff --check`: clean.
- Worktree before screenshot/report artifacts: clean for tracked files (`git status --short --untracked-files=no`).
- Build command: `./gradlew :OsmAnd:assembleGplayFreeLegacyFatDebug`
- Build result: success.
- APK installed: `OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk` (`178575188` bytes, mtime `2026-06-23 08:33:51 -0500`).
- Install command: `adb -s 192.168.1.139:38865 install -r .../OsmAnd-gplayFree-legacy-fat-debug.apk`
- Install result: success.
- Installed package: `com.yetiwurks.flockfree`, `versionName=5.4.0`, `versionCode=5399`, `lastUpdateTime=2026-06-23 08:48:19`.

## Device And App Presence

Observed with ADB:

- Device: `192.168.1.139:38865`, `moto_g_stylus__2023_`, product/device `gnevan`.
- FlockFree package installed: `com.yetiwurks.flockfree`.
- Google Maps package installed: `com.google.android.apps.maps`.
- Launcher screenshot confirms FlockFree and Maps icons are both on the home screen next to each other: `proper-comparison/launcher-home-icons.png`.
- Google Maps activity resolved to `com.google.android.apps.maps/com.google.android.maps.MapsActivity`.
- FlockFree activity resolved to `com.yetiwurks.flockfree/net.osmand.plus.activities.FlockFreeSplashActivity`.

## TomTom And FlockFree State

Observed evidence:

- TomTom setting exists in FlockFree app data: `PREF_FILE_WITH_TOMTOM=net.osmand.settings.xml`.
- TomTom API key is configured. I only checked configured/not configured and did not print the key.
- `traffic_routing_enabled` is set to `true`.
- FlockFree traffic summary persisted in device app state: `146 live, 0 no data, 12 samples`.
- Stored last routing summary: `Traffic routing applied: 8 road speed weights used.`
- FlockFree active navigation screen showed the live traffic legend (`Light`, `Moderate`, `Heavy`).

Inference:

- Traffic flow data is being sampled and used by FlockFree route logic, because the app state reports live samples and applied road speed weights.
- Visual route traffic coloring is not yet obvious enough in active navigation: the route remained primarily blue while the legend was visible.

## FlockFree Runtime Validation

Observed evidence:

- FlockFree launched to `MapActivity` after install.
- ADB process state showed `com.yetiwurks.flockfree` as top activity with `NavigationService` running.
- `logcat -c` was run before launch; after launch and validation I did not find `FATAL EXCEPTION`, `AndroidRuntime`, or app crash output for the current FlockFree run.
- Active route/navigation was available from `My Position` to `Minneapolis`.
- Route details showed `20.2 mi`, `39 min`, ETA around `9:32 AM`.
- Navigation screen showed upcoming turn guidance on Isanti St NE and a traffic legend.

Observed blockers/issues:

- Incident requests were attempted but failed: `TomTomIncidentProvider TomTom incident details request failed with HTTP 400`.
- I did not observe incident markers on the map during this pass.
- Current speed limit was not visible in FlockFree active navigation or route details, even with navigation running. The device was stationary and location state appeared stale, so this may be partly state-dependent, but Google Maps showed a `65` speed limit in the same area during its active navigation flow.

## Google Maps Direct Device Comparison

Observed evidence:

- Google Maps was launched on the same device, not checked through web screenshots.
- Static day map captured in Blaine: `proper-comparison/google-maps-static-current.png`.
- Dark/night attempt captured after `adb shell cmd uimode night yes`: `proper-comparison/google-maps-static-dark-attempt.png`.
- The dark attempt did not force Google Maps into a dark map; it stayed light and zoomed out. I restored UI mode with `cmd uimode night no`.
- Same route area/destination captured with Google Maps URL intent:
  - Origin: `45.183923,-93.221759` / displayed as `1918 118th Ave NE, Blaine, MN 55449`.
  - Destination: `Minneapolis`.
  - Route preview: `29 min`, `18 mi`, "Best route, despite the usual traffic".
  - Screenshot: `proper-comparison/google-maps-route-preview.png`.
- Google Maps route preview/navigation captured:
  - Preview mode screenshot: `proper-comparison/google-maps-navigation-preview.png`.
  - Active navigation screenshot after permissions: `proper-comparison/google-maps-active-navigation-with-location.png`.
  - Active navigation showed `28 min`, `18 mi`, ETA `9:27 AM`, a prominent route line, and a visible `65` speed limit sign plus current speed placeholder (`-- mph`).

Inference:

- Google Maps presents traffic state more directly: route colors and travel-time wording make traffic obvious without opening a separate settings/status surface.
- Google Maps exposes the speed limit during active navigation in this test state; FlockFree did not.

## Screenshot Inventory

- `proper-comparison/launcher-home-icons.png` - Home screen with FlockFree and Maps icons.
- `proper-comparison/flockfree-launch-map.png` - FlockFree launch map after install.
- `proper-comparison/flockfree-route-navigation-traffic-legend.png` - FlockFree active navigation with traffic legend.
- `proper-comparison/flockfree-route-details-expanded.png` - FlockFree route details from My Position to Minneapolis.
- `proper-comparison/flockfree-route-after-google-stopped.png` - FlockFree route after Google Maps was force-stopped.
- `proper-comparison/google-maps-static-current.png` - Google Maps static day map.
- `proper-comparison/google-maps-static-dark-attempt.png` - Google Maps dark-mode attempt; remained light.
- `proper-comparison/google-maps-route-preview.png` - Google Maps route overview with traffic-colored alternates.
- `proper-comparison/google-maps-navigation-preview.png` - Google Maps route preview.
- `proper-comparison/google-maps-active-navigation-with-location.png` - Google Maps active navigation with speed limit visible.

Additional intermediate screenshots are also in `proper-comparison/` for permission dialogs and transition states.

## Commands And Tools Used

- `git rev-parse HEAD`
- `git log -1 --oneline --decorate`
- `git diff --check`
- `git show --stat --oneline --decorate HEAD`
- `./gradlew :OsmAnd:assembleGplayFreeLegacyFatDebug`
- `adb devices -l`
- `adb install -r`
- `adb shell am start`, `am force-stop`, `pm list packages`, `cmd package resolve-activity`
- `adb shell run-as com.yetiwurks.flockfree ...` for masked TomTom/config state checks
- `adb logcat -c`, `adb logcat -d -v time`
- `adb exec-out screencap -p`
- `adb shell input tap`
- `adb shell cmd uimode night yes/no`
- `adb shell dumpsys package`, `dumpsys activity processes`, `dumpsys location`, `dumpsys window`
- `uiautomator dump` was attempted, but it sometimes returned stale/idle-timeout data; screenshots were treated as the primary UI evidence.

## Prioritized Improvements

1. Fix TomTom incident request failures. The device has a configured key, but incident fetches returned HTTP 400 and no incident markers were observed.
2. Make route traffic coloring visually legible in FlockFree navigation. The data is present (`146 live`, `12 samples`), but the route still reads as a normal blue route.
3. Surface the traffic summary in the UI. The useful summary exists in app state but was not visible in the active navigation/route details screens I captured.
4. Show speed limit during active navigation when data is available, or clearly document why it is hidden. Google Maps showed `65`; FlockFree did not show a speed-limit badge.
5. Keep the traffic legend, but anchor it to visible colored route data. A legend without visible segment colors feels disconnected.
6. Improve dark/day parity. FlockFree rendered a readable dark map; Google stayed in light mode during the dark attempt, but FlockFree should still support clear day captures and avoid relying on system state surprises.

