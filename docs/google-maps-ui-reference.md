# Google Maps UI Reference & FlockFree Improvement Plan

**Researched:** June 2026  
**Google Maps version:** March 2026 "Immersive Navigation" redesign (biggest navigation update in a decade)  
**Sources:** Ars Technica, 9to5Google, Fast Company, Google Maps Help docs, Android Police, on-device comparison screenshots

---

## Part 1: Google Maps UI Element Reference

### A. DAY MODE (Map Browse / Idle)

#### Top Bar Area
| Element | Google Maps | Details |
|---------|------------|---------|
| Search bar | Rounded white pill, ~360-400dp wide, 48dp tall | Centered at top. Contains: search icon (gray magnifier) + hint text "Search here" in `#5F6368`. Subtle shadow (4dp elevation). Tappable opens full search overlay. |
| Profile/avatar bubble | Circular avatar, 36dp, top-right corner | Opens account menu. Sometimes replaced by Google app icon. |
| Voice/Ask Maps button | Gemini sparkle icon button | New in March 2026. Appears as a circular icon button near search bar. Opens "Ask Maps" conversational AI. |
| Layer button | Missing in recent versions | Google moved layers into a bottom sheet carousel. No top-right layer button in current design. |

#### Map Area
| Element | Google Maps | Details |
|---------|------------|---------|
| Map style | Light theme with Google's custom palette | Land: warm off-white `#F5F5F0` / Parks: muted green `#C8E6C9` / Water: light blue `#AECDF0` / Roads: light gray `#D4D4D4` with subtle casing |
| POI markers | Minimal, selective POI density | Only essential POIs shown (gas, food, lodging). Small colored dots or icons. Not cluttered. |
| 3D buildings | Subtle in browse mode, transparent in nav | Light gray extrusions, low opacity, only for notable buildings. |
| Location puck | Blue circle with white outline, accuracy halo | Solid blue dot with white ring + light blue accuracy circle. Smaller than OsmAnd's default. |
| Compass | Small red/gray compass indicator | Appears top-right when map is rotated. Tapping reorients north. Hidden when facing north. |
| Map controls cluster | Bottom-right floating buttons | Stack of 2-3 circular white buttons (48dp): zoom in, zoom out, recenter/locate. Clean, minimal. |
| Bottom sheet | Persistent peeking sheet at bottom | Shows "Explore" and "Go" tabs with suggested places. Swipeable. Height: ~120dp peek, expands to full screen. Contains restaurant/place cards in horizontal carousel. |

#### Color Palette (Day)
| Token | Hex |
|-------|-----|
| Background (land) | `#F5F5F0` / `#EFEFE7` |
| Water | `#AECDF0` / `#BFD5F0` |
| Parks/Green | `#C8E6C9` / `#B7E1B7` |
| Roads (local) | `#FFFFFF` |
| Roads (major) | `#FBD744` (yellow-amber for highways) |
| Road casing | `#E0E0E0` (subtle gray outline) |
| Route line | `#4285F4` (Google Blue) in browse; `#AA46A4` (purple/violet) in navigation |
| Route outline | Darker shade of route color, 2-3dp shadow |
| Text primary | `#202124` |
| Text secondary | `#5F6368` |
| Accent blue | `#1A73E8` |
| Traffic green | `#00A854` |
| Traffic yellow | `#F9AB00` |
| Traffic red | `#EA4335` |

---

### B. NIGHT MODE (Dark Theme)

#### Top Bar Area
| Element | Google Maps | Details |
|---------|------------|---------|
| Search bar | Dark gray pill `#2A2A2E`, same shape | Text in `#9AA0A6`. Same layout as day. |
| Profile/avatar | Same position, unchanged | |
| Map style | Full dark theme | Land: `#1A1A1E` / Water: `#1C3A4A` / Parks: `#243B24` / Roads: `#3A3A3E` with subtle casing `#4A4A4E` |
| POI markers | Reduced density, same icons | Slightly muted colors at night. |
| 3D buildings | More prominent at night | Translucent white/gray extrusions, more visible against dark map. |
| Location puck | Brighter blue, larger accuracy halo | `#4285F4` with `#1A73E8` glow ring for visibility. |

