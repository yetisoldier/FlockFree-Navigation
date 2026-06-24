# Changelog

All notable changes to FlockFree Navigation are documented here.

## [Unreleased]

## [v1.6.0] — 2026-06-23

### Added
- Google Maps-style lane guidance — recommended lanes show white fill with blue highlight outline, non-recommended lanes show grey fill, with proper day/night card backgrounds
- Transparent buildings ahead of turns — 3D buildings automatically hide when approaching a turn (< 300m) and restore after (> 500m) with hysteresis to prevent flickering
- Destination arrival preview — navigation bar shows destination name and side-of-street (left/right) when within 500m of destination, with "You have arrived" message at 50m
- Search-along-route chips — row of pill buttons (Gas, Food, Coffee, Parking, EV Charging) above the navigation bar, opening OsmAnd's quick search as an intermediate stop
- Camera avoidance route tradeoff explanations — shows "Avoids N cameras · +X min" when camera avoidance reroutes, with last tradeoff visible in FlockFree settings
- Camera proximity widget repositioned to TOP panel — eliminates portrait-mode overlap with the search bar
- WidgetReattachHelper utility for common widget reattach patterns

### Changed
- Night mode now follows system UI theme (APP_THEME) instead of sun position (AUTO) for the car profile
- Map renderer colors refined to exact Google Maps day/night palettes: land (#F5F5F0/#1A1A1E), water (#AECDF0/#1C3A4A), parks (#C8E6C9/#243B24), grassland, farm, and built-up areas
- Camera markers restyled to Google Maps POI pins: outer brand-color ring (7dp), inner white circle (5dp), tiny camera glyph at center
- FlockFreeLayer rendering optimized — cached dpToPx values and reused Path/collection objects to eliminate ~3,000-5,000 per-frame allocations in camera-dense areas
- FlockFreeIncidentLayer rendering optimized — reused Path and RectF objects to eliminate ~120 per-frame allocations for incident icons
- Listener leak cleanup — FlockFreeNavigationBar, NavigationReportButton, and SearchAlongRouteChips now properly unregister route listeners and Handler callbacks on plugin disable
- CydParserSelfCheck logging replaced with proper PlatformUtil logger

### Fixed
- CydBleService ForegroundServiceStartNotAllowedException on Android 14+ — startForegroundService() now wrapped in try/catch with fallback to startService()
- Three listener leaks fixed — FlockFreeNavigationBar, NavigationReportButton, and SearchAlongRouteChips were not unregistering their IRouteInformationListener registrations or Handler callbacks on plugin disable, potentially retaining a full MapActivity instance

## [v1.5.0] — 2026-06-23

### Added
- Google Maps-style navigation workflow controls: route option cards, add-stop chips for gas/coffee/food/parking/EV charging, a compact layers sheet, a bottom faster-route prompt with undo, richer navigation notification status, and destination arrival hints
- Automatic 3D map tilt during navigation — the map tilts to a perspective view when navigation starts and resets when stopped
- Google Maps-style Add Camera button available during active navigation

### Changed
- Second-next-turn preview chip restyled to match Google Maps: white card (day) / dark grey card (night) with dark primary text, secondary grey text, and a compact blue arrow
- Main turn indicator arrow is now bold white on the teal navigation card
- Second-next-turn chip repositioned to sit below the main turn indicator as a smaller secondary element (via vertical widget panel stacking)
- Second-next-turn chip arrow shrunk from 30dp to 24dp; distance text increased to 16sp bold; street text increased to 14sp
- Google Maps-style nav bar polish: accent strip, larger ETA, circular traffic dot, elevation display
- Google-style bottom navigation bar with traffic color fix
- Purple route line migration now triggers correctly

### Fixed
- Speed limit display and incident diagnostics improved
- TomTom traffic incidents and navigation traffic indicators repaired
- NetworkOnMainThreadException in incident layer resolved
- Portrait overlap between FlockFree search bar and right-side widgets fixed

## [v1.4.0] — 2026-06-22

### Added
- Google Maps-inspired map and navigation UI pass with lighter map colors, reduced POI clutter, subtler road casing, circular map controls, search bar, layer button, compact ETA/speed treatment, and a simplified blue location puck
- Android Auto availability for the FlockFree package through OsmAnd's navigation car app service
- Optional TomTom traffic setup documentation for users who want to provide their own API key
- Local map/navigation capabilities including 3D relief/maps, advanced widgets, vehicle metrics/OBD widgets, route and terrain coloring, gradient palette editing, and richer track organization options

### Changed
- Route and navigation visuals now use a cleaner Google Maps-style palette, including traffic-aware route coloring when a TomTom key is configured
- Weather forecast layers remain off by default because they rely on OsmAnd-managed forecast tile downloads rather than a user-provided provider key

### Fixed
- Hardened FlockFree puck assets as vectors so the map layer renderer can load them consistently
- Guarded heading-icon drawing to avoid a stale resource/null bitmap crash

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
