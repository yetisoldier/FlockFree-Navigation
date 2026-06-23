package net.osmand.plus.plugins.flockfree;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Fetches traffic incidents from the TomTom Incident Details API v5.
 *
 * This class never owns an API key. The caller supplies the local user key per
 * request so secrets stay in app preferences and out of source control.
 */
public class TomTomIncidentProvider {

    private static final Log LOG = PlatformUtil.getLog(TomTomIncidentProvider.class);
    private static final String TOMTOM_TRAFFIC_HOST = "api.tomtom.com";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final long CACHE_TTL_MS = 60_000L;
    private static final String FIELDS_PARAM = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,events{description,code,iconCategory},startTime,endTime,from,to,length,delay,roadNumbers}}}";

    private final ConcurrentHashMap<String, CachedIncidents> incidentCache = new ConcurrentHashMap<>();
    private final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();

    /**
     * Represents a single traffic incident from the TomTom Incident Details API.
     */
    public static class TrafficIncident {
        public final double lat;
        public final double lon;
        public final int iconCategory;
        public final String description;
        public final long startTimeMs;
        public final long endTimeMs;
        public final boolean roadClosed;
        public final String id;

        public TrafficIncident(double lat, double lon, int iconCategory,
                               String description, long startTimeMs, long endTimeMs,
                               boolean roadClosed, String id) {
            this.lat = lat;
            this.lon = lon;
            this.iconCategory = iconCategory;
            this.description = description;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.roadClosed = roadClosed;
            this.id = id;
        }
    }

    /**
     * Returns cached traffic incidents for the given bounding box.
     * This method NEVER performs network I/O and is safe to call from the main thread.
     * Use {@link #prefetchIncidentsAsync} to populate the cache from a background thread.
     * Returns an empty list if the cache is cold or stale.
     */
    @NonNull
    public List<TrafficIncident> fetchIncidents(double minLat, double minLon,
                                                  double maxLat, double maxLon,
                                                  @NonNull String apiKey) {
        if (Algorithms.isEmpty(apiKey)) {
            return Collections.emptyList();
        }
        String cacheKey = formatBboxKey(minLat, minLon, maxLat, maxLon);
        long now = System.currentTimeMillis();
        CachedIncidents cached = incidentCache.get(cacheKey);
        if (cached != null && now - cached.createdAtMs <= CACHE_TTL_MS) {
            return cached.incidents;
        }
        // Cache cold or stale — return empty; prefetchIncidentsAsync will populate it
        return Collections.emptyList();
    }

    /**
     * Synchronously fetches incidents from the network. Must be called from a background thread.
     * Results are cached for CACHE_TTL_MS. Returns an empty list on error.
     */
    @NonNull
    public List<TrafficIncident> fetchIncidentsBlocking(double minLat, double minLon,
                                                         double maxLat, double maxLon,
                                                         @NonNull String apiKey) {
        if (Algorithms.isEmpty(apiKey)) {
            return Collections.emptyList();
        }
        String cacheKey = formatBboxKey(minLat, minLon, maxLat, maxLon);
        long now = System.currentTimeMillis();
        CachedIncidents cached = incidentCache.get(cacheKey);
        if (cached != null && now - cached.createdAtMs <= CACHE_TTL_MS) {
            return cached.incidents;
        }
        List<TrafficIncident> incidents = doFetchIncidents(minLat, minLon, maxLat, maxLon, apiKey);
        incidentCache.put(cacheKey, new CachedIncidents(incidents, now));
        return incidents;
    }

    /**
     * Asynchronously fetches traffic incidents and caches the result.
     * Calls onComplete on a background thread when finished (may be null).
     */
    public void prefetchIncidentsAsync(double minLat, double minLon,
                                        double maxLat, double maxLon,
                                        @NonNull String apiKey,
                                        @Nullable Runnable onComplete) {
        fetchExecutor.execute(() -> {
            try {
                fetchIncidentsBlocking(minLat, minLon, maxLat, maxLon, apiKey);
            } catch (Exception e) {
                LOG.warn("Async incident prefetch failed", e);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    /**
     * Clears all cached incidents.
     */
    public void clearCache() {
        incidentCache.clear();
    }

    /**
     * Shuts down the background executor. Call when the provider is no longer needed.
     */
    public void shutdown() {
        fetchExecutor.shutdownNow();
    }

    @NonNull
    private List<TrafficIncident> doFetchIncidents(double minLat, double minLon,
                                                    double maxLat, double maxLon,
                                                    @NonNull String apiKey) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(buildIncidentUrl(minLat, minLon, maxLat, maxLon, apiKey));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            int responseCode = connection.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) {
				LOG.warn("TomTom incident details request failed with HTTP " + responseCode);
				return Collections.emptyList();
			}
			List<TrafficIncident> incidents = parseIncidents(readResponse(connection));
			LOG.info("TomTom incident details returned " + incidents.size() + " incidents");
			return incidents;
		} catch (IOException | JSONException e) {
			LOG.warn("TomTom incident details request failed", e);
			return Collections.emptyList();
		} finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @NonNull
    private String buildIncidentUrl(double minLat, double minLon,
                                     double maxLat, double maxLon,
                                     @NonNull String apiKey) {
        // TomTom bbox format: minLon,minLat,maxLon,maxLat
        String bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f",
                minLon, minLat, maxLon, maxLat);
        return new Uri.Builder()
                .scheme("https")
                .authority(TOMTOM_TRAFFIC_HOST)
                .appendPath("traffic")
                .appendPath("services")
                .appendPath("5")
                .appendPath("incidentDetails")
                .appendQueryParameter("key", apiKey)
                .appendQueryParameter("bbox", bbox)
                .appendQueryParameter("fields", FIELDS_PARAM)
                .appendQueryParameter("language", "en-US")
                .appendQueryParameter("timeValidityFilter", "present")
                .build()
                .toString();
    }

    @NonNull
    private String readResponse(@NonNull HttpURLConnection connection) throws IOException {
        InputStream inputStream = connection.getInputStream();
        if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
            inputStream = new GZIPInputStream(inputStream);
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    @NonNull
    private List<TrafficIncident> parseIncidents(@NonNull String body) throws JSONException {
        JSONObject root = new JSONObject(body);
        JSONArray incidentsArray = root.optJSONArray("incidents");
        if (incidentsArray == null) {
            incidentsArray = root.optJSONArray("features");
        }
        if (incidentsArray == null || incidentsArray.length() == 0) {
            return Collections.emptyList();
        }
        List<TrafficIncident> incidents = new ArrayList<>(incidentsArray.length());
        for (int i = 0; i < incidentsArray.length(); i++) {
            JSONObject incidentJson = incidentsArray.optJSONObject(i);
            if (incidentJson == null) {
                continue;
            }
            TrafficIncident incident = parseFeature(incidentJson);
            if (incident != null) {
                incidents.add(incident);
            }
        }
        return incidents;
    }

    @Nullable
    private TrafficIncident parseFeature(@NonNull JSONObject feature) {
        JSONObject geometry = feature.optJSONObject("geometry");
        if (geometry == null) {
            return null;
        }
        JSONArray coordinates = geometry.optJSONArray("coordinates");
        if (coordinates == null || coordinates.length() == 0) {
            return null;
        }
        // coordinates may be a single point [lon, lat] or a line/multi-point array
        double lat;
        double lon;
        Object firstCoord = coordinates.opt(0);
        if (firstCoord instanceof JSONArray) {
            // Line or multi-point: take the first point
            JSONArray firstPoint = (JSONArray) firstCoord;
            if (firstPoint.length() < 2) {
                return null;
            }
            lon = firstPoint.optDouble(0, 0);
            lat = firstPoint.optDouble(1, 0);
        } else {
            // Single point [lon, lat]
            if (coordinates.length() < 2) {
                return null;
            }
            lon = coordinates.optDouble(0, 0);
            lat = coordinates.optDouble(1, 0);
        }

        JSONObject properties = feature.optJSONObject("properties");
        int iconCategory = 0;
        String description = null;
        long startTimeMs = 0;
        long endTimeMs = 0;
        String id = feature.optString("id", null);
        if (properties != null) {
            id = properties.optString("id", id);
            iconCategory = properties.optInt("iconCategory", 0);
            description = getIncidentDescription(properties);
            startTimeMs = parseTomTomTime(properties.optString("startTime", null));
            endTimeMs = parseTomTomTime(properties.optString("endTime", null));
        }
        boolean roadClosed = iconCategory == 8;
        return new TrafficIncident(lat, lon, iconCategory, description,
                startTimeMs, endTimeMs, roadClosed, id);
    }

    @Nullable
    private String getIncidentDescription(@NonNull JSONObject properties) {
        JSONArray events = properties.optJSONArray("events");
        if (events != null && events.length() > 0) {
            JSONObject event = events.optJSONObject(0);
            if (event != null) {
                String description = event.optString("description", null);
                if (!Algorithms.isEmpty(description)) {
                    return description;
                }
            }
        }
        String from = properties.optString("from", null);
        String to = properties.optString("to", null);
        if (!Algorithms.isEmpty(from) && !Algorithms.isEmpty(to)) {
            return from + " to " + to;
        }
        return null;
    }

    private long parseTomTomTime(@Nullable String value) {
        if (Algorithms.isEmpty(value)) {
            return 0L;
        }
        try {
            return java.time.Instant.parse(value).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    @NonNull
    private static String formatBboxKey(double minLat, double minLon, double maxLat, double maxLon) {
        return String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", minLat, minLon, maxLat, maxLon);
    }

    private static class CachedIncidents {
        final List<TrafficIncident> incidents;
        final long createdAtMs;

        CachedIncidents(@NonNull List<TrafficIncident> incidents, long createdAtMs) {
            this.incidents = incidents;
            this.createdAtMs = createdAtMs;
        }
    }
}
