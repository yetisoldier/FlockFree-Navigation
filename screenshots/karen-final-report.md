# Karen Final Validation Report

Date: 2026-06-23  
Repo: `/home/yetisoldier/projects/FlockFree-Navigation`  
Device: `192.168.1.139:38865` (`moto_g_stylus__2023_`)  
Package: `com.yetiwurks.flockfree`

## Summary

FlockFree Navigation launches and runs on the Moto without observed crash signatures. The latest repo commit is present, whitespace validation is clean, the installed APK is debuggable `versionName=5.4.0` / `versionCode=5399`, and visual defaults are applied in prefs.

Main remaining blockers / attention items:

- TomTom traffic and incident calls return HTTP 403. The API key is configured, and this is the known TomTom billing / `InsufficientFunds` issue, not a code failure.
- Forcing Android night mode with `cmd uimode night yes` did not visibly switch FlockFree's map/UI to dark mode in the captured screenshots. Because the car profile is set to `daynight_mode=AUTO`, this may be expected behavior if OsmAnd Auto mode follows time/sun rather than Android UI mode, but the requested system-night check did not produce a dark map.

## Evidence

### Build / Install / Launch

`git log --oneline -5`:

```text
a6a9eb9fb4 feat: Google-style navigation turn card, visual parity defaults, route arrow cleanup
eca5015fd9 fix: improve speed limit display and incident diagnostics
8b90b2d7a2 fix: repair TomTom incidents and navigation traffic indicators
b54a01c0c5 feat: polish improvements from Google Maps comparison report
ace040fc72 fix: NetworkOnMainThreadException in incident layer
```

`git diff --check`: clean, no whitespace errors.

Install evidence:

```text
package:/data/app/.../com.yetiwurks.flockfree.../base.apk
versionCode=5399 minSdk=24 targetSdk=35
versionName=5.4.0
lastUpdateTime=2026-06-23 10:26:02
```

Launch evidence:

- App launched to `net.osmand.plus.activities.MapActivity`.
- `pidof com.yetiwurks.flockfree` returned a running process.
- Final logcat scan found no `FATAL EXCEPTION`, `AndroidRuntime`, or `NetworkOnMainThread` entries.
- TomTom 403 entries were present and treated as the known billing issue.

### Visual Defaults

Prefs were checked with `run-as`:

```text
net.osmand.settings.xml: visual_defaults_migration_done=true
net.osmand.settings.car.xml: text_scale=1.2
net.osmand.settings.car.xml: daynight_mode=AUTO
```

Default map screenshot: `screenshots/final-validation/01-flockfree-map-default.png`

Observed: map labels and control text are larger than stock OsmAnd but still readable and not oversized on the Moto screen.

### Route Preview / Google Maps Comparison

FlockFree route intent used:

```text
osmand.api://navigate?start_lat=45.1608&start_lon=-93.2349&start_name=Start&dest_lat=44.9778&dest_lon=-93.2650&dest_name=Minneapolis&profile=car&force=true&location_permission=false
```

Screenshots:

- FlockFree route: `screenshots/final-validation/17-flockfree-route-zoomed-out.png`
- Google Maps route: `screenshots/final-validation/03-google-maps-route.png`

Observed differences:

- Route line: FlockFree uses a medium Google-blue route line, thick and rounded, on a flat 2D map. Google Maps uses a vivid purple line with a darker outline/shadow on a tilted 3D map.
- Navigation banner / turn card: FlockFree uses a white rounded top card with a black/blue maneuver icon, blue distance text, and black road text. Google Maps uses a dark teal banner with a large white maneuver arrow, compact instruction text, and a microphone button.
- Speed display: FlockFree shows a bottom-left `0 MPH` speed widget. Google Maps shows a bottom-left `0 mph` speed widget. No separate speed-limit sign was visible in either captured route screenshot.
- Bottom info bar: FlockFree shows a white road-name strip plus a lower ETA/distance/duration strip. Google Maps shows a bottom nav sheet with green remaining time, miles/ETA, close/recenter controls, and a Report action.
- Overall style: FlockFree is closer than stock OsmAnd, but still flatter and more OsmAnd-like than Google Maps because it lacks the teal turn banner, 3D tilted map, purple route styling, and Google Maps action cluster.

Route calculation evidence:

```text
Routing Lat 45.1608 Lon -93.2349 -> Lat 44.9778 Lon -93.265 ... selected Us_minnesota_northamerica.obf car
```

No crash or main-thread-network failure was observed during route setup.

### Traffic Legend / Layers

Screenshot: `screenshots/final-validation/11-flockfree-layers-dialog.png`

Observed in the Map layers dialog:

- `Traffic` toggle present and checked.
- `Traffic incidents` toggle present and checked.
- Related toggles present: Cameras, 3D buildings, Terrain, POI icons, POI labels.

### Night Mode

Commands run:

```text
cmd uimode night yes
cmd uimode night no
```

Screenshots:

- First forced-night capture: `screenshots/final-validation/12-flockfree-night-mode.png`
- Forced-night after FlockFree relaunch: `screenshots/final-validation/14-flockfree-night-mode-relaunch.png`
- Day restored: `screenshots/final-validation/15-flockfree-day-restored-relaunch.png`

Observed: Android reported `Night mode: yes`, but FlockFree remained visually light in both night captures. Day mode was restored afterward and verified with `dumpsys uimode` showing `mNightMode=1 (no)`.

## Saved Artifacts

All screenshots and logs from this pass were saved under:

```text
screenshots/final-validation/
```

Most useful files:

- `01-flockfree-map-default.png`
- `03-google-maps-route.png`
- `11-flockfree-layers-dialog.png`
- `12-flockfree-night-mode.png`
- `14-flockfree-night-mode-relaunch.png`
- `15-flockfree-day-restored-relaunch.png`
- `17-flockfree-route-zoomed-out.png`
- `18-final-device-state.png`
- `launch-logcat-filtered.txt`
- `route-logcat-filtered-second.txt`

## Verdict

Pass for build/install/launch stability, visual defaults, route launch, basic route UI, and traffic-layer toggle presence.

Blocked for live TomTom traffic/incidents by billing (`HTTP 403`, configured key, known `InsufficientFunds` condition).

Needs attention: forced Android night mode did not visibly activate a dark FlockFree map/UI under the current `daynight_mode=AUTO` setting.