#### Color Palette (Night)
| Token | Hex |
|-------|-----|
| Background (land) | `#1A1A1E` / `#212121` |
| Water | `#1C3A4A` / `#1A2A3A` |
| Parks/Green | `#243B24` / `#1E3A1E` |
| Roads (local) | `#3A3A3E` / `#4A4A4E` |
| Roads (major) | `#5C5C12` (dark amber) |
| Road casing | `#4A4A4E` |
| Route line | `#5C8DFF` (lighter blue for night visibility) |
| Route outline | `#3A5FCC` |
| Text primary | `#E8EAED` |
| Text secondary | `#9AA0A6` |
| Traffic green | `#00A854` (unchanged) |
| Traffic yellow | `#F9AB00` (unchanged) |
| Traffic red | `#EA4335` (unchanged) |
| Navigation banner | `#1F3A38` (dark teal) |
| Navigation banner text | `#FFFFFF` |

#### Night Mode Activation
- **Automatic:** Follows system dark theme setting
- **Settings path:** Profile → Settings → Navigation → Map display → Color scheme → Day / Night / Automatic
- **In navigation:** Switches based on time of day and sunset/sunrise when set to Automatic

---

### C. NAVIGATION MODE (Active Turn-by-Turn)

#### Top: Turn Instruction Card (Banner)
| Element | Google Maps | Details |
|---------|------------|---------|
| Card shape | Full-width banner, ~120-140dp tall | Rounded bottom corners (16dp radius). Extends edge-to-edge at top. |
| Background color (Day) | **Dark teal `#00796B`** (Material teal 700) | This is the signature Google Maps nav color. |
| Background color (Night) | **Darker teal `#004D40`** or `#1F3A38` | Slightly darker for night. |
| Maneuver arrow icon | Large white arrow/turn pictogram, 48-56dp | Left side of card. Bold, high contrast. Shows exact turn type (left, right, slight left, U-turn, merge, keep left/right, roundabout). |
| Distance to turn | Large white text, 28-32sp, bold | "200 m" or "500 ft" format. Right of arrow. |
| Turn instruction text | White text, 16-18sp | "Turn right onto Main St" or "Keep left to merge onto I-35W". Below distance. |
| Exit/ref badge | White rounded pill with highway shield | Shows exit number when applicable (e.g., "Exit 54C"). Right side of card. |
| Second-next turn preview | Small inset card below or right of main card | Shows next-next maneuver as a small chip with mini arrow + distance. Visible when next turn is within ~2 miles. |
| Microphone button | Small white circular button, right side | Voice input for "Ask Maps" during navigation. |
| Street/road name | White text at bottom of card | Current road name or next road name. |

#### Middle: Map View (3D Immersive Navigation)
| Element | Google Maps | Details |
|---------|------------|---------|
| Map perspective | **Tilted 3D view** (~45° angle) | The March 2026 "Immersive Navigation" update makes this the default. Shows buildings, overpasses, terrain in 3D. |
| 3D buildings | Semi-transparent ahead of turns | Buildings become transparent when approaching a turn so they don't block the route. Smart zoom widens the view. |
| Lane guidance | Highlighted lane markings on road | Correct lanes glow blue/white. Wrong lanes dimmed. Shows crosswalks, traffic lights, stop signs. |
| Route line | **Purple/violet `#AA46A4`** or `#9C4DCC` with darker outline | Thick (8-12dp), rounded caps. Has subtle shadow/glow. Distinctly different from browse-mode blue. |
| Route traveled | Faded/dimmed portion of route | Already-completed route segments fade to lighter shade. |
| Alternate routes | Thin gray/white lines | Non-selected routes shown as thin alternatives. |
| Traffic on route | Color-coded segments overlay on route line | Green/yellow/red segments directly on the route. Red for heavy, yellow for moderate, green for clear. |
| Location puck | **Blue navigation arrow** (chevron) | Triangular blue arrow pointing in travel direction. Replaces the round dot during navigation. |
| Speed limit sign | **Circular white sign with red border** | Top-left corner. Shows current speed limit (e.g., "65"). Uses MUTCD standard style (US). |
| Current speed | Below speed limit sign | Shows current speed (e.g., "62 mph"). Red if exceeding limit. |
| Compass | Hidden or minimal during navigation | |

