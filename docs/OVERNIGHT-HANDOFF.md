# FlockFree Overnight Handoff

## Repository State

- Repository: `https://github.com/yetisoldier/FlockFree-Navigation`
- Local path: `/home/yetisoldier/projects/FlockFree-Navigation`
- Branch: `master`
- Latest functional source: current `master` after the bundled camera seed fallback verification pass.
- Current source includes the route camera summary hook, exposed FlockFree settings screen, camera-data spatial indexing, OSM editor tag-prefill reporting, experimental two-pass offline camera avoidance, retained applied/fallback/skipped route diagnostics, movement/navigation nearby-camera alerts, cache-only route startup for existing camera data, a settings-driven CYD BLE scan/status/simulation path, CYD auto-reconnect on map resume, phone GPS streaming to CYD over `FYGPS`, and persisted CYD detection map/review candidates.

## Verified APK

- APK: `OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk`
- Synced artifact: `build-artifacts/FlockFree-gplayFree-legacy-fat-debug.apk`
- Package: `com.yetiwurks.flockfree`
- SHA-256: `52e36c7b897724729be6fcde362174804a515695cd35638a1c18b23d67168797`
- Source commit: `7a8803e6fb5b7ff73e56bcc1d117d1b10e8f193e`
- Signature: verifies with APK Signature Scheme v2 using the Android debug certificate
- Phone install: succeeded over Wi-Fi ADB on `192.168.1.139:5555`
- Launch: succeeded into `net.osmand.plus.activities.MapActivity`
- Build info: `build-artifacts/FlockFree-build-info.txt`
- Smoke log directory: `logs/flockfree-diagnostics/20260618-025201`
- Screenshot: `flockfree-latest-launch.png`

## What Is Ready To Test

- FlockFree-branded OsmAnd package installs and launches.
- FlockFree first-run copy appears on the phone.
- The in-tree FlockFree plugin is registered and enabled by default.
- FlockFree has a visible plugin settings screen wired into the OsmAnd plugin settings flow.
- Camera data loader accepts the live `data.dontgetflocked.com` response, which currently uses a `.gz` URL but returns plain GeoJSON.
- A bundled camera seed from `OsmAnd/assets/flockfree/cameras.geojson.gz` gives fresh installs a 104,902-camera fallback before the first successful network refresh.
- No-cache smoke log showed `No FlockFree camera cache found`, `Parsed 104902 camera points from bundled seed`, then a successful network refresh of the same 104,902 features.
- Camera map layer and camera context-menu/reporting code are present in source.
- The `Add ALPR Camera` flow now opens OsmAnd's POI editor with the selected ALPR tag preset attached to the new node.
- Route camera summary hook:
  - `FlockFreePlugin.newRouteIsCalculated(boolean)` now watches newly calculated routes when camera avoidance is enabled.
  - `CameraAvoidanceHelper` now checks cameras against route segments instead of only route vertices.
- The hook still shows an advisory FlockFree toast after route calculation so testers can see the camera count near the final route.
- The toast and `Last route check` settings row report whether route avoidance applied, fell back to the original route, skipped because camera data was unavailable, skipped because no camera-adjacent road objects were found, or skipped during a lightweight follow-me recalculation.
- Current source adds an experimental second-pass OsmAnd offline route calculation:
  - It maps cameras near the initial route to `RouteDataObject` IDs.
  - It passes those IDs as temporary per-calculation impassable roads.
  - It does not write to the user's Avoid Roads settings.
  - It skips partial follow-me recalculations for now to avoid heavy second-pass reroutes while driving.
  - It uses an isolated progress object for the avoided second pass so failed attempts do not poison the original route state.
  - It can load an existing camera cache synchronously before routing, but does not block routing on a network refresh.
  - It falls back to the original route if the avoided route fails.
