# FlockFree Overnight Handoff

## Repository State

- Repository: `https://github.com/yetisoldier/FlockFree-Navigation`
- Local path: `/home/yetisoldier/projects/FlockFree-Navigation`
- Branch: `master`
- Latest pushed source before the final settings handoff: `9a1b0ca9db Ignore local smoke-test logs`
- Current handoff source includes the route camera summary hook and exposed FlockFree settings screen.

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
- Route camera summary hook:
  - `FlockFreePlugin.newRouteIsCalculated(boolean)` now watches newly calculated routes when camera avoidance is enabled.
  - `CameraAvoidanceHelper` now checks cameras against route segments instead of only route vertices.
  - The hook shows an advisory FlockFree toast. It does not alter OsmAnd route calculation yet.

## Known Limits

- Route avoidance is advisory only; it does not block roads or force recalculation.
- CYD BLE is not wired in this OsmAnd fork.
- Camera data is still held in memory and filtered in Java; SQLite/geohash indexing is a later optimization.
- First useful camera display needs network access for the live GeoJSON download unless a cache is already present.
- The current OSM reporting helper needs end-to-end validation before treating it as upload-ready.

## Morning First Steps

1. Open the app on the Moto G Stylus. It should already be installed as `com.yetiwurks.flockfree`.
2. Work through or skip the first-run map download flow.
3. Confirm camera data finishes loading on Wi-Fi.
4. Move/zoom to a camera-dense area and verify markers.
5. Open the plugin settings, enable camera avoidance, calculate a route, and verify the advisory route-summary toast.
