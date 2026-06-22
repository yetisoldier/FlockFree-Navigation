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

	private final OsmandApplication app;
	private final FlockFreePlugin plugin;
	private final TomTomTrafficProvider tomTomTrafficProvider = new TomTomTrafficProvider();
	private TrafficStatus lastTrafficStatus = TrafficStatus.NONE;
	private int lastTrafficRoadCount;

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
			List<Integer> defaults = new ArrayList<>(segments.size());
			for (int i = 0; i < segments.size(); i++) {
				defaults.add(TomTomTrafficProvider.COLOR_NO_DATA);
			}
			return defaults;
		}
		List<double[]> midpoints = new ArrayList<>(segments.size());
		for (RouteSegmentResult segment : segments) {
			LatLon mid = getSegmentMidpoint(segment);
			if (mid != null) {
				midpoints.add(new double[]{mid.getLatitude(), mid.getLongitude()});
			} else {
				midpoints.add(null);
			}
		}
		Map<String, Integer> colorMap = tomTomTrafficProvider.prefetchTrafficColors(midpoints, tomTomApiKey.trim());
		List<Integer> colors = new ArrayList<>(segments.size());
		for (double[] midpoint : midpoints) {
			if (midpoint == null || midpoint.length < 2) {
				colors.add(TomTomTrafficProvider.COLOR_NO_DATA);
				continue;
			}
			String key = String.format(java.util.Locale.US, "%.4f,%.4f", midpoint[0], midpoint[1]);
			Integer color = colorMap.get(key);
			colors.add(color != null ? color : TomTomTrafficProvider.COLOR_NO_DATA);
		}
		return colors;
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
