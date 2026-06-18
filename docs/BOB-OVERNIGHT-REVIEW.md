# Bob Overnight Review

Note: this was an overnight architecture review snapshot. Current source has since added experimental route-scoped two-pass camera avoidance, camera-data spatial indexing, and a low-level CYD BLE UART scaffold. See `docs/OVERNIGHT-HANDOFF.md` for the current handoff state.

## Recommendation

Stay on the in-tree plugin path. FlockFree needs direct OsmAnd internals for route hooks, layers, plugin settings, and future road-blocking behavior. A standalone external plugin can compile for isolated experiments, but it should not be the main implementation path.

## Current Architecture Read

- Use the verified `gplayFreeLegacyFatDebug` APK for morning phone testing.
- Keep camera rendering and route summaries in the in-tree `net.osmand.plus.plugins.flockfree` package.
- Treat the exposed settings XML and `FlockFreeSettingsFragment` as the next user-facing control surface.
- Keep route avoidance honest in UI copy: the current implementation reports cameras near the route corridor; it does not reroute yet.

## Next Technical Step

Turn the current route-summary toast into a guarded reroute MVP by mapping nearby camera points to FlockFree-owned impassable road IDs and applying them through OsmAnd's `RoutingConfiguration.Builder` path. This should be behind the `camera_avoidance_enabled` preference and should fail open if road IDs cannot be resolved safely.

## Risks

- Blocking roads without a reliable camera-to-road mapping could produce strange or impossible routes.
- Reusing OsmAnd's user-blocked-road storage would mix FlockFree dynamic blocks with user intent; keep FlockFree-owned state separate.
- Camera data is still in-memory GeoJSON. It is acceptable for the morning test but should move to SQLite/geohash before broader use.
- CYD BLE should remain explicitly documented as not wired in this fork until a foreground service and parser are implemented.
