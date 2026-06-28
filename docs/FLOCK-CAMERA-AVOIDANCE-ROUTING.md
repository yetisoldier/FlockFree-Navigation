# Flock Camera Avoidance Routing

This document describes the current FlockFree route-avoidance implementation in
the OsmAnd fork. It reflects the Flock-only implementation built and installed
on 2026-06-28, where the on-device camera database contained 89,942 rows and a
SQLite check found 0 non-Flock rows.

## Short Version

FlockFree first calculates a normal OsmAnd offline route. It then scans that
route for Flock-labeled cameras inside the configured corridor radius, maps
those cameras to OsmAnd road object IDs, and runs a second offline route
calculation with those road IDs marked as temporary impassable roads. The
privacy route is accepted only if a fresh scan shows fewer Flock camera
exposures than the original route. If full avoidance cannot produce a better
route, FlockFree progressively unblocks the least camera-impactful roads and
tries again up to 4 times. If none of those attempts improves the route, the
original route is kept.

The implementation is intentionally conservative:

- It only runs on OsmAnd offline vector routing.
- It never writes to the user's permanent Avoid Roads list.
- It keeps the original route when avoidance fails, times out, or makes things
  worse.
- It treats Flock camera avoidance as a hard routing constraint, while optional
  live traffic remains a softer follow-up adjustment.

## Main Code Paths

The routing implementation is split across a few files:

- `OsmAnd/src/net/osmand/plus/routing/RouteProvider.java`
  - Hooks into OsmAnd route calculation.
  - Runs the initial route, Flock avoidance reroute, optional relaxation, and
    optional traffic reroute.
  - Injects temporary impassable road IDs into OsmAnd's routing configuration.
- `OsmAnd/src/net/osmand/plus/plugins/flockfree/CameraAvoidanceHelper.java`
  - Finds Flock cameras near a route.
  - Maps cameras to route road object IDs.
  - Tracks applied, partial, fallback, and skipped avoidance status.
- `OsmAnd/src/net/osmand/plus/plugins/flockfree/CameraData.java`
  - Loads camera data from SQLite, cache, bundled seed, or network.
  - Filters the source feed down to Flock-labeled records.
  - Serves bounding-box and radius camera queries.
- `OsmAnd/src/net/osmand/plus/plugins/flockfree/CameraDatabaseHelper.java`
  - Stores only Flock-filtered camera rows in app-private SQLite.
  - Defensively filters DB reads to Flock rows.
- `OsmAnd/src/net/osmand/plus/plugins/flockfree/FlockFreePlugin.java`
  - Owns preferences and user-facing route status.
  - Updates route comparison, route summary, and persisted diagnostics after
    route calculation.

## What Counts as a Flock Camera

The camera feed can contain many ALPR or surveillance camera brands. FlockFree
now filters that feed before map display, alerts, widgets, nearest-camera
queries, and route avoidance.

A camera is considered Flock-related when either of these text fields contains
`flock`, case-insensitive:

- `brand`
- `operator`

The parser uses `Locale.US` lowercasing and checks the normalized text. Rows
without valid coordinates are rejected before this test. Rows that do not match
the Flock token are counted as skipped and are not inserted into the runtime
camera list or SQLite database.

SQLite keeps the same privacy boundary. `CameraDatabaseHelper` inserts only
records passing `CameraData.isFlockCamera(...)`, and its reads include a
defensive SQL selection equivalent to:

```sql
LOWER(COALESCE(brand, '')) LIKE '%flock%'
OR LOWER(COALESCE(operator, '')) LIKE '%flock%'
```

That means general ALPR, traffic, Motorola, Genetec, or other camera records are
not routed around unless their brand or operator field explicitly contains
`flock`.

## Camera Data Loading

Camera data is loaded by `CameraData.ensureDataLoaded()` in this order:

1. App-private SQLite database, if it already has rows.
2. App cache file, `cameras.geojson`.
3. Bundled seed asset, `flockfree/cameras.geojson`.
4. Network refresh from `https://data.dontgetflocked.com/cameras.geojson.gz`.

Route calculation has a synchronous fallback,
`ensureCacheLoadedForRouting()`, so a valid existing cache or bundled seed can
be loaded before routing without waiting for a network refresh. Network refresh
is weekly by default and remains non-blocking for normal route calculation.

After a successful parse, FlockFree publishes the camera list atomically, rebuilds
the in-memory spatial grid, and persists the same Flock-only rows to SQLite.
Spatial queries prefer SQLite when the DB is ready and fall back to the in-memory
grid if needed.

## When Avoidance Runs

Avoidance is hooked into `RouteProvider.calculateRouteImpl(...)` after the first
normal offline OsmAnd route has been calculated:

1. `findVectorMapsRoute(...)` calculates the standard route.
2. `maybeRecalculateWithFlockFreeAvoidance(...)` tries the Flock avoidance route.
3. `maybeRecalculateWithFlockFreeTraffic(...)` optionally adjusts for live
   traffic while preserving camera-avoidance road blocks.

