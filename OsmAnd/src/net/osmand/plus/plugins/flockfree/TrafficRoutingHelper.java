package net.osmand.plus.plugins.flockfree;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.Algorithms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns FlockFree live-traffic routing state.
 *
 * Traffic is intentionally a soft routing signal: camera avoidance remains the
 * hard constraint and RouteProvider reruns camera checks before accepting any
 * future traffic-weighted reroute.
 */
public class TrafficRoutingHelper {

	public enum TrafficStatus {
		NONE,
		SKIPPED_DISABLED,
		SKIPPED_PARTIAL,
		SKIPPED_NO_PROVIDER,
		SKIPPED_NO_DATA,
		APPLIED,
		FALLBACK
	}

	public static final String TRAFFIC_ROUTE_INFO_ATTRIBUTE = "routeInfo_traffic";
	private static final long TRAFFIC_COLOR_REFRESH_INTERVAL_MS = 90_000L;

	private final OsmandApplication app;
	private final FlockFreePlugin plugin;
	private final TomTomTrafficProvider tomTomTrafficProvider = new TomTomTrafficProvider();
	private final ExecutorService trafficColorExecutor = Executors.newSingleThreadExecutor();
	private final Object trafficColorLock = new Object();
	private TrafficStatus lastTrafficStatus = TrafficStatus.NONE;
	private int lastTrafficRoadCount;
	@NonNull
	private List<Integer> cachedTrafficSegmentColors = Collections.emptyList();
	private long cachedTrafficRouteSignature;
	private long lastTrafficColorRefreshMs;
	private long trafficColorGeneration;
	private boolean trafficColorRefreshRunning;
	private int lastTrafficColorSegmentCount;
	private int lastTrafficColorSampleCount;
	private int lastTrafficFreeFlowCount;
	private int lastTrafficSlowCount;
	private int lastTrafficCongestedCount;
	private int lastTrafficNoDataCount;
	private boolean closed;

	public TrafficRoutingHelper(@NonNull OsmandApplication app, @NonNull FlockFreePlugin plugin) {
		this.app = app;
		this.plugin = plugin;
	}

	public boolean isTrafficRoutingEnabled() {
		return plugin.TRAFFIC_ROUTING_ENABLED.get();
	}

	@NonNull
	public Map<Long, Float> collectTrafficSpeedMultipliersForRoute(@NonNull RouteCalculationResult route) {
		if (!isTrafficRoutingEnabled()) {
			recordTrafficSkipped(TrafficStatus.SKIPPED_DISABLED);
			return Collections.emptyMap();
		}
		if (!route.isCalculated() || route.getOriginalRoute() == null || route.getOriginalRoute().isEmpty()) {
			recordTrafficSkipped(TrafficStatus.SKIPPED_NO_DATA);
			return Collections.emptyMap();
		}

		String tomTomApiKey = plugin.TOMTOM_API_KEY.get();
		if (Algorithms.isEmpty(tomTomApiKey)) {
			recordTrafficSkipped(TrafficStatus.SKIPPED_NO_PROVIDER);
			return Collections.emptyMap();
		}
		Map<Long, Float> multipliers = tomTomTrafficProvider.collectSpeedMultipliers(
				route.getOriginalRoute(), tomTomApiKey.trim());
		if (multipliers.isEmpty()) {
			recordTrafficSkipped(TrafficStatus.SKIPPED_NO_DATA);
		}
		return multipliers;
	}

	public synchronized void recordTrafficApplied(int roadCount) {
		lastTrafficStatus = TrafficStatus.APPLIED;
		lastTrafficRoadCount = roadCount;
	}

	public synchronized void recordTrafficFallback(int roadCount) {
		lastTrafficStatus = TrafficStatus.FALLBACK;
		lastTrafficRoadCount = roadCount;
	}

	public synchronized void recordTrafficSkipped(@NonNull TrafficStatus status) {
		lastTrafficStatus = status;
		lastTrafficRoadCount = 0;
	}

