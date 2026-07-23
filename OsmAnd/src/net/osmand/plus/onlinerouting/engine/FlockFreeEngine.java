package net.osmand.plus.onlinerouting.engine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.onlinerouting.EngineParameter;
import net.osmand.plus.onlinerouting.VehicleType;
import net.osmand.plus.plugins.flockfree.CameraData;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.router.TurnType;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.util.Algorithms;
import net.osmand.util.GeoPolylineParserUtil;
import net.osmand.util.MapUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.osmand.util.Algorithms.isEmpty;

/**
 * FlockFreeEngine — Custom online routing engine for the self-hosted
 * FlockFree GraphHopper backend at router.antonson.co.
 *
 * Uses POST requests with GraphHopper custom_model for camera penalty overlays.
 *
 * Two profiles:
 *   - car_fast_ch:      Fastest route via Contraction Hierarchies
 *   - car_dynamic_lm:   Privacy-optimized route via Landmarks (with ch.disable=true)
 *
 * Phase 2: Camera cone penalties sent as custom_model in the POST body.
 */
public class FlockFreeEngine extends JsonOnlineRoutingEngine {

	public static final String FLOCKFREE_TYPE = new FlockFreeEngine(null).getTypeName();

	private static final String DEFAULT_URL = "https://router.antonson.co/route";
	private static final String PROFILE_FAST = "car_fast_ch";
	private static final String PROFILE_PRIVACY = "car_dynamic_lm";

	private static final int MAX_CAMERA_PENALTY_AREAS = 500;

	// App reference for accessing camera data (set when this engine instance is used).
	@Nullable
	private OsmandApplication app;
	@Nullable
	private volatile String profileOverride;

	/** Override to set custom connect timeout (ms). 0 = use default. */
	public int getConnectTimeout() {
		return 10_000;
	}

	/** Override to set custom read timeout (ms). 0 = use default. */
	public int getReadTimeout() {
		return 30_000;
	}

	/** Set by FlockFreePlugin when user switches routes via comparison card */
	public void setViewingFastest(boolean viewing) {
		profileOverride = viewing ? PROFILE_FAST : PROFILE_PRIVACY;
	}

	/**
	 * Set the app context so getRequestBody can access camera data.
	 * Called by FlockFreePlugin before route calculation.
	 */
	public void setAppContext(@NonNull OsmandApplication app) {
		this.app = app;
	}

	public boolean isPrivacyProfile() {
		String profile = profileOverride != null ? profileOverride : getVehicleKeyForUrl();
		return PROFILE_PRIVACY.equals(profile);
	}

	// Camera cone parameters
	private static final double CONE_HALF_ANGLE = 60.0;  // degrees
	private static final double CONE_RANGE_FT = 300.0;   // feet

	public FlockFreeEngine(@Nullable Map<String, String> params) {
		super(params);
	}

	@NonNull
	@Override
	public OnlineRoutingEngine getType() {
		return EngineType.FLOCKFREE_TYPE;
	}

	@Override
	@NonNull
	public String getTitle() {
		return "FlockFree Router";
	}

	@NonNull
	@Override
	public String getTypeName() {
		return "FLOCKFREE";
	}

	@NonNull
	@Override
	public String getStandardUrl() {
		return DEFAULT_URL;
	}

	@Override
	public OnlineRoutingResponse responseByGpxFile(@NonNull OsmandApplication app, @NonNull GpxFile gpxFile, boolean initialCalculation, @Nullable RouteCalculationProgress calculationProgress) {
		return null;
	}

	@Override
	protected void collectAllowedParameters(@NonNull Set<EngineParameter> params) {
		params.add(EngineParameter.KEY);
		params.add(EngineParameter.VEHICLE_KEY);
		params.add(EngineParameter.CUSTOM_NAME);
		params.add(EngineParameter.NAME_INDEX);
		params.add(EngineParameter.CUSTOM_URL);
		params.add(EngineParameter.USE_ROUTING_FALLBACK);
	}

	@Override
	public OnlineRoutingEngine newInstance(Map<String, String> params) {
		return new FlockFreeEngine(params);
	}

	@Override
	protected void collectAllowedVehicles(@NonNull List<VehicleType> vehicles) {
		vehicles.add(new VehicleType(PROFILE_FAST, R.string.routing_engine_vehicle_type_car));
		vehicles.add(new VehicleType(PROFILE_PRIVACY, R.string.routing_engine_vehicle_type_car));
	}

	/**
	 * Use POST instead of GET so we can send the camera custom_model in the body.
	 */
	@Override
	public String getHTTPMethod() {
		return "POST";
	}

