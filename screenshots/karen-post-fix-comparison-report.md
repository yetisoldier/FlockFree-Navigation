# Karen Post-Fix Device Validation

Validation run: 2026-06-23, device `192.168.1.139:38865` (`moto_g_stylus__2023_`).

## Build And Install Evidence

Observed:
- `git rev-parse --short=12 HEAD`: `8b90b2d7a2c2`.
- `git log -1 --oneline`: `8b90b2d7a2 fix: repair TomTom incidents and navigation traffic indicators`.
- `git diff --check`: clean, no output.
- Existing APK was older than the commit (`APK 09:20:34`, commit `09:22:35 -0500`), so I rebuilt instead of relying on it.
- Forced build command completed successfully: `./gradlew :OsmAnd:assembleGplayFreeLegacyFatDebug --rerun-tasks`.
- Fresh APK timestamp: `2026-06-23 09:27:24 -0500`, size `141,534,076` bytes.
- Install command completed with `Success`.
- Installed package after install: `com.yetiwurks.flockfree`, `versionCode=5399`, `versionName=5.4.0`, `lastUpdateTime=2026-06-23 09:28:07`, debuggable.

Inference:
- The device is running the APK rebuilt from commit `8b90b2d7a2c2`.

## FlockFree Launch And Crash Check

Observed:
- FlockFree launched to `net.osmand.plus.activities.MapActivity`.
- `dumpsys activity` showed `topResumedActivity` and `mCurrentFocus` as `com.yetiwurks.flockfree/net.osmand.plus.activities.MapActivity`.
- Current-run targeted logcat checks found no `FATAL EXCEPTION`, no `NetworkOnMainThreadException`, and no current `AndroidRuntime` crash stack.
- Raw `dumpsys window` still emitted an older-looking `Application Error: com.yetiwurks.flockfree` focus line before the active `MapActivity` focus line, but no crash dialog was visible and activity state showed `MapActivity` resumed.

Evidence:
- `post-fix-comparison/flockfree-launch-map.png`
- `post-fix-comparison/flockfree-launch-filtered-logcat.txt`
- `post-fix-comparison/flockfree-post-layer-window-focus.txt`

## TomTom Configuration And Incident Fetch

Observed:
- TomTom preference exists in `net.osmand.settings.xml` and is configured. I checked only configured/not configured and did not print the key.
- `traffic_routing_enabled` is explicitly `true` for the car profile.
- `incidents_show_layer` was not present in profile prefs; code default is `true`.
- `camera_show_layer` was not present in profile prefs; code default is `true`.
- The FlockFree layer dialog/route screen showed Traffic and Cameras enabled.
- After launch, layer view, Blaine-area active navigation, and a 25-second wait, targeted logcat checks found zero matches for:
  - `TomTom incident details request failed with HTTP 400`
  - `incidentDetails/key/json`
  - `/incidentDetails/key/json`
  - `NetworkOnMainThreadException`
- No visible TomTom incident markers were observed in the captured FlockFree map screenshots.

Inference:
- The old HTTP 400 caused by the invalid `/incidentDetails/key/json` path did not reproduce in this run.
- The app code does not emit a success log for incident fetches, so I can confirm "no observed HTTP 400/current incident failure" but cannot prove a successful incident payload from logcat alone.
- Result classification: API failure fixed/not reproduced; no incident marker observed.

Evidence:
- `post-fix-comparison/flockfree-map-layers-traffic-cameras-on.png`
- `post-fix-comparison/flockfree-post-layer-wait-map.png`
- `post-fix-comparison/flockfree-post-layer-wait-errors.txt` (0 lines)
- `post-fix-comparison/flockfree-post-layer-wait-tomtom-traffic-log.txt` (0 lines)

## FlockFree Route Traffic And Speed Limit

Observed:
- Active route/navigation was already present in the Blaine-to-Minneapolis comparison area.
- Route mode/settings evidence:
  - `application_mode=car`
  - `last_used_application_mode=car`
  - `routing_profile=car`
  - `traffic_route_last_check_summary=146 live, 0 no data, 12 samples`
  - `show_speed_limit_warning` is absent from car prefs, so the profile uses its default.
- FlockFree active navigation showed:
  - traffic legend: `Light`, `Moderate`, `Heavy`
  - green route segments on the route line, not only the old default blue
  - `0 MPH` speedometer widget
  - no separate speed-limit badge/sign in the captured active-navigation screenshots

Inference:
- The traffic-coloring fix is visible for free-flow route segments: the local route line rendered green instead of blue.
- I did not observe yellow/red FlockFree route segments in the current local viewport; the stored traffic summary says live samples exist, but the visible local segment appeared free-flow.
- Speed-limit badge blocker: active car navigation showed a speedometer, but no speed-limit sign. Since the current residential segment may not have speed-limit data available in the map/routing context, this remains "not observed" rather than a confirmed setting failure.

Evidence:
- `post-fix-comparison/flockfree-active-navigation-traffic-green.png`
- `post-fix-comparison/flockfree-map-layers-traffic-cameras-on.png`
- `post-fix-comparison/flockfree-post-layer-wait-map.png`

## Google Maps Comparison

Observed:
- Google Maps package launched on the actual device: `com.google.android.apps.maps/com.google.android.maps.MapsActivity`.
- Google Maps active navigation was started with a navigation intent toward Minneapolis.
- Active Google Maps navigation showed:
  - guidance toward `Isanti St NE`
  - `28 min`, `18 mi`, arrival about `10:03-10:05 AM`
  - `0 mph` current speed widget
  - no visible speed-limit sign in the captured active-navigation screenshot
- Google Maps route preview showed a broad Blaine-to-Minneapolis route with alternate routes and orange traffic-colored segments on the main route.
- UIAutomator exposed alternate route labels including `27 minutes via I-94 E` and `27 minutes via I-35W S`.

Evidence:
- `post-fix-comparison/google-maps-navigation-intent.png`
- `post-fix-comparison/google-maps-route-preview-from-navigation.png`

## Verdict

Observed improvements after `8b90b2d7a2`:
- Fresh APK builds and installs.
- FlockFree launches to `MapActivity` without current-run fatal crash evidence.
- TomTom incident HTTP 400 / old invalid path did not reproduce after the layer/view wait.
- FlockFree route traffic coloring is no longer only blue; green route segments and the traffic legend are visible in active navigation.

Remaining gaps:
- No visible TomTom incident marker was observed, and the app does not log successful incident fetches.
- FlockFree speed-limit badge was not observed in car active navigation; only the current-speed widget appeared.
- Google Maps still presents broader route traffic context more clearly in route preview, including orange traffic segments and alternate-route labels.
