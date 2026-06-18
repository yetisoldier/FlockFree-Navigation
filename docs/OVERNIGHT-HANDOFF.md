# FlockFree Overnight Handoff

## Repository State

- Repository: `https://github.com/yetisoldier/FlockFree-Navigation`
- Local path: `/home/yetisoldier/projects/FlockFree-Navigation`
- Branch: `master`
- Latest source: `155a5382e8` — branded READY build after on-device feature proof.
- Current source includes FlockFree launcher/adaptive/splash branding with red/cyan visible UI colors, the route camera summary hook, exposed FlockFree settings screen with live dynamic status refresh, camera-data spatial indexing with source/freshness diagnostics, a nearest-camera-at-map-center inspection action with retained last-check status, OSM editor tag-prefill reporting with map-center draft action and profile-persisted report-draft status, iterative relaxation camera avoidance (full block first, then progressively unblocks least-camera roads up to 4 iterations), profile-persisted applied/partial/fallback/skipped route diagnostics, movement/navigation nearby-camera alerts with profile-persisted last-check status plus a map-center alert test action, cache-only route startup for existing camera data, a settings-driven CYD BLE scan/status/simulation path, CYD auto-reconnect on map resume, a CYD BLE foreground service using the Android `connectedDevice` foreground-service type with permission-gated background scan restart, phone GPS streaming to CYD over `FYGPS`, local CYD simulation from phone/OsmAnd GPS or current map center when hardware is absent, and persisted CYD detection map/review candidates.
- **CYD BLE foreground service proof, 2026-06-18 08:40 CDT:** source commit `9a9fae9b3f` (with `connectedDevice` FGS type) built, installed, and validated on-device. `CydBleService` running with `isForeground=true`, `foregroundId=200`, `types=00000010` (connectedDevice), `stopIfKilled=false`, notification channel `flockfree_cyd_service` active. Service survives backgrounding (home key press). CYD hardware is paired: `CYD-Flock-You | Protocol v1 | GPS ready | SD ready | Detections 2`. No crashes in filtered logcat.
- **On-device feature proof, 2026-06-18 09:30 CDT:** route avoidance, nearby-alert bench check, and OSM editor tag-prefill are proven on the Moto with app-code source `9a9fae9b3f`. The route test used Eric's supplied target, `600 9th Ave S, South St. Paul, MN 55075` (`44.8818584,-93.0457542`), after installing `Us_minnesota_northamerica_2.road.obf` to the app's external `roads/` directory. Driving-profile camera avoidance must be enabled; Browse-map preference state alone does not affect car routing. With Driving avoidance enabled, logcat showed `CameraAvoidanceHelper FlockFree collected 7 temporary avoid road ids` and `RouteProvider FlockFree recalculated route with 7 temporary avoid road ids`; the retained car-profile preference stored `FlockFree: 5 cameras near route (100-meter corridor): Flock Safety: 4, Other: 1; Avoidance applied: 7 camera-adjacent road objects blocked.` Nearby alert bench proof showed a forced map-center alert toast and retained `Last alert check`; OSM reporting proof opened OsmAnd's native POI editor with ALPR/surveillance tags attached. No OSM save/upload was performed.
- **Branding proof, 2026-06-18 09:48 CDT:** source commit `155a5382e8` replaced launcher/adaptive/splash assets and shifted visible FlockFree settings colors to cyan section headers and red switches. The clean helper build installed on the Moto with readiness `READY`; on-device screenshot proof showed the new dark-red splash/background and branded settings colors.

## Verified APK

- APK: `OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk`
- Synced artifact: `build-artifacts/FlockFree-gplayFree-legacy-fat-debug.apk`
- Package: `com.yetiwurks.flockfree`
- SHA-256: `cd93d295d16a2ca71eb46a46e2e0583add3885236ca834cf397a393715005534`
- Source commit: `155a5382e8`
- Signature: verifies with APK Signature Scheme v2 using the Android debug certificate
- Phone install: succeeded over Wi-Fi ADB on `192.168.1.139:39183`
- Launch: succeeded into `net.osmand.plus.activities.MapActivity`; readiness verdict READY
- CYD BLE service: source commit `9a9fae9b3f` started on map resume, `isForeground=true`, `foregroundId=200`, foreground-service type `types=00000010` (`connectedDevice`), notification channel `flockfree_cyd_service` registered, and survived backgrounding. Latest branded APK source `155a5382e8` retained CYD foreground service and notification readiness.
- Build info: `build-artifacts/FlockFree-build-info.txt`
- Readiness directory: `logs/flockfree-readiness/20260618-094725`
- Crash check: no fatal FlockFree crash entries in filtered logcat

## What Is Ready To Test