	@Override
	public Map<String, String> getRequestHeaders() {
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "application/json");
		return headers;
	}

	/**
	 * Build the POST body with points, profile, and camera penalty custom_model.
	 * The camera overlay is generated from the route's bounding box.
	 */
	@Override
	public String getRequestBody(@NonNull List<LatLon> path, @Nullable Float startBearing) throws JSONException {
		String profile = getVehicleKeyForUrl();
		if (isEmpty(profile)) {
			profile = PROFILE_FAST;
		}
		// If user switched to fastest via comparison card, override profile
		if (profileOverride != null) {
			profile = profileOverride;
		}

		JSONObject body = new JSONObject();
		JSONArray points = new JSONArray();
		for (LatLon point : path) {
			JSONArray coord = new JSONArray();
			coord.put(point.getLongitude());
			coord.put(point.getLatitude());
			points.put(coord);
		}
		body.put("points", points);
		body.put("profile", profile);
		body.put("points_encoded", true);

		// Privacy profile needs ch.disable=true (LM, not CH)
		if (PROFILE_PRIVACY.equals(profile)) {
			body.put("ch.disable", true);
		}

		// Add camera penalty custom_model for the privacy profile
		if (PROFILE_PRIVACY.equals(profile)) {
			JSONObject customModel = buildCameraPenaltyModel(path);
			if (customModel != null) {
				body.put("custom_model", customModel);
			}
		}

		return body.toString();
	}

	/**
	 * For POST requests, makeFullUrl just returns the base URL.
	 * The actual request parameters go in the body.
	 */
	@Override
	protected void makeFullUrl(@NonNull StringBuilder sb, @NonNull List<LatLon> path, @Nullable Float startBearing) {
		// Base URL is already set by getFullUrl() in the parent class
		// Nothing to append — params go in the POST body
	}

	// --- Camera Penalty Overlay ---

	private static final double RAMP_PENALTY = 0.1;
	private static final double ARTERIAL_PENALTY = 0.5;
	private static final double SURFACE_PENALTY = 0.7;

	/**
	 * Build GraphHopper custom_model with directional cone penalties for cameras
	 * near the route. Uses the app's CameraData (SQLite + OSM overlay) to find
	 * cameras in the route's bounding box, then creates cone-shaped polygon
	 * areas for each camera.
	 *
	 * Only applied for the privacy profile (car_dynamic_lm).
	 */
	@Nullable
	private JSONObject buildCameraPenaltyModel(@NonNull List<LatLon> path) {
		if (path.isEmpty()) return null;

		// getRequestBody does not receive a context, so OnlineRoutingHelper attaches
		// the application to this engine instance immediately before the request.
		OsmandApplication app = this.app;
		if (app == null) return null;

		// Get camera data from the FlockFree plugin
		FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
		if (plugin == null) return null;

		CameraData cameraData = plugin.getCameraData();
		if (cameraData == null || !cameraData.isDataLoaded()) return null;

		// Calculate route bounding box with padding
		double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
		double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
		for (LatLon p : path) {
			minLat = Math.min(minLat, p.getLatitude());
			maxLat = Math.max(maxLat, p.getLatitude());
			minLon = Math.min(minLon, p.getLongitude());
			maxLon = Math.max(maxLon, p.getLongitude());
		}
		// Pad by ~2km (0.02 degrees) to catch cameras near the route corridor
		double pad = 0.02;
		minLat -= pad; maxLat += pad;
		minLon -= pad; maxLon += pad;

		// Query cameras in the route's bounding box
		List<CameraData.CameraPoint> cameras = cameraData.getMergedCamerasInBoundingBox(
				maxLat, minLon, minLat, maxLon);
		if (Algorithms.isEmpty(cameras)) return null;
		if (cameras.size() > MAX_CAMERA_PENALTY_AREAS) {
			cameras = new ArrayList<>(cameras);
			cameras.sort(Comparator
					.comparingDouble((CameraData.CameraPoint camera) -> distanceToPath(camera, path))
					.thenComparingDouble(camera -> camera.lat)
					.thenComparingDouble(camera -> camera.lon));
			LOG.info("FlockFree online camera model trimmed from " + cameras.size()
					+ " to " + MAX_CAMERA_PENALTY_AREAS + " nearest route-corridor cameras");
			cameras = new ArrayList<>(cameras.subList(0, MAX_CAMERA_PENALTY_AREAS));
		}

		try {
			JSONObject customModel = new JSONObject();
			JSONObject areas = new JSONObject();
			JSONArray priority = new JSONArray();

			for (int i = 0; i < cameras.size(); i++) {
				CameraData.CameraPoint cam = cameras.get(i);
				String areaId = "cam_" + i;

				// Build cone polygon
				JSONArray polygon = makeConePolygon(
					cam.lon, cam.lat, cam.getBearing(),
					CONE_HALF_ANGLE, CONE_RANGE_FT);
				if (polygon == null) continue;

				// Create area feature
				JSONObject area = new JSONObject();
				area.put("type", "Feature");
				JSONObject geom = new JSONObject();
				geom.put("type", "Polygon");
				JSONArray coords = new JSONArray();
				coords.put(polygon);
				geom.put("coordinates", coords);
				area.put("geometry", geom);
				areas.put(areaId, area);

				// Priority rules within this cone
				// Ramps — strongest penalty
				priority.put(new JSONObject()
						.put("if", "in_" + areaId + " && road_class_link == true")
						.put("multiply_by", RAMP_PENALTY));
				// Arterials — moderate penalty
				priority.put(new JSONObject()
						.put("if", "in_" + areaId + " && (road_class == PRIMARY || road_class == SECONDARY)")
						.put("multiply_by", ARTERIAL_PENALTY));
				// Surface streets — mild penalty
				priority.put(new JSONObject()
						.put("if", "in_" + areaId + " && (road_class == TERTIARY || road_class == RESIDENTIAL || road_class == UNCLASSIFIED)")
						.put("multiply_by", SURFACE_PENALTY));
				// NO MOTORWAY penalty — cameras on ramps, not mainline
			}

			customModel.put("areas", areas);
			customModel.put("priority", priority);
			return customModel;
		} catch (JSONException e) {
			return null;
		}
	}

	private double distanceToPath(@NonNull CameraData.CameraPoint camera, @NonNull List<LatLon> path) {
		if (path.size() == 1) {
			LatLon point = path.get(0);
			return MapUtils.getDistance(camera.lat, camera.lon, point.getLatitude(), point.getLongitude());
		}
		double nearestDistance = Double.MAX_VALUE;
		for (int i = 1; i < path.size(); i++) {
			LatLon from = path.get(i - 1);
			LatLon to = path.get(i);
			nearestDistance = Math.min(nearestDistance, MapUtils.getOrthogonalDistance(
					camera.lat, camera.lon,
					from.getLatitude(), from.getLongitude(),
					to.getLatitude(), to.getLongitude()));
		}
		return nearestDistance;
	}

	/**
	 * Create a GeoJSON polygon representing a directional cone from a camera.
	 * If camera has no bearing (0), creates a full circle (omnidirectional).
	 * Returns array of [lon, lat] coordinates forming the cone polygon.
	 */
	@Nullable
	private JSONArray makeConePolygon(double lon, double lat, float bearing,
	                                   double halfAngle, double rangeFt) {
		try {
			JSONArray polygon = new JSONArray();
			int nPoints = 12;

			// Convert feet to degrees
			double rangeDegLat = rangeFt / 364000.0;
			double rangeDegLon = rangeFt / (364000.0 * Math.max(Math.cos(Math.toRadians(lat)), 0.01));

			if (bearing <= 0) {
				// Omnidirectional — full circle
				for (int i = 0; i < nPoints; i++) {
					double angle = 2 * Math.PI * i / nPoints;
					JSONArray pt = new JSONArray();
					pt.put(round(lon + rangeDegLon * Math.cos(angle)));
					pt.put(round(lat + rangeDegLat * Math.sin(angle)));
					polygon.put(pt);
				}
				// Close ring
				JSONArray first = (JSONArray) polygon.get(0);
				polygon.put(first);
			} else {
				// Directional cone — wedge from camera point
				// Apex at camera location
				JSONArray apex = new JSONArray();
				apex.put(round(lon));
				apex.put(round(lat));
				polygon.put(apex);

				double bearingRad = Math.toRadians(bearing);
				for (int i = 0; i <= nPoints; i++) {
					double angle = bearingRad + Math.toRadians(-halfAngle + 2 * halfAngle * i / nPoints);
					JSONArray pt = new JSONArray();
					pt.put(round(lon + rangeDegLon * Math.sin(angle)));
					pt.put(round(lat + rangeDegLat * Math.cos(angle)));
					polygon.put(pt);
				}

				// Close back to apex
				polygon.put(apex);
			}

			return polygon;
		} catch (JSONException e) {
			return null;
		}
	}

	private double round(double v) {
		return Math.round(v * 1e7) / 1e7;
	}

	@Nullable
	@Override
	public OnlineRoutingResponse responseByContent(@NonNull OsmandApplication app, @NonNull String content,
	                                               boolean leftSideNavigation, boolean initialCalculation,
	                                               @Nullable RouteCalculationProgress calculationProgress) throws JSONException {
		this.app = app;
		return super.responseByContent(app, content, leftSideNavigation, initialCalculation, calculationProgress);
	}

	@Nullable
	@Override
	protected OnlineRoutingResponse parseServerResponse(@NonNull JSONObject root,
	                                                    @NonNull OsmandApplication app,
	                                                    boolean leftSideNavigation) throws JSONException {
		// Handle both encoded and non-encoded points
		List<LatLon> points;
		if (root.has("points")) {
			Object pointsObj = root.get("points");
			if (pointsObj instanceof JSONObject) {
				// GeoJSON LineString: {"type": "LineString", "coordinates": [[lon, lat], ...]}
				JSONObject pointsJson = (JSONObject) pointsObj;
				JSONArray coords = pointsJson.getJSONArray("coordinates");
				points = new ArrayList<>();
				for (int i = 0; i < coords.length(); i++) {
					JSONArray coord = coords.getJSONArray(i);
					points.add(new LatLon(coord.getDouble(1), coord.getDouble(0)));
				}
			} else if (pointsObj instanceof JSONArray) {
				// Bare array: [[lon, lat], ...]
				JSONArray coords = (JSONArray) pointsObj;
				points = new ArrayList<>();
				for (int i = 0; i < coords.length(); i++) {
					JSONArray coord = coords.getJSONArray(i);
					points.add(new LatLon(coord.getDouble(1), coord.getDouble(0)));
				}
			} else {
				// Encoded polyline string
				String encoded = (String) pointsObj;
				points = GeoPolylineParserUtil.parse(encoded, GeoPolylineParserUtil.PRECISION_5);
			}
		} else {
			return null;
		}

		if (isEmpty(points) || points.size() < 2) return null;
		List<Location> route = convertRouteToLocationsList(points);

		// Parse turn-by-turn instructions
		JSONArray instructions = root.optJSONArray("instructions");
		List<RouteDirectionInfo> directions = new ArrayList<>();
		for (int i = 0; instructions != null && i < instructions.length(); i++) {
			JSONObject instruction = instructions.getJSONObject(i);
			int distance = (int) Math.round(instruction.optDouble("distance", 0));
			String description = instruction.optString("text", "");
			String streetName = instruction.optString("street_name", "");
			int timeInSeconds = Math.round((float) instruction.optLong("time", 0) / 1000f);
			JSONArray interval = instruction.optJSONArray("interval");
			if (interval == null || interval.length() < 1) {
				continue;
			}
			int startPointOffset = interval.optInt(0, -1);
			if (startPointOffset < 0 || startPointOffset >= route.size()) {
				continue;
			}

			float averageSpeed = timeInSeconds > 0 ? (float) distance / timeInSeconds : 0;
			TurnType turnType = parseTurnType(instruction, leftSideNavigation);
			RouteDirectionInfo direction = new RouteDirectionInfo(averageSpeed, turnType);

			direction.routePointOffset = startPointOffset;
			direction.setDescriptionRoute(description);
			direction.setStreetName(streetName);
			direction.setDistance(distance);
			directions.add(direction);
		}

		int routeDistanceMeters = (int) Math.round(root.optDouble("distance", -1));
		long routeTimeMilliseconds = root.optLong("time", -1);
		int routeTimeSeconds = routeTimeMilliseconds >= 0
				? (int) Math.round(routeTimeMilliseconds / 1000d) : -1;
		return new OnlineRoutingResponse(route, directions, routeDistanceMeters, routeTimeSeconds);
	}

	@NonNull
	private TurnType parseTurnType(@NonNull JSONObject instruction,
	                               boolean leftSide) throws JSONException {
		int sign = instruction.getInt("sign");
		TurnType turnType = GraphhopperEngine.identifyTurnType(sign, leftSide);

		if (turnType == null) {
			turnType = TurnType.straight();
		} else if (turnType.isRoundAbout()) {
			if (instruction.has("exit_number")) {
				turnType.setExitOut(instruction.getInt("exit_number"));
			}
			if (instruction.has("turn_angle")) {
				turnType.setTurnAngle((float) instruction.getDouble("turn_angle"));
			}
		}

		return turnType;
	}

	@NonNull
	@Override
	protected String getErrorMessageKey() {
		return "message";
	}

	@NonNull
	@Override
	protected String getRootArrayKey() {
		return "paths";
	}
}
