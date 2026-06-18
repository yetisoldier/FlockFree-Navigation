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

## Remaining Performance Work

- The current first-APK path keeps parsed camera points in memory and filters by bounding box in Java. That is acceptable for a morning test, but it is not the final data architecture.
- Move camera storage to SQLite or another indexed spatial store before broad daily use so map panning does not scan the full list repeatedly.
- Consider tile or geohash partitioning for faster viewport reads and lower memory pressure.
- Add feed metadata validation if the upstream endpoint exposes an ETag, generation timestamp, or checksum contract.
- Add a small diagnostic surface in settings for last update time, camera count, and last load failure once the plugin UI is more complete.
