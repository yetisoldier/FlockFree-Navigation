# FlockFree Overnight Handoff

## Repository State

- Repository: `https://github.com/yetisoldier/FlockFree-Navigation`
- Local path: `/home/yetisoldier/projects/FlockFree-Navigation`
- Branch: `master`
- Latest pushed source before the final settings handoff: `9a1b0ca9db Ignore local smoke-test logs`
- Current source includes the route camera summary hook, exposed FlockFree settings screen, camera-data spatial indexing, OSM editor tag-prefill reporting, experimental two-pass offline camera avoidance, and a low-level CYD BLE UART scaffold.

## Verified APK

- APK: `OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk`
- Synced artifact: `build-artifacts/FlockFree-gplayFree-legacy-fat-debug.apk`
- Package: `com.yetiwurks.flockfree`
- SHA-256: `aecab4bdb9f873649e90fc3b4d1f6ff8f8c673e599c0b902fecf4772ff168c73`
- Signature: verifies with APK Signature Scheme v2 using the Android debug certificate
- Phone install: succeeded over Wi-Fi ADB on `192.168.1.139:5555`
- Launch: succeeded into `net.osmand.plus.activities.MapActivity`
- Smoke log directory: `logs/flockfree-settings-smoke-20260618-000003`

## What Is Ready To Test

- FlockFree-branded OsmAnd package installs and launches.
- FlockFree first-run copy appears on the phone.
- The in-tree FlockFree plugin is registered and enabled by default.
- FlockFree has a visible plugin settings screen wired into the OsmAnd plugin settings flow.
- Camera data loader accepts the live `data.dontgetflocked.com` response, which currently uses a `.gz` URL but returns plain GeoJSON.
- Smoke log showed `CameraData Reading camera data payload; gzip=false`.
- Camera map layer and camera context-menu/reporting code are present in source.
- The `Add ALPR Camera` flow now opens OsmAnd's POI editor with the selected ALPR tag preset attached to the new node.
- Route camera summary hook:
  - `FlockFreePlugin.newRouteIsCalculated(boolean)` now watches newly calculated routes when camera avoidance is enabled.
  - `CameraAvoidanceHelper` now checks cameras against route segments instead of only route vertices.
- The hook still shows an advisory FlockFree toast after route calculation so testers can see the camera count near the final route.
- Current source adds an experimental second-pass OsmAnd offline route calculation:
  - It maps cameras near the initial route to `RouteDataObject` IDs.
  - It passes those IDs as temporary per-calculation impassable roads.
  - It does not write to the user's Avoid Roads settings.
  - It falls back to the original route if the avoided route fails.
- Camera data now builds a coarse in-memory spatial grid and the settings screen shows camera count/bucket diagnostics when loaded.
- A CYD BLE UART scaffold exists under `OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/`, including Nordic UART connection handling and parsers for `pair_status` and `detection` JSON.

## Known Limits

- Route avoidance is still experimental and only works for OsmAnd offline vector routing. It blocks whole road objects and can be coarse.
- CYD BLE has a low-level client/parser scaffold, but no UI scanner, foreground service, or pending-review workflow yet.
- Camera data is still held in memory and indexed in Java; persisted SQLite/geohash indexing is a later optimization.
- First useful camera display needs network access for the live GeoJSON download unless a cache is already present.
- The current OSM reporting helper pre-fills tags, but still needs end-to-end on-device validation before treating it as upload-ready.

## Morning First Steps

1. Open the app on the Moto G Stylus. It should already be installed as `com.yetiwurks.flockfree`.
2. Work through or skip the first-run map download flow.
3. Confirm camera data finishes loading on Wi-Fi.
4. Move/zoom to a camera-dense area and verify markers.
5. Open the plugin settings, confirm the camera-data diagnostic row, enable camera avoidance, calculate a route, and verify the route-summary toast.
6. Compare one camera-dense offline route with avoidance off and on. A successful newer build should either route around camera-adjacent road objects or fall back cleanly to the original route.

For a no-Gradle device snapshot before or after those checks, run:

```bash
scripts/flockfree-moto-diagnostics.sh
```

The collector uses Wi-Fi ADB, defaults to `192.168.1.139:5555`, writes only local ignored files under `logs/flockfree-diagnostics/`, and captures ADB state, package install state, current activity, PID, package metadata, and filtered `FlockFree` / `CameraData` / `FATAL` logcat evidence.
