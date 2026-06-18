# FlockFree Data Layer Notes

## Implemented for the first APK

- Camera data loads from `https://data.dontgetflocked.com/cameras.geojson.gz`.
- The loader accepts either a gzip payload or a plain GeoJSON payload. The current live endpoint uses the `.gz` URL but returns plain GeoJSON, so detection is based on gzip magic bytes instead of the file name or headers.
- Successful downloads are cached as `cameras.geojson` in the app cache directory.
- Cached files are also read with the same gzip/plain detection so a stale compressed cache does not block rendering.
- The parser supports the live camelCase property names (`osmId`, `osmType`, `surveillanceZone`, `mountType`, `osmTimestamp`) and snake_case fallbacks.
- Parsed camera points are published only after a successful parse. Corrupt cache files or failed network parses leave the previous in-memory data intact.
- The last-update preference is used when set; otherwise the cache file modified time is used so a valid cache does not force a refresh on every startup.
- Lightweight logs record cache/download load source, gzip detection, parsed camera count, skipped feature count, and timestamp-save success.

## Implemented after the first APK

- Parsed camera points now build a coarse in-memory spatial grid. Bounding-box and nearby-camera queries inspect only overlapping grid buckets before exact filtering.
- The grid is rebuilt atomically with every successful GeoJSON parse, so corrupt cache/network data cannot replace the previous usable camera list.
- The FlockFree settings screen includes a camera-data diagnostic row with loaded camera count and grid bucket count.
- Parsed camera points are also written to an app-private SQLite database. Cold startup and route startup can load the database without reparsing GeoJSON, while map/nearby queries use SQLite when the current database is available and fall back to the in-memory grid if needed.
- The camera-data diagnostic row can now report `database`, `cache`, `bundled seed`, or `network` as the active source.

## Remaining Performance Work

- The current path still keeps parsed camera points mirrored in memory for route/helper fallbacks. That is acceptable for a morning test, but it is not the final data architecture.
- Consider tile or geohash partitioning on top of SQLite for faster viewport reads and lower memory pressure.
- Add feed metadata validation if the upstream endpoint exposes an ETag, generation timestamp, or checksum contract.
- Add last update time and last load failure to the settings diagnostic surface once the plugin UI is more complete.