Avoidance does not run for online routing, BRouter routing, straight-line
routing, direct-to routing, or GPX-only routes that do not use OsmAnd routing.
It also skips lightweight follow-me recalculations where only the start point
changed, because those would otherwise trigger expensive second-pass reroutes
while driving.

Current source detail: the route hook checks
`FlockFreePlugin.isCameraAvoidanceActive()`. In the current tree, that method
returns a runtime override when one exists and otherwise defaults to `true`. The
profile preference `CAMERA_AVOIDANCE_ENABLED` is still registered for the
settings UI and defaults, but the routing gate should be verified against
`isCameraAvoidanceActive()` when changing this behavior.

## Route Corridor Scan

The route scanner works in two phases: a broad prefilter, then an exact
route-distance check.

`CameraAvoidanceHelper.findCamerasNearRoute(...)` receives the route geometry as
`LatLon` points. It first builds a bounding box around the route and expands it
by the configured avoidance radius. The default radius is 100 meters, with
settings values available from 50 to 500 meters.

The helper asks `CameraData` for Flock cameras inside that expanded bounding
box. It then checks each candidate against the actual route polyline:

- For normal routes, each camera is measured against each adjacent pair of route
  points using `MapUtils.getOrthogonalDistance(...)`.
- For a one-point route, it falls back to point-to-point distance.
- Only cameras at or inside the configured radius remain in the result.

This route-corridor scan is used in multiple places:

- Route summary and "Last route check" status.
- Route comparison camera counts.
- Camera proximity widget during active navigation.
- Candidate-route validation before accepting an avoidance reroute.

## Mapping Cameras to Roads

OsmAnd's router cannot avoid a lat/lon point directly in this implementation.
It avoids road object IDs. `CameraAvoidanceHelper.collectAvoidRoadIdsWithCameraCountForRoute(...)`
converts Flock cameras near the route into route road IDs.

The process is:

1. Collect Flock cameras near the initial route using the corridor scan above.
2. Read the original route's `RouteSegmentResult` list.
3. Skip the first and last route segments. Those are often start/end connector
   segments and are poor candidates for hard blocking.
4. For each Flock camera, convert the camera coordinate to OsmAnd's 31-bit tile
   coordinate space.
5. For each route road segment, iterate the segment geometry points between the
   segment's start and end indexes.
6. If any geometry point on that road object is within the avoidance radius,
   add that road object ID to the block set and increment its camera count.
7. If no geometry point matched, fall back to
   `RouteSegmentSearchResult.searchRouteSegment(...)` to find the nearest route
   segment within the radius.
8. Sort road IDs by camera count descending.

The important design choice is wide corridor blocking: a single Flock camera can
cause multiple adjacent route road objects to be blocked when they are within
the radius. That prevents the router from simply shifting onto the next parallel
road in the same camera corridor.

The sorted camera-count list is also what enables relaxation. Roads with more
camera associations are kept blocked longer; roads with fewer camera
associations are the first candidates to unblock when full avoidance cannot find
a viable route.

## Full Avoidance Pass

After collecting camera-adjacent road IDs, `RouteProvider` builds a copy of the
original `RouteCalculationParams` using `copyParamsForFlockFreeAvoidance(...)`.
That copy preserves the normal route inputs and adds the Flock road IDs to:

```java
temporaryImpassableRoadIds
```

Later, when OsmAnd builds the routing configuration, those temporary IDs are
merged with any existing impassable roads and passed to:

```java
configuration.router.setImpassableRoads(...)
```

This makes the Flock road blocks temporary and per-calculation. They are not
stored in the user's Avoid Roads list.

The full avoidance pass blocks every Flock camera-adjacent road ID found on the
initial route and runs `findVectorMapsRoute(...)` again. If OsmAnd finds a
candidate route, FlockFree does not accept it blindly. It scans the candidate
route for Flock cameras again and accepts the route only when the candidate has
fewer Flock camera exposures than the baseline.

If the route is not calculated, throws an exception, or has an equal-or-worse
camera count, FlockFree keeps looking instead of replacing the route.

## Iterative Relaxation

Full avoidance can be too strict. In dense camera areas, blocking every
camera-adjacent road can make the route impossible or force an unreasonable
detour. FlockFree handles that with iterative relaxation.

The relaxation loop is capped at 4 iterations:

```java
MAX_RELAXATION_ITERATIONS = 4
```

Because the road list is sorted by camera count descending, the least
camera-impactful roads are at the end. Each relaxation iteration:

1. Removes one lowest-camera road from the blocked set.
2. Recalculates an OsmAnd route with the remaining road IDs still blocked.
3. Scans the candidate route for Flock cameras.
4. Accepts the route only if it improves on the baseline camera exposure.
5. Continues if the candidate is not better.

If a relaxed route is accepted, the user-facing status is recorded as partial
avoidance. The accepted route still avoids the most camera-dense road objects,
but it allows enough lower-impact roads back into the graph for OsmAnd to find a
usable route.

If all relaxation attempts fail, FlockFree records a fallback and keeps the
original route.

## Acceptance Rules

FlockFree's route acceptance rule is deliberately simple: an avoidance route
must have fewer Flock camera exposures than the route it is replacing.