#### Bottom: Navigation Bar / ETA Strip
| Element | Google Maps | Details |
|---------|------------|---------|
| Container | White (day) or dark gray `#2A2A2E` (night) bottom bar | Full-width, ~64-72dp tall. |
| ETA / remaining time | **Green text** (day) / lighter green (night), 18-20sp bold | Left side. "28 min" format. Turns red/orange if delayed. |
| Distance remaining | Gray text, 14-16sp | "18 mi" next to or below ETA. |
| Arrival time | Gray text, 14sp | "9:27 AM" format. |
| Route conditions | Small colored dot or text | Shows traffic conditions on route (e.g., "Light traffic"). |
| Recenter button | Small circular button | Appears when map is panned. Re-centers on current location. |
| Close/exit navigation | X button, right side | Exits navigation mode. |
| Report button | Small icon button | Community reports: crash, speed trap, construction, etc. May appear as a floating action button on the right side instead of in the bottom bar. |
| Alternate route info | May show "1 min faster" pill | If a faster route is detected during navigation, a pill appears offering rerouting. |

#### Navigation Actions (Chips/Buttons)
| Element | Google Maps | Details |
|---------|------------|---------|
| Route options pill | Appears when alternates exist | Shows comparison: "10 min slower / No tolls" etc. |
| Search-along-route | Floating search pill | Appears mid-route: "Search along route" with gas/food/coffee quick chips. |
| Destination preview | Street View thumbnail + parking info | As approaching destination, shows entrance photos and parking suggestions. |

#### Voice Guidance
| Feature | Google Maps | Details |
|---------|------------|---------|
| Natural language | "Go past this exit and take the next one for Illinois 43 South" | References landmarks, not just street names. |
| Next-next references | Mentions turns after the immediate one | "Turn right, then in 500 feet, turn left." |
| Traffic conditions | "There's heavy traffic ahead on your route" | Proactive alerts. |
| Speed limit warnings | Audio alert when exceeding limit | Optional, can be toggled. |

---

### D. NEW MARCH 2026 "IMMERSIVE NAVIGATION" FEATURES

These are the latest Google Maps features that define the current state-of-the-art:

1. **3D Immersive View** — Buildings, overpasses, terrain rendered in 3D during navigation. Gemini models extract from Street View + aerial photos.
2. **Smart Zoom** — Automatically zooms out before complex maneuvers so you can see the full picture.
3. **Transparent Buildings** — Buildings become transparent ahead of tricky turns and lane changes so they don't block the route.
4. **Lane Guidance** — Highlights correct lanes, crosswalks, traffic lights, stop signs. "To help you make that turn or merge confidently."
5. **Natural Voice Guidance** — References landmarks, not just street names. Mentions turns after the next one.
6. **Route Tradeoff Explanations** — "Longer trip with less traffic or a faster one with a toll."
7. **Real-time Disruption Alerts** — Community-reported construction, crashes, with 10M+ contributions/day.
8. **Destination Preview** — Street View imagery, building entrance highlight, parking recommendations, "which side of the street."
9. **"Ask Maps" Gemini Button** — Conversational AI for trip planning, accessible during navigation.
10. **Material 3 Expressive** — Android 16 design language updates: rounded shapes, better one-handed usability, design consistency.

---

## Part 2: FlockFree Current State vs Google Maps

### What FlockFree Already Has ✓

| Feature | Status | Notes |
|---------|--------|-------|
| Search bar pill | ✓ | 280dp, 48dp, rounded, centered. Close to Google's style. |
| Layers button | ✓ | 48dp circular, top-right. Google moved away from this, but acceptable for FlockFree. |
| FlockFree renderer | ✓ | Custom renderer with lighter land/water/park colors, reduced POI clutter. |
| Google-style navigation turn card | ✓ | `bg_flockfree_navigation_card.xml` with teal `#00796B` background, rounded. |
| Speed limit sign | ✓ | `speed_limit_usa_shape.xml` — white circle with dark border. Present but not reliably visible. |
| Current speed widget | ✓ | Speedometer widget in bottom-left. |
| Traffic legend | ✓ | Light/Moderate/Heavy color dots. Visible in navigation. |
| Route line styling | ✓ | Medium Google-blue, thick. |
| Text scale defaults | ✓ | 1.2x default, applied via visual defaults migration. |
| Day/night auto mode | ✓ | `DAYNIGHT_MODE.AUTO` for car profile. |
| Route arrows off | ✓ | `ROUTE_SHOW_TURN_ARROWS = false` by default. |
| Location puck | ✓ | Custom `flockfree_location_puck.xml` and navigation puck. |
| Navigation action chips | ✓ | Route preview, gas, coffee, food, parking, EV chips. |
| Faster route prompt | ✓ | Bottom prompt with keep/undo buttons. |
| Traffic routing | ✓ | TomTom flow integration (billing issue aside). |
| Traffic incident layer | ✓ | TomTom incident markers with clustering (billing issue aside). |
| Camera avoidance routing | ✓ | Iterative relaxation camera-avoidance algorithm. |
| Camera alerts + proximity widget | ✓ | Toast alerts + widget showing camera count and nearest distance. |
| CYD BLE integration | ✓ | Companion hardware for Flock Safety WiFi detection. |
| OSM reporting | ✓ | Tag-prefill ALPR camera reporting. |
| Quick actions | ✓ | Show/hide cameras, toggle avoidance, toggle alerts, add camera. |

