# FlockFree Navigation

FlockFree Navigation is an OsmAnd fork with an in-tree FlockFree plugin for ALPR camera awareness. The current branch is an early source baseline, not a finished consumer release.

## Current Build Status

From the repository root:

```bash
cd /home/yetisoldier/projects/FlockFree-Navigation
git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git ../resources  # skip if ../resources already exists

ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK=$HOME/Android/Sdk \
  ./gradlew :OsmAnd:assembleGplayFreeLegacyFatDebug \
  -x test --no-daemon --max-workers=1
```

For a one-command manual build/install/launch/diagnostics flow, Eric can run:

```bash
scripts/flockfree-user-build-install.sh
```

The helper writes the copied APK and `FlockFree-build-info.txt` under `build-artifacts/` so the morning install can be traced back to a source commit and APK SHA-256.

That helper runs Gradle, installs the APK over Wi-Fi ADB, launches FlockFree, and captures a diagnostic bundle.

For source-only verification that does not run Gradle:

```bash
scripts/flockfree-source-checks.sh
```

For a one-command no-Gradle morning readiness pass using the already installed APK:

```bash
scripts/flockfree-morning-readiness.sh
```

It runs source-only checks, primes FlockFree runtime permissions on the Moto, captures diagnostics, and writes `logs/flockfree-readiness/.../readiness-report.txt`.
The report compares `build-artifacts/FlockFree-build-info.txt` with the current repo and says whether the installed APK is app-code current or whether app/runtime paths changed after the last APK build.
It ends with a `Readiness verdict` so morning testing starts with either `READY` or a short list of items needing attention.

Last verified local APK output:

```text
OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk
```

Verified debug package/application ID: `com.yetiwurks.flockfree`.
Verified APK SHA-256: `29cbf62a0695741202ce459d34cff99f0cc1a8be8f8a43f36b379b9e0b94e934`.
That APK was built from clean source commit `f5751c5cda9bcaff62fc0d47838ce4f87e8183bd`, installed and launched on a Moto G Stylus over Wi-Fi ADB, and diagnostics showed `MapActivity` top-resumed with no fatal FlockFree crash entries.

## What Works Now

- App branding and Gradle flavor are pointed at FlockFree for the `gplayFree` debug build.
- The FlockFree plugin is registered in OsmAnd and enabled by default.
- Camera data downloads from `https://data.dontgetflocked.com/cameras.geojson.gz`, is cached as GeoJSON, and refreshes weekly.
- The data loader handles both gzip and plain GeoJSON because the live `.gz` endpoint currently returns plain GeoJSON.
- A compressed bundled camera seed is included from `OsmAnd/assets/flockfree/cameras.geojson.gz`, packaged as `assets/flockfree/cameras.geojson`, so a fresh install can show the current 104,902-camera snapshot even if the first network refresh is unavailable.
- Parsed cameras are indexed into a coarse in-memory spatial grid so map and route-corridor lookups do not scan the full camera list every time.
- The FlockFree settings screen shows whether camera data was loaded from cache, bundled seed, or network, includes last-refresh freshness/refresh-due status, refreshes active status rows while loading/scanning, and can manually refresh the camera data cache for morning validation or later data updates.
- Camera points render on the map at zoom 10+ with basic vendor colors and higher-zoom labels.
- Tapping a rendered camera opens a simple details dialog with brand, operator, direction, mount, surveillance zone, OSM ID/type, and timestamp when present.
- The map context menu has an `Add ALPR Camera` action that opens OsmAnd's POI editor with the selected ALPR tag preset already attached to a new node.
- When camera avoidance is enabled, newly calculated routes get a FlockFree toast summary of cameras near the route corridor.
- The FlockFree settings screen keeps the last route camera/avoidance summary visible so route-test results can be checked after the toast disappears.
- Current source can perform one experimental second-pass OsmAnd offline route calculation using temporary impassable road IDs for roads adjacent to known cameras. These IDs are route-scoped and do not pollute the user's Avoid Roads settings.
- With nearby alerts enabled, FlockFree can warn while navigating or moving when the current GPS fix is within the configured alert distance of the nearest known camera.
- The plugin settings screen is exposed through the OsmAnd plugin settings flow for map layer visibility, route summaries, corridor radius, alert distance, and CYD BLE enablement.
- A CYD BLE UART path exists for `FYHELLO`, `FYSTATUS`, `FYSIM`, `FYGPS`, `pair_status`, and `detection` messages.
- If CYD BLE is enabled, returning to the map starts a scan when the CYD manager is idle.
- Once the CYD is connected, FlockFree forwards valid phone GPS fixes over `FYGPS` about once per second so the CYD can time/location-stamp detections without DeFlock running separately.
- The CYD settings status row reports recent phone GPS sends, making the `FYGPS` stream visible during bench testing.
- GPS-backed CYD detections are kept as recent candidates, persisted to a small local JSON store, drawn on the map as distinct CYD markers, and can be reviewed through the existing ALPR camera reporting flow.

