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

Last verified local APK output:

```text
OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk
```

Verified debug package/application ID: `com.yetiwurks.flockfree`.
Verified APK SHA-256: `f578dc2f5b14e063ceb53e0171a222a5ef4a1377d276b00e3352a56663ed1791`.
That APK was installed and launched on a Moto G Stylus over Wi-Fi ADB. The source tree has newer route-summary work after that packaged APK, so rebuild before testing route-summary behavior.

## What Works Now

- App branding and Gradle flavor are pointed at FlockFree for the `gplayFree` debug build.
- The FlockFree plugin is registered in OsmAnd and enabled by default.
- Camera data downloads from `https://data.dontgetflocked.com/cameras.geojson.gz`, is cached as GeoJSON, and refreshes weekly.
- The data loader handles both gzip and plain GeoJSON because the live `.gz` endpoint currently returns plain GeoJSON.
- Camera points render on the map at zoom 10+ with basic vendor colors and higher-zoom labels.
- Tapping a rendered camera opens a simple details dialog with brand, operator, direction, mount, surveillance zone, OSM ID/type, and timestamp when present.
- The map context menu has an `Add ALPR Camera` action that opens the current camera-reporting flow.
- When camera avoidance is enabled, newly calculated routes get a FlockFree toast summary of cameras near the route corridor.
- Basic preferences exist for map layer visibility, avoidance, alert distance, data timestamp, and CYD BLE enablement.

## Still Stubbed Or Thin

- Camera avoidance is advisory only. It reports cameras near the route corridor but does not yet block, penalize, or recalculate around roads.
- CYD BLE integration is preference-only in this repo state; no BLE manager/service/parser is wired in.
- Camera storage is an in-memory parsed GeoJSON list after cache/download, not a spatial SQLite database.
- No bundled first-run camera snapshot is present, so the first useful camera layer depends on network access to download data.
- Widgets, quick actions, a polished settings screen, and rich camera detail UI are placeholders or not implemented.
- Reporting depends on the current helper path and still needs end-to-end validation against OsmAnd's OSM editing flow.

## Phone Test Plan

Use [docs/MORNING-TEST-PLAN.md](docs/MORNING-TEST-PLAN.md). The first verified APK has been installed and launched on the Moto G Stylus over Wi-Fi ADB.

## Credits

- Built on [OsmAnd](https://github.com/osmandapp/osmand) under Apache 2.0.
- Camera data from [DeFlock](https://deflock.org) / [OpenStreetMap](https://openstreetmap.org) contributors.
- Optional hardware direction from [CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You).

## License

Apache 2.0, inherited from OsmAnd.
