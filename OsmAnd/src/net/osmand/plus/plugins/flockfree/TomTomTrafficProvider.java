package net.osmand.plus.plugins.flockfree;

import android.net.Uri;

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
}