- FlockFree-branded OsmAnd package installs and launches.
- FlockFree first-run copy appears on the phone.
- Launcher/adaptive/splash assets and the visible FlockFree settings palette are now branded away from stock OsmAnd.
- The in-tree FlockFree plugin is registered and enabled by default.
- FlockFree has a visible plugin settings screen wired into the OsmAnd plugin settings flow.
- Camera data loader accepts the live `data.dontgetflocked.com` response, which currently uses a `.gz` URL but returns plain GeoJSON.
- A bundled camera seed from `OsmAnd/assets/flockfree/cameras.geojson.gz` gives fresh installs a 104,902-camera fallback before the first successful network refresh.
- No-cache smoke log showed `No FlockFree camera cache found`, `Parsed 104902 camera points from bundled seed`, then a successful network refresh of the same 104,902 features.
- Camera map layer and camera context-menu/reporting code are present in source.
- The `Add ALPR Camera` flow now opens OsmAnd's POI editor with the selected ALPR tag preset attached to the new node.
- `Draft report at map center` opens the same ALPR report dialog from the current map center, making tag-prefill proof possible from a suggested anchor without relying on long-press context-menu discovery.
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
  - If full avoidance fails, iterative relaxation progressively unblocks the least-camera-impactful roads (up to 4 iterations) until a viable route is found or all blocks are removed, then falls back to the original route.
- Camera data now persists parsed cameras to an app-private SQLite database, rebuilds a coarse in-memory spatial grid for route/helper fallbacks, and the settings screen shows camera count/bucket diagnostics when loaded.
- The camera-data diagnostic row now shows whether the active data came from database, cache, bundled seed, or network, plus last-refresh age and whether refresh is current or due.
- The settings fragment refreshes dynamic status rows in place while camera data is loading or CYD scan/connect/status is active, so morning testers do not need to leave and re-enter the screen to see status settle.
- The settings screen now includes `Refresh camera data`, which starts an explicit network refresh and leaves existing loaded data in place if the refresh fails.
- The settings screen now includes `Nearest camera at map center`, which searches 5 kilometers around the current map center, opens the camera detail dialog with distance, brand/operator, direction, mount, zone, OSM ID/type, and timestamp when available, and preserves the latest found-camera or skip result in `Last nearest camera`.
- The `Nearby camera alerts` switch and `Alert distance` preference are now active: while navigating or moving, FlockFree checks the nearest indexed camera and shows a cooldown-limited nearby-camera toast. `Check map center alert` runs the nearest-camera check at the current map center and forces a toast when a camera is within range, making bench validation possible from a suggested anchor. The profile-persisted `Last alert check` settings row preserves the last trigger, no-camera, cooldown, or skipped reason after the toast disappears.
- A CYD BLE path exists under `OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/`, including Nordic UART connection handling, idle auto-scan on map resume when CYD BLE is enabled, parsers for `pair_status` and `detection` JSON, outbound `FYGPS` phone-location streaming, local phone/map-center test-marker creation when hardware is not connected, and FlockFree settings rows for scan/connect, status request, simulated detection, clearing recent detections, and visible phone-GPS-send/status readiness.
- Current source includes a CYD foreground service source path and notification channel intended to keep CYD BLE monitoring alive when the app is backgrounded. If CYD BLE remains enabled and Bluetooth permissions are already granted, the service can restart scanning without reopening settings; current `connectedDevice` service-type behavior is proven on-device at app-code source `9a9fae9b3f`.
- GPS-backed CYD detections are now retained in memory, persisted to `flockfree-cyd-detections.json` in app-private storage, drawn on the map as CYD diamond markers, selectable from the map/context menu, and can be handed to the existing `Add ALPR Camera` reporting flow.

## Known Limits

- Route avoidance uses iterative relaxation and only works for OsmAnd offline vector routing. It blocks whole road objects and can be coarse, but progressively relaxes the least-camera-impactful blocks when full avoidance fails.
- CYD BLE has a map-activity/settings-driven scanner/status/simulation/review MVP with idle scan-on-resume, a foreground service that keeps BLE alive in the background, local phone/map-center simulation fallback, and local candidate persistence, but no sync yet. The background service is validated on-device at app-code source `9a9fae9b3f` with the Android `connectedDevice` foreground-service type.
- Camera data now has app-private SQLite persistence and Java fallback indexing. A full geohash/tile store is still later work.
- The bundled camera seed is a snapshot. Live freshness still depends on the weekly or manual `Refresh camera data` network path.
- The current OSM reporting helper pre-fills tags and has been validated into OsmAnd's native POI editor. OSM save/upload remains intentionally untested; do not upload bench-test reports.

## Morning First Steps

