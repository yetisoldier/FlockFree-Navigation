package net.osmand.plus.plugins.flockfree;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.util.Algorithms;

import java.util.Collections;
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
}
