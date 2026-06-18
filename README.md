# FlockFree Navigation

FlockFree Navigation is an OsmAnd fork with an in-tree FlockFree plugin for ALPR camera awareness. The current branch is an early source baseline, not a finished consumer release.

## Current Build Status

From the repository root:

```bash
cd /home/yetisoldier/projects/FlockFree-Navigation
git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git ../resources  # skip if ../resources already exists

ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK=$HOME/Android/Sdk \
  ./gradlew --no-watch-fs --max-workers=1 \
  :OsmAnd:assembleNightlyFreeLegacyFatDebug \
  -x :OsmAnd-java:test
```

That assemble path has compiled the FlockFree plugin successfully in local testing. The installable APK packaging path still needs cleanup: this checkout hit Android Gradle desugar/universal-package intermediates and did not leave a FlockFree APK under `OsmAnd/build/outputs/apk`.

Debug package/application ID target: `com.yetiwurks.flockfree.dev`.

## What Works Now

- App branding and Gradle flavor are pointed at FlockFree for the `nightlyFree` debug build.
- The FlockFree plugin is registered in OsmAnd and enabled by default.
- Camera data downloads from `https://data.dontgetflocked.com/cameras.geojson.gz`, is cached as GeoJSON, and refreshes weekly.
- Camera points render on the map at zoom 10+ with basic vendor colors and higher-zoom labels.
- Tapping a rendered camera opens a simple details dialog with brand, operator, direction, mount, surveillance zone, OSM ID/type, and timestamp when present.
- The map context menu has an `Add ALPR Camera` action that opens the current camera-reporting flow.
- Basic preferences exist for map layer visibility, avoidance, alert distance, data timestamp, and CYD BLE enablement.

## Still Stubbed Or Thin

- Camera avoidance currently reports cameras near a supplied route corridor, but it is not wired into OsmAnd route calculation to block or penalize roads.
- CYD BLE integration is preference-only in this repo state; no BLE manager/service/parser is wired in.
- Camera storage is an in-memory parsed GeoJSON list after cache/download, not a spatial SQLite database.
- No bundled first-run camera snapshot is present, so the first useful camera layer depends on network access to download data.
- Widgets, quick actions, a polished settings screen, and rich camera detail UI are placeholders or not implemented.
- Reporting depends on the current helper path and still needs end-to-end validation against OsmAnd's OSM editing flow.

## Phone Test Plan

Use [docs/MORNING-TEST-PLAN.md](docs/MORNING-TEST-PLAN.md) after the APK packaging path is fixed.

## Credits

- Built on [OsmAnd](https://github.com/osmandapp/osmand) under Apache 2.0.
- Camera data from [DeFlock](https://deflock.org) / [OpenStreetMap](https://openstreetmap.org) contributors.
- Optional hardware direction from [CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You).

## License

Apache 2.0, inherited from OsmAnd.
