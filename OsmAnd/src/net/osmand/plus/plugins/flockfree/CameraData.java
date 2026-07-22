package net.osmand.plus.plugins.flockfree;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public class CameraData {

    private static final Log LOG = PlatformUtil.getLog(CameraData.class);

    private static final String CAMERA_DATA_URL = FlockFreePreferences.CAMERA_DATA_URL;
    private static final String CACHE_FILENAME = "cameras.geojson";
    private static final String BUNDLED_SEED_ASSET = "flockfree/cameras.geojson.gz";
    private static final long WEEK_MS = FlockFreePreferences.REFRESH_INTERVAL_MS;
    private static final long MAX_GEOJSON_BYTES = 128L * 1024 * 1024;
    private static final double SPATIAL_CELL_DEGREES = 0.05d;
    private static final double DEDUP_CELL_DEGREES = 0.001d;
    private static final int OVERPASS_TIMEOUT_MS = 60_000;
    private static final int OVERPASS_CONNECT_TIMEOUT_MS = 30_000;
    private static final String DEFAULT_OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter";
    private static final String OVERPASS_QUERY_TEMPLATE =
            "[out:json][timeout:60];(node[\"man_made\"=\"surveillance\"][\"surveillance:type\"=\"ALPR\"](15, -170, 75, -50););out body;";
    private static final double DEDUP_DISTANCE_METERS = 10.0;

    /** Known Flock manufacturer name variants for matching (case-insensitive). */
    private static final String[] FLOCK_MANUFACTURER_ALIASES = {
        "flock", "flock safety", "flock group inc", "flock group"
    };

    private final OsmandApplication app;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CameraDatabaseHelper databaseHelper;

    private volatile List<CameraPoint> cameras = new ArrayList<>();
    private volatile Map<Long, List<CameraPoint>> cameraGrid = new HashMap<>();
    private volatile boolean dataLoaded = false;
    private volatile boolean loading = false;
    private volatile boolean databaseReady = false;
    private volatile boolean osmDatabaseReady = false;
    @NonNull
    private volatile DataSource lastLoadedSource = DataSource.NONE;

    public CameraData(@NonNull OsmandApplication app) {
        this.app = app;
        this.databaseHelper = new CameraDatabaseHelper(app);
    }

    public boolean isDataLoaded() {
        return dataLoaded;
    }

    public boolean isLoading() {
        return loading;
    }

    public synchronized void ensureDataLoaded() {
        if (dataLoaded || loading) {
            return;
        }
        loading = true;
        executor.execute(() -> {
            try {
                boolean loadedFromDb = loadFromDatabase();
                if (!loadedFromDb) {
                    boolean loadedFromCache = loadFromCache();
                    if (!loadedFromCache) {
                        loadFromBundledSeed();
                    }
                }
                long lastUpdate = getLastUpdateTimestamp();
                if (!dataLoaded || isRefreshDue(lastUpdate)) {
                    downloadCameraData();
                }
            } catch (Exception e) {
                LOG.error("Failed to load camera data", e);
            } finally {
                loading = false;
            }
        });
    }

    public synchronized boolean refreshData() {
        if (loading) {
            return false;
        }
        loading = true;
        executor.execute(() -> {
            try {
                boolean refreshed = downloadCameraData();
                if (!refreshed && !dataLoaded) {
                    loadFromCacheOrSeed();
                }
            } catch (Exception e) {
                LOG.error("Failed to refresh camera data", e);
            } finally {
                loading = false;
            }
        });
        return true;
    }

    public synchronized boolean ensureCacheLoadedForRouting() {
        if (dataLoaded) {
            return true;
        }
        if (loading) {
            return false;
        }
        loading = true;
        try {
            return loadFromDatabase() || loadFromCacheOrSeed();
        } catch (Exception e) {
            LOG.error("Failed to load camera cache for routing", e);
            return false;
        } finally {
            loading = false;
        }
    }

    // ── OSM Overpass Second Source ──

    /**
     * Fetches ALPR camera data from the OpenStreetMap Overpass API.
     * Queries for nodes tagged with man_made=surveillance and surveillance:type=ALPR.
     *
     * @param overpassEndpoint the Overpass API endpoint URL
     * @return list of OSM-sourced camera points, or empty list on failure
     */
    @NonNull
    public List<CameraPoint> fetchOsmCameras(@NonNull String overpassEndpoint) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(overpassEndpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(OVERPASS_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(OVERPASS_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept-Encoding", "gzip");

            String formData = "data=" + java.net.URLEncoder.encode(OVERPASS_QUERY_TEMPLATE, StandardCharsets.UTF_8.name());
            try (OutputStream os = conn.getOutputStream()) {
                os.write(formData.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LOG.error("Overpass API error: HTTP " + responseCode);
                return Collections.emptyList();
            }

            String json = readGeoJsonResponse(conn);
            return parseOverpassJson(json);
        } catch (java.net.SocketTimeoutException e) {
            LOG.error("Overpass API timeout", e);
            return Collections.emptyList();
        } catch (Exception e) {
            LOG.error("Failed to fetch OSM cameras from Overpass", e);
            return Collections.emptyList();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Fetches OSM cameras using the default Overpass endpoint.
     *
     * @return list of OSM-sourced camera points, or empty list on failure
     */
    @NonNull
    public List<CameraPoint> fetchOsmCameras() {
        String endpoint = DEFAULT_OVERPASS_ENDPOINT;
        try {
            OsmandPreference<?> pref = app.getSettings().getPreference(FlockFreePreferences.OSM_OVERPASS_ENDPOINT);
            if (pref != null && pref.get() instanceof String) {
                String val = (String) pref.get();
                if (val != null && !val.isEmpty()) {
                    endpoint = val;
                }
            }
        } catch (Exception ignored) {
        }
        return fetchOsmCameras(endpoint);
    }

    /**
     * Parses an Overpass API JSON response into CameraPoint objects.
     * Overpass returns { "elements": [ { "type": "node", "lat": ..., "lon": ..., "tags": { ... } } ] }
     *
     * @param json the Overpass JSON response string
     * @return list of parsed camera points
     */
    @NonNull
    private List<CameraPoint> parseOverpassJson(@NonNull String json) {
        List<CameraPoint> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray elements = root.optJSONArray("elements");
            if (elements == null) {
                LOG.warn("Overpass response has no elements array");
                return result;
            }
            Set<String> seenKeys = new HashSet<>();
            int skipped = 0;
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.optJSONObject(i);
                if (element == null) {
                    skipped++;
                    continue;
                }
                String type = element.optString("type", "");
                if (!"node".equals(type)) {
                    skipped++;
                    continue;
                }
                double lat = element.optDouble("lat", Double.NaN);
                double lon = element.optDouble("lon", Double.NaN);
                if (!isValidCoordinate(lat, lon)) {
                    skipped++;
                    continue;
                }
                JSONObject tags = element.optJSONObject("tags");
                if (tags == null) {
                    tags = new JSONObject();
                }

                CameraPoint point = new CameraPoint();
                point.lat = lat;
                point.lon = lon;
                point.manufacturer = tags.optString("manufacturer", null);
                point.brand = tags.optString("brand", null);
                point.operator = tags.optString("operator", null);
                point.direction = tags.optString("camera:direction", tags.optString("direction", null));
                point.bearing = parseBearing(point.direction);

                // Keep the secondary source inside the same Flock-only boundary as the
                // primary feed. Overpass returns every ALPR node in the query area.
                if (!isFlockCamera(point)) {
                    skipped++;
                    continue;
                }

                // Deduplicate by rounded coords
                String dedupKey = Math.round(lat * 1_000_000d) + ":" + Math.round(lon * 1_000_000d);
                if (!seenKeys.add(dedupKey)) {
                    continue;
                }
                result.add(point);
            }
            LOG.info("Parsed " + result.size() + " OSM ALPR cameras from Overpass (skipped=" + skipped + ")");
        } catch (Exception e) {
            LOG.error("Failed to parse Overpass JSON response", e);
        }
        return result;
    }

    /**
     * Refreshes OSM camera data by fetching from Overpass and storing in the overlay table.
     * Must be called on a background thread.
     *
     * @param overpassEndpoint the Overpass API endpoint URL
     * @return true if the refresh succeeded
     */
    public boolean refreshOsmCameras(@NonNull String overpassEndpoint) {
        try {
            LOG.info("Starting OSM camera refresh from Overpass: " + overpassEndpoint);
            List<CameraPoint> osmCameras = fetchOsmCameras(overpassEndpoint);
            if (osmCameras.isEmpty()) {
                LOG.warn("OSM camera refresh returned no cameras");
                return false;
            }
            boolean persisted = databaseHelper.replaceAllOsmCameras(osmCameras);
            osmDatabaseReady = persisted;
            if (persisted) {
                LOG.info("OSM camera refresh complete: " + osmCameras.size() + " cameras stored");
                // Save refresh timestamp
                app.getSettings().setPreference(FlockFreePreferences.OSM_LAST_REFRESH_TIME, System.currentTimeMillis());
            } else {
                LOG.warn("Failed to persist OSM cameras to overlay database");
            }
            return persisted;
        } catch (Exception e) {
            LOG.error("OSM camera refresh failed", e);
            return false;
        }
    }

    /**
     * Refreshes OSM camera data using the configured Overpass endpoint.
     * Must be called on a background thread.
     *
     * @return true if the refresh succeeded
     */
    public boolean refreshOsmCameras() {
        String endpoint = DEFAULT_OVERPASS_ENDPOINT;
        try {
            OsmandPreference<?> pref = app.getSettings().getPreference(FlockFreePreferences.OSM_OVERPASS_ENDPOINT);
            if (pref != null && pref.get() instanceof String) {
                String val = (String) pref.get();
                if (val != null && !val.isEmpty()) {
                    endpoint = val;
                }
            }
        } catch (Exception ignored) {
        }
        return refreshOsmCameras(endpoint);
    }

    /**
     * Returns OSM overlay cameras within the given bounding box.
     *
     * @param top    northern latitude boundary
     * @param left   western longitude boundary
     * @param bottom southern latitude boundary
     * @param right  eastern longitude boundary
     * @return list of OSM camera points in the bounding box
     */
    @NonNull
    public List<CameraPoint> getOsmCamerasInBoundingBox(double top, double left, double bottom, double right) {
        if (!osmDatabaseReady && !databaseHelper.hasOsmData()) {
            return Collections.emptyList();
        }
        try {
            List<CameraPoint> osmCameras = databaseHelper.queryOsmCamerasInBounds(top, left, bottom, right);
            osmDatabaseReady = true;
            return osmCameras;
        } catch (Exception e) {
            LOG.error("Failed to query OSM cameras in bounding box", e);
            return Collections.emptyList();
        }
    }

    /**
     * Merges OSM overlay cameras into the primary camera list, deduplicating by lat/lon proximity.
     * Cameras within {@link #DEDUP_DISTANCE_METERS} of a primary camera are considered duplicates.
     *
     * @param primaryCameras cameras from the primary (dontgetflocked.com) source
     * @param osmCameras     cameras from the OSM Overpass source
     * @return merged list with duplicates removed
     */
    @NonNull
    public static List<CameraPoint> mergeWithOsmCameras(
            @NonNull List<CameraPoint> primaryCameras,
            @NonNull List<CameraPoint> osmCameras) {
        if (osmCameras.isEmpty()) {
            return primaryCameras;
        }
        List<CameraPoint> merged = new ArrayList<>(primaryCameras.size() + osmCameras.size());
        merged.addAll(primaryCameras);
        Map<Long, List<CameraPoint>> primaryGrid = buildDedupGrid(primaryCameras);
        int deduped = 0;
        for (CameraPoint osmCam : osmCameras) {
            boolean isDuplicate = isDuplicateOfPrimary(osmCam, primaryCameras, primaryGrid);
            if (!isDuplicate) {
                merged.add(osmCam);
            } else {
                deduped++;
            }
        }
        if (deduped > 0) {
            LOG.info("Merged OSM cameras: " + (osmCameras.size() - deduped) + " added, " + deduped + " duplicates removed");
        }
        return merged;
    }

    @NonNull
    private static Map<Long, List<CameraPoint>> buildDedupGrid(@NonNull List<CameraPoint> cameras) {
        Map<Long, List<CameraPoint>> grid = new HashMap<>();
        for (CameraPoint camera : cameras) {
            long key = getDedupGridKey(getDedupLatBucket(camera.lat), getDedupLonBucket(camera.lon));
            grid.computeIfAbsent(key, ignored -> new ArrayList<>()).add(camera);
        }
        return grid;
    }

    private static boolean isDuplicateOfPrimary(
            @NonNull CameraPoint camera,
            @NonNull List<CameraPoint> allPrimaryCameras,
            @NonNull Map<Long, List<CameraPoint>> primaryGrid) {
        int latRadius = (int) Math.ceil((DEDUP_DISTANCE_METERS / 111_000d) / DEDUP_CELL_DEGREES);
        double cosLatitude = Math.abs(Math.cos(Math.toRadians(camera.lat)));
        if (cosLatitude < 0.01d) {
            return containsDuplicate(camera, allPrimaryCameras);
        }
        int lonRadius = (int) Math.ceil(
                (DEDUP_DISTANCE_METERS / (111_000d * cosLatitude)) / DEDUP_CELL_DEGREES);
        double lonSearchDegrees = lonRadius * DEDUP_CELL_DEGREES;
        if (camera.lon - lonSearchDegrees <= -180d || camera.lon + lonSearchDegrees >= 180d) {
            return containsDuplicate(camera, allPrimaryCameras);
        }
        // Near the poles longitude buckets become very wide in angular terms. Those
        // locations are rare, and a linear scan is safer than allocating thousands of buckets.
        if (lonRadius > 100) {
            return containsDuplicate(camera, allPrimaryCameras);
        }

        int centerLatBucket = getDedupLatBucket(camera.lat);
        int centerLonBucket = getDedupLonBucket(camera.lon);
        for (int latBucket = centerLatBucket - latRadius;
             latBucket <= centerLatBucket + latRadius; latBucket++) {
            for (int lonBucket = centerLonBucket - lonRadius;
                 lonBucket <= centerLonBucket + lonRadius; lonBucket++) {
                List<CameraPoint> bucket = primaryGrid.get(getDedupGridKey(latBucket, lonBucket));
                if (bucket != null && containsDuplicate(camera, bucket)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsDuplicate(@NonNull CameraPoint camera,
                                             @NonNull List<CameraPoint> candidates) {
        for (CameraPoint candidate : candidates) {
            double distance = net.osmand.util.MapUtils.getDistance(
                    camera.lat, camera.lon, candidate.lat, candidate.lon);
            if (distance <= DEDUP_DISTANCE_METERS) {
                return true;
            }
        }
        return false;
    }

    private static int getDedupLatBucket(double lat) {
        return (int) Math.floor((lat + 90d) / DEDUP_CELL_DEGREES);
    }

    private static int getDedupLonBucket(double lon) {
        return (int) Math.floor((lon + 180d) / DEDUP_CELL_DEGREES);
    }

    private static long getDedupGridKey(int latBucket, int lonBucket) {
        return ((long) latBucket << 32) ^ (lonBucket & 0xffffffffL);
    }

    /**
     * Returns merged cameras (primary + OSM overlay) within the given bounding box.
     * Deduplicates by lat/lon proximity.
     *
     * @param top    northern latitude boundary
     * @param left   western longitude boundary
     * @param bottom southern latitude boundary
     * @param right  eastern longitude boundary
     * @return merged list of camera points in the bounding box
     */
    @NonNull
    public synchronized List<CameraPoint> getMergedCamerasInBoundingBox(
            double top, double left, double bottom, double right) {
        // getCamerasInBoundingBox already includes and deduplicates the OSM overlay.
        return getCamerasInBoundingBox(top, left, bottom, right);
    }

    /**
     * Returns merged cameras (primary + OSM overlay) near the given point.
     *
     * @param lat          center latitude
     * @param lon          center longitude
     * @param radiusMeters search radius in meters
     * @return merged list of cameras within the radius
     */
    @NonNull
    public synchronized List<CameraPoint> getMergedCamerasNear(double lat, double lon, double radiusMeters) {
        // getCamerasNear already includes, distance-filters, and deduplicates the overlay.
        return getCamerasNear(lat, lon, radiusMeters);
    }

    /**
     * Returns the total number of OSM overlay cameras in the database.
     *
     * @return OSM camera count, or 0 if unavailable
     */
    public int getOsmCameraCount() {
        return databaseHelper.getOsmCameraCount();
    }

    private File getCacheFile() {
        File dir = app.getCacheDir();
        return new File(dir, CACHE_FILENAME);
    }

    /**
     * Loads Flock camera data from the SQLite database.
     * This is the fastest path — no GeoJSON parsing needed.
     *
     * @return true if data was loaded from the database
     */
    private boolean loadFromDatabase() {
        try {
            if (!databaseHelper.hasData()) {
                LOG.info("Camera database is empty");
                return false;
            }
            int count = databaseHelper.getCameraCount();
            if (count <= 0) {
                return false;
            }
            List<CameraPoint> loaded = filterFlockCameras(databaseHelper.getAllCameras());
            if (loaded.isEmpty()) {
                return false;
            }
            // Deduplicate in case the database has stale duplicate rows from older versions
            List<CameraPoint> deduped = deduplicateCameras(loaded);
            int removed = loaded.size() - deduped.size();
            synchronized (this) {
                cameras = deduped;
                cameraGrid = buildSpatialGrid(deduped);
                lastLoadedSource = DataSource.DATABASE;
                databaseReady = true;
            }
            dataLoaded = true;
            LOG.info("Loaded " + deduped.size() + " Flock cameras from SQLite database (removed " + removed + " duplicates)");
            return true;
        } catch (Exception e) {
            LOG.error("Failed to load cameras from database", e);
            return false;
        }
    }

    @NonNull
    private static List<CameraPoint> deduplicateCameras(@NonNull List<CameraPoint> input) {
        Set<String> seen = new HashSet<>();
        List<CameraPoint> result = new ArrayList<>(input.size());
        int duplicates = 0;
        for (CameraPoint cam : input) {
            String key = (cam.osmId != null && cam.osmType != null)
                    ? cam.osmType + ":" + cam.osmId
                    : Math.round(cam.lat * 1_000_000d) + ":" + Math.round(cam.lon * 1_000_000d)
                      + ":" + (cam.manufacturer != null ? cam.manufacturer : "")
                      + ":" + (cam.brand != null ? cam.brand : "")
                      + ":" + (cam.direction != null ? cam.direction : "");
            if (seen.add(key)) {
                result.add(cam);
            } else {
                duplicates++;
            }
        }
        if (duplicates > 0) {
            LOG.info("Deduplicated " + duplicates + " camera entries (" + input.size() + " -> " + result.size() + ")");
        }
        return result;
    }

    private boolean loadFromCache() {
        File cacheFile = getCacheFile();
        if (cacheFile.exists()) {
            try {
                String json = readGeoJsonFile(cacheFile);
                if (!parseGeoJSON(json, DataSource.CACHE)) {
                    return false;
                }
                dataLoaded = true;
                LOG.info("Loaded " + cameras.size() + " Flock cameras from cache (" + cacheFile.length() + " bytes)");
                return true;
            } catch (Exception e) {
                LOG.error("Failed to load camera cache", e);
            }
        } else {
            LOG.info("No FlockFree camera cache found");
        }
        return false;
    }

    private boolean loadFromCacheOrSeed() {
        return loadFromCache() || loadFromBundledSeed();
    }

    private boolean loadFromBundledSeed() {
        try {
            String json = readGeoJsonAsset(BUNDLED_SEED_ASSET);
            if (!parseGeoJSON(json, DataSource.BUNDLED_SEED)) {
                return false;
            }
            dataLoaded = true;
            LOG.info("Loaded " + cameras.size() + " cameras from bundled seed");
            return true;
        } catch (IOException e) {
            LOG.warn("No bundled FlockFree camera seed available", e);
        } catch (Exception e) {
            LOG.error("Failed to load bundled FlockFree camera seed", e);
        }
        return false;
    }

    private boolean downloadCameraData() {
        File cacheFile = getCacheFile();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CAMERA_DATA_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Accept-Encoding", "gzip");

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LOG.error("HTTP error: " + responseCode);
                return false;
            }

            String json = readGeoJsonResponse(conn);
            if (!parseGeoJSON(json, DataSource.NETWORK)) {
                return false;
            }
            dataLoaded = true;

            writeStringToFile(json, cacheFile);
            long updateTime = System.currentTimeMillis();
            boolean timestampSaved = app.getSettings().setPreference(
                    FlockFreePreferences.CAMERA_DATA_LAST_UPDATE, updateTime);
            if (!timestampSaved) {
                LOG.warn("Failed to save camera data update timestamp preference");
            }

            LOG.info("Downloaded and parsed " + cameras.size() + " cameras; timestampSaved=" + timestampSaved);
            return true;
        } catch (Exception e) {
            LOG.error("Failed to download camera data", e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private boolean parseGeoJSON(@NonNull String json, @NonNull DataSource source) {
        try {
            JSONObject root = new JSONObject(json);
            String type = root.optString("type", "");
            if (!"FeatureCollection".equals(type)) {
                LOG.error("Unexpected GeoJSON type: " + type);
                return false;
            }
            JSONArray features = root.optJSONArray("features");
            if (features == null) {
                LOG.error("No features in GeoJSON");
                return false;
            }
            List<CameraPoint> parsed = new ArrayList<>(features.length());
            Set<String> seenKeys = new HashSet<>();
            int skipped = 0;
            int nonFlockSkipped = 0;
            int duplicates = 0;
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                if (feature == null) {
                    skipped++;
                    continue;
                }
                JSONObject geometry = feature.optJSONObject("geometry");
                if (geometry == null) {
                    skipped++;
                    continue;
                }
                String geomType = geometry.optString("type", "");
                if (!"Point".equals(geomType)) {
                    skipped++;
                    continue;
                }
                JSONArray coords = geometry.optJSONArray("coordinates");
                if (coords == null || coords.length() < 2) {
                    skipped++;
                    continue;
                }

                double lon = coords.getDouble(0);
                double lat = coords.getDouble(1);
                if (!isValidCoordinate(lat, lon)) {
                    skipped++;
                    continue;
                }

                JSONObject props = feature.optJSONObject("properties");
                if (props == null) props = new JSONObject();

                CameraPoint point = new CameraPoint();
                point.lat = lat;
                point.lon = lon;
                point.osmId = optProperty(props, "osmId", "osm_id");
                point.osmType = optProperty(props, "osmType", "osm_type");
                point.manufacturer = optProperty(props, "manufacturer", null);
                point.brand = optProperty(props, "brand", null);
                point.direction = optProperty(props, "direction", null);
                point.bearing = parseBearing(point.direction);
                point.operator = optProperty(props, "operator", null);
                point.mountType = optProperty(props, "mountType", "mount_type");
                point.surveillanceZone = optProperty(props, "surveillanceZone", "surveillance_zone");
                point.osmTimestamp = optProperty(props, "osmTimestamp", "osm_timestamp");
                if (!isFlockCamera(point)) {
                    nonFlockSkipped++;
                    continue;
                }

                // Deduplicate by osm_id+osm_type when available, otherwise by rounded coords+brand+direction
                String dedupKey = (point.osmId != null && point.osmType != null)
                        ? point.osmType + ":" + point.osmId
                        : Math.round(lat * 1_000_000d) + ":" + Math.round(lon * 1_000_000d)
                          + ":" + (point.manufacturer != null ? point.manufacturer : "")
                          + ":" + (point.brand != null ? point.brand : "")
                          + ":" + (point.direction != null ? point.direction : "");
                if (!seenKeys.add(dedupKey)) {
                    duplicates++;
                    continue;
                }
                parsed.add(point);
            }
            if (parsed.isEmpty()) {
                LOG.error("Parsed zero Flock camera points from " + source + "; skipped=" + skipped
                        + ", nonFlockSkipped=" + nonFlockSkipped
                        + ", duplicates=" + duplicates + ", features=" + features.length());
                return false;
            }
            synchronized (this) {
                cameras = parsed;
                cameraGrid = buildSpatialGrid(parsed);
                lastLoadedSource = source;
                databaseReady = false;
            }
            persistParsedCameras(parsed, source);
            LOG.info("Parsed " + parsed.size() + " Flock camera points from " + source.logName
                    + "; skipped=" + skipped + ", nonFlockSkipped=" + nonFlockSkipped
                    + ", duplicates=" + duplicates
                    + ", features=" + features.length()
                    + ", buckets=" + cameraGrid.size());
            return true;
        } catch (Exception e) {
            LOG.error("Failed to parse GeoJSON", e);
            return false;
        }
    }

    private void persistParsedCameras(@NonNull List<CameraPoint> parsed, @NonNull DataSource source) {
        if (source == DataSource.DATABASE) {
            return;
        }
        boolean persisted = databaseHelper.replaceAllCameras(parsed);
        databaseReady = persisted;
        if (persisted) {
            LOG.info("Persisted " + parsed.size() + " Flock cameras to SQLite database from " + source.logName);
        } else {
            LOG.warn("Failed to persist " + parsed.size() + " Flock cameras to SQLite database from " + source.logName);
        }
    }

    @Nullable
    private static String optProperty(@NonNull JSONObject props, @NonNull String primaryKey,
                                      @Nullable String fallbackKey) {
        String value = props.optString(primaryKey, null);
        if ((value == null || value.length() == 0) && fallbackKey != null) {
            value = props.optString(fallbackKey, null);
        }
        return value;
    }

    private long getLastUpdateTimestamp() {
        OsmandPreference<?> preference = app.getSettings().getPreference(FlockFreePreferences.CAMERA_DATA_LAST_UPDATE);
        if (preference != null && preference.get() instanceof Long) {
            long value = (Long) preference.get();
            if (value > 0) {
                return value;
            }
        }
        File cacheFile = getCacheFile();
        return cacheFile.exists() ? cacheFile.lastModified() : 0L;
    }

    private boolean isRefreshDue(long lastUpdate) {
        return lastUpdate <= 0 || System.currentTimeMillis() - lastUpdate > WEEK_MS;
    }

    @NonNull
    public synchronized List<CameraPoint> getCamerasInBoundingBox(double top, double left, double bottom, double right) {
        // When the database has data, query it directly for the bounding box.
        // This avoids iterating over all 104K in-memory cameras.
        List<CameraPoint> primaryResult;
        if (databaseReady) {
            primaryResult = databaseHelper.getCamerasInBoundingBox(top, left, bottom, right);
            if (!primaryResult.isEmpty() || cameras.isEmpty()) {
                // Merge OSM overlay cameras and deduplicate
                List<CameraPoint> osmResult = getOsmCamerasInBoundingBox(top, left, bottom, right);
                return mergeWithOsmCameras(primaryResult, osmResult);
            }
        } else {
            primaryResult = new ArrayList<>();
        }
        if (top < bottom) {
            double temp = top;
            top = bottom;
            bottom = temp;
        }
        top = clamp(top, -90d, 90d);
        bottom = clamp(bottom, -90d, 90d);

        if (left > right) {
            primaryResult = getCamerasInBoundingBoxInternal(top, left, bottom, 180d);
            primaryResult.addAll(getCamerasInBoundingBoxInternal(top, -180d, bottom, right));
        } else {
            primaryResult = getCamerasInBoundingBoxInternal(top, left, bottom, right);
        }
        // Merge OSM overlay cameras and deduplicate
        List<CameraPoint> osmResult = getOsmCamerasInBoundingBox(top, left, bottom, right);
        return mergeWithOsmCameras(primaryResult, osmResult);
    }

    @NonNull
    private List<CameraPoint> getCamerasInBoundingBoxInternal(double top, double left, double bottom, double right) {
        List<CameraPoint> result = new ArrayList<>();
        left = clamp(left, -180d, 180d);
        right = clamp(right, -180d, 180d);

        Map<Long, List<CameraPoint>> grid = cameraGrid;
        if (grid.isEmpty()) {
            for (CameraPoint cam : cameras) {
                if (cam.lat >= bottom && cam.lat <= top && cam.lon >= left && cam.lon <= right) {
                    result.add(cam);
                }
            }
            return result;
        }

        int minLatBucket = getLatBucket(bottom);
        int maxLatBucket = getLatBucket(top);
        int minLonBucket = getLonBucket(left);
        int maxLonBucket = getLonBucket(right);
        for (int latBucket = minLatBucket; latBucket <= maxLatBucket; latBucket++) {
            for (int lonBucket = minLonBucket; lonBucket <= maxLonBucket; lonBucket++) {
                List<CameraPoint> bucket = grid.get(getGridKey(latBucket, lonBucket));
                if (bucket == null) {
                    continue;
                }
                addCamerasInBounds(bucket, top, left, bottom, right, result);
            }
        }
        return result;
    }

    private void addCamerasInBounds(@NonNull List<CameraPoint> candidates, double top, double left, double bottom,
                                    double right, @NonNull List<CameraPoint> result) {
        for (CameraPoint cam : candidates) {
            if (cam.lat >= bottom && cam.lat <= top && cam.lon >= left && cam.lon <= right) {
                result.add(cam);
            }
        }
    }

    public synchronized int getCameraCount() {
        int count = cameras.size();
        return count > 0 || !databaseReady ? count : databaseHelper.getCameraCount();
    }

    public synchronized int getSpatialBucketCount() {
        return cameraGrid.size();
    }

    @NonNull
    public synchronized String getLastLoadedSourceLabel() {
        return app.getString(lastLoadedSource.labelRes);
    }

    @NonNull
    public synchronized String getLastLoadedFreshnessLabel() {
        long lastUpdate = getLastUpdateTimestamp();
        if (lastUpdate <= 0) {
            return app.getString(R.string.flockfree_camera_data_last_update_never);
        }

        long ageMs = Math.max(0L, System.currentTimeMillis() - lastUpdate);
        long ageHours = ageMs / (60L * 60L * 1000L);
        String ageLabel;
        if (ageHours < 1) {
            ageLabel = app.getString(R.string.flockfree_camera_data_last_update_recent);
        } else if (ageHours == 1) {
            ageLabel = app.getString(R.string.flockfree_camera_data_last_update_one_hour);
        } else if (ageHours < 24) {
            ageLabel = app.getString(R.string.flockfree_camera_data_last_update_hours, ageHours);
        } else {
            long ageDays = Math.max(1L, ageHours / 24L);
            ageLabel = ageDays == 1
                    ? app.getString(R.string.flockfree_camera_data_last_update_one_day)
                    : app.getString(R.string.flockfree_camera_data_last_update_days, ageDays);
        }
        String refreshState = app.getString(isRefreshDue(lastUpdate)
                ? R.string.flockfree_camera_data_refresh_due_suffix
                : R.string.flockfree_camera_data_refresh_current_suffix);
        return app.getString(R.string.flockfree_camera_data_last_update_with_refresh_state,
                ageLabel, refreshState);
    }

    public synchronized List<CameraPoint> getCamerasNear(double lat, double lon, double radiusMeters) {
        // When the database has data, query it directly with radius filtering.
        List<CameraPoint> primaryResult;
        if (databaseReady) {
            primaryResult = databaseHelper.getCamerasNear(lat, lon, radiusMeters);
            if (primaryResult.isEmpty() && !cameras.isEmpty()) {
                primaryResult = null; // fall through to in-memory search
            }
        } else {
            primaryResult = null;
        }
        if (primaryResult == null) {
            double latitudeDelta = radiusMeters / 111_000d;
            double longitudeScale = Math.max(0.01d, Math.cos(Math.toRadians(lat)));
            double longitudeDelta = radiusMeters / (111_000d * longitudeScale);
            double top = clamp(lat + latitudeDelta, -90d, 90d);
            double bottom = clamp(lat - latitudeDelta, -90d, 90d);
            double left = lon - longitudeDelta;
            double right = lon + longitudeDelta;
            if (left < -180d) {
                left += 360d;
            }
            if (right > 180d) {
                right -= 360d;
            }

            primaryResult = new ArrayList<>();
            List<CameraPoint> candidates = getCamerasInBoundingBoxInternal(top, left, bottom, right);
            for (CameraPoint cam : candidates) {
                double dist = net.osmand.util.MapUtils.getDistance(cam.lat, cam.lon, lat, lon);
                if (dist <= radiusMeters) {
                    primaryResult.add(cam);
                }
            }
        }
        // Merge OSM overlay cameras and deduplicate
        List<CameraPoint> osmResult = getOsmCamerasInBoundingBox(
                clamp(lat + (radiusMeters / 111_000d), -90d, 90d),
                lon - (radiusMeters / (111_000d * Math.max(0.01d, Math.cos(Math.toRadians(lat))))),
                clamp(lat - (radiusMeters / 111_000d), -90d, 90d),
                lon + (radiusMeters / (111_000d * Math.max(0.01d, Math.cos(Math.toRadians(lat))))));
        // Filter OSM results by precise distance
        List<CameraPoint> osmFiltered = new ArrayList<>();
        for (CameraPoint cam : osmResult) {
            double dist = net.osmand.util.MapUtils.getDistance(cam.lat, cam.lon, lat, lon);
            if (dist <= radiusMeters) {
                osmFiltered.add(cam);
            }
        }
        return mergeWithOsmCameras(primaryResult, osmFiltered);
    }

    @NonNull
    private static Map<Long, List<CameraPoint>> buildSpatialGrid(@NonNull List<CameraPoint> points) {
        Map<Long, List<CameraPoint>> mutableGrid = new HashMap<>();
        for (CameraPoint point : points) {
            long key = getGridKey(getLatBucket(point.lat), getLonBucket(point.lon));
            List<CameraPoint> bucket = mutableGrid.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                mutableGrid.put(key, bucket);
            }
            bucket.add(point);
        }
        Map<Long, List<CameraPoint>> grid = new HashMap<>(mutableGrid.size());
        for (Map.Entry<Long, List<CameraPoint>> entry : mutableGrid.entrySet()) {
            grid.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(grid);
    }

    private static int getLatBucket(double lat) {
        return (int) Math.floor((clamp(lat, -90d, 90d) + 90d) / SPATIAL_CELL_DEGREES);
    }

    private static int getLonBucket(double lon) {
        return (int) Math.floor((clamp(lon, -180d, 180d) + 180d) / SPATIAL_CELL_DEGREES);
    }

    private static long getGridKey(int latBucket, int lonBucket) {
        return ((long) latBucket << 32) ^ (lonBucket & 0xffffffffL);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @NonNull
    private static List<CameraPoint> filterFlockCameras(@NonNull List<CameraPoint> input) {
        List<CameraPoint> result = new ArrayList<>(input.size());
        for (CameraPoint point : input) {
            if (isFlockCamera(point)) {
                result.add(point);
            }
        }
        return result;
    }

    public static boolean isFlockCamera(@NonNull CameraPoint point) {
        // Primary: check manufacturer (OSM canonical tag)
        if (matchesFlockAlias(point.manufacturer)) {
            return true;
        }
        // Secondary: check brand
        if (matchesFlockAlias(point.brand)) {
            return true;
        }
        // Tertiary: check operator
        return matchesFlockAlias(point.operator);
    }

    /**
     * Checks whether the given value matches any known Flock manufacturer alias.
     * Matching is case-insensitive and checks if the value contains any alias as a substring.
     *
     * @param value the string to check (may be null)
     * @return true if the value matches a known Flock alias
     */
    private static boolean matchesFlockAlias(@Nullable String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        for (String alias : FLOCK_MANUFACTURER_ALIASES) {
            if (lower.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private String readGeoJsonFile(@NonNull File file) throws IOException {
        if (file.length() > MAX_GEOJSON_BYTES) {
            throw new IOException("Camera cache is too large: " + file.length() + " bytes");
        }
        try (BufferedInputStream buffered = new BufferedInputStream(new java.io.FileInputStream(file))) {
            return readMaybeGzipStream(buffered);
        }
    }

    private String readGeoJsonAsset(@NonNull String assetPath) throws IOException {
        try (InputStream inputStream = app.getAssets().open(assetPath);
             BufferedInputStream buffered = new BufferedInputStream(inputStream)) {
            return readMaybeGzipStream(buffered);
        }
    }

    private String readStreamAsString(java.io.InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        long total = 0;
        char[] buffer = new char[16 * 1024];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                total += read;
                if (total > MAX_GEOJSON_BYTES) {
                    throw new IOException("Camera data exceeds " + MAX_GEOJSON_BYTES + " bytes");
                }
                sb.append(buffer, 0, read);
            }
        }
        return sb.toString();
    }

    private void writeStringToFile(String content, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create camera cache directory: " + parent);
        }
        File temp = new File(parent != null ? parent : app.getCacheDir(), file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(file)) {
            if (file.exists() && file.delete() && temp.renameTo(file)) {
                return;
            }
            throw new IOException("Unable to replace camera cache file: " + file);
        }
    }

    private String readGeoJsonResponse(@NonNull HttpURLConnection conn) throws IOException {
        int contentLength = conn.getContentLength();
        if (contentLength > MAX_GEOJSON_BYTES) {
            throw new IOException("Camera data response is too large: " + contentLength + " bytes");
        }
        try (InputStream inputStream = conn.getInputStream();
             BufferedInputStream buffered = new BufferedInputStream(inputStream)) {
            return readMaybeGzipStream(buffered);
        }
    }

    private String readMaybeGzipStream(@NonNull BufferedInputStream buffered) throws IOException {
        buffered.mark(2);
        int b1 = buffered.read();
        int b2 = buffered.read();
        buffered.reset();
        boolean gzipMagic = b1 == 0x1f && b2 == 0x8b;
        InputStream payload = gzipMagic ? new GZIPInputStream(buffered) : buffered;
        try {
            LOG.info("Reading camera data payload; gzip=" + gzipMagic);
            return readStreamAsString(payload);
        } finally {
            payload.close();
        }
    }

    private static boolean isValidCoordinate(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90 && lat <= 90
                && lon >= -180 && lon <= 180;
    }

    /**
     * Parses a bearing (compass degrees 1-360) from a direction string.
     * Accepts numeric values like "270", "355", "0".
     * Returns 0 for null/empty/unparseable values, meaning "no bearing data".
     * 0 is also returned for literal "0" since we treat 0 as no-bearing per spec.
     */
    private static float parseBearing(@Nullable String direction) {
        if (direction == null || direction.isEmpty()) {
            return 0f;
        }
        try {
            float val = Float.parseFloat(direction.trim());
            if (val > 0 && val <= 360) {
                return val;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0f;
    }

    public static class CameraPoint {
        public double lat;
        public double lon;
        @Nullable public String osmId;
        @Nullable public String osmType;
        @Nullable public String manufacturer;
        @Nullable public String brand;
        @Nullable public String direction;
        @Nullable public String operator;
        @Nullable public String mountType;
        @Nullable public String surveillanceZone;
        @Nullable public String osmTimestamp;

        /**
         * Parsed bearing (compass degrees 1-360) derived from the {@code direction} string.
         * 0 means no bearing data available (falls back to omnidirectional blocking).
         */
        public float bearing = 0f;

        /**
         * Returns the camera bearing, lazily parsing it from the {@code direction} string
         * if the {@code bearing} field has not been set yet. This ensures bearing is
         * available even when CameraPoint instances are created by CameraDatabaseHelper
         * (which sets {@code direction} but not {@code bearing}).
         *
         * @return bearing in degrees (1-360), or 0 if no bearing data is available
         */
        public float getBearing() {
            if (bearing != 0f) {
                return bearing;
            }
            if (direction == null || direction.isEmpty()) {
                return 0f;
            }
            try {
                float val = Float.parseFloat(direction.trim());
                if (val > 0 && val <= 360) {
                    bearing = val;
                    return val;
                }
            } catch (NumberFormatException ignored) {
            }
            return 0f;
        }
    }

    private enum DataSource {
        NONE("unknown", R.string.flockfree_camera_data_source_unknown),
        CACHE("cache", R.string.flockfree_camera_data_source_cache),
        BUNDLED_SEED("bundled seed", R.string.flockfree_camera_data_source_bundled_seed),
        NETWORK("network", R.string.flockfree_camera_data_source_network),
        DATABASE("database", R.string.flockfree_camera_data_source_database);

        @NonNull
        private final String logName;
        private final int labelRes;

        DataSource(@NonNull String logName, int labelRes) {
            this.logName = logName;
            this.labelRes = labelRes;
        }
    }
}
