# Changelog

All notable changes to FlockFree Navigation are documented here.

## [Unreleased]

### Fixed
- Speed limit sign now renders above the layers button instead of behind it (added elevation to the alarm warning widget).
- CYD detections list now shows all detections including those without a GPS fix. Previously detections without GPS were silently dropped from both the in-memory list and the persisted store, causing "No detections recorded" even when the CYD status showed active detections.

### Changed
- Online search results are now sorted by distance from your current location, so closer matches appear first instead of being ordered by relevance alone.

## [v1.8.6] — 2026-06-30

### Changed
- Search now defaults to online when internet is available and automatically falls back to offline if online returns no results. Previously only street-address queries triggered online search; now all queries prefer online first.

## [v1.8.5] — 2026-06-30

### Changed
- Camera proximity alert toast now persists until the driver has passed the camera, with the distance updating live as the vehicle approaches. No more fixed 10-second timer.
- When navigating with an active route, camera alerts now only fire for cameras on or near the route line, not cameras on side streets or cross roads that happen to be within the radial search radius.
- Camera alert toast redesigned with a wider, more readable layout (⚠ icon + text side-by-side, 17sp font, rounded dark background).

### Fixed
- Fixed the next-next-turn widget showing a rectangular outline artifact between the distance unit (e.g. "ft") and the street name. The exit-sign background drawable and text stroke outlines are now removed when FlockFree is active.
- Fixed camera alert toast using a double-toast hack (firing two standard toasts 3.5s apart) to approximate longer duration. Replaced with a single WindowManager overlay that stays visible until the camera is passed, with a 2-minute safety fallback timeout.

## [v1.8.4] — 2026-06-29

### Fixed
- Fixed `APP_RELEASE_VERSION` still hardcoded as `1.8.1`, causing the in-app update checker to report the wrong installed version. Now correctly set to `1.8.4`.

## [v1.8.3] — 2026-06-29

### Fixed
- Fixed the nightlyFree (dev) launcher icon to use FlockFree-branded adaptive icon assets instead of OsmAnd's stock nightly icon drawables.
- Fixed the right-side widget panel overlapping the top bar in portrait mode when FlockFree is active; the panel now starts 96dp below the top edge.

### Added
- Added a 'View CYD detections' preference in FlockFree settings that displays a dialog listing recent CYD hardware detections with detection type, source, age, and GPS fix status. Tapping a detection shows its details.

## [v1.8.2] — 2026-06-28

### Changed
- Camera-avoidance route acceptance now compares actual original-route Flock exposure against actual candidate-route Flock exposure, while keeping camera-to-road association counts only for road blocking, diagnostics, and relaxation ordering.
- Non-zero-camera privacy routes now have detour guardrails: no more than the greater of 10 minutes or 20 percent extra time, plus no more than 25 percent extra distance. Zero-camera privacy routes are still accepted immediately.
- Camera-to-road mapping now checks projected distance to route road geometry edges instead of only matching stored geometry vertices.

### Fixed
- The route preview sheet now refreshes after FlockFree route analysis and shows either the fastest-vs-privacy comparison card or a FlockFree route-check status card when no separate privacy route is available.

### Documentation
- Clarified the Flock-only source metadata limitation in the README and route-avoidance design notes.

### Verification
- Source-only checks passed after the route-guardrail and route-sheet visibility changes.
- Built and installed a local route-sheet status APK on a Moto G Stylus over Wi-Fi ADB, and the route preview behavior was field-validated.

## [v1.8.1] — 2026-06-28

### Added
- Added a FlockFree settings update checker that reads the latest GitHub Release, reports whether the installed FlockFree release is current, and links directly to the newest sideload APK.
- Added a quiet once-daily update check on map resume for future builds so users who install this release can be notified about later public releases from inside the app.

### Changed
- FlockFree settings now include an App Updates section with `Check for updates` and `Last update check` rows.

### Verification
- Source-only checks passed after adding the GitHub Releases update checker.

## [v1.8.0] — 2026-06-28

### Added
- Added a dedicated Flock Camera Avoidance Routing document covering Flock-only filtering, route corridor scanning, temporary road blocking, iterative relaxation, route acceptance rules, traffic interaction, known limits, and verification steps.

