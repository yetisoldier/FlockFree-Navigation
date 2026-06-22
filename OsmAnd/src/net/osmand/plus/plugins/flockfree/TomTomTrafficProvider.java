package net.osmand.plus.plugins.flockfree;

import android.net.Uri;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Converts TomTom Traffic Flow Segment Data into OsmAnd road speed multipliers.
 *
 * This class never owns an API key. The caller supplies the local user key per
 * request so secrets stay in app preferences and out of source control.
 */
public class TomTomTrafficProvider {

	private static final Log LOG = PlatformUtil.getLog(TomTomTrafficProvider.class);
	private static final String TOMTOM_TRAFFIC_HOST = "api.tomtom.com";
	private static final int FLOW_ZOOM = 10;
	private static final int MAX_ROUTE_SAMPLES = 12;
	private static final int CONNECT_TIMEOUT_MS = 5_000;
	private static final int READ_TIMEOUT_MS = 5_000;
	private static final long CACHE_TTL_MS = 60_000L;
	private static final float MIN_SLOWDOWN_MULTIPLIER = 0.10f;
	private static final float ROAD_CLOSURE_MULTIPLIER = 0.05f;
	private static final float MIN_CONFIDENCE = 0.35f;
	private static final float MIN_APPLIED_SLOWDOWN = 0.92f;

	private final Map<String, CachedFlow> flowCache = new HashMap<>();

	// Traffic color cache for route line coloring
	private static final long COLOR_CACHE_TTL_MS = 90_000L;
	private static final int MAX_CONCURRENT_COLOR_FETCHES = 3;
	private static final int MAX_COLOR_FETCHES_PER_CYCLE = 30;
	private final ConcurrentHashMap<String, CachedColor> trafficColorCache = new ConcurrentHashMap<>();

	// Traffic colors (ARGB)
	public static final int COLOR_ROAD_CLOSED = 0xFF8B0000;
	public static final int COLOR_FREE_FLOW = 0xFF00A854;
	public static final int COLOR_SLOW = 0xFFFFC107;
	public static final int COLOR_CONGESTED = 0xFFE53935;
	public static final int COLOR_NO_DATA = 0xFF4285F4;

	/**
	 * Returns a traffic color int for the given speed ratio and road closure state.
	 * Used for Google Maps-style traffic-colored route lines.
	 */
	@ColorInt
	public static int getTrafficColor(double speedRatio, boolean roadClosure) {
		if (roadClosure) {
			return COLOR_ROAD_CLOSED;
		}
		if (speedRatio >= 0.9d) {
			return COLOR_FREE_FLOW;
		}
		if (speedRatio >= 0.5d) {
			return COLOR_SLOW;
		}
		return COLOR_CONGESTED;
	}

	/**
	 * Returns the cached traffic color for a segment midpoint, or the default
	 * no-data color if the cache has no valid entry.
	 */
	@ColorInt
	public int getTrafficColorForSegment(double lat, double lon) {
		String key = formatColorCacheKey(lat, lon);
		CachedColor cached = trafficColorCache.get(key);
		if (cached != null && System.currentTimeMillis() - cached.createdAtMs <= COLOR_CACHE_TTL_MS) {
			return cached.color;
		}
		return COLOR_NO_DATA;
	}