- Camera data now builds a coarse in-memory spatial grid and the settings screen shows camera count/bucket diagnostics when loaded.
- The camera-data diagnostic row now shows whether the active data came from cache, bundled seed, or network.
- The settings screen now includes `Refresh camera data`, which starts an explicit network refresh and leaves existing loaded data in place if the refresh fails.
- The `Nearby camera alerts` switch and `Alert distance` preference are now active: while navigating or moving, FlockFree checks the nearest indexed camera and shows a cooldown-limited nearby-camera toast.
- A CYD BLE path exists under `OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/`, including Nordic UART connection handling, idle auto-scan on map resume when CYD BLE is enabled, parsers for `pair_status` and `detection` JSON, outbound `FYGPS` phone-location streaming, and FlockFree settings rows for scan/connect, status request, simulated detection, clearing recent detections, and visible phone-GPS-send status.
- GPS-backed CYD detections are now retained in memory, persisted to `flockfree-cyd-detections.json` in app-private storage, drawn on the map as CYD diamond markers, selectable from the map/context menu, and can be handed to the existing `Add ALPR Camera` reporting flow.

## Known Limits

- Route avoidance is still experimental and only works for OsmAnd offline vector routing. It blocks whole road objects and can be coarse.
- CYD BLE has a map-activity/settings-driven scanner/status/simulation/review MVP with idle scan-on-resume and local candidate persistence, but no foreground service or sync.
- Camera data is still held in memory and indexed in Java; persisted SQLite/geohash indexing is a later optimization.
- The bundled camera seed is a snapshot. Live freshness still depends on the weekly or manual `Refresh camera data` network path.
- The current OSM reporting helper pre-fills tags, but still needs end-to-end on-device validation before treating it as upload-ready.

## Morning First Steps

1. Open the app on the Moto G Stylus. The latest verified APK should already be installed as `com.yetiwurks.flockfree`.
2. Work through or skip the first-run map download flow.
3. Confirm camera data finishes loading on Wi-Fi.
4. Move/zoom to a camera-dense area and verify markers.
5. Open the plugin settings, confirm the camera-data diagnostic row includes an indexed camera count and source, tap `Refresh camera data` on Wi-Fi, and confirm the row returns to an indexed camera count.
6. Confirm the `Nearby camera alerts` / `Alert distance` rows are present.
7. While navigating or moving near a known camera, verify FlockFree shows a nearby-camera toast and does not repeat it continuously.
8. Enable camera avoidance, calculate a route, and verify the route-summary toast includes an applied/fallback/skipped status line.
9. Reopen FlockFree settings and confirm `Last route check` preserves the same route summary/status after the toast disappears.
10. Compare one camera-dense offline route with avoidance off and on. A successful newer build should either route around camera-adjacent road objects, fall back cleanly to the original route, or say why avoidance was skipped.
11. In the CYD hardware section, enable CYD BLE, scan/connect to a powered `CYD-Flock-You`, request status, and try the simulated detection command.
12. Relaunch or leave/return to the map with `CYD BLE` still enabled and confirm FlockFree starts scanning again without visiting the settings screen.
13. After connecting, confirm the FlockFree `CYD status` row reports `Phone GPS sent ... seconds ago` once FlockFree has a valid GPS fix.
14. Request status and confirm the CYD reports `gps:true` once FlockFree has had roughly one second to send the fix over `FYGPS`.
15. After a GPS-backed CYD detection, return to the map, confirm a CYD diamond marker appears near the detection location, tap it, and choose `Review as ALPR camera` to open the normal ALPR report flow.

For a no-Gradle device snapshot before or after those checks, run:

```bash
scripts/flockfree-moto-diagnostics.sh
```

The collector uses Wi-Fi ADB, defaults to `192.168.1.139:5555`, writes only local ignored files under `logs/flockfree-diagnostics/`, and captures ADB state, package install state, current activity, PID, package metadata, and filtered `FlockFree` / `CameraData` / `FATAL` logcat evidence.

For source-only checks without running Gradle, run:

```bash
scripts/flockfree-source-checks.sh
```