This protects against the inverted-route bug where a "privacy" route exists but
actually passes more cameras than the original route.

There are two related counts in the code:

- The route summary count is a fresh count of Flock camera points near a route.
- The road-blocking baseline is the sum of camera-to-road associations produced
  by the road mapper. A single camera may map to multiple adjacent road objects
  when wide corridor blocking is active.

The user-visible route summary and widget use the fresh route camera scan. The
road-blocking baseline is used internally to decide whether a reroute is worth
accepting.

## Optional Routing Budget

Flock avoidance is optional post-route work. The current budget is 15 seconds
from the start of route calculation:

```java
FLOCKFREE_OPTIONAL_ROUTING_BUDGET_MS = 15_000L
```

If the original route already consumed that budget, FlockFree skips the optional
avoidance pass and keeps the standard route. The same budget is checked during
relaxation. The route calculation progress object is also isolated for avoidance
attempts so a failed second pass does not poison the original route state.

## Traffic Interaction

Traffic routing is separate from camera avoidance. The route order is:

1. Normal offline route.
2. Flock camera avoidance.
3. Optional traffic adjustment.

When traffic runs after camera avoidance, it receives the active
camera-avoidance road IDs and carries them into its route copy as temporary
impassable roads. Traffic can apply speed multipliers, but it is not allowed to
quietly discard the camera hard blocks.

If a traffic candidate still lands on camera-adjacent roads, FlockFree tries to
run camera avoidance again against the traffic candidate. If it cannot preserve
a camera-safer route, the traffic candidate is rejected and the previous route
is kept.

## Reserved Multi-Pass Hook

`RouteProvider` contains an `applyMultiPassAvoidance(...)` helper intended to
rescan an avoidance route, add newly discovered camera-adjacent roads, and rerun
avoidance. That helper is not part of the active route-selection path in the
current build, and `MAX_AVOIDANCE_PASSES` is set to `0`.

The active behavior today is full avoidance plus iterative relaxation. Any
future work that re-enables multi-pass avoidance should update this document and
ensure the README does not overstate the production path.

## Status and User Feedback

`CameraAvoidanceHelper` records the last route result so the plugin can show a
toast and persist the result in the FlockFree settings screen.

Important statuses:

- `APPLIED`: full avoidance found and accepted a better route.
- `PARTIAL_APPLIED`: relaxation accepted a better route after unblocking one or
  more lower-impact roads.
- `FALLBACK`: avoidance failed, timed out, or did not improve the route, so the
  original route was kept.
- `SKIPPED_PARTIAL`: the recalculation was a lightweight start-point update.
- `SKIPPED_NO_DATA`: no usable camera data was available.
- `SKIPPED_NO_ROAD_IDS`: cameras were not mapped to avoidable route road IDs.

After route calculation, `FlockFreePlugin.newRouteIsCalculated(...)` builds the
route summary, route comparison card data, and route tradeoff text. The camera
proximity widget uses the same avoidance helper to count remaining Flock cameras
on the active route.

## Known Limits

- Avoidance requires OsmAnd offline vector routing. Online routes, straight
  routes, direct-to routes, and external routing services are outside this path.
- The Flock-only filter depends on source metadata. A real Flock camera without
  `flock` in `brand` or `operator` is intentionally excluded until the source
  data labels it.
- Road blocking uses OsmAnd road object IDs, so one camera can block a longer
  road object than a driver might expect.
- The road-ID mapper checks road geometry points and falls back to nearest route
  segment search. The route-corridor camera scan is more precise than the road
  ID mapping step.
- First and last route segments are skipped for blocking to avoid hard-blocking
  start/end connectors.
- Relaxation is capped at 4 attempts to keep route calculation responsive.
- The active route can still pass a Flock camera when no better offline route is
  found within the configured constraints.

## Verification Checklist

Use this checklist after changing camera data, route mapping, or route-provider
logic:

1. Run source checks:

   ```bash
   scripts/flockfree-source-checks.sh
   ```

2. Build and install the OpenGL debug APK:

   ```bash
   FLOCKFREE_ARTIFACT_VERSION=local-flock-routing \
     scripts/flockfree-user-build-install.sh --serial 192.168.1.139:44121
   ```

3. Confirm readiness output shows `MapActivity` focused and a non-empty camera
   database.

4. Confirm the installed SQLite database contains only Flock rows:

   ```sql
   SELECT COUNT(*) FROM cameras;
   SELECT COUNT(*) FROM cameras
   WHERE LOWER(COALESCE(brand, '')) NOT LIKE '%flock%'
     AND LOWER(COALESCE(operator, '')) NOT LIKE '%flock%';
   ```

5. Calculate a route through a known Flock-dense area using offline vector maps.

6. Check logcat for:

   - `FlockFree found ... camera-adjacent roads on route`
   - `FlockFree full avoidance route has ... cameras`
   - `FlockFree recalculated route ...` or relaxation/fallback status

7. Reopen FlockFree settings and confirm `Last route check` and the route
   tradeoff summary match the route behavior.

8. Verify the camera proximity widget and route comparison card count Flock
   cameras only.
