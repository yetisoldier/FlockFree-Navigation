# FlockFree Navigation

FlockFree Navigation is an OsmAnd fork with an in-tree FlockFree plugin for ALPR camera awareness, CYD hardware integration, and community reporting.

![FlockFree Splash](docs/screenshots/ff-splash-branding.png)

## Features

### Camera Awareness
- **105,000+ camera database** — Bundled seed of 104,902 ALPR cameras with direction data, stored in SQLite for fast spatial queries
- **Camera orientation cones** — Translucent view cones rendered on the map at zoom 15+ showing camera heading
- **Nearby camera alerts** — Proximity notifications with cooldown logic when approaching known cameras
- **Nearest camera inspection** — Map-center query to find the closest camera within 1,000m

![Map View](docs/screenshots/ff-map.png)

### CYD Hardware Integration
- **BLE foreground service** — `CydBleService` runs as `connectedDevice` type, survives backgrounding
- **Global BLE setting** — CYD BLE toggle is global across all OsmAnd profiles
- **Phone GPS streaming** — Streams phone GPS to CYD hardware via `FYGPS` BLE UART
- **Detection review workflow** — CYD detections appear as map markers for manual review before submission
- **Simulate mode** — `FYSIM` support for bench testing without live RF

### Route Avoidance
- **Iterative relaxation avoidance** — Identifies camera-adjacent roads, blocks them, and recalculates. If full avoidance fails, progressively unblocks the least-camera-impactful roads (up to 4 iterations) until a viable route is found.
- **Partial avoidance reporting** — When full avoidance isn't possible, reports how many camera roads were blocked and how many cameras remain on the route.
- **Status persistence** — Route check results persist across app restarts

### Reporting
- **OSM POI editor integration** — Opens OsmAnd's native editor with ALPR/surveillance tags prefilled
- **Brand presets** — Automatic tagging for known ALPR manufacturers (Flock Safety, Motorola Solutions, etc.)
- **Draft persistence** — Report drafts survive app restarts

### Branding
- **FlockFree identity** — Custom blue/white FF road mark on navy splash, cyan headers, red toggles
- **All asset densities** — Launcher, adaptive, and splash icons in mdpi through xxxhdpi

![FlockFree Settings](docs/screenshots/ff-settings-branding.png)

## Installation

### Option 1: Download the APK (easiest)

