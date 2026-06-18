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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public class CameraData {

    private static final Log LOG = PlatformUtil.getLog(CameraData.class);

    private static final String CAMERA_DATA_URL = "https://data.dontgetflocked.com/cameras.geojson.gz";
    private static final String CACHE_FILENAME = "cameras.geojson";
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long MAX_GEOJSON_BYTES = 128L * 1024 * 1024;

    private final OsmandApplication app;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile List<CameraPoint> cameras = new ArrayList<>();
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

    private void downloadCameraData() {
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
                return;
            }

            String json = readGeoJsonResponse(conn);
            if (!parseGeoJSON(json, "network")) {
                return;
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
        } catch (Exception e) {
            LOG.error("Failed to download camera data", e);
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
            }
            LOG.info("Parsed " + parsed.size() + " camera points from " + source
                    + "; skipped=" + skipped + ", features=" + features.length());
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
        List<CameraPoint> result = new ArrayList<>();
        for (CameraPoint cam : cameras) {
            if (cam.lat >= bottom && cam.lat <= top && cam.lon >= left && cam.lon <= right) {
                result.add(cam);
            }
        }
        return result;
    }

    public synchronized int getCameraCount() {
        return cameras.size();
    }

    public synchronized List<CameraPoint> getCamerasNear(double lat, double lon, double radiusMeters) {
        List<CameraPoint> result = new ArrayList<>();
        for (CameraPoint cam : cameras) {
            double dist = net.osmand.util.MapUtils.getDistance(cam.lat, cam.lon, lat, lon);
            if (dist <= radiusMeters) {
                result.add(cam);
            }
        }
        return result;
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
