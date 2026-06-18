# FlockFree Overnight Handoff

## Repository State

- Repository: `https://github.com/yetisoldier/FlockFree-Navigation`
- Local path: `/home/yetisoldier/projects/FlockFree-Navigation`
- Branch: `master`
- Latest pushed source commit at handoff: `a8fac86046 Add route camera summary hook`

## Verified APK

- APK: `OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk`
- Package: `com.yetiwurks.flockfree`
- SHA-256: `f578dc2f5b14e063ceb53e0171a222a5ef4a1377d276b00e3352a56663ed1791`
- Phone install: succeeded over Wi-Fi ADB on `192.168.1.139:5555`
- Launch: succeeded into `net.osmand.plus.activities.MapActivity`
- Smoke log directory: `logs/flockfree-smoke-20260617-234829`

That APK was built before the final route-summary source commit. Rebuild from current `master` before testing route-summary behavior.

## What Is Ready To Test

- FlockFree-branded OsmAnd package installs and launches.
- FlockFree first-run copy appears on the phone.
- The in-tree FlockFree plugin is registered and enabled by default.
- Camera data loader accepts the live `data.dontgetflocked.com` response, which currently uses a `.gz` URL but returns plain GeoJSON.
- Smoke log showed `CameraData Reading camera data payload; gzip=false`.
- Camera map layer and camera context-menu/reporting code are present in source.

## Source-Ready But Needs Rebuild

- Route camera summary hook:
  - `FlockFreePlugin.newRouteIsCalculated(boolean)` now watches newly calculated routes when camera avoidance is enabled.
  - `CameraAvoidanceHelper` now checks cameras against route segments instead of only route vertices.
  - The hook shows an advisory FlockFree toast. It does not alter OsmAnd route calculation yet.

## Known Limits

- The verified installed APK does not include the newest route-summary hook.
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
5. Rebuild current `master` before testing the route-summary toast.