1. Go to [Releases](https://github.com/yetisoldier/FlockFree-Navigation/releases)
2. Download the latest `FlockFree-gplayFree-legacy-fat-debug.apk`
3. Enable "Install unknown apps" for your browser/Files app in Android settings
4. Tap the APK to install
5. Launch **FlockFree** from your app drawer

### Option 2: Build from source

```bash
git clone https://github.com/yetisoldier/FlockFree-Navigation.git
cd FlockFree-Navigation
git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git ../resources

ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK=$HOME/Android/Sdk \
  ./gradlew :OsmAnd:assembleGplayFreeLegacyFatDebug \
  -x test --no-daemon --max-workers=1
```

The APK will be at:
```
OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk
```

Install to a connected device:
```bash
adb install -r OsmAnd/build/outputs/apk/gplayFreeLegacyFat/debug/OsmAnd-gplayFree-legacy-fat-debug.apk
```

### One-command build + install (for developers)

```bash
scripts/flockfree-user-build-install.sh
```

This builds, signs, installs over Wi-Fi ADB, launches FlockFree, and runs a readiness check. Add `--field-session` to also start the timed evidence collector.

## First Run Setup

1. **Launch FlockFree** — The app opens to the map. The FlockFree plugin is enabled by default.
2. **Grant permissions** — Allow location, Bluetooth, and notifications when prompted.
3. **Download offline maps** (optional but recommended) — Go to Menu → Maps & Resources → Download maps → choose your region. Route avoidance requires offline vector maps.
4. **Camera data loads automatically** — The bundled seed (104,902 cameras) is available immediately. A network refresh updates from `data.dontgetflocked.com` weekly.

![Navigation Drawer](docs/screenshots/ff-drawer.png)

## Usage Guide

### Viewing Cameras

Cameras appear on the map at zoom 10+. At zoom 15+, orientation cones show which direction each camera faces. Tap any camera for details (brand, operator, direction, mount type).

### Nearby Camera Alerts

1. Open Menu → Plugins → FlockFree → Settings
2. Enable **Nearby camera alerts**
3. Set the **Alert distance** (default: 200 meters)
4. While navigating or moving, you will receive a toast when approaching a known camera

Use **Check map center alert** in settings to bench-test alerts without driving.

### Route Avoidance

1. Open Menu → Plugins → FlockFree → Settings
2. Enable **Avoid cameras on routes**
3. Calculate a route as normal. FlockFree runs a second pass to block roads adjacent to known cameras and reroutes around them.
4. A toast summary shows how many cameras were found near the route. The result persists in Settings as "Last route check".

Note: Route avoidance works with offline vector maps only.

![Plugins List](docs/screenshots/ff-plugins.png)

### Reporting a New Camera

1. **From the map**: Long-press the location → tap **Add ALPR Camera** from the context menu
2. **From settings**: Use **Draft report at map center** to test the flow from a specific location
3. Choose the camera brand preset (Flock Safety, Motorola Solutions, etc.)
4. OsmAnd's POI editor opens with ALPR/surveillance tags prefilled
5. Review, adjust, and save through the standard OSM editor

Nothing uploads automatically. You always review and confirm before submitting.

### CYD Hardware Integration

The CYD (Cheap Yellow Display) is an optional ESP32-based sensor that detects ALPR camera Wi-Fi signatures. See [CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You) for hardware details.

#### Pairing a CYD

1. Power on the CYD device
2. In FlockFree, open Menu → Plugins → FlockFree → Settings
3. Enable **CYD BLE hardware**
4. FlockFree scans for `CYD-Flock-You` over Bluetooth LE and connects automatically
5. The CYD status row in Settings shows connection state, GPS, SD, and detection count

#### When CYD Detects Something

1. The CYD sends a detection event over BLE
2. FlockFree shows a "CYD detection received" toast
3. The detection appears as a **cyan diamond marker** on the map labeled `CYD`
4. Tap the CYD marker → choose **Review as ALPR camera**
5. Select brand preset, adjust direction if known
6. OsmAnd's POI editor opens with tags prefilled at the detection coordinates
7. Review, adjust the position, and submit manually

If the CYD has no GPS fix, the detection is logged but does not create a map marker until coordinates are available.

#### Bench Testing Without Hardware

Use **Simulate CYD detection** in Settings to create a test marker from your current phone location or map center. This tests the full review flow without a physical CYD.

## Settings Reference

| Setting | Description |
|---------|-------------|
| Show cameras on map | Toggle camera point visibility |
| Camera data | Shows database status, source, and last refresh |
| Refresh camera data | Manually refresh from network |
| Nearest camera at map center | Find the closest camera within 1km |
| Avoid cameras on routes | Enable experimental route avoidance |
| Route corridor radius | Distance from route to check for cameras |
| Nearby camera alerts | Enable proximity notifications |
| Alert distance | Radius for proximity alerts |
| Check map center alert | Bench-test alerts from current map position |
| Draft report at map center | Open the ALPR reporting flow at map center |
| CYD BLE hardware | Enable CYD Bluetooth connection |
| Simulate CYD detection | Create a test detection marker |
| Request CYD status | Query CYD for telemetry |

## Verification Scripts

For developers and field testers:

| Script | Purpose |
|--------|---------|
| `scripts/flockfree-source-checks.sh` | Source-only verification (no build required) |
| `scripts/flockfree-morning-readiness.sh` | Full readiness check on installed APK |
| `scripts/flockfree-user-build-install.sh` | Build, install, launch, and verify |
| `scripts/flockfree-moto-diagnostics.sh` | Capture logcat, UI, and database state |
| `scripts/flockfree-field-test-session.sh` | Timed evidence collection for manual testing |
| `scripts/flockfree-adb-recover.sh` | Troubleshoot lost Wi-Fi ADB connections |

## Technical Details

- **Package:** `com.yetiwurks.flockfree`
- **Min Android:** API 21 (Android 5.0)
- **Target:** Android 14 (API 34)
- **Camera seed:** 104,902 points (bundled, offline-first)
- **Camera data source:** [DeFlock](https://deflock.org) / [OpenStreetMap](https://openstreetmap.org)

## Known Limitations

- Route avoidance is offline-only (requires downloaded vector maps)
- Iterative relaxation caps at 4 retries to limit recalculation latency; very dense camera areas may still fall back to the original route
- CYD detection to camera submission is a manual review flow (no auto-upload)
- Reporting flow opens the editor but does not verify end-to-end OSM upload
- No live RF drive test completed yet (bench simulation verified only)

## Companion Hardware

Pair with [CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You) firmware v1.2.0+ for ALPR detection hardware. The CYD is an ESP32 with a 2.4" TFT that passively monitors Wi-Fi for Flock-style RF signatures and sends detections to FlockFree over Bluetooth LE.

## Credits

- Built on [OsmAnd](https://github.com/osmandapp/osmand) under Apache 2.0
- Camera data from [DeFlock](https://deflock.org) / [OpenStreetMap](https://openstreetmap.org) contributors
- Optional hardware direction from [CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You)

## License

Apache 2.0, inherited from OsmAnd.