	@NonNull
	public synchronized String consumeLastTrafficStatusSummary() {
		String summary;
		switch (lastTrafficStatus) {
			case SKIPPED_DISABLED:
				summary = app.getString(R.string.flockfree_traffic_status_disabled);
				break;
			case SKIPPED_PARTIAL:
				summary = app.getString(R.string.flockfree_traffic_status_skipped_partial);
				break;
			case SKIPPED_NO_PROVIDER:
				summary = app.getString(R.string.flockfree_traffic_status_no_provider);
				break;
			case SKIPPED_NO_DATA:
				summary = app.getString(R.string.flockfree_traffic_status_no_data);
				break;
			case APPLIED:
				summary = app.getString(R.string.flockfree_traffic_status_applied, lastTrafficRoadCount);
				break;
			case FALLBACK:
				summary = app.getString(R.string.flockfree_traffic_status_fallback, lastTrafficRoadCount);
				break;
			case NONE:
			default:
				summary = "";
				break;
		}
		lastTrafficStatus = TrafficStatus.NONE;
		lastTrafficRoadCount = 0;
		return summary;
	}

	/**
	 * Returns per-segment traffic colors for the given route segments, suitable for
	 * use with ColoringType.ATTRIBUTE route line coloring.
	 *
	 * Colors are fetched from TomTom Flow Segment Data API via midpoints of each
	 * segment. If traffic data is unavailable, the default no-data color is returned
	 * for each segment.
	 *
	 * @param segments Route segments from RouteCalculationResult.getOriginalRoute()
	 * @return List of ARGB color ints, one per segment
	 */
	@NonNull
	public List<Integer> getTrafficColorsForRoute(@Nullable List<RouteSegmentResult> segments) {
		if (Algorithms.isEmpty(segments)) {
			return Collections.emptyList();
		}
		String tomTomApiKey = plugin.TOMTOM_API_KEY.get();
		if (!isTrafficRoutingEnabled() || Algorithms.isEmpty(tomTomApiKey)) {
			resetCachedTrafficColors();
			return buildNoDataColors(segments.size());
		}

		long routeSignature = buildRouteSignature(segments);
		scheduleTrafficColorRefreshIfNeeded(segments, tomTomApiKey.trim(), routeSignature);
		synchronized (trafficColorLock) {
			if (cachedTrafficRouteSignature == routeSignature && cachedTrafficSegmentColors.size() == segments.size()) {
				return new ArrayList<>(cachedTrafficSegmentColors);
			}
		}
		return buildNoDataColors(segments.size());
	}

	public long getTrafficColorGeneration() {
		synchronized (trafficColorLock) {
			return trafficColorGeneration;
		}
	}

	public boolean isTrafficColorRefreshRunning() {
		synchronized (trafficColorLock) {
			return trafficColorRefreshRunning;
		}
	}

	public long getLastTrafficColorRefreshMs() {
		synchronized (trafficColorLock) {
			return lastTrafficColorRefreshMs;
		}
	}

	public int getLastTrafficColorSegmentCount() {
		synchronized (trafficColorLock) {
			return lastTrafficColorSegmentCount;
		}
	}

	public int getLastTrafficColorSampleCount() {
		synchronized (trafficColorLock) {
			return lastTrafficColorSampleCount;
		}
	}

	@NonNull
	public String getTrafficColorLegendSummary() {
		if (!isTrafficRoutingEnabled()) {
			return app.getString(R.string.flockfree_traffic_widget_off);
		}
		if (Algorithms.isEmpty(plugin.TOMTOM_API_KEY.get())) {
			return app.getString(R.string.flockfree_traffic_widget_no_provider);
		}
		synchronized (trafficColorLock) {
			if (trafficColorRefreshRunning && lastTrafficColorRefreshMs == 0) {
				return app.getString(R.string.flockfree_traffic_widget_refreshing);
			}
			if (lastTrafficColorRefreshMs == 0) {
				return app.getString(R.string.flockfree_traffic_widget_waiting);
			}
			if (trafficColorRefreshRunning) {
				return app.getString(R.string.flockfree_traffic_widget_refreshing);
			}
			int coloredCount = lastTrafficFreeFlowCount + lastTrafficSlowCount + lastTrafficCongestedCount;
			return app.getString(R.string.flockfree_traffic_widget_summary,
					coloredCount, lastTrafficNoDataCount, lastTrafficColorSampleCount);
		}
	}

