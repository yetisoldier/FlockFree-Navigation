package net.osmand.plus.routing;


import static net.osmand.plus.settings.enums.RoutingType.A_STAR_2_PHASE;
import static net.osmand.plus.settings.enums.RoutingType.HH_CPP;
import static net.osmand.plus.settings.enums.RoutingType.HH_JAVA;

import android.os.Bundle;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.LocationsHolder;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.plus.shared.SharedUtil;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.data.QuadPointDouble;
import net.osmand.gpx.GPXFile;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.avoidroads.AvoidRoadsHelper;
import net.osmand.plus.avoidroads.DirectionPointsHelper;
import net.osmand.plus.helpers.TargetPointsHelper;
import net.osmand.plus.helpers.TargetPoint;
import net.osmand.plus.measurementtool.GpxApproximationHelper;
import net.osmand.plus.measurementtool.GpxApproximationParams;
import net.osmand.plus.onlinerouting.OnlineRoutingHelper;
import net.osmand.plus.onlinerouting.engine.OnlineRoutingEngine;
import net.osmand.plus.onlinerouting.engine.OnlineRoutingEngine.OnlineRoutingResponse;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.CameraAvoidanceHelper;
import net.osmand.plus.plugins.flockfree.CameraData;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.plugins.flockfree.TrafficRoutingHelper;
import net.osmand.plus.render.NativeOsmandLibrary;
import net.osmand.plus.routing.GPXRouteParams.GPXRouteParamsBuilder;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.enums.ApproximationType;
import net.osmand.plus.settings.enums.RoutingType;
import net.osmand.router.*;
import net.osmand.router.GeneralRouter.RoutingParameter;
import net.osmand.router.GeneralRouter.RoutingParameterType;
import net.osmand.router.RoutePlannerFrontEnd.GpxPoint;
import net.osmand.router.RoutePlannerFrontEnd.RouteCalculationMode;
import net.osmand.router.RoutingConfiguration.Builder;
import net.osmand.router.RoutingConfiguration.RoutingMemoryLimits;
import net.osmand.router.RoutingContext;
import net.osmand.router.TurnType;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.gpx.GPXUtilities.Route;
import net.osmand.gpx.GPXUtilities.TrkSegment;
import net.osmand.gpx.GPXUtilities.WptPt;
import net.osmand.util.Algorithms;
import net.osmand.util.CollectionUtils;
import net.osmand.util.MapUtils;

import org.json.JSONException;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;

import btools.routingapp.IBRouterService;


public class RouteProvider {

	private static final org.apache.commons.logging.Log log = PlatformUtil.getLog(RouteProvider.class);
	private static final int MIN_STRAIGHT_DIST = 50000;
	private static final int MAX_RELAXATION_ITERATIONS = 15;
	// Reserved for a future bounded multi-pass route scan; inactive in the current production path.
	private static final int MAX_AVOIDANCE_PASSES = 0;

	/** Number of roads to block per tier in staged tier-based avoidance. */
	private static final int TIER_SIZE = 3;
	private static final long FLOCKFREE_OPTIONAL_ROUTING_BUDGET_MS = 30_000L;
	/** Only scan cameras in the next N km of route during active navigation. */
	private static final int ACTIVE_NAVIGATION_HORIZON_KM = 10;
	/** Tighter time budget for avoidance replanning during active driving. */
	private static final long ACTIVE_NAVIGATION_BUDGET_MS = 5_000L;
	private static final double FLOCKFREE_BALANCED_MAX_AVOIDANCE_TIME_MULTIPLIER = 1.50d;
	private static final double FLOCKFREE_BALANCED_MAX_AVOIDANCE_DISTANCE_MULTIPLIER = 1.50d;
	private static final int FLOCKFREE_BALANCED_MAX_AVOIDANCE_EXTRA_TIME_SECONDS = 15 * 60;
	private static final double FLOCKFREE_STRICT_MAX_AVOIDANCE_TIME_MULTIPLIER = 2.00d;
	private static final double FLOCKFREE_STRICT_MAX_AVOIDANCE_DISTANCE_MULTIPLIER = 2.00d;
	private static final int FLOCKFREE_STRICT_MAX_AVOIDANCE_EXTRA_TIME_SECONDS = 30 * 60;
	private static final int FLOCKFREE_OPTIONAL_AVOIDANCE_STEPS = 1 + MAX_RELAXATION_ITERATIONS;
	private static final long FLOCKFREE_OPTIONAL_STEP_EXPECTED_MS =
			FLOCKFREE_OPTIONAL_ROUTING_BUDGET_MS / FLOCKFREE_OPTIONAL_AVOIDANCE_STEPS;
	private static final int ACTIVE_NAVIGATION_AVOIDANCE_STEPS = 1 + MAX_RELAXATION_ITERATIONS;
	private static final long ACTIVE_NAVIGATION_STEP_EXPECTED_MS =
			ACTIVE_NAVIGATION_BUDGET_MS / ACTIVE_NAVIGATION_AVOIDANCE_STEPS;

	private final GpxRouteHelper gpxRouteHelper = new GpxRouteHelper(this);

	private static class FlockFreeRouteVariant {
		final RouteCalculationResult result;
		final Set<Long> activeCameraAvoidanceRoadIds;

		FlockFreeRouteVariant(@NonNull RouteCalculationResult result,
		                      @Nullable Set<Long> activeCameraAvoidanceRoadIds) {
			this.result = result;
			this.activeCameraAvoidanceRoadIds = activeCameraAvoidanceRoadIds == null
					? Collections.emptySet()
					: new LinkedHashSet<>(activeCameraAvoidanceRoadIds);
		}
	}

	public static Location createLocation(@NonNull WptPt pt) {
		Location loc = new Location("OsmandRouteProvider");
		loc.setLatitude(pt.lat);
		loc.setLongitude(pt.lon);
		loc.setSpeed((float) pt.speed);
		if (!Double.isNaN(pt.ele)) {
			loc.setAltitude(pt.ele);
		}
		loc.setTime(pt.time);
		if (!Double.isNaN(pt.hdop)) {
			loc.setAccuracy((float) pt.hdop);
		}
		return loc;
	}

	public static Location createLocation(net.osmand.shared.gpx.primitives.WptPt pt){
		Location loc = new Location("OsmandRouteProvider");
		loc.setLatitude(pt.getLatitude());
		loc.setLongitude(pt.getLongitude());
		loc.setSpeed((float) pt.getSpeed());
		if(!Double.isNaN(pt.getEle())) {
			loc.setAltitude(pt.getEle());
		}
		loc.setTime(pt.getTime());
		if(!Double.isNaN(pt.getHdop())) {
			loc.setAccuracy((float) pt.getHdop());
		}
		return loc;
	}

	public static List<Location> locationsFromWpts(List<WptPt> wpts) {
		List<Location> locations = new ArrayList<>(wpts.size());
		for (WptPt pt : wpts) {
			locations.add(createLocation(pt));
		}
		return locations;
	}

	public static List<Location> locationsFromSharedWpts(List<net.osmand.shared.gpx.primitives.WptPt> wpts) {
		List<Location> locations = new ArrayList<>(wpts.size());
		for (net.osmand.shared.gpx.primitives.WptPt pt : wpts) {
			locations.add(createLocation(pt));
		}
		return locations;
	}

