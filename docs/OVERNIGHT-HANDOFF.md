# FlockFree Overnight Handoff

## Repository State

- Repository: `https://github.com/yetisoldier/FlockFree-Navigation`
- Local path: `/home/yetisoldier/projects/FlockFree-Navigation`
- Branch: `master`
- Latest functional source: current `master` after the profile-persisted proof-row status pass.
- Current source includes the route camera summary hook, exposed FlockFree settings screen with live dynamic status refresh, camera-data spatial indexing with source/freshness diagnostics, OSM editor tag-prefill reporting with profile-persisted report-draft status, experimental two-pass offline camera avoidance, profile-persisted applied/fallback/skipped route diagnostics, movement/navigation nearby-camera alerts with profile-persisted last-check status, cache-only route startup for existing camera data, a settings-driven CYD BLE scan/status/simulation path, CYD auto-reconnect on map resume, phone GPS streaming to CYD over `FYGPS`, local phone-GPS CYD simulation when hardware is absent, and persisted CYD detection map/review candidates.

## Verified APK

- APK: `OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk`
- Synced artifact: `build-artifacts/FlockFree-gplayFree-legacy-fat-debug.apk`
- Package: `com.yetiwurks.flockfree`
- SHA-256: `29cbf62a0695741202ce459d34cff99f0cc1a8be8f8a43f36b379b9e0b94e934`
- Source commit: `f5751c5cda9bcaff62fc0d47838ce4f87e8183bd`
- Signature: verifies with APK Signature Scheme v2 using the Android debug certificate
- Phone install: succeeded over Wi-Fi ADB on `192.168.1.139:5555`
- Launch: succeeded into `net.osmand.plus.activities.MapActivity`; diagnostics showed it top-resumed with PID `16659`
- Build info: `build-artifacts/FlockFree-build-info.txt`
- Smoke log directory: `logs/flockfree-diagnostics/20260618-032012`
- UI-evidence diagnostic directory: `logs/flockfree-diagnostics/20260618-032221`
- Crash check: no fatal FlockFree crash entries in filtered logcat

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
- The `Last report draft` settings row profile-persists whether the latest ALPR report attempt opened the OSM editor or showed manual fallback tags.
- Route camera summary hook:
  - `FlockFreePlugin.newRouteIsCalculated(boolean)` now watches newly calculated routes when camera avoidance is enabled.
  - `CameraAvoidanceHelper` now checks cameras against route segments instead of only route vertices.
- The hook still shows an advisory FlockFree toast after route calculation so testers can see the camera count near the final route.
- The toast and profile-persisted `Last route check` settings row report whether route avoidance applied, fell back to the original route, skipped because camera data was unavailable, skipped because no camera-adjacent road objects were found, or skipped during a lightweight follow-me recalculation.
- Current source adds an experimental second-pass OsmAnd offline route calculation:
  - It maps cameras near the initial route to `RouteDataObject` IDs.
  - It passes those IDs as temporary per-calculation impassable roads.
  - It does not write to the user's Avoid Roads settings.
  - It skips partial follow-me recalculations for now to avoid heavy second-pass reroutes while driving.
  - It uses an isolated progress object for the avoided second pass so failed attempts do not poison the original route state.
  - It can load an existing camera cache synchronously before routing, but does not block routing on a network refresh.
  - It falls back to the original route if the avoided route fails.
- Camera data now builds a coarse in-memory spatial grid and the settings screen shows camera count/bucket diagnostics when loaded.
- The camera-data diagnostic row now shows whether the active data came from cache, bundled seed, or network, plus last-refresh age and whether refresh is current or due.
- The settings fragment refreshes dynamic status rows in place while camera data is loading or CYD scan/connect/status is active, so morning testers do not need to leave and re-enter the screen to see status settle.
- The settings screen now includes `Refresh camera data`, which starts an explicit network refresh and leaves existing loaded data in place if the refresh fails.
- The `Nearby camera alerts` switch and `Alert distance` preference are now active: while navigating or moving, FlockFree checks the nearest indexed camera and shows a cooldown-limited nearby-camera toast. The profile-persisted `Last alert check` settings row preserves the last trigger, no-camera, cooldown, or skipped reason after the toast disappears.
- A CYD BLE path exists under `OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/`, including Nordic UART connection handling, idle auto-scan on map resume when CYD BLE is enabled, parsers for `pair_status` and `detection` JSON, outbound `FYGPS` phone-location streaming, local GPS-backed test-marker creation when hardware is not connected, and FlockFree settings rows for scan/connect, status request, simulated detection, clearing recent detections, and visible phone-GPS-send/status readiness.
- GPS-backed CYD detections are now retained in memory, persisted to `flockfree-cyd-detections.json` in app-private storage, drawn on the map as CYD diamond markers, selectable from the map/context menu, and can be handed to the existing `Add ALPR Camera` reporting flow.

## Known Limits

- Route avoidance is still experimental and only works for OsmAnd offline vector routing. It blocks whole road objects and can be coarse.
- CYD BLE has a map-activity/settings-driven scanner/status/simulation/review MVP with idle scan-on-resume, local phone-GPS simulation fallback, and local candidate persistence, but no foreground service or sync.
- Camera data is still held in memory and indexed in Java; persisted SQLite/geohash indexing is a later optimization.
- The bundled camera seed is a snapshot. Live freshness still depends on the weekly or manual `Refresh camera data` network path.
- The current OSM reporting helper pre-fills tags, but still needs end-to-end on-device validation before treating it as upload-ready.

## Morning First Steps