	/**
	 * Prefetch traffic colors for a list of segment midpoints by querying the
	 * TomTom Flow Segment Data API. Colors are cached for later retrieval.
	 *
	 * @param segmentMidpoints List of [lat, lon] pairs for each segment midpoint
	 * @param apiKey            TomTom API key
	 * @return Map of "lat,lon" → color int for successfully fetched segments
	 */
	@NonNull
	public Map<String, Integer> prefetchTrafficColors(@NonNull List<double[]> segmentMidpoints,
	                                                    @NonNull String apiKey) {
		Map<String, Integer> result = new ConcurrentHashMap<>();
		if (Algorithms.isEmpty(apiKey) || segmentMidpoints.isEmpty()) {
			return result;
		}
		ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_COLOR_FETCHES);
		AtomicInteger completed = new AtomicInteger(0);
		for (double[] midpoint : segmentMidpoints) {
			if (completed.get() >= MAX_COLOR_FETCHES_PER_CYCLE) {
				break;
			}
			if (midpoint == null || midpoint.length < 2) {
				continue;
			}
			double lat = midpoint[0];
			double lon = midpoint[1];
			if (!isValidCoordinate(lat, lon)) {
				continue;
			}
			String key = formatColorCacheKey(lat, lon);
			CachedColor existing = trafficColorCache.get(key);
			if (existing != null && System.currentTimeMillis() - existing.createdAtMs <= COLOR_CACHE_TTL_MS) {
				result.put(key, existing.color);
				continue;
			}
			completed.incrementAndGet();
			executor.submit(() -> {
				try {
					TrafficFlow flow = requestFlow(new RouteSample(0L, lat, lon), apiKey);
					int color;
					if (flow == null) {
						color = COLOR_NO_DATA;
					} else if (flow.roadClosure) {
						color = COLOR_ROAD_CLOSED;
					} else if (flow.confidence < MIN_CONFIDENCE || flow.currentSpeedKmph <= 0d || flow.freeFlowSpeedKmph <= 0d) {
						color = COLOR_NO_DATA;
					} else {
						double ratio = flow.currentSpeedKmph / flow.freeFlowSpeedKmph;
						color = getTrafficColor(ratio, false);
					}
					trafficColorCache.put(key, new CachedColor(color, System.currentTimeMillis()));
					result.put(key, color);
				} catch (Exception e) {
					LOG.warn("Failed to prefetch traffic color for " + key, e);
				}
			});
		}
		executor.shutdown();
		try {
			executor.awaitTermination(15, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return result;
	}

	@NonNull
	private static String formatColorCacheKey(double lat, double lon) {
		return String.format(Locale.US, "%.4f,%.4f", lat, lon);
	}

	@NonNull
	public Map<Long, Float> collectSpeedMultipliers(@NonNull List<RouteSegmentResult> route,
	                                                @NonNull String apiKey) {
		Map<Long, Float> multipliers = new LinkedHashMap<>();
		if (Algorithms.isEmpty(apiKey) || route.isEmpty()) {
			return multipliers;
		}
		int step = Math.max(1, route.size() / MAX_ROUTE_SAMPLES);
		int samples = 0;
		for (int i = 0; i < route.size() && samples < MAX_ROUTE_SAMPLES; i += step) {
			RouteSegmentResult segment = route.get(i);
			RouteSample sample = createSample(segment);
			if (sample == null || multipliers.containsKey(sample.roadId)) {
				continue;
			}
			samples++;
			TrafficFlow flow = requestFlow(sample, apiKey);
			if (flow == null) {
				continue;
			}
			Float multiplier = flow.toSpeedMultiplier();
			if (multiplier != null) {
				multipliers.merge(sample.roadId, multiplier, Math::min);
			}
		}
		return multipliers;
	}

	@Nullable
	private RouteSample createSample(@NonNull RouteSegmentResult segment) {
		RouteDataObject road = segment.getObject();
		if (road == null) {
			return null;
		}
		int start = segment.getStartPointIndex();
		int end = segment.getEndPointIndex();
		int midpoint = start + ((end - start) / 2);
		LatLon point = segment.getPoint(midpoint);
		if (point == null || !isValidCoordinate(point.getLatitude(), point.getLongitude())) {
			return null;
		}
		return new RouteSample(road.getId(), point.getLatitude(), point.getLongitude());
	}

	private boolean isValidCoordinate(double latitude, double longitude) {
		return !Double.isNaN(latitude) && !Double.isNaN(longitude)
				&& latitude >= -90d && latitude <= 90d
				&& longitude >= -180d && longitude <= 180d;
	}

	@Nullable
	private TrafficFlow requestFlow(@NonNull RouteSample sample, @NonNull String apiKey) {
		String cacheKey = sample.getCacheKey();
		long now = System.currentTimeMillis();
		synchronized (flowCache) {
			CachedFlow cached = flowCache.get(cacheKey);
			if (cached != null && now - cached.createdAtMs <= CACHE_TTL_MS) {
				return cached.flow;
			}
		}
		TrafficFlow flow = fetchFlow(sample, apiKey);
		if (flow != null) {
			synchronized (flowCache) {
				flowCache.put(cacheKey, new CachedFlow(flow, now));
			}
		}
		return flow;
	}

	@Nullable
	private TrafficFlow fetchFlow(@NonNull RouteSample sample, @NonNull String apiKey) {
		HttpURLConnection connection = null;
		try {
			URL url = new URL(buildFlowUrl(sample, apiKey));
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestMethod("GET");
			connection.setRequestProperty("Accept", "application/json");
			int responseCode = connection.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) {
				LOG.warn("TomTom traffic flow request failed with HTTP " + responseCode);
				return null;
			}
			return parseFlow(readResponse(connection));
		} catch (IOException | JSONException e) {
			LOG.warn("TomTom traffic flow request failed", e);
			return null;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@NonNull
	private String buildFlowUrl(@NonNull RouteSample sample, @NonNull String apiKey) {
		return new Uri.Builder()
				.scheme("https")
				.authority(TOMTOM_TRAFFIC_HOST)
				.appendPath("traffic")
				.appendPath("services")
				.appendPath("4")
				.appendPath("flowSegmentData")
				.appendPath("absolute")
				.appendPath(String.valueOf(FLOW_ZOOM))
				.appendPath("json")
				.appendQueryParameter("key", apiKey)
				.appendQueryParameter("point", String.format(Locale.US, "%.6f,%.6f",
						sample.latitude, sample.longitude))
				.appendQueryParameter("unit", "kmph")
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

	@Nullable
	private TrafficFlow parseFlow(@NonNull String body) throws JSONException {
		JSONObject flowSegment = new JSONObject(body).optJSONObject("flowSegmentData");
		if (flowSegment == null) {
			return null;
		}
		return new TrafficFlow(
				flowSegment.optDouble("currentSpeed", 0d),
				flowSegment.optDouble("freeFlowSpeed", 0d),
				flowSegment.optDouble("confidence", 0d),
				flowSegment.optBoolean("roadClosure", false));
	}

	private static class RouteSample {
		final long roadId;
		final double latitude;
		final double longitude;

		RouteSample(long roadId, double latitude, double longitude) {
			this.roadId = roadId;
			this.latitude = latitude;
			this.longitude = longitude;
		}

		@NonNull
		String getCacheKey() {
			return String.format(Locale.US, "%.4f,%.4f", latitude, longitude);
		}
	}

	private static class TrafficFlow {
		final double currentSpeedKmph;
		final double freeFlowSpeedKmph;
		final double confidence;
		final boolean roadClosure;

		TrafficFlow(double currentSpeedKmph, double freeFlowSpeedKmph,
		            double confidence, boolean roadClosure) {
			this.currentSpeedKmph = currentSpeedKmph;
			this.freeFlowSpeedKmph = freeFlowSpeedKmph;
			this.confidence = confidence;
			this.roadClosure = roadClosure;
		}

		@Nullable
		Float toSpeedMultiplier() {
			if (roadClosure) {
				return ROAD_CLOSURE_MULTIPLIER;
			}
			if (confidence < MIN_CONFIDENCE || currentSpeedKmph <= 0d || freeFlowSpeedKmph <= 0d) {
				return null;
			}
			float multiplier = (float) (currentSpeedKmph / freeFlowSpeedKmph);
			if (multiplier >= MIN_APPLIED_SLOWDOWN) {
				return null;
			}
			return Math.max(MIN_SLOWDOWN_MULTIPLIER, Math.min(1.0f, multiplier));
		}
	}

	private static class CachedFlow {
		final TrafficFlow flow;
		final long createdAtMs;

		CachedFlow(@NonNull TrafficFlow flow, long createdAtMs) {
			this.flow = flow;
			this.createdAtMs = createdAtMs;
		}
	}

	private static class CachedColor {
		final int color;
		final long createdAtMs;

		CachedColor(int color, long createdAtMs) {
			this.color = color;
			this.createdAtMs = createdAtMs;
		}
	}
}
