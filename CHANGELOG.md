# Changelog

All notable changes to FlockFree Navigation are documented here.

## [v1.3.0] — 2026-06-22

### Added
- WiFi Flock detection — passively scans for Flock Safety camera WiFi beacons using `WifiManager` and known Flock OUI list
- CYD auto-pause integration — automatically pauses CYD companion WiFi scanning when a Flock WiFi beacon is detected
- Driving mode as default profile on app startup
- Debug broadcast receiver (`net.osmand.flockfree.TEST_ALERT`) for testing camera alerts without driving past a camera
- Vibration feedback on camera alerts (buzz-pause-buzz pattern)

### Changed
- Camera alerts now use toast + vibration only — removed persistent notification, notification channel, and `NotificationCompat` infrastructure
- Cleaner alert UX with no notification bar clutter

### Fixed
- WiFi scanner now checks and requests location permission and device Location before enabling; reports start failure instead of silently leaving the toggle on

## [v1.2.0] — 2026-06-19

### Added
- Low-zoom camera clustering — dense camera areas display as cluster circles at low zoom levels for better performance and readability
- Branded FlockFree startup splash — full-screen FF branding replaces OsmAnd secondary splash
- Android 12+ splash screen handling with transparent system icon slot
- Refreshed README screenshots from current build

## [v1.1.0] — 2026-06-18

### Fixed
- Camera marker drift on map rotation — fixed double-rotation in `FlockFreeLayer` where OsmAnd rotated the canvas while our layer also used already-rotated screen coordinates
- Camera cone direction — fixed cone bearing math that treated `tileBox.getRotate()` as radians instead of degrees
- Duplicate cameras — added deduplication in both GeoJSON parsing and SQLite database loading paths (823 exact duplicate entries removed)

### Added
- Camera proximity widget — shows count of cameras within 1 km and distance to nearest camera
- 4 Quick Actions — show/hide cameras, toggle avoidance, toggle alerts, add camera
- SQLite camera persistence with spatial grid for fast viewport queries
- CYD BLE integration — foreground service, GPS streaming, detection review workflow
- OSM ALPR report tag-prefill via OsmAnd POI editor
- Nearby camera alerts with cooldown logic
- Camera orientation cones at zoom 15+
- Bundled 104,902 camera seed asset
- Iterative relaxation camera avoidance routing
- Partial avoidance reporting when full avoidance isn't possible
- Status persistence for route check results

## [v1.0.0] — 2026-06-18

### Added
- Initial MVP release
- FlockFree plugin skeleton registered in OsmAnd
- Camera data download from `data.dontgetflocked.com`
- Camera markers on map with brand-based colors
- Basic settings screen with toggle for camera layer