### What FlockFree is Missing or Needs Work ✗

| Gap | Priority | Google Maps Reference |
|-----|----------|----------------------|
| **Route line is blue, not purple/violet during navigation** | High | Google uses `#AA46A4` purple in nav mode to distinguish from browse blue |
| **No 3D tilted map during navigation** | High | 45° tilt is core to Immersive Navigation |
| **No lane guidance** | High | Correct lanes highlighted, wrong lanes dimmed |
| **Night mode not visibly activating** | High | Karen's test confirmed `cmd uimode night yes` doesn't switch FlockFree to dark |
| **Speed limit sign not consistently visible during navigation** | High | Google shows it reliably top-left |
| **Turn card is white, not teal** | Medium | Karen's screenshots show the card appears white-ish, not the intended teal `#00796B` |
| **No second-next-turn preview chip** | Medium | Google shows next-next maneuver as small chip |
| **No transparent buildings ahead of turns** | Medium | Core Immersive Navigation feature |
| **No destination arrival preview** | Medium | Street View, entrance highlight, parking |
| **No route tradeoff explanations** | Medium | "Longer but no tolls" etc. |
| **No community reporting button** | Medium | Google's "Report" for crashes, speed traps, construction |
| **ETA strip not Google-styled** | Medium | Need green ETA text, distance, arrival time, close button |
| **No "search along route" chips** | Low | Gas/food/coffee chips that appear mid-route |
| **No natural voice guidance** | Low | Landmark references, next-next turn mentions |
| **Traffic colors not visible on route line** | High | Karen noted route stays blue despite data being present |
| **Bottom sheet not Google-styled** | Low | "Explore/Go" tabs, but this is less critical for a navigation-focused app |
| **Map controls not Google-styled** | Low | Google uses bottom-right circular button stack |
| **No "Ask Maps" equivalent** | N/A | FlockFree has no AI assistant (by design) |

---

## Part 3: FlockFree UI Improvement Plan

### Phase 1: Core Visual Parity (High Impact, Moderate Effort)

**Goal:** Make FlockFree navigation look like Google Maps at a glance.

#### 1.1 Fix Night Mode Activation
- **Problem:** `daynight_mode=AUTO` doesn't visibly switch to dark map when Android night mode is forced.
- **Root cause:** OsmAnd's AUTO mode follows sun position, not Android system UI mode. This is correct behavior, but Karen's test shows it doesn't switch even at appropriate times.
- **Action:** Investigate the OsmAnd AUTO day/night calculation for the car profile. Consider adding a `daynight_mode=SYSTEM` fallback or verify the sun-position calculation is working for the test location/time. At minimum, ensure manual Night mode works.
- **Files:** `OsmandSettings.java`, `FlockFreePlugin.java` (visual defaults)
- **Effort:** 1-2 hours

#### 1.2 Purple Navigation Route Line
- **Problem:** Route line stays Google-blue `#4285F4` during navigation. Google switches to purple/violet.
- **Action:** When navigation is active, change the route line color to `#9C4DCC` (purple) with a darker outline `#6A1B9A`. Keep blue for browse/route-preview mode.
- **Files:** `BaseRouteLayer.java`, `RouteGeometryWay.java`, `colors.xml`
- **Effort:** 2-3 hours

#### 1.3 Fix Turn Card Color Visibility
- **Problem:** Karen's screenshots show the turn card appears white/light, not the intended teal `#00796B`.
- **Action:** Verify `bg_flockfree_navigation_card.xml` is being applied correctly. Check if day/night mode is overriding the background. Ensure the card background uses the teal color in both day and night navigation. Add a night variant (`#004D40`).
- **Files:** `bg_flockfree_navigation_card.xml`, `navigation_widget_full.xml`, `colors.xml`
- **Effort:** 1-2 hours

#### 1.4 Speed Limit Sign Consistency
- **Problem:** Speed limit sign not reliably visible during navigation.
- **Action:** Ensure the speed limit widget is registered for the car profile with top-left positioning. Verify the OsmAnd speed limit data source is active (comes from map data). Check if the widget is being hidden by other HUD elements.
- **Files:** `FlockFreePlugin.java` (widget registration), `speed_limit_usa_shape.xml`, `map_hud_top.xml`
- **Effort:** 1-2 hours

