# Contributing to FlockFree Navigation

FlockFree Navigation is an OsmAnd fork with an in-tree ALPR camera awareness plugin. Contributions are welcome — bug reports, feature ideas, camera data improvements, code, and documentation.

## Quick Start for Developers

### Prerequisites

- Android Studio (Hedgehog or newer) or command-line Gradle
- Android SDK (API 21–34)
- Java 17
- Git

### Build from source

```bash
git clone https://github.com/yetisoldier/FlockFree-Navigation.git
cd FlockFree-Navigation

# OsmAnd resources (required for build)
git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git ../resources

# Build the debug APK
ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK=$HOME/Android/Sdk \
  ./gradlew :OsmAnd:assembleGplayFreeLegacyFatDebug \
  -x test --no-daemon --max-workers=1
```

The APK will be at:
```
OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk
```

### Install to a connected device

```bash
adb install -r OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk
```

### One-command build + install

```bash
scripts/flockfree-user-build-install.sh
```

This builds, installs over Wi-Fi ADB, launches FlockFree, and runs a readiness check.

## Project Structure

```
FlockFree-Navigation/
├── OsmAnd/                          # Main app module
│   └── src/net/osmand/plus/plugins/flockfree/
│       ├── FlockFreePlugin.java     # Main plugin class
│       ├── data/                    # Camera database, sync, spatial grid
│       ├── layer/                   # Map rendering (camera markers, cones, clusters)
│       ├── routing/                 # Camera avoidance routing
│       ├── reporting/               # OSM POI editor integration
│       ├── ble/                     # CYD BLE service, protocol parser
│       ├── widgets/                 # Camera proximity widget
│       ├── fragments/               # Settings, camera details
│       └── quickactions/            # Quick actions (toggle cameras, add camera, etc.)
├── OsmAnd-api/                      # API module (AIDL, constants)
├── docs/
│   ├── ARCHITECTURE.md              # Full architecture document
│   └── DATA-LAYER-NOTES.md          # Camera data pipeline notes
├── scripts/                         # Build, test, and diagnostics scripts
└── README.md
```

## Plugin Architecture

FlockFree is built as an **in-tree plugin** inside OsmAnd's main module, following the same pattern as `OsmEditingPlugin` and `ParkingPositionPlugin`. This gives full access to OsmAnd internals without a separate Gradle module.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full architecture document covering:
- Plugin registration and lifecycle
- Camera data layer (fetch, cache, SQLite, spatial grid)
- Camera avoidance routing (iterative relaxation)
- BLE integration with CYD-Flock-You hardware
- WiFi Flock detection
- Reporting flow (OSM editor integration)
- UI customization and branding
- Key OsmAnd classes reference

## Key Features for Contributors

### Camera Data

- **Source:** [DeFlock](https://deflock.org) / OpenStreetMap — `https://data.dontgetflocked.com/cameras.geojson.gz`
- **Bundled seed:** 104,902 cameras in `OsmAnd/assets/flockfree/cameras.geojson.gz`
- **Storage:** SQLite with spatial grid index for viewport queries
- **Sync:** Weekly network refresh with gzip/plain auto-detection

### WiFi Flock Detection

- Uses Android `WifiManager` scan results to detect Flock Safety camera WiFi beacons
- Matches against known Flock OUI list
- Triggers toast + vibration alert and auto-pauses CYD companion when detected
- Requires `ACCESS_FINE_LOCATION` and device Location enabled at runtime

### BLE CYD Integration

- `CydBleService` — foreground service maintaining BLE connection
- Nordic UART service UUIDs (see [CYD-Flock-You pairing protocol](https://github.com/yetisoldier/CYD-Flock-You/blob/main/docs/deflock-pairing-protocol.md))
- Streams phone GPS to CYD via `FYGPS` BLE UART
- Receives detection events as JSON lines
- Auto-pauses CYD WiFi scanning when Flock WiFi beacon detected

### Camera Alerts

- Toast + vibration only (no persistent notification)
- Cooldown logic: 60s per-camera, 30s for same camera
- Debug trigger: `adb shell am broadcast -a net.osmand.flockfree.TEST_ALERT`

## Coding Guidelines

- Follow existing OsmAnd code style (Java, tabs, 4-space indent)
- Keep plugin code inside `net.osmand.plus.plugins.flockfree` package
- Register new preferences in `FlockFreePlugin` constructor
- Use `OsmandPreference` system for all user settings
- Add string resources to `OsmAnd/res/values/flockfree_strings.xml`
- Test on the Moto G Power (Android 13, API 34) as the primary test device

## Testing

### Source checks (no build required)

```bash
scripts/flockfree-source-checks.sh
```

### Build verification

```bash
./gradlew :OsmAnd:compileGplayFreeLegacyFatDebugJavaWithJavac
```

### Full readiness check on installed APK

```bash
scripts/flockfree-morning-readiness.sh
```

### Field test session

```bash
scripts/flockfree-field-test-session.sh
```

### Debug camera alert trigger

```bash
adb shell am broadcast -a net.osmand.flockfree.TEST_ALERT
```

## Reporting Issues

1. Check existing issues first
2. Include: device model, Android version, FlockFree version, steps to reproduce
3. Attach logcat output if possible: `adb logcat -d | grep -i flockfree`

## Pull Requests

1. Fork the repo and create a feature branch
2. Keep changes focused — one feature or fix per PR
3. Test your build: `./gradlew :OsmAnd:compileGplayFreeLegacyFatDebugJavaWithJavac`
4. Update relevant documentation (README, ARCHITECTURE.md, etc.)
5. Reference any related issues

## Companion Repositories

| Repository | Role |
|------------|------|
| [CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You) | ESP32 firmware for the CYD companion hardware |
| [DeFlock CYD branch](https://github.com/yetisoldier/deflock-app/tree/cyd-flock-you-integration) | Flutter Android app with CYD companion support |

## License

Apache 2.0, inherited from OsmAnd. By contributing, you agree your contributions are licensed under the same terms.