	private void scheduleTrafficColorRefreshIfNeeded(@NonNull List<RouteSegmentResult> segments,
	                                                 @NonNull String tomTomApiKey,
	                                                 long routeSignature) {
		long now = System.currentTimeMillis();
		synchronized (trafficColorLock) {
			if (closed) {
				return;
			}
			boolean routeChanged = cachedTrafficRouteSignature != routeSignature
					|| cachedTrafficSegmentColors.size() != segments.size();
			boolean stale = now - lastTrafficColorRefreshMs >= TRAFFIC_COLOR_REFRESH_INTERVAL_MS;
			if (trafficColorRefreshRunning || (!routeChanged && !stale)) {
				return;
			}
			trafficColorRefreshRunning = true;
		}
		List<RouteSegmentResult> segmentsSnapshot = new ArrayList<>(segments);
		try {
			trafficColorExecutor.execute(() -> refreshTrafficColors(segmentsSnapshot, tomTomApiKey, routeSignature));
		} catch (RuntimeException e) {
			synchronized (trafficColorLock) {
				trafficColorRefreshRunning = false;
			}
		}
	}

	private void refreshTrafficColors(@NonNull List<RouteSegmentResult> segments,
	                                  @NonNull String tomTomApiKey,
	                                  long routeSignature) {
		List<Integer> colors;
		try {
			colors = buildTrafficColors(segments, tomTomApiKey);
		} catch (RuntimeException e) {
			colors = buildNoDataColors(segments.size());
		}
		long now = System.currentTimeMillis();
		int freeFlowCount = 0;
		int slowCount = 0;
		int congestedCount = 0;
		int noDataCount = 0;
		for (Integer color : colors) {
			int normalized = color != null && color != 0 ? color : TomTomTrafficProvider.COLOR_NO_DATA;
			if (normalized == TomTomTrafficProvider.COLOR_FREE_FLOW) {
				freeFlowCount++;
			} else if (normalized == TomTomTrafficProvider.COLOR_SLOW) {
				slowCount++;
			} else if (normalized == TomTomTrafficProvider.COLOR_CONGESTED
					|| normalized == TomTomTrafficProvider.COLOR_ROAD_CLOSED) {
				congestedCount++;
			} else {
				noDataCount++;
			}
		}

		synchronized (trafficColorLock) {
			cachedTrafficRouteSignature = routeSignature;
			cachedTrafficSegmentColors = new ArrayList<>(colors);
			lastTrafficColorRefreshMs = now;
			lastTrafficColorSegmentCount = segments.size();
			lastTrafficColorSampleCount = getTrafficColorSampleIndexes(segments.size()).size();
			lastTrafficFreeFlowCount = freeFlowCount;
			lastTrafficSlowCount = slowCount;
			lastTrafficCongestedCount = congestedCount;
			lastTrafficNoDataCount = noDataCount;
			trafficColorGeneration++;
			trafficColorRefreshRunning = false;
		}
		plugin.setLastTrafficRouteCheckSummary(getTrafficColorLegendSummary());
		app.runInUIThread(() -> app.getOsmandMap().refreshMap());
		scheduleNextTrafficColorRefresh(routeSignature);
	}

	private void scheduleNextTrafficColorRefresh(long routeSignature) {
		app.runInUIThread(() -> {
			synchronized (trafficColorLock) {
				if (closed) {
					return;
				}
			}
			RouteCalculationResult route = app.getRoutingHelper().getRoute();
			if (route != null && route.isCalculated()
					&& route.getOriginalRoute() != null && !route.getOriginalRoute().isEmpty()
					&& buildRouteSignature(route.getOriginalRoute()) == routeSignature) {
				getTrafficColorsForRoute(route.getOriginalRoute());
			}
		}, TRAFFIC_COLOR_REFRESH_INTERVAL_MS);
	}

	@NonNull
	private List<Integer> buildTrafficColors(@NonNull List<RouteSegmentResult> segments, @NonNull String tomTomApiKey) {
		List<Integer> sampleIndexes = getTrafficColorSampleIndexes(segments.size());
		List<double[]> sampleMidpoints = new ArrayList<>(sampleIndexes.size());
		for (Integer index : sampleIndexes) {
			LatLon mid = getSegmentMidpoint(segments.get(index));
			if (mid != null) {
				sampleMidpoints.add(new double[]{mid.getLatitude(), mid.getLongitude()});
			} else {
				sampleMidpoints.add(null);
			}
		}
		Map<String, Integer> colorMap = tomTomTrafficProvider.prefetchTrafficColors(sampleMidpoints, tomTomApiKey.trim());
		List<Integer> sampleColors = new ArrayList<>(sampleMidpoints.size());
		for (double[] midpoint : sampleMidpoints) {
			sampleColors.add(getTrafficColorForMidpoint(colorMap, midpoint));
		}

		List<Integer> colors = new ArrayList<>(segments.size());
		for (int i = 0; i < segments.size(); i++) {
			colors.add(getNearestSampleColor(i, sampleIndexes, sampleColors));
		}
		return colors;
	}