#### 1.5 Traffic Colors on Route Line
- **Problem:** Karen noted route stays blue despite traffic data being present (`146 live, 12 samples`).
- **Action:** Wire the `TrafficRoutingHelper` color data into `RouteGeometryWay` so traffic segments are colored green/yellow/red along the route line. This may already be partially implemented — verify the color path is actually being drawn.
- **Files:** `RouteGeometryWay.java`, `TrafficRoutingHelper.java`, `FlockFreePlugin.java`
- **Effort:** 3-4 hours

#### 1.6 Google-Style Bottom Navigation Bar
- **Problem:** FlockFree's bottom bar is the stock OsmAnd ETA strip — not Google-styled.
- **Action:** Create a FlockFree-styled bottom nav bar with:
  - Green ETA text (left)
  - Distance + arrival time (below ETA, gray)
  - Close/exit button (right)
  - Recenter button (appears when panned)
  - Optional: Report button (floating, right side)
- **Files:** New layout `flockfree_navigation_bottom_bar.xml`, `map_hud_bottom.xml`, `FlockFreePlugin.java`
- **Effort:** 3-4 hours

### Phase 2: Navigation Experience Polish (Medium Impact, Higher Effort)

**Goal:** Close the gap on Google Maps' navigation experience.

#### 2.1 3D Map Tilt During Navigation
- **Problem:** FlockFree uses flat 2D map. Google Maps uses 45° tilt.
- **Action:** Enable OsmAnd's 3D map mode during navigation. OsmAnd supports map tilt — set the car profile default tilt to ~45° during active navigation. This is the single biggest visual differentiator.
- **Files:** `OsmandSettings.java` (map tilt preference), `FlockFreePlugin.java` (apply on navigation start)
- **Effort:** 2-3 hours (if OsmAnd tilt API is accessible)

#### 2.2 Second-Next-Turn Preview Chip
- **Problem:** `SecondNextTurnWidget` exists but isn't consistently visible.
- **Action:** Ensure the second-next-turn widget shows as a small chip below/beside the main turn card during navigation. Style it as a mini version of the main card with smaller arrow + distance.
- **Files:** `SecondNextTurnWidget.java`, `navigation_widget_full.xml`, `widget_second_next_turn_day.xml`
- **Effort:** 1-2 hours

#### 2.3 Destination Arrival Preview
- **Problem:** No destination preview with entrance/parking info.
- **Action:** When navigation is within 0.5 miles of destination, show a bottom card with:
  - Destination name + address
  - "Arriving at [side of street]"
  - Nearby parking suggestions (from OSM POI data)
- **Files:** New `FlockFreeDestinationPreview.java`, new layout XML
- **Effort:** 4-6 hours

#### 2.4 Route Tradeoff Explanations
- **Problem:** No "longer but no tolls" style comparison.
- **Action:** When multiple routes are calculated, show comparison pills: "5 min slower / Avoids cameras" or "2 min faster / Has 3 cameras". This is FlockFree-specific — trade camera avoidance against time.
- **Files:** `FlockFreeNavigationAssistant.java`, new layout for route comparison pills
- **Effort:** 3-4 hours

#### 2.5 Community/Flock Reporting Button
- **Problem:** No quick report button during navigation.
- **Action:** Add a floating "Report" button (right side, mid-screen) during navigation that opens quick-report options:
  - Report ALPR camera (existing flow)
  - Report Flock Safety WiFi detection
  - Report traffic incident (if TomTom data available)
  - Report road closure
- **Files:** `FlockFreePlugin.java`, new layout `flockfree_report_button.xml`
- **Effort:** 2-3 hours

#### 2.6 Transparent Buildings Ahead of Turns
- **Problem:** 3D buildings can block the route view during turns.
- **Action:** When approaching a turn (< 0.5 miles), reduce 3D building opacity to 30%. Restore after turn completion. This leverages OsmAnd's existing 3D building layer.
- **Files:** `FlockFreeNavigationAssistant.java` (distance-to-turn monitoring), building layer opacity control
- **Effort:** 3-4 hours

### Phase 3: Advanced Features (Lower Priority, Higher Effort)

#### 3.1 Lane Guidance
- **Problem:** No highlighted lane markings.
- **Note:** OsmAnd has some lane data in OSM. This is a significant feature requiring lane rendering on the map.
- **Effort:** 8-12 hours (would need custom lane rendering overlay)

