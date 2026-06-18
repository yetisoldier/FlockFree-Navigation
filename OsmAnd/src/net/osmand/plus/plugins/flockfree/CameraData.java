package net.osmand.plus.plugins.flockfree;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public class CameraData {

    private static final Log LOG = PlatformUtil.getLog(CameraData.class);

    private static final String CAMERA_DATA_URL = FlockFreePreferences.CAMERA_DATA_URL;
    private static final String CACHE_FILENAME = "cameras.geojson";
    private static final long WEEK_MS = FlockFreePreferences.REFRESH_INTERVAL_MS;
    private static final long MAX_GEOJSON_BYTES = 128L * 1024 * 1024;
    private static final double SPATIAL_CELL_DEGREES = 0.05d;

    private final OsmandApplication app;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile List<CameraPoint> cameras = new ArrayList<>();
    private volatile Map<Long, List<CameraPoint>> cameraGrid = new HashMap<>();
    private volatile boolean dataLoaded = false;
    private volatile boolean loading = false;

    public CameraData(@NonNull OsmandApplication app) {
        this.app = app;
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
                boolean loadedFromCache = loadFromCache();
                long lastUpdate = getLastUpdateTimestamp();
                if (!loadedFromCache || isRefreshDue(lastUpdate)) {
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
                    loadFromCache();
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
            return loadFromCache();
        } catch (Exception e) {
            LOG.error("Failed to load camera cache for routing", e);
            return false;
        } finally {
            loading = false;
        }
    }

    private File getCacheFile() {
        File dir = app.getCacheDir();
        return new File(dir, CACHE_FILENAME);
    }

    private boolean loadFromCache() {
        File cacheFile = getCacheFile();
        if (cacheFile.exists()) {
            try {
                String json = readGeoJsonFile(cacheFile);
                if (!parseGeoJSON(json, "cache")) {
                    return false;
                }
                dataLoaded = true;
                LOG.info("Loaded " + cameras.size() + " cameras from cache (" + cacheFile.length() + " bytes)");
                return true;
            } catch (Exception e) {
                LOG.error("Failed to load camera cache", e);
            }
        } else {
            LOG.info("No FlockFree camera cache found");
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
            if (!parseGeoJSON(json, "network")) {
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

    private boolean parseGeoJSON(@NonNull String json, @NonNull String source) {
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
            int skipped = 0;
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
                point.brand = optProperty(props, "brand", null);
                point.direction = optProperty(props, "direction", null);
                point.operator = optProperty(props, "operator", null);
                point.mountType = optProperty(props, "mountType", "mount_type");
                point.surveillanceZone = optProperty(props, "surveillanceZone", "surveillance_zone");
                point.osmTimestamp = optProperty(props, "osmTimestamp", "osm_timestamp");

                parsed.add(point);
            }
            if (parsed.isEmpty()) {
                LOG.error("Parsed zero camera points from " + source + "; skipped=" + skipped
                        + ", features=" + features.length());
                return false;
            }
            synchronized (this) {
                cameras = parsed;
                cameraGrid = buildSpatialGrid(parsed);
            }
            LOG.info("Parsed " + parsed.size() + " camera points from " + source
                    + "; skipped=" + skipped + ", features=" + features.length()
                    + ", buckets=" + cameraGrid.size());
            return true;
        } catch (Exception e) {
            LOG.error("Failed to parse GeoJSON", e);
            return false;
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
        if (top < bottom) {
            double temp = top;
            top = bottom;
            bottom = temp;
        }
        top = clamp(top, -90d, 90d);
        bottom = clamp(bottom, -90d, 90d);

        if (left > right) {
            List<CameraPoint> result = getCamerasInBoundingBoxInternal(top, left, bottom, 180d);
            result.addAll(getCamerasInBoundingBoxInternal(top, -180d, bottom, right));
            return result;
        }
        return getCamerasInBoundingBoxInternal(top, left, bottom, right);
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
        return cameras.size();
    }

    public synchronized int getSpatialBucketCount() {
        return cameraGrid.size();
    }

    public synchronized List<CameraPoint> getCamerasNear(double lat, double lon, double radiusMeters) {
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

        List<CameraPoint> result = new ArrayList<>();
        List<CameraPoint> candidates = getCamerasInBoundingBox(top, left, bottom, right);
        for (CameraPoint cam : candidates) {
            double dist = net.osmand.util.MapUtils.getDistance(cam.lat, cam.lon, lat, lon);
            if (dist <= radiusMeters) {
                result.add(cam);
            }
        }
        return result;
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

    private String readGeoJsonFile(@NonNull File file) throws IOException {
        if (file.length() > MAX_GEOJSON_BYTES) {
            throw new IOException("Camera cache is too large: " + file.length() + " bytes");
        }
        try (BufferedInputStream buffered = new BufferedInputStream(new java.io.FileInputStream(file))) {
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
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
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

    public static class CameraPoint {
        public double lat;
        public double lon;
        @Nullable public String osmId;
        @Nullable public String osmType;
        @Nullable public String brand;
        @Nullable public String direction;
        @Nullable public String operator;
        @Nullable public String mountType;
        @Nullable public String surveillanceZone;
        @Nullable public String osmTimestamp;
    }
}