	private void resetCachedTrafficColors() {
		synchronized (trafficColorLock) {
			cachedTrafficSegmentColors = Collections.emptyList();
			cachedTrafficRouteSignature = 0;
			lastTrafficColorRefreshMs = 0;
			lastTrafficColorSegmentCount = 0;
			lastTrafficColorSampleCount = 0;
			lastTrafficFreeFlowCount = 0;
			lastTrafficSlowCount = 0;
			lastTrafficCongestedCount = 0;
			lastTrafficNoDataCount = 0;
			trafficColorRefreshRunning = false;
			trafficColorGeneration++;
		}
	}

	@NonNull
	private List<Integer> buildNoDataColors(int count) {
		List<Integer> colors = new ArrayList<>(Math.max(0, count));
		for (int i = 0; i < count; i++) {
			colors.add(TomTomTrafficProvider.COLOR_NO_DATA);
		}
		return colors;
	}

	private long buildRouteSignature(@NonNull List<RouteSegmentResult> segments) {
		long signature = segments.size();
		List<Integer> sampleIndexes = getTrafficColorSampleIndexes(segments.size());
		for (Integer index : sampleIndexes) {
			if (index >= 0 && index < segments.size()) {
				signature = 31 * signature + getCoordinateHash(getSegmentMidpoint(segments.get(index)));
			}
		}
		return signature;
	}

	private long getCoordinateHash(@Nullable LatLon point) {
		if (point == null) {
			return 0;
		}
		long lat = Math.round(point.getLatitude() * 100000);
		long lon = Math.round(point.getLongitude() * 100000);
		return (lat << 32) ^ lon;
	}

	public void close() {
		synchronized (trafficColorLock) {
			closed = true;
			trafficColorRefreshRunning = false;
		}
		trafficColorExecutor.shutdownNow();
	}

	@NonNull
	private List<Integer> getTrafficColorSampleIndexes(int segmentCount) {
		List<Integer> indexes = new ArrayList<>();
		if (segmentCount <= 0) {
			return indexes;
		}
		int maxSamples = Math.min(segmentCount, 12);
		if (maxSamples == 1) {
			indexes.add(0);
			return indexes;
		}
		for (int i = 0; i < maxSamples; i++) {
			int index = Math.round((segmentCount - 1) * (i / (float) (maxSamples - 1)));
			if (indexes.isEmpty() || indexes.get(indexes.size() - 1) != index) {
				indexes.add(index);
			}
		}
		return indexes;
	}

	private int getTrafficColorForMidpoint(@NonNull Map<String, Integer> colorMap, @Nullable double[] midpoint) {
		if (midpoint == null || midpoint.length < 2) {
			return TomTomTrafficProvider.COLOR_NO_DATA;
		}
		String key = String.format(Locale.US, "%.4f,%.4f", midpoint[0], midpoint[1]);
		Integer color = colorMap.get(key);
		return color != null && color != 0 ? color : TomTomTrafficProvider.COLOR_NO_DATA;
	}

	private int getNearestSampleColor(int segmentIndex, @NonNull List<Integer> sampleIndexes,
	                                  @NonNull List<Integer> sampleColors) {
		if (sampleIndexes.isEmpty() || sampleColors.isEmpty()) {
			return TomTomTrafficProvider.COLOR_NO_DATA;
		}
		int bestColor = TomTomTrafficProvider.COLOR_NO_DATA;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < sampleIndexes.size() && i < sampleColors.size(); i++) {
			int distance = Math.abs(segmentIndex - sampleIndexes.get(i));
			if (distance < bestDistance) {
				bestDistance = distance;
				Integer color = sampleColors.get(i);
				bestColor = color != null && color != 0 ? color : TomTomTrafficProvider.COLOR_NO_DATA;
			}
		}
		return bestColor;
	}

	/**
	 * Checks whether traffic-colored route line rendering is available:
	 * traffic routing must be enabled and the TomTom API key must be configured.
	 */
	public boolean isTrafficColoringAvailable() {
		return isTrafficRoutingEnabled() && !Algorithms.isEmpty(plugin.TOMTOM_API_KEY.get());
	}

	@Nullable
	private LatLon getSegmentMidpoint(@NonNull RouteSegmentResult segment) {
		int start = segment.getStartPointIndex();
		int end = segment.getEndPointIndex();
		int mid = start + ((end - start) / 2);
		return segment.getPoint(mid);
	}
}