#### 3.2 Search-Along-Route Chips
- **Problem:** No quick gas/food/coffee search during navigation.
- **Action:** Reuse the existing navigation action chips but make them contextual — show gas/food/parking when within 2-5 miles of route midpoint. Wire to OSM POI search.
- **Effort:** 4-6 hours

#### 3.3 Natural Voice Guidance Enhancement
- **Problem:** OsmAnd's TTS is basic — street names only, no landmark references.
- **Action:** Enhance the `FlockFreeNavigationAssistant` TTS to include:
  - Next-next turn references ("Turn right, then turn left in 500 feet")
  - Camera proximity mentions ("Flock Safety camera in 0.3 miles")
  - Traffic condition mentions
- **Effort:** 3-4 hours

#### 3.4 Map Style Refinement
- **Problem:** FlockFree renderer is close but not exactly matching Google's palette.
- **Action:** Fine-tune the FlockFree renderer XML to match Google's day/night color tokens exactly. Add a night-specific renderer variant.
- **Files:** `rendering_styles/flockfree.render.xml` (or equivalent), night variant
- **Effort:** 2-3 hours

### Phase 4: FlockFree-Specific UI (Not in Google Maps)

These are FlockFree features that Google Maps doesn't have, so they need original design that still feels cohesive.

#### 4.1 Camera Marker Style Polish
- Current camera markers should be distinct but not jarring against the Google-style map.
- Use Google Maps' POI marker style: small circular pin with icon, drop shadow.
- Flock Safety cameras: blue/white pin with small camera icon.
- Other cameras: gray pin with camera icon.

#### 4.2 Camera Proximity Widget Restyle
- Restyle the Camera Proximity Widget to match Google Maps' widget aesthetic:
  - White/dark rounded card with shadow
  - Small camera icon + count + distance
  - Red accent when within alert distance
- Currently this was removed from the right panel due to portrait overlap. Find a better position (bottom strip or top-right below layers).

#### 4.3 Flock Detection Alert Card
- When WiFi Flock detection or CYD detection triggers, show a Google-style alert card (rounded, red accent) rather than just a toast.
- Card should persist for 10 seconds with "Dismiss" and "Report" buttons.

---

## Part 4: Recommended Execution Order

### Sprint 1 — Visual Foundation (Day 1-2)
1. **1.1** Fix night mode activation
2. **1.3** Fix turn card teal color visibility
3. **1.4** Speed limit sign consistency
4. **1.2** Purple navigation route line

### Sprint 2 — Navigation Bar & Traffic (Day 2-3)
5. **1.6** Google-style bottom navigation bar
6. **1.5** Traffic colors on route line

### Sprint 3 — 3D & Navigation Experience (Day 3-5)
7. **2.1** 3D map tilt during navigation
8. **2.2** Second-next-turn preview chip
9. **2.5** Reporting button

### Sprint 4 — Polish & FlockFree Features (Day 5-7)
10. **2.4** Route tradeoff explanations (camera avoidance vs time)
11. **2.3** Destination arrival preview
12. **2.6** Transparent buildings ahead of turns
13. **3.4** Map style refinement

### Sprint 5 — Advanced (Future)
14. **3.1** Lane guidance
15. **3.2** Search-along-route chips
16. **3.3** Natural voice guidance

---

## Appendix: Key Color Tokens for FlockFree

```xml
<!-- Day -->
<color name="google_maps_blue">#4285F4</color>
<color name="google_maps_nav_purple">#9C4DCC</color>
<color name="google_maps_nav_purple_outline">#6A1B9A</color>
<color name="google_maps_navigation_teal">#00796B</color>
<color name="google_maps_navigation_teal_night">#004D40</color>
<color name="google_maps_text_primary">#202124</color>
<color name="google_maps_text_secondary">#5F6368</color>
<color name="google_maps_eta_green">#00A854</color>

<!-- Night -->
<color name="google_maps_blue_night">#5C8DFF</color>
<color name="google_maps_nav_purple_night">#B39DDB</color>
<color name="google_maps_text_primary_night">#E8EAED</color>
<color name="google_maps_text_secondary_night">#9AA0A6</color>
<color name="google_maps_bg_night">#1A1A1E</color>
<color name="google_maps_road_night">#3A3A3E</color>
<color name="google_maps_water_night">#1C3A4A</color>
<color name="google_maps_park_night">#243B24</color>
```