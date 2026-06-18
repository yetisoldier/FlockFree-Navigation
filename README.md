# FlockFree Navigation

**ALPR camera-aware offline navigation. Powered by OpenStreetMap.**

FlockFree Navigation is an OsmAnd fork that adds surveillance camera awareness to your everyday navigation. It shows known ALPR (Automatic License Plate Reader) cameras on the map, can route you around them, and lets you report new cameras to OpenStreetMap - all in one app.

## Features

### Camera Map Layer
- 104,000+ ALPR cameras from OpenStreetMap, pre-loaded
- Color-coded by manufacturer (Flock Safety, Motorola, Genetec, Leonardo, and more)
- Tap any camera for details: brand, direction, operator, last updated

### Camera-Avoidance Routing
- Toggle "Avoid cameras" and routes automatically steer around known ALPR locations
- Configurable avoidance radius (50-500m)
- Uses OsmAnd's native turn-by-turn navigation engine

### Camera Reporting
- One-tap reporting of new cameras to OpenStreetMap
- Pre-configured tag presets for Flock Safety, Motorola, Genetec, and other ALPR vendors
- Direction and orientation controls
- Submits through OsmAnd's existing OSM changeset pipeline

### CYD Hardware Integration (Optional)
- Connects to [CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You) ESP32 hardware sensor
- Passive 2.4 GHz WiFi promiscuous detection of Flock Safety devices
- Detections appear as review candidates on the map
- Phone streams GPS coordinates to the hardware via Bluetooth LE

### Offline Everything
- Vector map tiles (tiny downloads, fast rendering)
- Camera data cached locally (28MB, refreshed weekly)
- Full offline navigation with voice guidance
- No tracking, no ads, no data collection

## Data Source

All camera data comes from [OpenStreetMap](https://www.openstreetmap.org), exported by [DeFlock](https://deflock.org) as GeoJSON from `data.dontgetflocked.com`. FlockFree Navigation reads and writes the same OSM data - we are another client on the same network.

## Building

```bash
git clone https://github.com/yetisoldier/FlockFree-Navigation.git
cd FlockFree-Navigation
git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git ../resources
ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK=$HOME/Android/Sdk \
  ./gradlew --no-watch-fs --max-workers=1 :OsmAnd:assembleNightlyFreeLegacyFatDebug -x :OsmAnd-java:test
```

The debug build uses a development keystore included in the repo.

## Credits

- Built on [OsmAnd](https://github.com/osmandapp/osmand) - Apache 2.0 licensed
- Camera data from [DeFlock](https://deflock.org) / [OpenStreetMap](https://openstreetmap.org) contributors
- Hardware detection by [CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You)

## License

Apache 2.0 (inherited from OsmAnd)