### Changed
- Camera awareness now filters the bundled and refreshed source feed to Flock-labeled records only. Map markers, nearest-camera checks, nearby alerts, route avoidance, route comparison counts, and the camera proximity widget now use the same Flock-only data boundary.
- The on-device SQLite camera database was upgraded and defensively filters reads to Flock rows, with the current bundled snapshot indexing 89,942 Flock camera records.
- FlockFree map defaults now hide house/building numbers, POI labels, and POI icons to keep the driving map quieter. The normal Configure Map toggles remain available for users who want to turn them back on.
- Nearby Flock camera warnings now repeat the toast once after a short delay so the alert is visible long enough during a drive.
- FlockFree wording in settings, route status, route comparison, diagnostics, and documentation now says Flock cameras where the feature is Flock-specific instead of implying all ALPR cameras are routed around.

### Fixed
- The lanes widget no longer shows free-driving/current-road lane graphics while browsing or casually driving. Route-derived lane guidance remains available during active navigation.
- FlockFree navigation HUD elements were tightened for the cleaner map layout, including compact search, map HUD, speedometer, ruler, compass, and turn-chip styling.

### Verification
- Source-only checks passed after the Flock-only and map-cleanup changes.
- Built and installed a map-cleanup APK on a Moto G Stylus over Wi-Fi ADB; readiness confirmed `MapActivity` focused and the Flock-only camera database populated with 89,942 rows.

## [v1.7.1] — 2026-06-28

### Added
- US street-address detection now routes likely address searches through the US Census geocoder before falling back to broader online search, fixing valid addresses that OsmAnd's local fuzzy search treated as POI queries.
- Active navigation now uses a Google Maps-style directional vehicle arrow with route and bearing fallbacks so the marker keeps a stable heading through brief GPS gaps or low-speed movement.

### Changed
- Route calculation progress now reserves its final segment for FlockFree post-route comparison/refinement work and only reaches 100% after route options are ready.
- Camera proximity widget now appears only during active navigation and reports known ALPR cameras remaining on the active route.
- Developer build/install docs and helper scripts now target the `gplayFreeOpenglFat` APK flavor so local test builds include the OpenGL renderer, 2D/3D mode, and 3D buildings.

### Fixed
- Non-navigation car tracking now snaps accurate followed-map positions to the nearest road when snap-to-road is enabled, capped at 35 meters to avoid dragging truly off-road positions onto unrelated streets.
- Camera markers now redraw during map rotate, pan, and zoom animations and use the elevated OpenGL projection path, preventing delayed marker jumps during animated map movement.
- Camera viewport queries now use a short-lived padded bounds cache so camera-dense map rotation does not repeatedly re-query and temporarily drop nearby markers.
- Map tracking animation and prediction windows were tightened to reduce laggy or over-projected vehicle movement on bursty location updates.

### Verification
- Built the OpenGL debug APK, installed it on a Moto G Stylus, and confirmed FlockFree launches to `MapActivity` without FlockFree fatal or ANR log lines.
- Bench-tested the road-stick fix with an intentionally off-road mock driving path in South St. Paul; the vehicle marker snapped back onto 9th Avenue South.
- Camera marker redraw changes are compiled into the release; final confidence still benefits from a real rotate/pan field pass over a camera-dense area.

## [v1.6.3] — 2026-06-24

### Changed
- Removed the Traffic Status widget from FlockFree widget creation and availability while keeping TomTom traffic routing, route coloring, and navigation traffic summaries active in the background.
- Camera widget now hides while browsing the map and only appears during active navigation.
- Camera widget now reports the number of known ALPR cameras remaining on the active route instead of nearby cameras around the current map position.
- Traffic routing is enabled by default again for FlockFree profiles when a TomTom API key is configured.

## [v1.6.2] — 2026-06-24

### Fixed
- Removed Public Transport, Train, Boat, Aircraft, Skiing, and Horseback Riding from the FlockFree profile catalogue, including the drawer profile switcher, App profiles settings list, and New profile base-profile picker.

## [v1.6.1] — 2026-06-24

### Fixed
- Camera and incident marker positions now use the OpenGL renderer's own screen-space projection (NativeUtilities.getPixelFromLatLon) instead of 2D getPixXFromLatLon — markers stay fixed to their geographic positions during map rotation
- Tap handlers also updated to use NativeUtilities.getLatLonFromElevatedPixel for accurate screen-to-lat/lon conversion with OpenGL renderer

### Changed
- Camera Proximity and Traffic Status widgets moved from TOP panel to RIGHT panel — now appear as small side widgets instead of being hidden behind the search bar
- Default app profiles cleaned up — removed Public Transport, Boat, Aircraft, Skiing, Train, and Horseback Riding as not relevant to FlockFree's use case

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