1. If Wi-Fi ADB is unreachable, run `scripts/flockfree-adb-recover.sh`. If it still cannot reach `device`, check the recovery bundle's `adb-mdns-after.txt` and `ip-neigh-after.txt`, then wake the phone, confirm same-Wi-Fi and Wireless debugging, copy the current IP:port, and rerun with `--serial PHONE_IP:PORT`.
2. If app-code changes land after `155a5382e8`, rebuild/install with `scripts/flockfree-user-build-install.sh` so the phone has the newest runtime. The helper runs the no-Gradle readiness gate after install and leaves a `logs/flockfree-readiness/.../readiness-report.txt` with APK freshness, permission, camera cache/database, launch, and crash evidence.
3. Open the app on the Moto G Stylus. It should install as `com.yetiwurks.flockfree`.
4. Work through or skip the first-run map download flow.
5. Confirm camera data finishes loading on Wi-Fi.
6. Move/zoom to a camera-dense area and verify markers.
7. Open the plugin settings, confirm the camera-data diagnostic row includes an indexed camera count, source, and freshness/refresh-due status, tap `Refresh camera data` on Wi-Fi, and confirm the row refreshes in place or returns to an indexed camera count.
8. Move the map to a suggested camera-dense anchor, tap `Nearest camera at map center`, and confirm the `Nearest ALPR camera` dialog opens with distance and camera metadata or `Last nearest camera` preserves the found-camera/skip result.
9. Confirm the `Nearby camera alerts` / `Alert distance` / `Last alert check` / `Check map center alert` rows are present.
10. Move the map to a suggested camera-dense anchor, tap `Check map center alert`, and confirm `Last alert check` preserves the trigger, no-camera, loading, or disabled result after an app restart.
11. During a real drive or simulated movement later, confirm the live nearby-camera alert toast appears no more than once per cooldown window.
12. Enable camera avoidance in the route profile being tested, such as `Driving` for car routing. Browse-map preference state alone does not affect car routes. Calculate a route and verify the route-summary toast or retained row includes an applied/fallback/skipped status line.
13. Reopen FlockFree settings and confirm `Last route check` preserves the same route summary/status after the toast disappears and after an app restart.
14. Compare one camera-dense offline route with avoidance off and on. A successful newer build should either route around camera-adjacent road objects, fall back cleanly to the original route, or say why avoidance was skipped.
15. In the CYD hardware section, enable CYD BLE, scan/connect to a powered `CYD-Flock-You`, request status, and try the simulated detection command.
16. Relaunch or leave/return to the map with `CYD BLE` still enabled and confirm FlockFree starts scanning again without visiting the settings screen.
17. Background FlockFree after CYD BLE is enabled and confirm Android shows the FlockFree CYD foreground notification; if Bluetooth permissions are granted, confirm the CYD status eventually shows scanning/ready behavior without reopening settings.
18. After FlockFree has a valid GPS fix, confirm the `CYD status` row reports `Phone GPS sent ... seconds ago` when connected or `Phone GPS ready ... seconds ago` before hardware is connected.
19. Request status and confirm the CYD reports `gps:true` once FlockFree has had roughly one second to send the fix over `FYGPS`.
20. Tap `Draft report at map center`, long-press a map location, or tap a CYD marker to open `Add ALPR Camera`, and confirm OsmAnd's POI editor opens with ALPR/surveillance tags.
21. Reopen FlockFree settings and confirm `Last report draft` preserves the editor-opened or manual-tag-fallback result after an app restart.
22. After a GPS-backed CYD detection or local phone/map-center simulated CYD marker, return to the map, confirm a CYD diamond marker appears near the detection location, tap it, and choose `Review as ALPR camera` to open the normal ALPR report flow.

For a no-Gradle device snapshot before or after those checks, run:

```bash
scripts/flockfree-moto-diagnostics.sh
```

The collector uses Wi-Fi ADB, defaults to `192.168.1.139:39183`, writes only local ignored files under `logs/flockfree-diagnostics/`, and captures ADB state, package install state, current activity, PID, package metadata, screenshot/UI hierarchy evidence, a FlockFree UI text summary, app-private camera cache, camera database size/row count when local `sqlite3` is available, CYD candidate file state, runtime location/Bluetooth/notification permission state, CYD foreground service/notification state summarized in `summary.txt`, and filtered `FlockFree` / `CameraData` / `FATAL` logcat evidence.

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

To go directly from manual rebuild/install/readiness into the timed field-test window, run:

```bash
scripts/flockfree-user-build-install.sh --field-session
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

This view now prints the session source commit, current `HEAD`, and a warning when the latest bundle is older than the current source. It also annotates older nearby-alert and CYD rows with the current map-center proof paths. That matters while the APK is stale: rerun the build/install helper and a fresh field session before treating TODO rows as final evidence.

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
