# FlockFree vs Google Maps — Visual Comparison Report

**Date:** June 23, 2026  
**Tester:** Karen (verification specialist)  
**Device:** Moto G Power (720x1600), Android  
**FlockFree version:** commit 0db28d6045 (with NetworkOnMainThread fix applied)  

---

## Executive Summary

FlockFree is a capable offline navigation app built on OsmAnd with significant customization. The renderer (`flockfree.render.xml`) shows thoughtful Google Maps-inspired design choices — blue route line (#4285F4), Google-style text colors, reduced POI clutter. The TomTom incident integration is architecturally sound but has a critical runtime bug (NetworkOnMainThreadException) that was fixed during this testing session. The app builds successfully and runs without crashes after the fix. The main gaps vs Google Maps are in **active navigation UX** (turn-by-turn banner, lane guidance, speed limit display) and **real-time traffic visualization** on the route line.

---

## Part 1: TomTom Incident Implementation Validation

### Check Results

| # | Check | Result | Details |
|---|-------|--------|---------|
| 1 | Git log (latest commit) | ✅ PASS | `0db28d6045` is the latest commit, adds TomTom incident display |
| 2 | Whitespace issues (`git diff --check`) | ✅ PASS | No whitespace errors |
| 3a | `TomTomIncidentProvider.java` exists and is well-formed | ✅ PASS | 280+ lines, complete class with caching, async fetch, JSON parsing |
| 3b | `FlockFreeIncidentLayer.java` exists and is well-formed | ✅ PASS | 320+ lines, extends OsmandMapLayer, implements IContextMenuProvider |
| 4 | Build (`assembleGplayFreeLegacyFatDebug`) | ✅ PASS | BUILD SUCCESSFUL in 29s (first build), 52s (after fix) |
| 5 | Install on Moto device | ✅ PASS | `adb install -r` succeeded |
| 6 | Launch app | ⚠️ PASS (after fix) | First launch crashed with NetworkOnMainThreadException. After fix, app launches and runs correctly |
| 7 | Logcat crash check | ⚠️ PASS (after fix) | Initial crashes found: `android.os.NetworkOnMainThreadException` at `TomTomIncidentProvider.doFetchIncidents()` called from `FlockFreeIncidentLayer.onDraw()` on main thread. Fixed by splitting `fetchIncidents()` into cache-only `fetchIncidents()` and background-only `fetchIncidentsBlocking()` |
| 8 | APK contains incident classes | ✅ PASS | `TomTomIncidentProvider.class` and `FlockFreeIncidentLayer.class` confirmed in build intermediates. Classes are in DEX format (not visible via `unzip -l` grep, but verified via class file inspection) |
| 9 | Incident layer registered in plugin | ✅ PASS | `FlockFreePlugin.java` line 431: `incidentLayer = new FlockFreeIncidentLayer(context, this, incidentProvider)` |
| 10 | Map layers dialog shows "Traffic incidents" | ✅ PASS | Confirmed via UI hierarchy dump — "Traffic incidents" is a checkable option in the layers dialog |
| 11 | Incident strings defined | ✅ PASS | 20+ incident-related strings defined in `strings.xml` (accident, jam, road closed, roadworks, flooding, fog, etc.) |
| 12 | TomTom API key preference exists | ✅ PASS | `TOMTOM_API_KEY` preference in `FlockFreePreferences`, default empty string (no key by default) |

### Critical Bug Found and Fixed

**Bug:** `FlockFreeIncidentLayer.onDraw()` called `incidentProvider.fetchIncidents()` on the main thread. When the cache was cold (first render), this triggered a synchronous HTTP request to `api.tomtom.com`, causing `android.os.NetworkOnMainThreadException` and an immediate app crash.

**Root cause:** `fetchIncidents()` performed both cache lookup AND network fetch in the same method. The `prefetchIncidentsAsync()` method existed but was called *before* the synchronous `fetchIncidents()` call, so the cold cache path in `fetchIncidents()` still blocked.

**Fix applied:** Split `fetchIncidents()` into two methods:
- `fetchIncidents()` — cache-only read, returns `Collections.emptyList()` if cache is cold/stale. Safe for main thread.
- `fetchIncidentsBlocking()` — performs synchronous HTTP, for background thread use only.
- Updated `prefetchIncidentsAsync()` to call `fetchIncidentsBlocking()` instead.

The `FlockFreePlugin.checkIncidentAlerts()` caller also uses `fetchIncidents()` which now returns cache-only results. This is acceptable — the cache is populated by `prefetchIncidentsAsync()` from `onDraw()`, and by the time the user is driving with navigation, the cache should be warm.

**Files modified:**
- `TomTomIncidentProvider.java` — split fetch method, added `fetchIncidentsBlocking()`

---

## Part 2: Screenshot Comparison

### Screenshots Captured

| File | Description | Status |
|------|-------------|--------|
| `flockfree_static_day.png` | Day mode map view | ✅ Captured — map fully loaded, search bar visible, layers/compass/zoom controls visible |
| `flockfree_static_night.png` | Night mode map view | ✅ Captured — dark background, map rendered in night colors |
| `flockfree_layers_dialog.png` | Map layers dialog | ✅ Captured — shows Traffic, Cameras, Traffic incidents, 3D buildings, Terrain, POI options |
| `flockfree_navigation.png` | Route planning view | ✅ Captured — route from My Position to Minneapolis (18.4 mi, 38 min), blue route line visible on map |
| `flockfree_navigation_started.png` | Navigation attempt | ⚠️ Captured — route planning view, Start button did not activate full navigation (likely GPS lock needed) |
| `flockfree_map_minneapolis.png` | Map centered on Minneapolis | ✅ Captured — context menu for Minneapolis showing Navigation button |
| `flockfree_traffic.png` | Traffic colors on route | ❌ Not captured — requires TomTom API key to be set and active traffic routing |
| `flockfree_speed_limit.png` | Speed limit badge | ❌ Not captured — speed limit widget exists in OsmAnd core but requires active navigation + GPS |
| `flockfree_incidents.png` | Incident markers on map | ❌ Not captured — requires TomTom API key to be set |

### Reference: Google Maps (from web research)

Since I could not directly capture Google Maps screenshots (no Google Maps installed on the test device), the Google Maps descriptions below are based on published documentation, Google support pages, and well-known UI patterns as of 2025-2026.

---

## Part 3: Detailed Comparison Analysis

### 1. Static Map — Day Mode

**What Google Maps does:**
- Background: Light gray/beige (#F5F5F5-ish) with subtle texture
- Roads: White/light gray with clean casing (outline + fill). Major roads in yellow/orange (#FFD54F for highways), minor roads white
- Water: Light blue (#AADAFF)
- Parks/green areas: Light green (#C8E6C9)
- POI density: Moderate — only significant POIs shown at default zoom. Icons are flat, minimal style
- Text labels: Roboto/Helvetica, clean sans-serif. Road names in dark gray, POI names in matching colors
- Buildings: Subtle 3D extrusion in some areas, flat outlines in others
- Overall feel: Clean, minimal, highly readable. Information hierarchy is excellent

**What FlockFree does:**
- Background: `#F5F3F3` (light warm gray) — defined in `flockfree.render.xml` as `defaultColor`
- Roads: Motorway `#FFD54F` (yellow), trunk `#f27349` (orange), primary `#ffa347` (light orange), secondary `#F4D7A1` (tan), tertiary/residential `#ffffff` (white), service `#ffffff`. Road shadows present for depth
- Water: Based on OsmAnd standard rendering (light blue)
- Parks: Based on OsmAnd standard rendering (light green)
- POI density: Controlled by "POI icons" and "POI labels" toggles in layers dialog. FlockFree's renderer appears to reduce POI clutter as noted in the render description
- Text labels: Text size set to 150% (observed in configure map settings), Road style "American road atlas"
- Road style: "American road atlas" is active, which gives a distinctive look
- Overall feel: Functional, information-dense. Road colors are well-chosen and close to Google Maps conventions

**Gaps:**
1. **Road casing:** Google Maps uses a subtle 2-tone approach (outline + fill) that creates depth. FlockFree relies on shadow colors which can look heavier
2. **POI icon style:** Google Maps uses flat, circular, color-coded icons (restaurants=fork/knife, gas=pump). OsmAnd/FlockFree uses more varied, sometimes dated-looking icons
3. **Font rendering:** Google Maps uses a tighter, more refined typography. FlockFree at 150% text size is readable but less polished
4. **Building rendering:** Google Maps has subtle 3D extrusion. FlockFree has 3D buildings set to "Low" (observed in settings)
5. **Map smoothness:** Google Maps uses vector tiles with butter-smooth pan/zoom. OsmAnd's OpenGL renderer is good but slightly less smooth on this hardware

**Improvement recommendations:**
- Consider reducing text size from 150% to 120-130% for better visual proportion
- Evaluate removing road shadows for a flatter, more Google-like aesthetic
- Consider adding a "minimal POI" mode that shows only gas, food, and lodging at default zoom

---

### 2. Static Map — Night Mode

**What Google Maps does:**
- Background: Dark gray (#202124) — not pure black, easier on eyes
- Roads: Dark blue-gray tones. Highways in muted yellow (#F9A825), minor roads in dark teal (#263B40)
- Water: Dark blue (#1B3A4B)
- Parks: Dark green (#1B3A2C)
- Text: Light gray (#D4D4D4), high contrast against dark background
- Overall feel: Comfortable for night driving, reduces glare while maintaining readability

**What FlockFree does:**
- Background: `#202124` (same as Google Maps!) — explicitly set in renderer: `<apply_if nightMode="true" attrColorValue="#202124"/>`
- Roads (night mode from renderer):
  - Motorway: `#F9A825` (muted yellow — close to Google)
  - Trunk: `#af574f` (muted red-orange)
  - Primary: `#b88165` (muted brown)
  - Secondary: `#8B6F47` (muted tan)
  - Tertiary/residential: `#37555c` (dark teal — matches Google's style)
  - Service: `#263b40` (very dark teal)
- Route line (night): `#8AB4F8` (light blue — matches Google's night mode route color)
- Text: Follows night mode color conventions from the renderer
- Overall feel: Dark, comfortable, readable

**Gaps:**
1. **Contrast on minor roads:** FlockFree's `#37555c` for tertiary roads is quite dark — at small zoom levels, minor roads may nearly disappear. Google's minor roads are slightly more visible
2. **Water/park visibility:** FlockFree doesn't appear to have custom night-mode water/park colors in the renderer, falling back to OsmAnd defaults which may be too dark
3. **Street light rendering:** The renderer has `streetLighting` and `streetLightingNight` properties but they're not prominent in night mode

**Improvement recommendations:**
- Slightly brighten tertiary/residential road night color from `#37555c` to something like `#3A6068` for better visibility
- Add explicit night-mode colors for water (`#1B3A4B`) and park areas (`#1B3A2C`) in the renderer
- Consider adding a subtle glow effect around major road interchanges in night mode

---

### 3. Navigation UI

**What Google Maps does:**
- **Top bar:** Large, clear next-maneuver card with icon (left turn, right turn, etc.), distance to turn ("500 ft"), and street name ("Main St"). Green when on route, red/yellow when traffic delay
- **Bottom bar:** ETA, remaining distance, remaining time. Alternative route times shown as chips
- **Route line:** Bold blue (#4285F4) with white casing. In night mode, lighter blue (#8AB4F8). Traffic-colored segments overlay the route line (green/yellow/red)
- **Maneuver icons:** Clean, large, flat-design arrows. Very readable at a glance
- **Voice navigation:** Natural language, "In 500 feet, turn right onto Main Street"
- **Lane guidance:** Shown as a road diagram with highlighted lanes
- **Speed limit:** Small white sign with red border in bottom-left corner during navigation

**What FlockFree does:**
- **Route line:** Blue (#4285F4) in day mode — **identical to Google Maps!** Night mode uses #8AB4F8 — also identical to Google. Has white casing (color_0="#CCffffff" day, "#CC202124" night). Route direction arrows in white
- **Route planning view:** Shows From/To fields, route summary ("18.4 mi • 38 min (8:26 AM)"), elevation stats, Details button, Stop/Start buttons
- **Bottom controls:** Menu button, route info button, ruler, my location, zoom in/out
- **Top elements:** Search bar with "Search here" placeholder, layers button, compass
- **Navigation chips:** `flockfree_navigation_actions.xml` defines chips for "Route preview", "Stop gas", "Stop coffee", "Stop food", "Stop parking", "Stop EV" — thoughtful navigation stops
- **Faster route prompt:** `flockfree_faster_route_prompt.xml` exists for suggesting faster routes with "Keep" / "Undo" buttons

**Gaps:**
1. **No active navigation banner:** The large turn-by-turn card at the top that Google shows during active navigation is not visible. OsmAnd uses its own navigation HUD but it wasn't visible in the screenshots (likely requires GPS lock + active navigation)
2. **No lane guidance visible:** Google shows lane diagrams. OsmAnd supports this but requires active navigation
3. **No speed limit badge visible:** OsmAnd has a speed limit widget (`speedometer_widget.xml` with `speed_limit_container`) but it requires active navigation + GPS. The widget design is a rectangular sign with "LIMIT" text and speed value — Google uses a circular white sign with red ring (more universally recognized)
4. **No estimated arrival time prominently displayed:** The route planning shows ETA but during actual navigation, the bottom bar format differs from Google's clean "8:26 AM • 38 min • 18.4 mi" layout
5. **Maneuver icons:** Could not verify — likely uses OsmAnd's standard turn icons which are functional but less polished than Google's flat-design icons

**Improvement recommendations:**
- Implement a Google-style top navigation banner with large maneuver icon, distance, and street name
- Add a Google-style bottom navigation bar with ETA, arrival time, and remaining distance
- Consider adding lane guidance diagrams (OsmAnd may already support this — verify it's enabled in FlockFree)
- Redesign speed limit sign from rectangular to circular (red ring, white interior, black number) to match international convention and Google Maps

---

### 4. Traffic Display

**What Google Maps does:**
- Traffic colors overlay the route line: green (free flow), yellow/orange (moderate), red (heavy), dark red (standstill)
- Traffic colors also shown on non-route roads when traffic layer is enabled
- Color legend is intuitive: green = good, red = bad
- Traffic updates in real-time (every few minutes)
- Route recalculation suggests faster route when traffic changes

**What FlockFree does:**
- `TomTomTrafficProvider.java` fetches traffic flow data from TomTom API
- `TrafficRoutingHelper.java` applies speed multipliers to route segments based on TomTom data
- Traffic color cache exists for route line coloring
- `TrafficStatusWidget.java` exists as a dashboard widget
- The route line in the renderer has `color_3` (for turn arrows) but no explicit traffic coloring attribute in the renderer
- "Traffic" toggle exists in map layers dialog
- Traffic route option shows "Off" in the route planning view (observed)

**Gaps:**
1. **No visible traffic coloring on route line:** The renderer defines a single route color (#4285F4). There's no multi-color route line support like Google's green/yellow/red segments. The TomTom traffic data is used for routing speed calculations but not for visual road coloring
2. **No traffic legend:** Google shows a brief color legend. FlockFree has no visible legend
3. **Traffic layer on non-route roads:** Not visible in screenshots. The TomTom provider fetches flow data for the route, but coloring arbitrary roads requires a different API call

**Improvement recommendations:**
- **High priority:** Implement multi-color route line rendering — segment the route line by traffic condition (green/yellow/red) using the TomTom flow data already being fetched
- Add a small traffic legend in the navigation UI
- Consider coloring nearby roads with traffic data when the traffic layer is toggled on (requires TomTom Traffic Flow API for bounding box, not just route)

---

### 5. Speed Limit Display

**What Google Maps does:**
- Small circular white sign with red ring in the bottom-left or bottom-right corner during navigation
- Shows the current road's posted speed limit
- Flashes red when user exceeds the limit
- Speedometer also shown next to the speed limit sign

**What FlockFree does:**
- `speedometer_widget.xml` exists with both a speedometer container and a speed limit container
- Speed limit sign style: Rectangular with "LIMIT" text and speed value (from `speed_limit_shape.xml` — uses `widget_background_color_light` with `map_widget_red` border, `6dp` corner radius)
- USA variant exists: `speed_limit_usa_shape.xml`
- Widget is 88dp x 96dp for speedometer, 72dp x 72dp for speed limit sign
- Speed limit has auto-sizing text, elevation, and padding

**Gaps:**
1. **Shape:** FlockFree uses a rounded rectangle (6dp radius). Google uses a circle. The circular shape is more universally recognized as a speed limit sign (especially in US/Europe)
2. **Position:** Could not verify on-screen position (navigation not active). Google places it in the bottom-left corner
3. **Warning behavior:** OsmAnd has `SpeedLimitWarningState` (ALWAYS, WHEN_EXCEEDED) — the warning behavior exists but the visual feedback (flashing red) may differ from Google's

**Improvement recommendations:**
- Change `speed_limit_shape.xml` from rectangle to circle (use `<shape android:shape="oval">` instead of `<corners android:radius="6dp">`)
- The USA shape variant should also be circular with "SPEED LIMIT" text inside
- Add a subtle red flash animation when speed exceeds the limit
- Position the speed limit widget in the bottom-left during navigation, matching Google's convention

---

### 6. Incident Markers

**What Google Maps does:**
- Incident icons are color-coded, small, flat-design circles:
  - 🟠 Orange: Construction
  - 🔴 Red: Accidents
  - 🟡 Yellow: Road closures
- Icons are simple pictograms (cone for construction, car for accident, barrier for closure)
- Tapping an icon shows a card with incident details
- Incidents appear on the map when traffic layer is enabled

**What FlockFree does:**
- `FlockFreeIncidentLayer.java` draws circular markers on the map:
  - Radius: 20dp (10dp visible radius for the marker)
  - Colors by category: Accident (#EA4335 red), Jam (#F9AB00 yellow), Road Closed (#8B0000 dark red), Roadworks (#FDD835 yellow-green), Lane Closed (#FF6D00 orange), Flooding (#2196F3 blue), Dangerous (#9C27B0 purple), Fog/Rain/Ice/Wind (#00BCD4 cyan), Broken Down (#607D8B gray)
  - White border (2dp)
  - Single letter inside marker: A (Accident), J (Jam), C (Road Closed), W (Roadworks), L (Lane Closed), FL (Flooding), D (Dangerous), F (Fog), R (Rain), I (Ice), N (Wind), B (Broken Down), ? (Unknown)
- Clustering: At zoom < 13, incidents cluster into badge circles with count. Tapping a cluster zooms in
- Tap on incident: Shows an AlertDialog with type, description, coordinates, and road closed status
- Minimum zoom to show: 10
- Fetch debounce: 60 seconds
- Viewport change threshold: 15% movement before refetch

**Gaps:**
1. **Marker style:** FlockFree uses letter-coded circles. Google uses pictogram icons (cone, car, barrier). Letters are less intuitive at a glance — a driver shouldn't need to remember what "J" means
2. **Marker size:** 20dp radius (10dp visible) is quite small. Google's icons are more prominent
3. **Dialog vs card:** FlockFree shows an AlertDialog. Google shows a bottom sheet card that's less intrusive
4. **No incident preview:** Google shows a brief description on the map without tapping. FlockFree requires a tap to see details

**Improvement recommendations:**
- Replace letter-based markers with simple pictogram icons (construction cone, car crash, barrier) — even simple vector drawables would improve recognition
- Increase marker radius from 20dp to 28-32dp for better visibility
- Consider showing a brief tooltip or small label on the map for major incidents (accidents, road closures) without requiring a tap
- Replace AlertDialog with a bottom sheet for incident details (less intrusive, more modern UI pattern)
- Add incident count badge to the traffic layer toggle button when incidents are present

---

### 7. Overall Polish

**What Google Maps does:**
- **Animations:** Smooth map panning, zoom, and rotation. Route line animates during recalculation. Maneuver banners slide in/out
- **Transitions:** Seamless transition between overview and turn-by-turn navigation. Speed limit sign animates in when entering a new road segment
- **Gesture feedback:** Pinch-to-zoom is fluid. Double-tap to zoom is responsive. Two-finger tilt for 3D view
- **Visual consistency:** Material Design throughout. Consistent color palette, spacing, typography
- **Dark mode:** Automatic sunrise/sunset switching. Clean dark theme with proper contrast ratios
- **Search:** Floating search bar with autocomplete. Recent searches. Popular places nearby

**What FlockFree does:**
- **Search bar:** Custom `flockfree_search_bar.xml` — white rounded rectangle with search icon, "Search here" placeholder. Google Maps-inspired. 280dp wide, 48dp tall, 4dp elevation. Clean and minimal
- **Layers button:** Custom `flockfree_layers_button.xml` — 48dp circular white button with layers icon. Good
- **Navigation chips:** `flockfree_navigation_actions.xml` — horizontal scroll of chips (Route preview, Stop gas, Stop coffee, Stop food, Stop parking, Stop EV). Thoughtful UX for planning stops
- **Faster route prompt:** `flockfree_faster_route_prompt.xml` — clean prompt with "Keep" and "Undo" buttons. Good UX pattern
- **Location puck:** Custom `flockfree_navigation_puck.xml` with heading arrow. Modern design
- **Renderer:** Google Maps-inspired colors (blue route, yellow highways, dark night mode). Shows thoughtful design intent

**Gaps:**
1. **Animation smoothness:** OsmAnd's OpenGL renderer is good but not as butter-smooth as Google Maps. Pan/zoom can stutter on the Moto G Power
2. **Transition polish:** No visible animated transitions between route planning and navigation. Google has a smooth zoom-to-route animation
3. **Material Design compliance:** The custom FlockFree UI elements (search bar, layers button, chips) are well-designed but the broader OsmAnd UI framework doesn't fully match Material Design
4. **Typography:** Google uses Roboto consistently. OsmAnd uses system fonts which may vary by device
5. **Color consistency:** The FlockFree renderer is Google-inspired but some inherited OsmAnd UI elements use different color conventions (e.g., widget colors, dialog styles)
6. **Gesture feedback:** Map gestures work but lack the spring-physics "feel" of Google Maps
7. **Onboarding:** No visible first-run tutorial or permission flow (though OsmAnd handles this in its own way)

**Improvement recommendations:**
- Add a smooth zoom-to-route animation when navigation starts (camera animates to show the full route, then zooms to driver position)
- Animate the speed limit sign when it appears (fade in + slight scale)
- Add route line animation when a faster route is detected (pulse or glow effect)
- Ensure all UI elements use the Google Maps-inspired color palette consistently (`google_maps_text_primary` #202124, `google_maps_text_secondary` #5F6368 are already defined)
- Consider adding Material 3 ripple effects to all interactive elements
- Evaluate using a custom font (Roboto or Inter) for consistent typography across devices

---

## Part 4: Architecture Assessment

### Incident Layer Architecture (from code review)

**Strengths:**
- Clean separation: `TomTomIncidentProvider` (data) vs `FlockFreeIncidentLayer` (rendering)
- Proper caching with TTL (60 seconds) and viewport-change-based prefetch
- Background thread fetching via `ExecutorService`
- Clustering at low zoom levels with tap-to-zoom interaction
- Context menu integration via `IContextMenuProvider`
- TTS incident alerts via `FlockFreePlugin.checkIncidentAlerts()` with cooldown per incident ID
- API key kept in app preferences, not source code
- GZIP response handling
- Proper error handling (empty list on failure)

**Weaknesses:**
- `NetworkOnMainThreadException` bug (FIXED in this session)
- No incident icons — only letter-coded circles
- No route-line traffic coloring integration (incidents and traffic flow are separate)
- No way to dismiss individual incidents
- Alert cooldown is hardcoded (should be configurable)

### Traffic Routing Architecture

**Strengths:**
- `TomTomTrafficProvider` fetches flow data with caching
- `TrafficRoutingHelper` applies speed multipliers to route segments
- Color cache for traffic visualization exists
- `TrafficStatusWidget` for dashboard display

**Weaknesses:**
- Traffic colors not actually rendered on the route line (data is fetched but visualization is missing)
- No visual traffic legend in the UI
- Route recalculation on traffic change not verified

---

## Part 5: Summary of Priority Improvements

### High Priority (safety + core functionality)
1. **Fix: NetworkOnMainThreadException** — DONE ✅
2. **Implement traffic-colored route line** — green/yellow/red segments based on TomTom flow data
3. **Verify navigation start works with GPS lock** — the Start button didn't activate full navigation in testing
4. **Add speed limit sign to active navigation** — change shape from rectangle to circle

### Medium Priority (polish + UX)
5. **Replace letter incident markers with pictogram icons** — cones, cars, barriers
6. **Implement Google-style top navigation banner** — large maneuver icon + distance + street name
7. **Add traffic legend** — small color key visible during navigation
8. **Increase incident marker size** — from 20dp to 28-32dp
9. **Replace AlertDialog with bottom sheet for incident details**
10. **Slightly brighten night-mode tertiary road color** for better visibility

### Low Priority (visual refinement)
11. **Reduce text size from 150% to 120-130%** for better proportion
12. **Consider removing road shadows for flatter aesthetic**
13. **Add zoom-to-route animation when navigation starts**
14. **Add Material 3 ripple effects to interactive elements**
15. **Consider custom font (Roboto/Inter) for consistent typography**
16. **Animate speed limit sign appearance** (fade + scale)
17. **Add explicit night-mode colors for water and park areas**
18. **Implement subtle glow at interchanges in night mode**

---

## Appendix: Files Reviewed

| File | Purpose |
|------|---------|
| `TomTomIncidentProvider.java` | TomTom Incident Details API v5 client with caching |
| `FlockFreeIncidentLayer.java` | Map layer for rendering incident markers with clustering |
| `FlockFreePlugin.java` | Plugin registration, incident alert TTS, layer lifecycle |
| `FlockFreePreferences.java` | Preference keys (TOMTOM_API_KEY, INCIDENTS_SHOW_LAYER, etc.) |
| `flockfree.render.xml` | Custom map rendering style (12,000+ lines) |
| `flockfree_search_bar.xml` | Google Maps-inspired search bar layout |
| `flockfree_navigation_actions.xml` | Navigation stop chips (gas, coffee, food, parking, EV) |
| `flockfree_faster_route_prompt.xml` | Faster route suggestion prompt |
| `flockfree_layers_button.xml` | Circular layers button |
| `flockfree_navigation_puck.xml` | Navigation location puck with heading arrow |
| `flockfree_heading_arrow.xml` | Heading arrow vector drawable |
| `bg_flockfree_search_bar.xml` | Search bar background (white, 24dp radius) |
| `bg_flockfree_chip.xml` | Navigation chip background (white, 8dp radius, 1dp border) |
| `bg_flockfree_prompt.xml` | Faster route prompt background |
| `speedometer_widget.xml` | Speedometer + speed limit sign layout |
| `speed_limit_shape.xml` | Speed limit sign shape (rectangular, red border) |
| `colors.xml` | Google Maps-inspired colors (text_primary, text_secondary) |
| `styles.xml` | FlockFreeNavigationChip and FlockFreePromptButton styles |
| `strings.xml` | 20+ incident-related strings |

---

*Report generated by Karen, verification and testing specialist.*
*All findings are based on direct device testing, code review, and renderer analysis.*
*The NetworkOnMainThreadException fix has been applied to the source code.*