	public RouteCalculationResult calculateRouteImpl(@NonNull RouteCalculationParams params) {
		long time = System.currentTimeMillis();
		if (params.start != null && params.end != null) {
			params.calculationProgress.routeCalculationStartTime = time;
			if (log.isInfoEnabled()) {
				log.info("Start finding route from " + params.start + " to " + params.end + " using " +
						params.mode.getRouteService().getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			try {
				RouteCalculationResult res;
				boolean calcGPXRoute = shouldCalculateGpxRoute(params);
				if (calcGPXRoute && !params.gpxRoute.calculateOsmAndRoute) {
					res = gpxRouteHelper.calculateGpxRoute(params);
				} else if (params.mode.getRouteService() == RouteService.OSMAND) {
					res = findVectorMapsRoute(params, calcGPXRoute);
					Set<Long> activeCameraAvoidanceRoadIds = Collections.emptySet();
					FlockFreeRouteVariant avoided = maybeRecalculateWithFlockFreeAvoidance(params, res, calcGPXRoute);
					if (avoided != null) {
						res = avoided.result;
						activeCameraAvoidanceRoadIds = avoided.activeCameraAvoidanceRoadIds;
					}
					FlockFreeRouteVariant trafficAdjusted = maybeRecalculateWithFlockFreeTraffic(params, res,
							calcGPXRoute, activeCameraAvoidanceRoadIds);
					if (trafficAdjusted != null) {
						res = trafficAdjusted.result;
					}
					if (params.calculationProgress.missingMapsCalculationResult != null) {
						res.setMissingMapsCalculationResult(params.calculationProgress.missingMapsCalculationResult);
					}
				} else if (params.mode.getRouteService() == RouteService.BROUTER) {
					res = findBROUTERRoute(params);
				} else if (params.mode.getRouteService() == RouteService.ONLINE) {
					boolean useFallbackRouting = false;
					try {
						res = findOnlineRoute(params);
					} catch (IOException | JSONException e) {
						res = new RouteCalculationResult(null);
						params.initialCalculation = false;
						useFallbackRouting = true;
					}
					if (useFallbackRouting || !res.isCalculated()) {
						OnlineRoutingHelper helper = params.ctx.getOnlineRoutingHelper();
						String engineKey = params.mode.getRoutingProfile();
						OnlineRoutingEngine engine = helper.getEngineByKey(engineKey);
						if (engine != null && engine.useRoutingFallback()) {
							res = findVectorMapsRoute(params, calcGPXRoute);
						}
					}
				} else if (params.mode.getRouteService() == RouteService.STRAIGHT ||
						params.mode.getRouteService() == RouteService.DIRECT_TO) {
					res = findStraightRoute(params);
				} else {
					res = new RouteCalculationResult("Selected route service is not available");
				}
				if (log.isInfoEnabled()) {
					log.info("Finding route contained " + res.getImmutableAllLocations().size() + " points for " + (System.currentTimeMillis() - time) + " ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return res;
			} catch (IOException | ParserConfigurationException | SAXException e) {
				log.error("Failed to find route ", e);
			}
		}
		return new RouteCalculationResult(null);
	}

	private boolean shouldCalculateGpxRoute(@NonNull RouteCalculationParams params) {
		if (params.gpxRoute != null) {
			GpxApproximationParams approximationParams = params.gpxRoute.approximationParams;
			if (approximationParams != null && !params.gpxRoute.gpxFile.isAttachedToRoads()) {
				GpxFile gpxFile = GpxApproximationHelper
						.approximateGpxSync(params.ctx, params.gpxRoute.gpxFile, approximationParams, null);
				if (gpxFile.getError() == null && gpxFile.isAttachedToRoads()) {
					params.gpxRoute = new GPXRouteParamsBuilder(gpxFile, params.gpxRoute).build(params.ctx, params.end);
				}
			}
			return params.gpxRoute != null && (!params.gpxRoute.points.isEmpty()
					|| (params.gpxRoute.reverse && !params.gpxRoute.routePoints.isEmpty()));
		}
		return false;
	}

	@Nullable
	private FlockFreeRouteVariant maybeRecalculateWithFlockFreeAvoidance(@NonNull RouteCalculationParams params,
	                                                                     @NonNull RouteCalculationResult initial,
	                                                                     boolean calcGPXRoute) throws IOException {
		FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
		log.info("FlockFree maybeRecalculateWithFlockFreeAvoidance: cameraAvoidanceApplied="
				+ params.cameraAvoidanceApplied + ", initialCalculated=" + initial.isCalculated()
				+ ", plugin=" + (plugin != null) + ", cameraAvoidanceActive="
				+ (plugin != null && plugin.isCameraAvoidanceActive()));
		if (params.cameraAvoidanceApplied || !initial.isCalculated()) {
			return null;
		}
		if (plugin == null || !plugin.isCameraAvoidanceActive()) {
			return null;
		}
		CameraAvoidanceHelper avoidanceHelper = plugin.getAvoidanceHelper();
		if (params.previousToRecalculate != null && params.onlyStartPointChanged) {
			log.info("FlockFree avoidance skipped: only start point changed");
			avoidanceHelper.recordAvoidanceSkipped(CameraAvoidanceHelper.AvoidanceStatus.SKIPPED_PARTIAL);
			return null;
		}
		boolean dataLoaded = plugin.getCameraData().isDataLoaded();
		if (!dataLoaded) {
			dataLoaded = plugin.getCameraData().ensureCacheLoadedForRouting();
		}
		log.info("FlockFree camera data loaded=" + dataLoaded + ", radius=" + plugin.CAMERA_AVOIDANCE_RADIUS.get());
		if (!dataLoaded) {
			avoidanceHelper.recordAvoidanceSkipped(CameraAvoidanceHelper.AvoidanceStatus.SKIPPED_NO_DATA);
			return null;
		}
		int avoidanceRadius = plugin.CAMERA_AVOIDANCE_RADIUS.get();
		// Use a wider radius for blocking road segments than for scanning cameras.
		// This prevents the router from using parallel roads just outside the scan radius.
		int blockRadius = (int) (avoidanceRadius * 1.5); // 50% wider for blocking
		boolean activelyNavigating = isActivelyNavigating(params);

		// Determine scan locations: use horizon-limited subset during active navigation
		List<Location> scanLocations = initial.getImmutableAllLocations();
		int totalRouteCameraCount = -1; // -1 means not yet computed
		if (activelyNavigating) {
			List<Location> horizonLocations = getHorizonRouteLocations(
					initial.getImmutableAllLocations(), ACTIVE_NAVIGATION_HORIZON_KM);
			// Count total cameras on the full route for display, but only scan/avoid within horizon
			totalRouteCameraCount = avoidanceHelper.findCamerasNearRouteLocations(
					initial.getImmutableAllLocations(), avoidanceRadius).size();
			scanLocations = horizonLocations;
			log.info("FlockFree active navigation horizon planning: scanning first "
					+ ACTIVE_NAVIGATION_HORIZON_KM + " km ("
					+ horizonLocations.size() + " of " + initial.getImmutableAllLocations().size()
					+ " locations), total route cameras=" + totalRouteCameraCount
					+ ", budget=" + ACTIVE_NAVIGATION_BUDGET_MS + "ms");
		}

		List<CameraAvoidanceHelper.RoadWithCameraCount> roadsWithCameras =
				avoidanceHelper.collectAvoidRoadIdsWithCameraCountForRoute(initial,
						blockRadius);
		if (activelyNavigating) {
			int fullRoadCount = roadsWithCameras.size();
			roadsWithCameras = filterRoadsToHorizon(initial, roadsWithCameras, ACTIVE_NAVIGATION_HORIZON_KM);
			log.info("FlockFree horizon road filtering: " + roadsWithCameras.size()
					+ " of " + fullRoadCount + " camera-adjacent roads within "
					+ ACTIVE_NAVIGATION_HORIZON_KM + " km horizon");
		}
		log.info("FlockFree found " + roadsWithCameras.size() + " camera-adjacent roads on route");
		if (Algorithms.isEmpty(roadsWithCameras)) {
			avoidanceHelper.recordAvoidanceSkipped(CameraAvoidanceHelper.AvoidanceStatus.SKIPPED_NO_ROAD_IDS);
			return null;
		}
		int originalRouteCameraCount = avoidanceHelper.findCamerasNearRouteLocations(
				scanLocations, avoidanceRadius).size();
		if (originalRouteCameraCount <= 0) {
			log.warn("FlockFree avoidance skipped: road mapping found camera roads but route exposure scan found none");
			avoidanceHelper.recordAvoidanceSkipped(CameraAvoidanceHelper.AvoidanceStatus.SKIPPED_NO_ROAD_IDS);
			return null;
		}
		if (activelyNavigating && totalRouteCameraCount >= 0) {
			log.info("FlockFree horizon segment has " + originalRouteCameraCount
					+ " cameras (full route has " + totalRouteCameraCount + " cameras)");
		}

		// roadsWithCameras is sorted DESCENDING by cameraCount (most cameras first).
		int totalCameraRoadCount = roadsWithCameras.size();
		int originalRoadAssociationCount = 0;
		for (CameraAvoidanceHelper.RoadWithCameraCount rwc : roadsWithCameras) {
			originalRoadAssociationCount += rwc.cameraCount;
		}
		int originalRouteTimeSeconds = initial.getLeftTime(null);
		int originalRouteDistanceMeters = initial.getWholeDistance();
		log.info("FlockFree original route exposure: " + originalRouteCameraCount
				+ " cameras, " + originalRoadAssociationCount + " camera-road associations");

		MissingMapsCalculationResult originalMissingMaps = params.calculationProgress != null
				? params.calculationProgress.missingMapsCalculationResult : null;
		if (isFlockFreeOptionalRoutingBudgetExceeded(params)) {
			log.info("FlockFree avoidance skipped: initial route calculation already exceeded optional reroute budget");
			avoidanceHelper.recordAvoidanceFallback(0, originalRouteCameraCount, originalRouteTimeSeconds, originalRouteDistanceMeters);
			return null;
		}
		startFlockFreeOptionalRoutingProgress(params);

		// --- Stage 1: Tier-based avoidance (block TIER_SIZE roads at a time) ---
		List<CameraAvoidanceHelper.RoadWithCameraCount> severitySorted =
				computeSeveritySortedRoads(roadsWithCameras, initial, plugin, avoidanceRadius);
		List<List<CameraAvoidanceHelper.RoadWithCameraCount>> tiers =
				buildBlockTiers(severitySorted, TIER_SIZE);
		log.info("FlockFree starting tier-based avoidance (" + tiers.size() + " tiers of max "
				+ TIER_SIZE + ", budget "
				+ (activelyNavigating ? ACTIVE_NAVIGATION_BUDGET_MS : FLOCKFREE_OPTIONAL_ROUTING_BUDGET_MS)
				+ "ms" + (activelyNavigating ? ", horizon mode" : "") + ")");

		Set<Long> blockedIds = new LinkedHashSet<>();
		RouteCalculationResult bestRoute = null;
		Set<Long> bestRouteBlockedIds = null;
		int bestRouteCameraCount = originalRouteCameraCount;
		int bestRouteBlockedSize = 0;
		int tierIteration = 0;

		for (int tierIdx = 0; tierIdx < tiers.size() && tierIteration < MAX_RELAXATION_ITERATIONS; tierIdx++) {
			if (isFlockFreeOptionalRoutingBudgetExceeded(params)) {
				log.info("FlockFree tier avoidance stopped: optional reroute budget exceeded at tier " + (tierIdx + 1));
				finishFlockFreeOptionalRoutingProgress(params);
				break;
			}

			List<CameraAvoidanceHelper.RoadWithCameraCount> tier = tiers.get(tierIdx);
			for (CameraAvoidanceHelper.RoadWithCameraCount rwc : tier) {
				blockedIds.add(rwc.roadId);
			}

			tierIteration++;
			log.info("FlockFree tier " + (tierIdx + 1) + " (iteration " + tierIteration + ")"
					+ ": blocking " + tier.size() + " roads, total blocked=" + blockedIds.size()
					+ "/" + totalCameraRoadCount);

			RouteCalculationParams avoidedParams = copyParamsForFlockFreeAvoidance(params, blockedIds);
			try {
				beginFlockFreeOptionalRoutingStep(params, tierIteration - 1);
				RouteCalculationResult avoided = findVectorMapsRoute(avoidedParams, calcGPXRoute);
				completeFlockFreeOptionalRoutingStep(params, tierIteration);
				if (avoided.isCalculated()) {
					int avoidedCameraCount = avoidanceHelper.findCamerasNearRouteLocations(
							avoided.getImmutableAllLocations(), avoidanceRadius).size();
					log.info("FlockFree tier " + (tierIdx + 1) + " route has " + avoidedCameraCount
							+ " cameras (original had " + originalRouteCameraCount + ")");

					if (avoidedCameraCount < originalRouteCameraCount) {
						String rejectionReason = getFlockFreeAvoidanceRejectionReason(avoided, avoidedCameraCount,
								originalRouteCameraCount, originalRouteTimeSeconds, originalRouteDistanceMeters,
								plugin.getAvoidanceMode());
						if (Algorithms.isEmpty(rejectionReason)) {
							log.info("FlockFree tier " + (tierIdx + 1)
									+ " accepted: blocked " + blockedIds.size()
									+ " of " + totalCameraRoadCount + " camera roads"
									+ ", cameras on route=" + avoidedCameraCount
									+ " vs original " + originalRouteCameraCount);
							if (avoidedCameraCount < bestRouteCameraCount) {
								bestRoute = avoided;
								bestRouteBlockedIds = new LinkedHashSet<>(blockedIds);
								bestRouteCameraCount = avoidedCameraCount;
								bestRouteBlockedSize = blockedIds.size();
							}
						} else {
							log.info("FlockFree tier " + (tierIdx + 1)
									+ " route has fewer cameras but rejected: " + rejectionReason
									+ "; continuing to next tier");
							if (avoidedCameraCount < bestRouteCameraCount) {
								bestRoute = avoided;
								bestRouteBlockedIds = new LinkedHashSet<>(blockedIds);
								bestRouteCameraCount = avoidedCameraCount;
								bestRouteBlockedSize = blockedIds.size();
							}
						}
					} else {
						log.info("FlockFree tier " + (tierIdx + 1)
								+ " route has same cameras (" + avoidedCameraCount
								+ "); expanding to next tier");
					}
				} else {
					log.info("FlockFree tier " + (tierIdx + 1)
							+ " route not calculated; continuing to next tier");
				}
			} catch (IOException e) {
				log.warn("FlockFree tier " + (tierIdx + 1) + " threw", e);
				restoreFlockFreeProgressState(params, originalMissingMaps);
				finishFlockFreeOptionalRoutingProgress(params);
				avoidanceHelper.recordAvoidanceFallback(blockedIds.size(), originalRouteCameraCount, originalRouteTimeSeconds, originalRouteDistanceMeters);
				log.warn("FlockFree temporary camera avoidance failed; returning original route");
				return null;
			}
		}

		// --- Stage 2: Single-road fallback if tier-based approach didn't find a viable route ---
		if (bestRoute == null && bestRouteCameraCount >= originalRouteCameraCount) {
			log.info("FlockFree tier-based avoidance found no improvement; falling back to single-road greedy approach");
			blockedIds.clear();
			for (int i = 0; i < MAX_RELAXATION_ITERATIONS && i < totalCameraRoadCount; i++) {
				if (isFlockFreeOptionalRoutingBudgetExceeded(params)) {
					log.info("FlockFree single-road fallback stopped: budget exceeded at iteration " + (i + 1));
					finishFlockFreeOptionalRoutingProgress(params);
					break;
				}

				CameraAvoidanceHelper.RoadWithCameraCount toBlock = severitySorted.get(i);
				blockedIds.add(toBlock.roadId);

				log.info("FlockFree single-road fallback iteration " + (i + 1)
						+ ": blocking roadId=" + toBlock.roadId
						+ " (camera-road associations=" + toBlock.cameraCount + ")"
						+ ", total blocked=" + blockedIds.size()
						+ "/" + totalCameraRoadCount);

				RouteCalculationParams avoidedParams = copyParamsForFlockFreeAvoidance(params, blockedIds);
				try {
					beginFlockFreeOptionalRoutingStep(params, i);
					RouteCalculationResult avoided = findVectorMapsRoute(avoidedParams, calcGPXRoute);
					completeFlockFreeOptionalRoutingStep(params, i + 1);
					if (avoided.isCalculated()) {
						int avoidedCameraCount = avoidanceHelper.findCamerasNearRouteLocations(
								avoided.getImmutableAllLocations(), avoidanceRadius).size();
						log.info("FlockFree single-road fallback iteration " + (i + 1)
								+ " route has " + avoidedCameraCount
								+ " cameras (original had " + originalRouteCameraCount + ")");

						if (avoidedCameraCount < originalRouteCameraCount) {
							String rejectionReason = getFlockFreeAvoidanceRejectionReason(avoided, avoidedCameraCount,
									originalRouteCameraCount, originalRouteTimeSeconds, originalRouteDistanceMeters,
									plugin.getAvoidanceMode());
							if (Algorithms.isEmpty(rejectionReason)) {
								log.info("FlockFree single-road fallback iteration " + (i + 1)
										+ " accepted: blocked " + blockedIds.size()
										+ " of " + totalCameraRoadCount + " camera roads"
										+ ", cameras on route=" + avoidedCameraCount
										+ " vs original " + originalRouteCameraCount);
								if (avoidedCameraCount < bestRouteCameraCount) {
									bestRoute = avoided;
									bestRouteBlockedIds = new LinkedHashSet<>(blockedIds);
									bestRouteCameraCount = avoidedCameraCount;
									bestRouteBlockedSize = blockedIds.size();
								}
							} else {
								log.info("FlockFree single-road fallback iteration " + (i + 1)
										+ " route has fewer cameras but rejected: " + rejectionReason
										+ "; continuing to block more roads");
								if (avoidedCameraCount < bestRouteCameraCount) {
									bestRoute = avoided;
									bestRouteBlockedIds = new LinkedHashSet<>(blockedIds);
									bestRouteCameraCount = avoidedCameraCount;
									bestRouteBlockedSize = blockedIds.size();
								}
							}
						} else {
							log.info("FlockFree single-road fallback iteration " + (i + 1)
									+ " route has same cameras (" + avoidedCameraCount
									+ "); adding more roads to blocked set");
						}
					} else {
						log.info("FlockFree single-road fallback iteration " + (i + 1)
								+ " route not calculated; continuing to block more roads");
					}
				} catch (IOException e) {
					log.warn("FlockFree single-road fallback iteration " + (i + 1) + " threw", e);
					restoreFlockFreeProgressState(params, originalMissingMaps);
					finishFlockFreeOptionalRoutingProgress(params);
					avoidanceHelper.recordAvoidanceFallback(blockedIds.size(), originalRouteCameraCount, originalRouteTimeSeconds, originalRouteDistanceMeters);
					log.warn("FlockFree temporary camera avoidance failed; returning original route");
					return null;
				}
			}
		}

		// --- Stage 3: Dual-route motorway penalty approach ---
		// Always try motorway-penalized route as an alternative, even if greedy found
		// some improvement. The dual-route may find a much better result (e.g. surface
		// streets with 3 cameras vs greedy's 6 cameras on highway).
		if (!isFlockFreeOptionalRoutingBudgetExceeded(params)) {
				log.info("FlockFree dual-route: greedy avoidance found no improvement; trying motorway-penalized approach");
				Set<Long> motorwayBlocks = identifyMotorwaySegmentsNearCameras(
						initial, avoidanceHelper, avoidanceRadius);
				if (!motorwayBlocks.isEmpty()) {
					log.info("FlockFree dual-route: blocking " + motorwayBlocks.size()
							+ " motorway segments near cameras");
					RouteCalculationParams altParams = copyParamsForFlockFreeAvoidance(params, motorwayBlocks);
					try {
						beginFlockFreeOptionalRoutingStep(params, tierIteration);
						RouteCalculationResult altRoute = findVectorMapsRoute(altParams, calcGPXRoute);
						completeFlockFreeOptionalRoutingStep(params, tierIteration + 1);
						if (altRoute.isCalculated()) {
							int altCameraCount = avoidanceHelper.findCamerasNearRouteLocations(
									altRoute.getImmutableAllLocations(), avoidanceRadius).size();
							log.info("FlockFree dual-route: alternative has " + altCameraCount
									+ " cameras vs original " + originalRouteCameraCount
									+ ", best so far " + bestRouteCameraCount);
							if (altCameraCount < bestRouteCameraCount) {
								bestRoute = altRoute;
								bestRouteBlockedIds = new LinkedHashSet<>(motorwayBlocks);
								bestRouteCameraCount = altCameraCount;
								bestRouteBlockedSize = motorwayBlocks.size();
								log.info("FlockFree dual-route: using motorway-penalized route as Privacy option");
							} else {
								log.info("FlockFree dual-route: alternative has " + altCameraCount
										+ " cameras, not better than best (" + bestRouteCameraCount + "); discarding");
							}
						} else {
							log.info("FlockFree dual-route: alternative route not calculated; discarding");
						}
					} catch (IOException e) {
						log.warn("FlockFree dual-route: motorway-penalized route threw", e);
					}
				} else {
					log.info("FlockFree dual-route: no motorway segments found near cameras on original route");
				}
			} else {
				log.info("FlockFree dual-route skipped: optional reroute budget exceeded");
			}

		// All avoidance iterations exhausted — fall back to best route found so far
		restoreFlockFreeProgressState(params, originalMissingMaps);
		finishFlockFreeOptionalRoutingProgress(params);
		if (bestRoute != null) {
			log.info("FlockFree avoidance exhausted; using best partial route with "
					+ bestRouteCameraCount + " cameras (original had " + originalRouteCameraCount + ")"
					+ ", blocked " + bestRouteBlockedSize + " of " + totalCameraRoadCount + " roads");
			avoidanceHelper.recordAvoidancePartial(bestRouteBlockedSize,
					totalCameraRoadCount, bestRouteCameraCount,
					originalRouteCameraCount, originalRouteTimeSeconds,
					originalRouteDistanceMeters);
			return new FlockFreeRouteVariant(bestRoute, bestRouteBlockedIds);
		}
		avoidanceHelper.recordAvoidanceFallback(blockedIds.size(), originalRouteCameraCount, originalRouteTimeSeconds, originalRouteDistanceMeters);
		log.warn("FlockFree avoidance exhausted after tier-based + single-road fallback"
				+ "; returning original route");
		return null;
	}

	/**
	 * Computes a severity score for a road-camera association.
	 * Higher score means the road is more important to block.
	 *
	 * @param cameraCount    Number of cameras associated with this road
	 * @param bearingDelta    Angular difference between camera bearing and route bearing (0-60 degrees)
	 * @param distanceToRoad  Distance from camera to road centerline in meters
	 * @return severity score (higher = more important to block)
	 */
	private double computeRoadSeverity(int cameraCount, float bearingDelta, double distanceToRoad) {
		// cameraCount: more cameras = higher severity
		double cameraWeight = cameraCount * 10.0;
		// bearingDelta: smaller delta (better direction match) = higher severity
		// 0 if at window edge (60°), 5 if perfect match (0°)
		double directionWeight = (1.0 - (bearingDelta / 60.0)) * 5.0;
		// distanceToRoad: closer to road = higher severity
		// 3 if at road centerline, 0 if 100m+ away
		double distanceWeight = Math.max(0, (100.0 - distanceToRoad) / 100.0) * 3.0;
		return cameraWeight + directionWeight + distanceWeight;
	}

	/**
	 * Identifies motorway/interstate road segments on the route that are near cameras.
	 * Returns a set of road IDs suitable for temporary blocking to encourage surface-street alternatives.
	 *
	 * @param route    The original calculated route
	 * @param helper   The camera avoidance helper for camera lookups
	 * @param radius  Search radius in meters
	 * @return Set of motorway road IDs near cameras
	 */
	private Set<Long> identifyMotorwaySegmentsNearCameras(
			@NonNull RouteCalculationResult route,
			@NonNull CameraAvoidanceHelper helper,
			int radius) {
		List<RouteSegmentResult> segments = route.getOriginalRoute();
		List<Location> locations = route.getImmutableAllLocations();
		if (segments == null || locations == null || locations.isEmpty()) {
			return new HashSet<>();
		}
		List<CameraData.CameraPoint> cameras = helper.findCamerasNearRouteLocations(locations, radius);
		if (cameras.isEmpty()) {
			return new HashSet<>();
		}
		Set<Long> motorwayIds = new HashSet<>();
		for (CameraData.CameraPoint camera : cameras) {
			int cameraX31 = MapUtils.get31TileNumberX(camera.lon);
			int cameraY31 = MapUtils.get31TileNumberY(camera.lat);
			for (RouteSegmentResult seg : segments) {
				RouteDataObject obj = seg.getObject();
				if (obj == null) {
					continue;
				}
				if (!isMotorwayType(obj)) {
					continue;
				}
				if (isCameraNearSegment(obj, seg, cameraX31, cameraY31, radius)) {
					motorwayIds.add(obj.getId());
				}
			}
		}
		return motorwayIds;
	}

	/**
	 * Checks if a RouteDataObject represents a motorway or trunk road.
	 * These are high-speed roads (interstates, expressways) that the dual-route
	 * approach should consider penalizing near cameras.
	 */
	private boolean isMotorwayType(@NonNull RouteDataObject obj) {
		String highway = obj.getHighway();
		if (highway == null) {
			return false;
		}
		return "motorway".equals(highway)
				|| "motorway_link".equals(highway)
				|| "trunk".equals(highway)
				|| "trunk_link".equals(highway);
	}

	/**
	 * Checks if a camera point (in 31-bit tile coordinates) is near any point on
	 * the given road segment's geometry, within the specified radius in meters.
	 * This mirrors the geometry check in CameraAvoidanceHelper.isCameraNearRoadGeometry
	 * but is self-contained here because that helper method is private.
	 */
	private boolean isCameraNearSegment(@NonNull RouteDataObject obj,
									   @NonNull RouteSegmentResult seg,
									   int cameraX31, int cameraY31, int radiusMeters) {
		int startPointIndex = Math.min(seg.getStartPointIndex(), seg.getEndPointIndex());
		int endPointIndex = Math.max(seg.getEndPointIndex(), seg.getStartPointIndex());
		for (int j = startPointIndex; j <= endPointIndex; j++) {
			int pointX31 = obj.getPoint31XTile(j);
			int pointY31 = obj.getPoint31YTile(j);
			if (MapUtils.squareRootDist31(pointX31, pointY31, cameraX31, cameraY31) <= radiusMeters) {
				return true;
			}
			if (j > startPointIndex) {
				QuadPointDouble projection = MapUtils.getProjectionPoint31(cameraX31, cameraY31,
						obj.getPoint31XTile(j - 1), obj.getPoint31YTile(j - 1),
						pointX31, pointY31);
				double distance = MapUtils.squareRootDist31((int) projection.x, (int) projection.y,
						cameraX31, cameraY31);
				if (distance <= radiusMeters) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Computes severity scores for all road-camera associations and returns roads sorted
	 * by severity descending. Severity combines camera count, direction match quality,
	 * and distance from camera to road centerline.
	 *
	 * @param roadsWithCameras List of road-camera associations (sorted by cameraCount desc)
	 * @param initial          The original calculated route
	 * @param plugin           FlockFree plugin for camera data access
	 * @param avoidanceRadius  Camera avoidance radius in meters
	 * @return List of RoadWithCameraCount sorted by severity score descending
	 */
	@NonNull
	private List<CameraAvoidanceHelper.RoadWithCameraCount> computeSeveritySortedRoads(
			@NonNull List<CameraAvoidanceHelper.RoadWithCameraCount> roadsWithCameras,
			@NonNull RouteCalculationResult initial,
			@NonNull FlockFreePlugin plugin,
			int avoidanceRadius) {
		List<RouteSegmentResult> roads = initial.getOriginalRoute();
		if (roads == null || roads.size() < 3) {
			log.info("FlockFree severity scoring: no route segments available, using camera-count sort");
			return roadsWithCameras;
		}

		// Get cameras near the route for bearing/distance lookups
		List<Location> locations = initial.getImmutableAllLocations();
		List<CameraData.CameraPoint> cameras = plugin.getAvoidanceHelper()
				.findCamerasNearRouteLocations(locations, avoidanceRadius);
		if (cameras.isEmpty()) {
			log.info("FlockFree severity scoring: no cameras found near route, using camera-count sort");
			return roadsWithCameras;
		}

		// Build a map from roadId to route segments for quick lookup
		Map<Long, List<RouteSegmentResult>> roadIdToSegments = new HashMap<>();
		for (int i = 1; i < roads.size() - 1; i++) {
			RouteSegmentResult seg = roads.get(i);
			if (seg == null || seg.getObject() == null) continue;
			Long roadId = seg.getObject().getId();
			roadIdToSegments.computeIfAbsent(roadId, k -> new ArrayList<>()).add(seg);
		}

		// Compute severity score for each road
		List<Double> severities = new ArrayList<>();
		for (CameraAvoidanceHelper.RoadWithCameraCount rwc : roadsWithCameras) {
			List<RouteSegmentResult> segments = roadIdToSegments.get(rwc.roadId);
			if (segments == null || segments.isEmpty()) {
				// No segment data available; fall back to camera-count-only severity
				severities.add(rwc.cameraCount * 10.0);
				continue;
			}

			float bestBearingDelta = 60f; // worst case: at window edge
			double bestDistance = 100.0;  // worst case: 100m away

			for (RouteSegmentResult seg : segments) {
				RouteDataObject obj = seg.getObject();
				if (obj == null) continue;

				// Compute segment bearing
				int startX = obj.getPoint31XTile(0);
				int startY = obj.getPoint31YTile(0);
				int endX = obj.getPoint31XTile(obj.getPointsLength() - 1);
				int endY = obj.getPoint31YTile(obj.getPointsLength() - 1);
				double startLat = MapUtils.get31LatitudeY(startY);
				double startLon = MapUtils.get31LongitudeX(startX);
				double endLat = MapUtils.get31LatitudeY(endY);
				double endLon = MapUtils.get31LongitudeX(endX);
				double dLon = endLon - startLon;
				double y = Math.sin(Math.toRadians(dLon)) * Math.cos(Math.toRadians(endLat));
				double x = Math.cos(Math.toRadians(startLat)) * Math.sin(Math.toRadians(endLat))
						- Math.sin(Math.toRadians(startLat)) * Math.cos(Math.toRadians(endLat)) * Math.cos(Math.toRadians(dLon));
				double segBearing = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;

				// Find nearest camera and compute bearing delta + distance
				double segMidLat = (startLat + endLat) / 2;
				double segMidLon = (startLon + endLon) / 2;

				for (CameraData.CameraPoint cam : cameras) {
					double camDist = MapUtils.getDistance(segMidLat, segMidLon, cam.lat, cam.lon);
					if (camDist > avoidanceRadius) continue;

					float camBearing = cam.getBearing();
					if (camBearing > 0f) {
						float delta = (float) Math.abs(camBearing - segBearing);
						if (delta > 180f) delta = 360f - delta;
						if (delta < bestBearingDelta) {
							bestBearingDelta = delta;
						}
					}

					if (camDist < bestDistance) {
						bestDistance = camDist;
					}
				}
			}

			double severity = computeRoadSeverity(rwc.cameraCount, bestBearingDelta, bestDistance);
			log.info("FlockFree severity: roadId=" + rwc.roadId
					+ " cameras=" + rwc.cameraCount
					+ " bearingDelta=" + String.format("%.1f", bestBearingDelta)
					+ " distance=" + String.format("%.1f", bestDistance)
					+ " severity=" + String.format("%.2f", severity));
			severities.add(severity);
		}

		// Create indexed list and sort by severity descending
		List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < roadsWithCameras.size(); i++) {
			indices.add(i);
		}
		indices.sort((a, b) -> Double.compare(severities.get(b), severities.get(a)));

		List<CameraAvoidanceHelper.RoadWithCameraCount> sorted = new ArrayList<>();
		for (int idx : indices) {
			sorted.add(roadsWithCameras.get(idx));
		}
		log.info("FlockFree severity sorting complete: " + sorted.size()
				+ " roads sorted by severity (" + roadsWithCameras.size() + " input)");
		return sorted;
	}

	/**
	 * Groups roads into tiers of maxTierSize each for staged blocking.
	 * Tier 1 contains the highest-severity roads (block these first).
	 * Tier 2 contains the next highest, etc.
	 *
	 * @param roadsWithCameras List of roads sorted by severity descending
	 * @param maxTierSize      Maximum number of roads per tier
	 * @return List of tiers, each containing up to maxTierSize roads
	 */
	@NonNull
	private List<List<CameraAvoidanceHelper.RoadWithCameraCount>> buildBlockTiers(
			@NonNull List<CameraAvoidanceHelper.RoadWithCameraCount> roadsWithCameras,
			int maxTierSize) {
		List<List<CameraAvoidanceHelper.RoadWithCameraCount>> tiers = new ArrayList<>();
		if (maxTierSize <= 0) {
			maxTierSize = TIER_SIZE;
		}
		for (int i = 0; i < roadsWithCameras.size(); i += maxTierSize) {
			int end = Math.min(i + maxTierSize, roadsWithCameras.size());
			tiers.add(new ArrayList<>(roadsWithCameras.subList(i, end)));
		}
		log.info("FlockFree built " + tiers.size() + " tiers (max " + maxTierSize + " roads each)"
				+ " from " + roadsWithCameras.size() + " camera-adjacent roads");
		return tiers;
	}

	/**
	 * Multi-pass avoidance: after an avoidance route is calculated, scan it for cameras
	 * that weren't on the original route. If found, add those road IDs to the blocked set
	 * and recalculate. Only accept a new route if it has fewer cameras than the current one.
	 * Up to {@link #MAX_AVOIDANCE_PASSES} extra passes.
	 *
	 * @return a better avoidance route if one was found, or null if no improvement was made
	 */
	@Nullable
	private RouteCalculationResult applyMultiPassAvoidance(@NonNull RouteCalculationParams params,
	                                                       @NonNull RouteCalculationResult currentRoute,
	                                                       @NonNull Set<Long> blockedIds,
	                                                       boolean calcGPXRoute,
	                                                       @NonNull FlockFreePlugin plugin,
	                                                       int originalRouteTimeSeconds,
	                                                       int originalRouteDistanceMeters,
	                                                       int originalCameraCount,
	                                                       int originalRoadCount) throws IOException {
		CameraAvoidanceHelper helper = plugin.getAvoidanceHelper();
		int radius = plugin.CAMERA_AVOIDANCE_RADIUS.get();

		// Track the best route found so far
		RouteCalculationResult bestRoute = currentRoute;
		Set<Long> bestBlockedIds = new LinkedHashSet<>(blockedIds);
		int bestCameraCount = helper.findCamerasNearRouteLocations(
				currentRoute.getImmutableAllLocations(), radius).size();

		for (int pass = 0; pass < MAX_AVOIDANCE_PASSES; pass++) {
			// Find cameras on the current best route
			List<CameraAvoidanceHelper.RoadWithCameraCount> newCameras =
					helper.collectAvoidRoadIdsWithCameraCountForRoute(bestRoute, radius);
			if (Algorithms.isEmpty(newCameras)) {
				// No cameras on the route — we're done
				break;
			}

			// Check if any of these road IDs are new (not already in bestBlockedIds)
			Set<Long> newBlockedIds = new LinkedHashSet<>(bestBlockedIds);
			for (CameraAvoidanceHelper.RoadWithCameraCount rwc : newCameras) {
				newBlockedIds.add(rwc.roadId);
			}
			if (newBlockedIds.size() == bestBlockedIds.size()) {
				// All road IDs were already blocked — can't improve further
				break;
			}

			log.info("FlockFree multi-pass avoidance pass " + (pass + 1)
					+ ": found " + newCameras.size() + " cameras on route, "
					+ (newBlockedIds.size() - bestBlockedIds.size()) + " new road IDs to block");

			// Recalculate with the expanded blocked set
			RouteCalculationParams expandedParams = copyParamsForFlockFreeAvoidance(params, newBlockedIds);
			RouteCalculationResult expanded = findVectorMapsRoute(expandedParams, calcGPXRoute);
			if (!expanded.isCalculated()) {
				// Can't find a route with the expanded blocks — stop trying
				log.info("FlockFree multi-pass pass " + (pass + 1)
						+ " failed to find route with expanded blocks; stopping");
				break;
			}

			// Count cameras on the new route
			int newCameraCount = helper.findCamerasNearRouteLocations(
					expanded.getImmutableAllLocations(), radius).size();
			log.info("FlockFree multi-pass pass " + (pass + 1)
					+ " result: " + newCameraCount + " cameras (was " + bestCameraCount + ")");

			if (newCameraCount < bestCameraCount) {
				// Improvement — accept this route
				bestRoute = expanded;
				bestBlockedIds = newBlockedIds;
				bestCameraCount = newCameraCount;

				if (bestCameraCount == 0) {
					// All cameras avoided!
					break;
				}
			} else {
				// No improvement or worse — stop, we've hit diminishing returns
				log.info("FlockFree multi-pass pass " + (pass + 1)
						+ " did not improve; stopping at " + bestCameraCount + " cameras");
				break;
			}
		}

		if (bestCameraCount < helper.findCamerasNearRouteLocations(
				currentRoute.getImmutableAllLocations(), radius).size()) {
			// We found a better route — update blockedIds and return it
			blockedIds.clear();
			blockedIds.addAll(bestBlockedIds);
			int originalCameras = helper.findCamerasNearRouteLocations(
					currentRoute.getImmutableAllLocations(), radius).size();
			log.info("FlockFree multi-pass improved from "
					+ originalCameras + " to " + bestCameraCount + " cameras");
			// Record the avoidance with the improved camera count
			helper.recordAvoidanceApplied(bestBlockedIds.size(), originalCameras, bestCameraCount,
					originalRouteTimeSeconds, originalRouteDistanceMeters);
			return bestRoute;
		}

		log.info("FlockFree multi-pass did not improve beyond initial route");
		return null;
	}

	@Nullable
	private FlockFreeRouteVariant maybeRecalculateWithFlockFreeTraffic(@NonNull RouteCalculationParams params,
	                                                                   @NonNull RouteCalculationResult initial,
	                                                                   boolean calcGPXRoute,
	                                                                   @NonNull Set<Long> activeCameraAvoidanceRoadIds) {
		if (params.trafficRoutingApplied || !initial.isCalculated()) {
			return null;
		}
		FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
		if (plugin == null || !plugin.TRAFFIC_ROUTING_ENABLED.get()) {
			return null;
		}
		TrafficRoutingHelper trafficHelper = plugin.getTrafficRoutingHelper();
		if (params.previousToRecalculate == null) {
			log.info("FlockFree traffic reroute skipped during initial route preview");
			return null;
		}
		if (params.previousToRecalculate != null && params.onlyStartPointChanged) {
			trafficHelper.recordTrafficSkipped(TrafficRoutingHelper.TrafficStatus.SKIPPED_PARTIAL);
			return null;
		}
		if (isFlockFreeOptionalRoutingBudgetExceeded(params)) {
			log.info("FlockFree traffic reroute skipped: optional reroute budget exceeded");
			return null;
		}

		Map<Long, Float> speedMultipliers = trafficHelper.collectTrafficSpeedMultipliersForRoute(initial);
		if (speedMultipliers.isEmpty()) {
			return null;
		}

		RouteCalculationParams trafficParams = copyParamsForFlockFreeTraffic(params,
				activeCameraAvoidanceRoadIds, speedMultipliers);
		try {
			RouteCalculationResult trafficRoute = findVectorMapsRoute(trafficParams, calcGPXRoute);
			if (!trafficRoute.isCalculated()) {
				trafficHelper.recordTrafficFallback(speedMultipliers.size());
				return null;
			}
			if (hasCameraRoadsOnTrafficCandidate(plugin, trafficRoute)) {
				FlockFreeRouteVariant cameraSafeTraffic =
						maybeRecalculateWithFlockFreeAvoidance(trafficParams, trafficRoute, calcGPXRoute);
				if (cameraSafeTraffic != null) {
					trafficHelper.recordTrafficApplied(speedMultipliers.size());
					return cameraSafeTraffic;
				}
				trafficHelper.recordTrafficFallback(speedMultipliers.size());
				log.warn("FlockFree traffic candidate rejected because camera avoidance could not preserve a camera-safe route");
				return null;
			}
			trafficHelper.recordTrafficApplied(speedMultipliers.size());
			return new FlockFreeRouteVariant(trafficRoute, activeCameraAvoidanceRoadIds);
		} catch (IOException e) {
			trafficHelper.recordTrafficFallback(speedMultipliers.size());
			log.warn("FlockFree traffic routing failed; returning previous route", e);
			return null;
		}
	}

	private boolean hasCameraRoadsOnTrafficCandidate(@NonNull FlockFreePlugin plugin,
	                                                @NonNull RouteCalculationResult candidate) {
		if (!plugin.isCameraAvoidanceActive()) {
			return false;
		}
		if (!plugin.getCameraData().isDataLoaded()
				&& !plugin.getCameraData().ensureCacheLoadedForRouting()) {
			return true;
		}
		List<CameraAvoidanceHelper.RoadWithCameraCount> roadsWithCameras =
				plugin.getAvoidanceHelper().collectAvoidRoadIdsWithCameraCountForRoute(candidate,
						plugin.CAMERA_AVOIDANCE_RADIUS.get());
		return !Algorithms.isEmpty(roadsWithCameras);
	}

	@NonNull
	private String getFlockFreeAvoidanceRejectionReason(@NonNull RouteCalculationResult candidate,
	                                                    int candidateCameraCount,
	                                                    int originalCameraCount,
	                                                    int originalRouteTimeSeconds,
	                                                    int originalRouteDistanceMeters,
	                                                    @NonNull String avoidanceMode) {
		if (candidateCameraCount >= originalCameraCount) {
			return candidateCameraCount + " cameras is not fewer than original " + originalCameraCount;
		}
		if (candidateCameraCount == 0) {
			return "";
		}
		int candidateRouteTimeSeconds = candidate.getLeftTime(null);
		if (originalRouteTimeSeconds > 0 && candidateRouteTimeSeconds > 0) {
			int extraTimeSeconds = candidateRouteTimeSeconds - originalRouteTimeSeconds;
			int maxExtraTimeSeconds = getFlockFreeMaxAvoidanceExtraTimeSeconds(originalRouteTimeSeconds, avoidanceMode);
			if (extraTimeSeconds > maxExtraTimeSeconds) {
				return "extra time " + extraTimeSeconds + "s exceeds "
						+ maxExtraTimeSeconds + "s";
			}
		}
		int candidateRouteDistanceMeters = candidate.getWholeDistance();
		if (originalRouteDistanceMeters > 0 && candidateRouteDistanceMeters > 0) {
			double distanceMultiplier = getMaxAvoidanceDistanceMultiplier(avoidanceMode);
			int maxDistanceMeters = (int) Math.ceil(
					originalRouteDistanceMeters * distanceMultiplier);
			if (candidateRouteDistanceMeters > maxDistanceMeters) {
				return "distance " + candidateRouteDistanceMeters + "m exceeds max "
						+ maxDistanceMeters + "m";
			}
		}
		return "";
	}

	private int getFlockFreeMaxAvoidanceExtraTimeSeconds(int originalRouteTimeSeconds,
	                                                     @NonNull String avoidanceMode) {
		double timeMultiplier = getMaxAvoidanceTimeMultiplier(avoidanceMode);
		int maxConstantSeconds = getMaxAvoidanceExtraTimeSeconds(avoidanceMode);
		int percentageAllowanceSeconds = (int) Math.ceil(
				originalRouteTimeSeconds * (timeMultiplier - 1d));
		return Math.max(maxConstantSeconds, percentageAllowanceSeconds);
	}

	private int getMaxAvoidanceExtraTimeSeconds(@NonNull String avoidanceMode) {
		if ("strict_privacy".equals(avoidanceMode)) {
			return FLOCKFREE_STRICT_MAX_AVOIDANCE_EXTRA_TIME_SECONDS;
		}
		return FLOCKFREE_BALANCED_MAX_AVOIDANCE_EXTRA_TIME_SECONDS;
	}

	private double getMaxAvoidanceTimeMultiplier(@NonNull String avoidanceMode) {
		if ("strict_privacy".equals(avoidanceMode)) {
			return FLOCKFREE_STRICT_MAX_AVOIDANCE_TIME_MULTIPLIER;
		}
		return FLOCKFREE_BALANCED_MAX_AVOIDANCE_TIME_MULTIPLIER;
	}

	private double getMaxAvoidanceDistanceMultiplier(@NonNull String avoidanceMode) {
		if ("strict_privacy".equals(avoidanceMode)) {
			return FLOCKFREE_STRICT_MAX_AVOIDANCE_DISTANCE_MULTIPLIER;
		}
		return FLOCKFREE_BALANCED_MAX_AVOIDANCE_DISTANCE_MULTIPLIER;
	}

	private boolean isActivelyNavigating(@NonNull RouteCalculationParams params) {
		if (params.ctx == null) {
			return false;
		}
		RoutingHelper routingHelper = params.ctx.getRoutingHelper();
		if (routingHelper == null) {
			return false;
		}
		// Active navigation: user is in following mode AND this is a recalculation
		// (not an initial route preview)
		return routingHelper.isFollowingMode() && params.previousToRecalculate != null;
	}

	private List<Location> getHorizonRouteLocations(@NonNull List<Location> allLocations, int maxKm) {
		if (allLocations.isEmpty()) {
			return allLocations;
		}
		double maxDistanceMeters = maxKm * 1000.0;
		double accumulated = 0.0;
		List<Location> horizon = new ArrayList<>();
		horizon.add(allLocations.get(0));
		for (int i = 1; i < allLocations.size(); i++) {
			Location prev = allLocations.get(i - 1);
			Location curr = allLocations.get(i);
			accumulated += prev.distanceTo(curr);
			horizon.add(curr);
			if (accumulated >= maxDistanceMeters) {
				break;
			}
		}
		return horizon;
	}

	/**
	 * Filters the list of camera-adjacent roads to only include roads within the first maxKm of the route.
	 * Uses the route segment distances to accumulate distance and stops including roads past the horizon.
	 */
	private List<CameraAvoidanceHelper.RoadWithCameraCount> filterRoadsToHorizon(
			@NonNull RouteCalculationResult route,
			@NonNull List<CameraAvoidanceHelper.RoadWithCameraCount> allRoads,
			int maxKm) {
		List<RouteSegmentResult> segments = route.getOriginalRoute();
		if (segments == null || segments.isEmpty()) {
			return allRoads; // can't filter, return as-is
		}
		double maxDistanceMeters = maxKm * 1000.0;
		double accumulated = 0.0;
		Set<Long> horizonRoadIds = new HashSet<>();
		for (RouteSegmentResult seg : segments) {
			if (seg == null || seg.getObject() == null) {
				continue;
			}
			horizonRoadIds.add(seg.getObject().getId());
			accumulated += seg.getDistance();
			if (accumulated >= maxDistanceMeters) {
				break;
			}
		}
		List<CameraAvoidanceHelper.RoadWithCameraCount> filtered = new ArrayList<>();
		for (CameraAvoidanceHelper.RoadWithCameraCount rwc : allRoads) {
			if (horizonRoadIds.contains(rwc.roadId)) {
				filtered.add(rwc);
			}
		}
		return filtered;
	}

	private boolean isFlockFreeOptionalRoutingBudgetExceeded(@NonNull RouteCalculationParams params) {
		if (params.calculationProgress == null || params.calculationProgress.routeCalculationStartTime <= 0) {
			return false;
		}
		long budget = isActivelyNavigating(params)
				? ACTIVE_NAVIGATION_BUDGET_MS
				: FLOCKFREE_OPTIONAL_ROUTING_BUDGET_MS;
		return System.currentTimeMillis() - params.calculationProgress.routeCalculationStartTime
				> budget;
	}

	private void startFlockFreeOptionalRoutingProgress(@NonNull RouteCalculationParams params) {
		if (params.calculationProgress != null) {
			if (isActivelyNavigating(params)) {
				params.calculationProgress.startPostRouteWork(ACTIVE_NAVIGATION_AVOIDANCE_STEPS,
						ACTIVE_NAVIGATION_STEP_EXPECTED_MS);
			} else {
				params.calculationProgress.startPostRouteWork(FLOCKFREE_OPTIONAL_AVOIDANCE_STEPS,
						FLOCKFREE_OPTIONAL_STEP_EXPECTED_MS);
			}
		}
	}

	private void beginFlockFreeOptionalRoutingStep(@NonNull RouteCalculationParams params, int completedSteps) {
		if (params.calculationProgress != null) {
			params.calculationProgress.beginPostRouteWorkStep(completedSteps);
		}
	}

	private void completeFlockFreeOptionalRoutingStep(@NonNull RouteCalculationParams params, int completedSteps) {
		if (params.calculationProgress != null) {
			params.calculationProgress.completePostRouteWorkStep(completedSteps);
		}
	}

	private void finishFlockFreeOptionalRoutingProgress(@NonNull RouteCalculationParams params) {
		if (params.calculationProgress != null) {
			params.calculationProgress.finishPostRouteWork();
		}
	}

	private void restoreFlockFreeProgressState(@NonNull RouteCalculationParams params,
	                                           @Nullable MissingMapsCalculationResult missingMaps) {
		if (params.calculationProgress != null) {
			params.calculationProgress.missingMapsCalculationResult = missingMaps;
		}
	}

	@NonNull
	private RouteCalculationParams copyParamsForFlockFreeAvoidance(@NonNull RouteCalculationParams params,
	                                                               @NonNull Set<Long> avoidIds) {
		RouteCalculationParams copy = new RouteCalculationParams();
		copy.start = params.start;
		copy.end = params.end;
		copy.intermediates = params.intermediates;
		copy.currentLocation = params.currentLocation;
		copy.ctx = params.ctx;
		copy.mode = params.mode;
		copy.gpxRoute = params.gpxRoute;
		copy.onlyStartPointChanged = false;
		copy.fast = params.fast;
		copy.leftSide = params.leftSide;
		copy.startTransportStop = params.startTransportStop;
		copy.targetTransportStop = params.targetTransportStop;
		copy.inPublicTransportMode = params.inPublicTransportMode;
		copy.extraIntermediates = params.extraIntermediates;
		copy.initialCalculation = params.initialCalculation;
		copy.gpxFile = params.gpxFile;
		copy.calculationProgress = new RouteCalculationProgress();
		if (params.calculationProgress != null) {
			copy.calculationProgress.isCancelled = params.calculationProgress.isCancelled;
			copy.calculationProgress.routeCalculationStartTime =
					params.calculationProgress.routeCalculationStartTime;
		}
		copy.calculationProgressListener = params.calculationProgressListener;
		copy.alternateResultListener = params.alternateResultListener;
		copy.temporaryImpassableRoadIds = new LinkedHashSet<>();
		if (!Algorithms.isEmpty(params.temporaryImpassableRoadIds)) {
			copy.temporaryImpassableRoadIds.addAll(params.temporaryImpassableRoadIds);
		}
		copy.temporaryImpassableRoadIds.addAll(avoidIds);
		if (params.temporaryTrafficSpeedMultipliers != null
				&& !params.temporaryTrafficSpeedMultipliers.isEmpty()) {
			copy.temporaryTrafficSpeedMultipliers =
					new LinkedHashMap<>(params.temporaryTrafficSpeedMultipliers);
		}
		copy.cameraAvoidanceApplied = true;
		copy.trafficRoutingApplied = params.trafficRoutingApplied;
		return copy;
	}

	@NonNull
	private RouteCalculationParams copyParamsForFlockFreeTraffic(@NonNull RouteCalculationParams params,
	                                                             @NonNull Set<Long> activeCameraAvoidanceRoadIds,
	                                                             @NonNull Map<Long, Float> speedMultipliers) {
		RouteCalculationParams copy = new RouteCalculationParams();
		copy.start = params.start;
		copy.end = params.end;
		copy.intermediates = params.intermediates;
		copy.currentLocation = params.currentLocation;
		copy.ctx = params.ctx;
		copy.mode = params.mode;
		copy.gpxRoute = params.gpxRoute;
		copy.onlyStartPointChanged = false;
		copy.fast = params.fast;
		copy.leftSide = params.leftSide;
		copy.startTransportStop = params.startTransportStop;
		copy.targetTransportStop = params.targetTransportStop;
		copy.inPublicTransportMode = params.inPublicTransportMode;
		copy.extraIntermediates = params.extraIntermediates;
		copy.initialCalculation = params.initialCalculation;
		copy.gpxFile = params.gpxFile;
		copy.calculationProgress = new RouteCalculationProgress();
		if (params.calculationProgress != null) {
			copy.calculationProgress.isCancelled = params.calculationProgress.isCancelled;
			copy.calculationProgress.routeCalculationStartTime =
					params.calculationProgress.routeCalculationStartTime;
		}
		copy.calculationProgressListener = params.calculationProgressListener;
		copy.alternateResultListener = params.alternateResultListener;
		copy.temporaryImpassableRoadIds = new LinkedHashSet<>(activeCameraAvoidanceRoadIds);
		copy.temporaryTrafficSpeedMultipliers = new LinkedHashMap<>(speedMultipliers);
		copy.cameraAvoidanceApplied = false;
		copy.trafficRoutingApplied = true;
		return copy;
	}

	public RouteCalculationResult recalculatePartOfflineRoute(RouteCalculationResult res, RouteCalculationParams params) {
		RouteCalculationResult rcr = params.previousToRecalculate;
		List<Location> locs = new ArrayList<Location>(rcr.getRouteLocations());
		try {
			int[] startI = {0};
			int[] endI = {locs.size()};
			locs = findStartAndEndLocationsFromRoute(locs, params.start, params.end, startI, endI);
			List<RouteDirectionInfo> directions = calcDirections(params, startI[0], endI[0], rcr.getRouteDirections(params.ctx));
			gpxRouteHelper.insertInitialSegment(params, locs, directions, true);
			res = new RouteCalculationResult(locs, directions, params, null, true);
		} catch (RuntimeException e) {
			log.error(e.getMessage(), e);
		}
		return res;
	}


	protected List<RouteDirectionInfo> calcDirections(RouteCalculationParams params, int startIndex, int endIndex,
                                                      List<RouteDirectionInfo> inputDirections) {
		List<RouteDirectionInfo> directions = new ArrayList<RouteDirectionInfo>();
		if (inputDirections != null) {
			for (RouteDirectionInfo info : inputDirections) {
				if (info.routePointOffset >= startIndex && info.routePointOffset < endIndex) {
					RouteDirectionInfo ch = new RouteDirectionInfo(info.getAverageSpeed(), info.getTurnType());
					ch.routePointOffset = info.routePointOffset - startIndex;
					if(info.routeEndPointOffset != 0) {
						ch.routeEndPointOffset = info.routeEndPointOffset - startIndex;
					}
					ch.setDescriptionRoute(info.getDescriptionRoutePart(params.ctx));
					ch.setRouteDataObject(info.getRouteDataObject());
					// Issue #2894
					if (info.getRef() != null && !"null".equals(info.getRef())) {
						ch.setRef(info.getRef());
					}
					if (info.getStreetName() != null && !"null".equals(info.getStreetName())) {
						ch.setStreetName(info.getStreetName());
					}
					if (info.getDestinationName() != null && !"null".equals(info.getDestinationName())) {
						ch.setDestinationName(info.getDestinationName());
					}

					directions.add(ch);
				}
			}
		}
		return directions;
	}

	protected ArrayList<Location> findStartAndEndLocationsFromRoute(List<Location> route, Location startLoc, LatLon endLoc, int[] startI, int[] endI) {
		float minDist = Integer.MAX_VALUE;
		int start = 0;
		int end = route.size();
		if (startLoc != null) {
			for (int i = 0; i < route.size(); i++) {
				float d = route.get(i).distanceTo(startLoc);
				if (d < minDist) {
					start = i;
					minDist = d;
				}
			}
//		} else {
//			startLoc = route.get(0); // no more used
		}
		Location l = new Location("temp"); //$NON-NLS-1$
		l.setLatitude(endLoc.getLatitude());
		l.setLongitude(endLoc.getLongitude());
		minDist = Integer.MAX_VALUE;
		// get in reverse order taking into account ways with cycle
		for (int i = route.size() - 1; i >= start; i--) {
			float d = route.get(i).distanceTo(l);
			if (d < minDist) {
				end = i + 1;
				// slightly modify to allow last point to be added
				minDist = d - 40;
			}
		}
		ArrayList<Location> sublist = new ArrayList<Location>(route.subList(start, end));
		if(startI != null) {
			startI[0] = start;
		}
		if(endI != null) {
			endI[0] = end;
		}
		return sublist;
	}

	public RoutingEnvironment getRoutingEnvironment(OsmandApplication ctx, ApplicationMode mode, LatLon start, LatLon end) throws IOException {
		RouteCalculationParams params = new RouteCalculationParams();
		params.ctx = ctx;
		params.mode = mode;
		params.start = new Location("", start.getLatitude(), start.getLongitude());
		params.end = end;
		return calculateRoutingEnvironment(params, false, true);
	}

	public List<GpxPoint> generateGpxPoints(RoutingEnvironment env, GpxRouteApproximation gctx, LocationsHolder locationsHolder) {
		return env.getRouter().generateGpxPoints(gctx, locationsHolder);
	}

	public GpxRouteApproximation calculateGpxPointsApproximation(RoutingEnvironment env, GpxRouteApproximation gctx, List<GpxPoint> points, ResultMatcher<GpxRouteApproximation> resultMatcher, boolean useExternalTimestamps) throws IOException, InterruptedException {
		return env.getRouter().searchGpxRoute(gctx, points, resultMatcher, useExternalTimestamps);
	}

	protected RoutingEnvironment calculateRoutingEnvironment(RouteCalculationParams params, boolean calcGPXRoute, boolean skipComplex) throws IOException {
		BinaryMapIndexReader[] files = params.ctx.getResourceManager().getRoutingMapFiles();
		RoutePlannerFrontEnd router = new RoutePlannerFrontEnd();

		OsmandSettings settings = params.ctx.getSettings();

		RoutePlannerFrontEnd.CALCULATE_MISSING_MAPS = !OsmandSettings.IGNORE_MISSING_MAPS;
		RoutePlannerFrontEnd.CONTINUE_ON_MISSING_MAPS = !OsmandSettings.STOP_ON_MISSING_MAPS;

		RoutingType routingType = settings.ROUTING_TYPE.getModeValue(params.mode);
		if (routingType.isHHRouting()) {
			router.setDefaultHHRoutingConfig();
			router.setHHRouteCpp(routingType == HH_CPP);
		} else {
			router.setHHRoutingConfig(null);
		}

		ApproximationType approximationType = settings.APPROXIMATION_TYPE.getModeValue(params.mode);
		router.setUseNativeApproximation(approximationType.isNativeApproximation());
		router.setUseGeometryBasedApproximation(approximationType.isGeoApproximation());

		RoutingConfiguration.Builder config = params.ctx.getRoutingConfigForMode(params.mode);
		GeneralRouter generalRouter = params.ctx.getRouter(config, params.mode);
		if (generalRouter == null) {
			return null;
		}
		RoutingConfiguration cf = initOsmAndRoutingConfig(config, params, settings, generalRouter);
		if (cf == null) {
			return null;
		}
		PrecalculatedRouteDirection precalculated = null;
		if (calcGPXRoute) {
			ArrayList<Location> sublist = findStartAndEndLocationsFromRoute(params.gpxRoute.points,
					params.start, params.end, null, null);
			LatLon[] latLon = new LatLon[sublist.size()];
			for (int k = 0; k < latLon.length; k++) {
				latLon[k] = new LatLon(sublist.get(k).getLatitude(), sublist.get(k).getLongitude());
			}
			precalculated = PrecalculatedRouteDirection.build(latLon, generalRouter.getMaxSpeed());
			precalculated.setFollowNext(true);
			//cf.planRoadDirection = 1;
		}
		// BUILD context
		NativeOsmandLibrary lib = settings.SAFE_MODE.get() ? null : NativeOsmandLibrary.getLoadedLibrary();
		// check loaded files
		int leftX = MapUtils.get31TileNumberX(params.start.getLongitude());
		int rightX = leftX;
		int bottomY = MapUtils.get31TileNumberY(params.start.getLatitude());
		int topY = bottomY;
		if (params.intermediates != null) {
			for (LatLon l : params.intermediates) {
				leftX = Math.min(MapUtils.get31TileNumberX(l.getLongitude()), leftX);
				rightX = Math.max(MapUtils.get31TileNumberX(l.getLongitude()), rightX);
				bottomY = Math.max(MapUtils.get31TileNumberY(l.getLatitude()), bottomY);
				topY = Math.min(MapUtils.get31TileNumberY(l.getLatitude()), topY);
			}
		}
		LatLon l = params.end;
		leftX = Math.min(MapUtils.get31TileNumberX(l.getLongitude()), leftX);
		rightX = Math.max(MapUtils.get31TileNumberX(l.getLongitude()), rightX);
		bottomY = Math.max(MapUtils.get31TileNumberY(l.getLatitude()), bottomY);
		topY = Math.min(MapUtils.get31TileNumberY(l.getLatitude()), topY);

		params.ctx.getResourceManager().getRenderer().checkInitialized(15, lib, leftX, rightX, bottomY, topY);

		RoutingContext ctx = router.buildRoutingContext(cf, lib, files, RouteCalculationMode.NORMAL);
		ctx.leftSideNavigation = params.leftSide;
		ctx.calculationProgress = params.calculationProgress;
		ctx.publicTransport = params.inPublicTransportMode;
		ctx.startTransportStop = params.startTransportStop;
		ctx.targetTransportStop = params.targetTransportStop;
		if (params.previousToRecalculate != null && params.onlyStartPointChanged) {
			int currentRoute = params.previousToRecalculate.getCurrentRoute();
			List<RouteSegmentResult> originalRoute = params.previousToRecalculate.getOriginalRoute();
			if (originalRoute != null && currentRoute < originalRoute.size()) {
				ctx.previouslyCalculatedRoute = originalRoute.subList(currentRoute, originalRoute.size());
			}
		}
		boolean complex = !skipComplex && params.mode.isDerivedRoutingFrom(ApplicationMode.CAR)
				&& (routingType == A_STAR_2_PHASE || routingType == HH_JAVA || routingType == HH_CPP)
				&& precalculated == null && router.getRecalculationEnd(ctx) == null;

		RoutingContext complexCtx = null;
		if (complex) {
			complexCtx = router.buildRoutingContext(cf, lib, files, RouteCalculationMode.COMPLEX);
			complexCtx.calculationProgress = params.calculationProgress;
			complexCtx.leftSideNavigation = params.leftSide;
			complexCtx.previouslyCalculatedRoute = ctx.previouslyCalculatedRoute;
		}
		return new RoutingEnvironment(router, ctx, complexCtx, precalculated);
	}

	protected RouteCalculationResult findVectorMapsRoute(RouteCalculationParams params, boolean calcGPXRoute) throws IOException {
		RoutingEnvironment env = calculateRoutingEnvironment(params, calcGPXRoute, false);
		if (env == null) {
			return applicationModeNotSupported(params);
		}
		LatLon st = new LatLon(params.start.getLatitude(), params.start.getLongitude());
		LatLon en = new LatLon(params.end.getLatitude(), params.end.getLongitude());
		List<LatLon> inters = new ArrayList<>();
		if (params.intermediates != null) {
			inters = new ArrayList<>(params.intermediates);
		}
		return calcOfflineRouteImpl(params, env.getRouter(), env.getCtx(), env.getComplexCtx(), st, en, inters, env.getPrecalculated());
	}

	private RoutingConfiguration initOsmAndRoutingConfig(Builder builder, RouteCalculationParams params, OsmandSettings settings,
	                                                     GeneralRouter generalRouter) {
		Map<String, String> paramsR = new LinkedHashMap<String, String>();
		for (Map.Entry<String, RoutingParameter> e : RoutingHelperUtils.getParametersForDerivedProfile(params.mode, generalRouter).entrySet()) {
			String key = e.getKey();
			RoutingParameter pr = e.getValue();
			String vl;
			if (key.equals(GeneralRouter.USE_SHORTEST_WAY)) {
				boolean bool = !settings.FAST_ROUTE_MODE.getModeValue(params.mode);
				vl = bool ? "true" : null;
			} else if (pr.getType() == RoutingParameterType.BOOLEAN) {
				CommonPreference<Boolean> pref = settings.getCustomRoutingBooleanProperty(key, pr.getDefaultBoolean());
				Boolean bool = pref.getModeValue(params.mode);
				vl = bool ? "true" : null;
			} else {
				vl = settings.getCustomRoutingProperty(key, pr.getDefaultString()).getModeValue(params.mode);
			}
			if (vl != null && vl.length() > 0) {
				paramsR.put(key, vl);
			}
		}
		Float defaultSpeed = params.mode.getDefaultSpeed();
		if (defaultSpeed > 0) {
			paramsR.put(GeneralRouter.DEFAULT_SPEED, String.valueOf(defaultSpeed));
		}
		Float minSpeed = params.mode.getMinSpeed();
		if (minSpeed > 0) {
			paramsR.put(GeneralRouter.MIN_SPEED, String.valueOf(minSpeed));
		}
		Float maxSpeed = params.mode.getMaxSpeed();
		if (maxSpeed > 0) {
			paramsR.put(GeneralRouter.MAX_SPEED, String.valueOf(maxSpeed));
		}
		OsmandApplication app = settings.getContext();
		DirectionPointsHelper helper = app.getAvoidSpecificRoads().getPointsHelper();
		builder.setDirectionPoints(helper.getDirectionPoints(params.mode));

		float mb = (1 << 20);
		Runtime rt = Runtime.getRuntime();
		// make visible
		int memoryLimitMb = (int) (0.95 * ((rt.maxMemory() - rt.totalMemory()) + rt.freeMemory()) / mb);
		int nativeMemoryLimitMb = settings.MEMORY_ALLOCATED_FOR_ROUTING.get();
		RoutingMemoryLimits memoryLimits = new RoutingMemoryLimits(memoryLimitMb, nativeMemoryLimitMb);
		log.warn("Use " + memoryLimitMb + " MB Free " + rt.freeMemory() / mb + " of " + rt.totalMemory() / mb + " max " + rt.maxMemory() / mb);
		log.warn("Use " + nativeMemoryLimitMb + " MB of native memory ");
		String derivedProfile = params.mode.getDerivedProfile();
		String routingProfile = "default".equals(derivedProfile) ? params.mode.getRoutingProfile() : derivedProfile;
		Double direction = params.start.hasBearing() ? params.start.getBearing() / 180d * Math.PI : null;

		RoutingConfiguration configuration = builder.build(routingProfile, direction, memoryLimits, paramsR);
			if (!Algorithms.isEmpty(params.temporaryImpassableRoadIds)) {
				Set<Long> impassableRoads = new HashSet<>(builder.getImpassableRoadLocations());
				impassableRoads.addAll(params.temporaryImpassableRoadIds);
				configuration.router.setImpassableRoads(impassableRoads);
			}
			if (params.temporaryTrafficSpeedMultipliers != null
					&& !params.temporaryTrafficSpeedMultipliers.isEmpty()) {
				configuration.router.setFlockFreeTrafficSpeedMultipliers(
						params.temporaryTrafficSpeedMultipliers);
			}
			if (settings.ENABLE_TIME_CONDITIONAL_ROUTING.getModeValue(params.mode)) {
				configuration.routeCalculationTime = System.currentTimeMillis();
			}
		configuration.showMinorTurns = settings.SHOW_MINOR_TURNS.getModeValue(params.mode);

		return configuration;
	}

	private RouteCalculationResult calcOfflineRouteImpl(RouteCalculationParams params,
	                                                    RoutePlannerFrontEnd router, RoutingContext ctx, RoutingContext complexCtx, LatLon st, LatLon en,
	                                                    List<LatLon> inters, PrecalculatedRouteDirection precalculated) throws IOException {
		try {
			RouteResultPreparation.RouteCalcResult result = null;
			if (complexCtx != null) {
				try {
					result = router.searchRoute(complexCtx, st, en, inters, precalculated);
					// discard ctx and replace with calculated
					ctx = complexCtx;
				} catch(RuntimeException e) {
					params.ctx.runInUIThread(() -> {
						log.error("Runtime error: " + e.getMessage(), e);
						params.ctx.showToastMessage(R.string.complex_route_calculation_failed, e.getMessage());
					});
				}
			}
			if (result == null) {
				result = router.searchRoute(ctx, st, en, inters);
			}

			if (result == null || result.getList().isEmpty()) {
				if(ctx.calculationProgress.segmentNotFound == 0) {
					return new RouteCalculationResult(params.ctx.getString(R.string.starting_point_too_far));
				} else if(ctx.calculationProgress.segmentNotFound == inters.size() + 1) {
					return new RouteCalculationResult(params.ctx.getString(R.string.ending_point_too_far));
				} else if(ctx.calculationProgress.segmentNotFound > 0) {
					return new RouteCalculationResult(params.ctx.getString(R.string.intermediate_point_too_far, "'" + ctx.calculationProgress.segmentNotFound + "'"));
				} else if (ctx.calculationProgress.directSegmentQueueSize == 0) {
					return new RouteCalculationResult("Route can not be found from start point (" + ctx.calculationProgress.distanceFromBegin / 1000f + " km)");
				} else if (ctx.calculationProgress.reverseSegmentQueueSize == 0) {
					return new RouteCalculationResult("Route can not be found from end point (" + ctx.calculationProgress.distanceFromEnd / 1000f + " km)");
				} else if (ctx.calculationProgress.isCancelled) {
					return interrupted();
				} else if(result != null && !Algorithms.isEmpty(result.getError())) {
					return new RouteCalculationResult(result.getError());
				}
				// something really strange better to see that message on the scren
				return emptyResult();
			} else {
				return new RouteCalculationResult(result.getList(), params, ctx,
						params.gpxRoute == null ? null : params.gpxRoute.wpt, true);
			}
		} catch (RuntimeException e) {
			log.error("Runtime error: " + e.getMessage(), e);
			return new RouteCalculationResult(e.getMessage() );
		} catch (InterruptedException e) {
			log.error("Interrupted: " + e.getMessage(), e);
			return interrupted();
		} catch (OutOfMemoryError e) {
//			ActivityManager activityManager = (ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
//			ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
//			activityManager.getMemoryInfo(memoryInfo);
//			int avl = (int) (memoryInfo.availMem / (1 << 20));
			int max = (int) (Runtime.getRuntime().maxMemory() / (1 << 20));
			int avl = (int) (Runtime.getRuntime().freeMemory() / (1 << 20));
			String s = " (" + avl + " MB available of " + max  + ") ";
			return new RouteCalculationResult("Not enough process memory "+ s);
		}
	}

	private RouteCalculationResult applicationModeNotSupported(RouteCalculationParams params) {
		return new RouteCalculationResult("Application mode '"+ params.mode.toHumanString()+ "' is not supported.");
	}

	private RouteCalculationResult interrupted() {
		return new RouteCalculationResult("Route calculation was interrupted");
	}

	private RouteCalculationResult emptyResult() {
		return new RouteCalculationResult("Empty result");
	}

	@NonNull
	public static List<RouteSegmentResult> parseOsmAndGPXRoute(List<Location> points, GpxFile gpxFile,
	                                                           List<Location> segmentEndpoints,
	                                                           int selectedSegment) {
		return parseOsmAndGPXRoute(points, gpxFile, segmentEndpoints, selectedSegment, false);
	}

	@NonNull
	public static List<RouteSegmentResult> parseOsmAndGPXRoute(List<Location> points, GpxFile gpxFile,
	                                                           List<Location> segmentEndpoints,
	                                                           int selectedSegment, boolean leftSide) {
		GPXFile javaGpx = SharedUtil.jGpxFile(gpxFile);
		List<TrkSegment> segments = javaGpx.getNonEmptyTrkSegments(false);
		if (selectedSegment != -1 && segments.size() > selectedSegment) {
			TrkSegment segment = segments.get(selectedSegment);
			points.addAll(locationsFromWpts(segment.points));
			RouteImporter routeImporter = new RouteImporter(segment, javaGpx.getRoutePoints(selectedSegment));
			return routeImporter.importRoute();
		} else {
			collectPointsFromSegments(segments, points, segmentEndpoints);
			RouteImporter routeImporter = new RouteImporter(javaGpx, leftSide);
			return routeImporter.importRoute();
		}
	}

	protected static void collectSegmentPointsFromGpx(GpxFile gpxFile, List<Location> points,
													  List<Location> segmentEndpoints, int selectedSegment) {
		List<net.osmand.shared.gpx.primitives.TrkSegment> segments = gpxFile.getNonEmptyTrkSegments(false);
		if (selectedSegment != -1 && segments.size() > selectedSegment) {
			net.osmand.shared.gpx.primitives.TrkSegment segment = segments.get(selectedSegment);
			points.addAll(locationsFromSharedWpts(segment.getPoints()));
		} else {
			collectPointsFromSharedSegments(segments, points, segmentEndpoints);
		}
	}

	protected static void collectSegmentPointsFromGpx(GPXFile gpxFile, List<Location> points,
													  List<Location> segmentEndpoints, int selectedSegment) {
		List<TrkSegment> segments = gpxFile.getNonEmptyTrkSegments(false);
		if (selectedSegment != -1 && segments.size() > selectedSegment) {
			TrkSegment segment = segments.get(selectedSegment);
			points.addAll(locationsFromWpts(segment.points));
		} else {
			collectPointsFromSegments(segments, points, segmentEndpoints);
		}
	}

	protected static void collectPointsFromSegments(List<TrkSegment> segments, List<Location> points, List<Location> segmentEndpoints) {
		Location lastPoint = null;
		for (int i = 0; i < segments.size(); i++) {
			TrkSegment segment = segments.get(i);
			points.addAll(locationsFromWpts(segment.points));
			if (i <= segments.size() - 1 && lastPoint != null) {
				segmentEndpoints.add(lastPoint);
				segmentEndpoints.add(points.get((points.size() - segment.points.size())));
			}
			lastPoint = points.get(points.size() - 1);
		}
	}

	protected static void collectPointsFromSharedSegments(List<net.osmand.shared.gpx.primitives.TrkSegment> segments, List<Location> points, List<Location> segmentEndpoints) {
		Location lastPoint = null;
		for (int i = 0; i < segments.size(); i++) {
			net.osmand.shared.gpx.primitives.TrkSegment segment = segments.get(i);
			points.addAll(locationsFromSharedWpts(segment.getPoints()));
			if (i <= segments.size() - 1 && lastPoint != null) {
				segmentEndpoints.add(lastPoint);
				segmentEndpoints.add(points.get((points.size() - segment.getPoints().size())));
			}
			lastPoint = points.get(points.size() - 1);
		}
	}

	protected static List<RouteDirectionInfo> parseOsmAndGPXRoute(List<Location> points, GpxFile gpxFile,
																  List<Location> segmentEndpoints,
																  boolean osmandRouter, boolean leftSide,
																  float defSpeed, int selectedSegment) {
		GPXFile javaGpx = SharedUtil.jGpxFile(gpxFile);
		List<RouteDirectionInfo> directions = null;
		if (!osmandRouter) {
			for (WptPt pt : javaGpx.getPoints()) {
				points.add(createLocation(pt));
			}
		} else {
			collectSegmentPointsFromGpx(javaGpx, points, segmentEndpoints, selectedSegment);
		}
		float[] distanceToEnd = new float[points.size()];
		for (int i = points.size() - 2; i >= 0; i--) {
			distanceToEnd[i] = distanceToEnd[i + 1] + points.get(i).distanceTo(points.get(i + 1));
		}

		Route route = null;
		if (javaGpx.routes.size() > 0) {
			route = javaGpx.routes.get(0);
		}
		RouteDirectionInfo previous = null;
		if (route != null && route.points.size() > 0) {
			directions = new ArrayList<RouteDirectionInfo>();
			Iterator<WptPt> iterator = route.points.iterator();
			float lasttime = 0;
			while(iterator.hasNext()){
				WptPt item = iterator.next();
				try {
					String stime = item.getExtensionsToRead().get("time");
					int time  = 0;
					if (stime != null) {
						time = Integer.parseInt(stime);
					}
					int offset = Integer.parseInt(item.getExtensionsToRead().get("offset")); //$NON-NLS-1$
					if(directions.size() > 0) {
						RouteDirectionInfo last = directions.get(directions.size() - 1);
						// update speed using time and idstance
						if (distanceToEnd.length > last.routePointOffset && distanceToEnd.length > offset) {
							float lastDistanceToEnd = distanceToEnd[last.routePointOffset];
							float currentDistanceToEnd = distanceToEnd[offset];
							if (lasttime != 0) {
								last.setAverageSpeed((lastDistanceToEnd - currentDistanceToEnd) / lasttime);
							}
							last.distance = Math.round(lastDistanceToEnd - currentDistanceToEnd);
						}
					}
					// save time as a speed because we don't know distance of the route segment
					lasttime = time;
					float avgSpeed = defSpeed;
					if (!iterator.hasNext() && time > 0 && distanceToEnd.length > offset) {
						avgSpeed = distanceToEnd[offset] / time;
					}
					String stype = item.getExtensionsToRead().get("turn"); //$NON-NLS-1$
					TurnType turnType;
					if (stype != null) {
						turnType = TurnType.fromString(stype.toUpperCase(), leftSide);
					} else {
						turnType = TurnType.straight();
					}
					String sturn = item.getExtensionsToRead().get("turn-angle"); //$NON-NLS-1$
					if (sturn != null) {
						turnType.setTurnAngle((float) Double.parseDouble(sturn));
					}
					String slanes = item.getExtensionsToRead().get("lanes");
					if (slanes != null) {
						try {
							int[] lanes = CollectionUtils.stringToArray(slanes);
							if (lanes != null && lanes.length > 0) {
								turnType.setLanes(lanes);
							}
						} catch (NumberFormatException e) {
							// ignore
						}
					}
					RouteDirectionInfo dirInfo = new RouteDirectionInfo(avgSpeed, turnType);
					dirInfo.setDescriptionRoute(item.desc); //$NON-NLS-1$
					dirInfo.routePointOffset = offset;

					// Issue #2894
					String sref = item.getExtensionsToRead().get("ref"); //$NON-NLS-1$
					if (sref != null && !"null".equals(sref)) {
						dirInfo.setRef(sref); //$NON-NLS-1$
					}
					String sstreetname = item.getExtensionsToRead().get("street-name"); //$NON-NLS-1$
					if (sstreetname != null && !"null".equals(sstreetname)) {
						dirInfo.setStreetName(sstreetname); //$NON-NLS-1$
					}
					String sdest = item.getExtensionsToRead().get("dest"); //$NON-NLS-1$
					if (sdest != null && !"null".equals(sdest)) {
						dirInfo.setDestinationName(sdest); //$NON-NLS-1$
					}

					if (previous != null && TurnType.C != previous.getTurnType().getValue() &&
							!osmandRouter) {
						// calculate angle
						if (previous.routePointOffset > 0) {
							float paz = points.get(previous.routePointOffset - 1).bearingTo(points.get(previous.routePointOffset));
							float caz;
							if (previous.getTurnType().isRoundAbout() && dirInfo.routePointOffset < points.size() - 1) {
								caz = points.get(dirInfo.routePointOffset).bearingTo(points.get(dirInfo.routePointOffset + 1));
							} else {
								caz = points.get(dirInfo.routePointOffset - 1).bearingTo(points.get(dirInfo.routePointOffset));
							}
							float angle = caz - paz;
							if (angle < 0) {
								angle += 360;
							} else if (angle > 360) {
								angle -= 360;
							}
							// that magic number helps to fix some errors for turn
							angle += 75;

							if (previous.getTurnType().getTurnAngle() < 0.5f) {
								previous.getTurnType().setTurnAngle(angle);
							}
						}
					}
					directions.add(dirInfo);

					previous = dirInfo;
				} catch (IllegalArgumentException e) {
					log.info("Exception", e);
				}
			}
		}
		if (previous != null && TurnType.C != previous.getTurnType().getValue()) {
			// calculate angle
			if (previous.routePointOffset > 0 && previous.routePointOffset < points.size() - 1) {
				float paz = points.get(previous.routePointOffset - 1).bearingTo(points.get(previous.routePointOffset));
				float caz = points.get(previous.routePointOffset).bearingTo(points.get(points.size() - 1));
				float angle = caz - paz;
				if (angle < 0) {
					angle += 360;
				}
				if (previous.getTurnType().getTurnAngle() < 0.5f) {
					previous.getTurnType().setTurnAngle(angle);
				}
			}
		}
		return directions;
	}

	public GpxFile createOsmandRouterGPX(RouteCalculationResult route, OsmandApplication ctx, String name) {
		TargetPointsHelper helper = ctx.getTargetPointsHelper();
		List<net.osmand.shared.gpx.primitives.WptPt> points = new ArrayList<>();
		List<TargetPoint> ps = helper.getIntermediatePointsWithTarget();
		for (int k = 0; k < ps.size(); k++) {
			net.osmand.shared.gpx.primitives.WptPt pt = new net.osmand.shared.gpx.primitives.WptPt();
			pt.setLat(ps.get(k).getLatitude());
			pt.setLon(ps.get(k).getLongitude());
			if (k < ps.size()) {
				pt.setName(ps.get(k).getOnlyName());
				if (k == ps.size() - 1) {
					String target = ctx.getString(R.string.destination_point, "");
					if (pt.getName() != null && pt.getName().startsWith(target)) {
						pt.setName(ctx.getString(R.string.destination_point, pt.getName()));
					}
				} else {
					String prefix = (k + 1) +". ";
					if(Algorithms.isEmpty(pt.getName())) {
						pt.setName(ctx.getString(R.string.target_point, pt.getName()));
					}
					if (pt.getName().startsWith(prefix)) {
						pt.setName(prefix + pt.getName());
					}
				}
				pt.setDesc(pt.getName());
			}
			points.add(pt);
		}

		List<Location> locations = route.getImmutableAllLocations();
		List<RouteSegmentResult> originalRoute = route.getOriginalRoute();
		RouteExporter exporter = new RouteExporter(name, originalRoute, locations, null, points);

		return exporter.exportRoute();
	}

	private RouteCalculationResult findOnlineRoute(RouteCalculationParams params) throws IOException, JSONException {
		OsmandApplication app = params.ctx;
		OnlineRoutingHelper helper = app.getOnlineRoutingHelper();
		OsmandSettings settings = app.getSettings();
		String engineKey = params.mode.getRoutingProfile();
		OnlineRoutingResponse response =
				helper.calculateRouteOnline(engineKey, getPathFromParams(params), params);

		if (response != null) {
			if (response.getGpxFile() != null) {
				GPXRouteParamsBuilder builder = new GPXRouteParamsBuilder(response.getGpxFile(), settings);
				builder.setCalculatedRouteTimeSpeed(response.hasCalculatedTimeSpeed());
				params.gpxFile = response.getGpxFile();
				params.gpxRoute = builder.build(app);
				return gpxRouteHelper.calculateGpxRoute(params);
			}
			List<Location> route = response.getRoute();
			List<RouteDirectionInfo> directions = response.getDirections();
			if (!Algorithms.isEmpty(route) && !Algorithms.isEmpty(directions)) {
				params.intermediates = null;
				return new RouteCalculationResult(route, directions, params, null, false);
			}
		} else {
			params.initialCalculation = false;
		}

		return new RouteCalculationResult("Route is empty");
	}

	private static List<LatLon> getPathFromParams(RouteCalculationParams params) {
		List<LatLon> points = new ArrayList<>();
		points.add(new LatLon(params.start.getLatitude(), params.start.getLongitude()));
		if (!Algorithms.isEmpty(params.intermediates)) {
			points.addAll(params.intermediates);
		}
		points.add(params.end);
		return points;
	}

	@NonNull
	protected RouteCalculationResult findBROUTERRoute(@NonNull RouteCalculationParams params) throws
			IOException, ParserConfigurationException, FactoryConfigurationError, SAXException {
		boolean addMissingTurns = true;
		Bundle brouterParams = getBRouterParams(params);

		OsmandApplication app = params.ctx;
		List<Location> res = new ArrayList<>();
		List<RouteDirectionInfo> infos = new ArrayList<>();
		List<Location> segmentEndpoints = new ArrayList<>();

		IBRouterService brouterService = app.getBRouterService();
		if (brouterService == null) {
			brouterService = app.reconnectToBRouter();
			if (brouterService == null) {
				return new RouteCalculationResult("BRouter service is not available");
			}
		}
		try {
			String gpxMessage = brouterService.getTrackFromParams(brouterParams);
			if (gpxMessage == null) {
				gpxMessage = "no result from brouter";
			}
			boolean isZ64Encoded = gpxMessage.startsWith("ejY0"); // base-64 version of "z64"
			if (!(isZ64Encoded || gpxMessage.startsWith("<"))) {
				return new RouteCalculationResult(gpxMessage);
			}
			InputStream gpxStream;
			if (isZ64Encoded) {
				ByteArrayInputStream bais = new ByteArrayInputStream(Base64.decode(gpxMessage, Base64.DEFAULT));
				bais.read(new byte[3]); // skip prefix
				gpxStream = new GZIPInputStream(bais);
			} else {
				gpxStream = new ByteArrayInputStream(gpxMessage.getBytes(StandardCharsets.UTF_8));
			}
			GpxFile gpxFile = SharedUtil.loadGpxFile(gpxStream);
			infos = parseOsmAndGPXRoute(res, gpxFile, segmentEndpoints, true, params.leftSide, params.mode.getDefaultSpeed(), -1);
			if (infos != null) {
				addMissingTurns = false;
			}
		} catch (Exception e) {
			return new RouteCalculationResult("Exception calling BRouter: " + e); //$NON-NLS-1$
		}
		return new RouteCalculationResult(res, infos, params, null, addMissingTurns);
	}

	@NonNull
	private Bundle getBRouterParams(@NonNull RouteCalculationParams params) {
		int numpoints = 2 + (params.intermediates != null ? params.intermediates.size() : 0);
		double[] lats = new double[numpoints];
		double[] lons = new double[numpoints];
		int index = 0;
		String mode;
		lats[index] = params.start.getLatitude();
		lons[index] = params.start.getLongitude();
		index++;
		if (params.intermediates != null && params.intermediates.size() > 0) {
			for (LatLon il : params.intermediates) {
				lats[index] = il.getLatitude();
				lons[index] = il.getLongitude();
				index++;
			}
		}
		lats[index] = params.end.getLatitude();
		lons[index] = params.end.getLongitude();

		AvoidRoadsHelper avoidRoadsHelper = params.ctx.getAvoidSpecificRoads();
		Set<LatLon> impassableRoads = avoidRoadsHelper.getImpassableRoadsCoordinates();
		double[] nogoLats = new double[impassableRoads.size()];
		double[] nogoLons = new double[impassableRoads.size()];
		double[] nogoRadi = new double[impassableRoads.size()];

		if (impassableRoads.size() != 0) {
			int nogoindex = 0;
			for (LatLon nogos : impassableRoads) {
				nogoLats[nogoindex] = nogos.getLatitude();
				nogoLons[nogoindex] = nogos.getLongitude();
				nogoRadi[nogoindex] = 10;
				nogoindex++;
			}
		}
		if (params.mode.isDerivedRoutingFrom(ApplicationMode.PEDESTRIAN)) {
			mode = "foot"; //$NON-NLS-1$
		} else if (params.mode.isDerivedRoutingFrom(ApplicationMode.BICYCLE)) {
			mode = "bicycle"; //$NON-NLS-1$
		} else {
			mode = "motorcar"; //$NON-NLS-1$
		}
		Bundle bundle = new Bundle();
		bundle.putDoubleArray("lats", lats);
		bundle.putDoubleArray("lons", lons);
		bundle.putDoubleArray("nogoLats", nogoLats);
		bundle.putDoubleArray("nogoLons", nogoLons);
		bundle.putDoubleArray("nogoRadi", nogoRadi);
		bundle.putString("fast", params.fast ? "1" : "0");
		bundle.putString("v", mode);
		bundle.putString("trackFormat", "gpx");
		bundle.putString("turnInstructionFormat", "osmand");
		bundle.putString("acceptCompressedResult", "true");

		String osmandProfileName = params.mode.getUserProfileName();
		if (osmandProfileName.indexOf("Brouter") == 0) {
			if (osmandProfileName.contains("[") && osmandProfileName.contains("]")) {
				String brouterProfileName = osmandProfileName.substring(osmandProfileName.indexOf("[") + 1, osmandProfileName.indexOf("]"));

				// log.info (" BROUTER_PROFILE_NAME = " + brouterProfileName );
				if (brouterProfileName.length() > 0) {
					//  set the profile-name in the new parameter "profile" to transmit the profile-name to the brouter
					bundle.putString("profile", brouterProfileName);
				}
			}
		}
		return bundle;
	}

	protected RouteCalculationResult findStraightRoute(@NonNull RouteCalculationParams params) {
		LinkedList<Location> points = new LinkedList<>();
		List<Location> segments = new ArrayList<>();
		points.add(new Location("pnt", params.start.getLatitude(), params.start.getLongitude()));
		if (params.intermediates != null) {
			for (LatLon l : params.intermediates) {
				points.add(new Location(params.extraIntermediates ? "" : "pnt", l.getLatitude(), l.getLongitude()));
			}
			if (params.extraIntermediates) {
				params.intermediates = null;
			}
		}
		points.add(new Location("", params.end.getLatitude(), params.end.getLongitude()));
		Location lastAdded = null;
		float speed = params.mode.getDefaultSpeed();
		List<RouteDirectionInfo> computeDirections = new ArrayList<>();
		while (!points.isEmpty()) {
			Location pl = points.peek();
			if (lastAdded == null || lastAdded.distanceTo(pl) < MIN_STRAIGHT_DIST) {
				lastAdded = points.poll();
				if (lastAdded != null && lastAdded.getProvider().equals("pnt")) {
					RouteDirectionInfo previousInfo = new RouteDirectionInfo(speed, TurnType.straight());
					previousInfo.routePointOffset = segments.size();
					previousInfo.setDescriptionRoute(params.ctx.getString(R.string.route_head));
					computeDirections.add(previousInfo);
				}
				segments.add(lastAdded);
			} else {
				if (pl != null) {
					Location mp = MapUtils.calculateMidPoint(lastAdded, pl);
					points.add(0, mp);
				}
			}
		}
		return new RouteCalculationResult(segments, computeDirections, params, null, params.extraIntermediates);
	}
}