## Still Stubbed Or Thin

- Camera avoidance is experimental and applies only to OsmAnd offline vector routing. It blocks whole route road objects, which can be coarse on long roads, and falls back to the original route if the avoided route fails.
- CYD BLE integration is still map-activity/settings-driven. It has no foreground service yet, and recent detection candidates are persisted locally but not synced anywhere.
- Camera storage is an in-memory parsed GeoJSON list with a spatial grid, not a persisted SQLite/geohash database.
- The bundled camera seed is a snapshot. `Refresh camera data` or the normal weekly refresh is still needed for the latest live camera data.
- Widgets, quick actions, final settings polish, and rich camera detail UI are placeholders or not implemented.
- Reporting now pre-fills OsmAnd's OSM POI editor with ALPR tags, but still needs end-to-end on-device validation against the save/upload flow.

## Phone Test Plan

Use [docs/MORNING-TEST-PLAN.md](docs/MORNING-TEST-PLAN.md). The current verified APK has been installed and launched on the Moto G Stylus over Wi-Fi ADB.

For a no-Gradle diagnostic snapshot during morning validation, run:

```bash
scripts/flockfree-moto-diagnostics.sh
```

It writes local artifacts under ignored `logs/flockfree-diagnostics/` and checks ADB state, package install state, current activity, PID/process state, package metadata, UI screenshot/hierarchy, a FlockFree UI text summary, app-private camera cache/CYD candidate file state, runtime location/Bluetooth/notification permission state summarized in `summary.txt`, and filtered FlockFree/CameraData/FATAL logcat output.

For a no-Gradle CYD/GPS permission prep pass on the Moto, run:

```bash
scripts/flockfree-moto-permission-primer.sh
```

It grants only FlockFree's declared location, Bluetooth, and notification runtime permissions, then runs the standard diagnostics collector so `summary.txt` confirms whether CYD/GPS testing is permission-ready.

For a timed no-Gradle evidence bundle while manually walking through the route, OSM reporting, and CYD checks:

```bash
scripts/flockfree-field-test-session.sh
```

It runs the readiness gate, writes manual prompts, captures filtered logcat during the test window, then captures post-session diagnostics.
It also writes `test-area-suggestions.txt` with bundled-seed map anchors, prints the first anchors into the session report, creates `manual-test-results.tsv` for PASS/FAIL/SKIP notes during the walk-through, writes ready-made `manual-result-commands.txt` marker commands for the current session, and writes `session-summary.txt` with crash status, observed evidence buckets, manual results, and missing manual checks.

To reopen the newest field-session summary and find its marker commands:

```bash
scripts/flockfree-latest-field-session.sh
```

To print only the ready-made marker commands for the newest field session:

```bash
scripts/flockfree-latest-field-session.sh --commands-only
```

To mark the newest field session directly:

```bash
scripts/flockfree-mark-latest-result.sh route_avoidance PASS --notes "Avoidance applied toast observed"
```

To mark a visual/manual result and refresh the summary afterward:

```bash
scripts/flockfree-mark-result.py logs/flockfree-field-session/RUN_ID route_avoidance PASS --notes "Avoidance applied toast observed" --summarize
```

With `--summarize`, the helper refreshes both `session-summary.txt` and the generated manual-result block in `field-session-report.txt`.

For offline route-test anchors from the bundled camera seed:

```bash
scripts/flockfree-suggest-test-areas.py --limit 10 --radius-km 80
```

It prints camera-dense map anchors that can be used for manual marker, alert, and route-avoidance checks.

## Credits

- Built on [OsmAnd](https://github.com/osmandapp/osmand) under Apache 2.0.
- Camera data from [DeFlock](https://deflock.org) / [OpenStreetMap](https://openstreetmap.org) contributors.
- Optional hardware direction from [CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You).

## License

Apache 2.0, inherited from OsmAnd.