1. Rebuild/install the latest source manually with `scripts/flockfree-user-build-install.sh` so the phone has the newest app-code changes.
2. Open the app on the Moto G Stylus. It should install as `com.yetiwurks.flockfree`.
3. Work through or skip the first-run map download flow.
4. Confirm camera data finishes loading on Wi-Fi.
5. Move/zoom to a camera-dense area and verify markers.
6. Open the plugin settings, confirm the camera-data diagnostic row includes an indexed camera count, source, and freshness/refresh-due status, tap `Refresh camera data` on Wi-Fi, and confirm the row refreshes in place or returns to an indexed camera count.
7. Confirm the `Nearby camera alerts` / `Alert distance` / `Last alert check` rows are present.
8. While navigating or moving near a known camera, verify FlockFree shows a nearby-camera toast and does not repeat it continuously.
9. Reopen FlockFree settings and confirm `Last alert check` preserves the trigger, no-camera, cooldown, or skipped reason after an app restart.
10. Enable camera avoidance, calculate a route, and verify the route-summary toast includes an applied/fallback/skipped status line.
11. Reopen FlockFree settings and confirm `Last route check` preserves the same route summary/status after the toast disappears and after an app restart.
12. Compare one camera-dense offline route with avoidance off and on. A successful newer build should either route around camera-adjacent road objects, fall back cleanly to the original route, or say why avoidance was skipped.
13. In the CYD hardware section, enable CYD BLE, scan/connect to a powered `CYD-Flock-You`, request status, and try the simulated detection command.
14. Relaunch or leave/return to the map with `CYD BLE` still enabled and confirm FlockFree starts scanning again without visiting the settings screen.
15. After FlockFree has a valid GPS fix, confirm the `CYD status` row reports `Phone GPS sent ... seconds ago` when connected or `Phone GPS ready ... seconds ago` before hardware is connected.
16. Request status and confirm the CYD reports `gps:true` once FlockFree has had roughly one second to send the fix over `FYGPS`.
17. Long-press or tap a CYD marker, open `Add ALPR Camera`, and confirm OsmAnd's POI editor opens with ALPR/surveillance tags.
18. Reopen FlockFree settings and confirm `Last report draft` preserves the editor-opened or manual-tag-fallback result after an app restart.
19. After a GPS-backed CYD detection or local GPS-backed simulated CYD marker, return to the map, confirm a CYD diamond marker appears near the detection location, tap it, and choose `Review as ALPR camera` to open the normal ALPR report flow.

For a no-Gradle device snapshot before or after those checks, run:

```bash
scripts/flockfree-moto-diagnostics.sh
```

The collector uses Wi-Fi ADB, defaults to `192.168.1.139:5555`, writes only local ignored files under `logs/flockfree-diagnostics/`, and captures ADB state, package install state, current activity, PID, package metadata, screenshot/UI hierarchy evidence, a FlockFree UI text summary, app-private camera cache/CYD candidate file state, runtime location/Bluetooth/notification permission state summarized in `summary.txt`, and filtered `FlockFree` / `CameraData` / `FATAL` logcat evidence.

For a no-Gradle permission prep pass before CYD/GPS checks, run:

```bash
scripts/flockfree-moto-permission-primer.sh
```

The primer grants only FlockFree's declared location, Bluetooth, and notification runtime permissions, then runs the diagnostics collector so the resulting `summary.txt` proves whether CYD/GPS testing is permission-ready.

For source-only checks without running Gradle, run:

```bash
scripts/flockfree-source-checks.sh
```

For the full no-Gradle morning readiness pass against the already installed APK, run:

```bash
scripts/flockfree-morning-readiness.sh
```

Start with the generated `logs/flockfree-readiness/.../readiness-report.txt`; it includes source-check status, permission-primer status, copied APK build provenance, an installed-APK app-code freshness check, the latest Moto diagnostic summary, and a final `Readiness verdict`.

For a timed evidence bundle while Eric performs the manual route, OSM reporting, and CYD checks, run:

```bash
scripts/flockfree-field-test-session.sh
```

Start with the generated `logs/flockfree-field-session/.../field-session-report.txt`; it includes the readiness gate, manual prompts, `test-area-suggestions.txt` map anchors from the bundled seed, `manual-test-results.tsv` for PASS/FAIL/SKIP notes, ready-made `manual-result-commands.txt` marker commands for that session, filtered logcat from the test window, post-session diagnostics, and a `session-summary.txt` evidence summary with explicit crash status, manual results, and missing manual checks.

To reopen the latest field-session bundle without hunting for the timestamp:

```bash
scripts/flockfree-latest-field-session.sh
```

If the latest bundle is only an ADB/network failure, reopen the newest session with live phone/package evidence:

```bash
scripts/flockfree-latest-field-session.sh --latest-phone-evidence
```

To print only the ready-made marker commands for the latest field-session bundle:

```bash
scripts/flockfree-latest-field-session.sh --commands-only
```

To print only the remaining TODO/FAIL proof rows for the latest field-session bundle:

```bash
scripts/flockfree-latest-field-session.sh --todo-only
```

To mark the latest field session directly after a visual/manual check:

```bash
scripts/flockfree-mark-latest-result.sh route_avoidance PASS --notes "Avoidance applied toast observed"
```

To mark a visual/manual result and refresh the summary without opening the TSV by hand:

```bash
scripts/flockfree-mark-result.py logs/flockfree-field-session/RUN_ID route_avoidance PASS --notes "Avoidance applied toast observed" --summarize
```

With `--summarize`, the helper refreshes `session-summary.txt` and updates a generated manual-result block in `field-session-report.txt`, so the report remains the primary file to read.

For data-backed camera-dense route-test anchors from the bundled seed, run:

```bash
scripts/flockfree-suggest-test-areas.py --limit 10 --radius-km 80
```

Use the printed `Map anchor` coordinates to pick route, marker, and nearby-alert test areas without guessing.
