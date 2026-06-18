package net.osmand.plus.plugins.flockfree;

import androidx.annotation.NonNull;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RouteSegmentSearchResult;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Helps avoid known ALPR camera locations during route calculation.
 * Before route calculation, finds cameras within a configurable radius
 * of the route corridor and blocks nearby road segments.
 */
public class CameraAvoidanceHelper {

    private static final Log LOG = PlatformUtil.getLog(CameraAvoidanceHelper.class);

    public enum AvoidanceStatus {
        NONE,
        APPLIED,
        FALLBACK,
        SKIPPED_PARTIAL,
        SKIPPED_NO_DATA,
        SKIPPED_NO_ROAD_IDS
    }

    private final OsmandApplication app;
    private final FlockFreePlugin plugin;
    private AvoidanceStatus lastAvoidanceStatus = AvoidanceStatus.NONE;
    private int lastAvoidanceRoadCount;

    public CameraAvoidanceHelper(@NonNull OsmandApplication app, @NonNull FlockFreePlugin plugin) {
        this.app = app;
        this.plugin = plugin;
    }

    public boolean isAvoidanceEnabled() {
        return plugin.CAMERA_AVOIDANCE_ENABLED.get();
    }

    public int getAvoidanceRadius() {
        return plugin.CAMERA_AVOIDANCE_RADIUS.get();
    }

    public synchronized void recordAvoidanceApplied(int roadCount) {
        lastAvoidanceStatus = AvoidanceStatus.APPLIED;
        lastAvoidanceRoadCount = roadCount;
    }

    public synchronized void recordAvoidanceFallback(int roadCount) {
        lastAvoidanceStatus = AvoidanceStatus.FALLBACK;
        lastAvoidanceRoadCount = roadCount;
    }

    public synchronized void recordAvoidanceSkipped(@NonNull AvoidanceStatus status) {
        lastAvoidanceStatus = status;
        lastAvoidanceRoadCount = 0;
    }

    @NonNull
    public synchronized String consumeLastAvoidanceStatusSummary() {
        String summary;
        switch (lastAvoidanceStatus) {
            case APPLIED:
                summary = app.getString(R.string.flockfree_route_status_applied, lastAvoidanceRoadCount);
                break;
            case FALLBACK:
                summary = app.getString(R.string.flockfree_route_status_fallback, lastAvoidanceRoadCount);
                break;
            case SKIPPED_PARTIAL:
                summary = app.getString(R.string.flockfree_route_status_skipped_partial);
                break;
            case SKIPPED_NO_DATA:
                summary = app.getString(R.string.flockfree_route_status_no_data);
                break;
            case SKIPPED_NO_ROAD_IDS:
                summary = app.getString(R.string.flockfree_route_status_no_road_ids);
                break;
            case NONE:
            default:
                summary = "";
                break;
        }
        lastAvoidanceStatus = AvoidanceStatus.NONE;
        lastAvoidanceRoadCount = 0;
        return summary;
    }

    /**
     * Returns cameras that fall within the given route corridor.
     * The corridor is defined by a series of waypoints and a radius.
     *
     * @param routePoints  List of LatLon points along the route
     * @param radiusMeters  Radius in meters to search around the route
     * @return List of cameras near the route
     */
    @NonNull
    public List<CameraData.CameraPoint> findCamerasNearRoute(@NonNull List<LatLon> routePoints, int radiusMeters) {
        List<CameraData.CameraPoint> result = new ArrayList<>();
        CameraData cameraData = plugin.getCameraData();
        if (!cameraData.isDataLoaded() || routePoints.isEmpty()) {
            return result;
        }

        double[] bounds = getRouteCorridorBounds(routePoints, radiusMeters);
        List<CameraData.CameraPoint> candidates = cameraData.getCamerasInBoundingBox(
                bounds[0], bounds[1], bounds[2], bounds[3]);
        for (CameraData.CameraPoint cam : candidates) {
            if (isCameraNearRoute(cam, routePoints, radiusMeters)) {
                result.add(cam);
            }
        }
        return result;
    }

    @NonNull
    public List<CameraData.CameraPoint> findCamerasNearRouteLocations(@NonNull List<Location> routeLocations,
                                                                      int radiusMeters) {
        List<LatLon> routePoints = new ArrayList<>(routeLocations.size());
        for (Location location : routeLocations) {
            routePoints.add(new LatLon(location.getLatitude(), location.getLongitude()));
        }
        return findCamerasNearRoute(routePoints, radiusMeters);
    }

    @NonNull
    public Set<Long> collectAvoidRoadIdsForRoute(@NonNull RouteCalculationResult route, int radiusMeters) {
        Set<Long> result = new LinkedHashSet<>();
        CameraData cameraData = plugin.getCameraData();
        if (!isAvoidanceEnabled() || !cameraData.isDataLoaded()) {
            return result;
        }

        List<RouteSegmentResult> roads = route.getOriginalRoute();
        List<Location> locations = route.getImmutableAllLocations();
        if (roads == null || roads.size() < 3 || locations == null || locations.isEmpty()) {
            return result;
        }

        List<CameraData.CameraPoint> cameras = findCamerasNearRouteLocations(locations, radiusMeters);
        for (CameraData.CameraPoint camera : cameras) {
            RouteSegmentSearchResult searchResult = RouteSegmentSearchResult.searchRouteSegment(
                    camera.lat, camera.lon, radiusMeters, roads);
            if (searchResult == null) {
                continue;
            }
            int roadIndex = searchResult.getRoadIndex();
            if (roadIndex <= 0 || roadIndex >= roads.size() - 1) {
                continue;
            }
            RouteDataObject object = roads.get(roadIndex).getObject();
            if (object != null) {
                result.add(object.getId());
            }
        }
        if (!result.isEmpty()) {
            LOG.info("FlockFree collected " + result.size() + " temporary avoid road ids");
        }
        return result;
    }

    /**
     * Returns the bounding box of a route expanded by the avoidance radius.
     * This can be used as a pre-filter before precise distance checks.
     */
    public double[] getRouteCorridorBounds(@NonNull List<LatLon> routePoints, int radiusMeters) {
        if (routePoints.isEmpty()) {
            return new double[]{0, 0, 0, 0};
        }
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (LatLon p : routePoints) {
            minLat = Math.min(minLat, p.getLatitude());
            maxLat = Math.max(maxLat, p.getLatitude());
            minLon = Math.min(minLon, p.getLongitude());
            maxLon = Math.max(maxLon, p.getLongitude());
        }
        // Expand by radius in degrees (approximate)
        double latOffset = radiusMeters / 111000.0;
        double lonOffset = radiusMeters / (111000.0 * Math.cos(Math.toRadians((minLat + maxLat) / 2)));
        return new double[]{
                maxLat + latOffset,  // top
                minLon - lonOffset,  // left
                minLat - latOffset,   // bottom
                maxLon + lonOffset    // right
        };
    }

    private boolean isCameraNearRoute(@NonNull CameraData.CameraPoint cam, @NonNull List<LatLon> routePoints,
                                      int radiusMeters) {
        if (routePoints.size() == 1) {
            LatLon point = routePoints.get(0);
            return MapUtils.getDistance(point.getLatitude(), point.getLongitude(), cam.lat, cam.lon) <= radiusMeters;
        }
        for (int i = 1; i < routePoints.size(); i++) {
            LatLon from = routePoints.get(i - 1);
            LatLon to = routePoints.get(i);
            double distance = MapUtils.getOrthogonalDistance(
                    cam.lat, cam.lon,
                    from.getLatitude(), from.getLongitude(),
                    to.getLatitude(), to.getLongitude());
            if (distance <= radiusMeters) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a human-readable summary of cameras near the route.
     */
    @NonNull
    public String getRouteCameraSummary(@NonNull List<LatLon> routePoints) {
        if (!isAvoidanceEnabled()) {
            return app.getString(R.string.flockfree_route_avoidance_disabled);
        }
        int radius = getAvoidanceRadius();
        List<CameraData.CameraPoint> cameras = findCamerasNearRoute(routePoints, radius);
        return formatRouteCameraSummary(cameras, radius);
    }

    @NonNull
    public String getRouteCameraSummaryFromLocations(@NonNull List<Location> routeLocations) {
        if (!isAvoidanceEnabled()) {
            return app.getString(R.string.flockfree_route_avoidance_disabled);
        }
        int radius = getAvoidanceRadius();
        List<CameraData.CameraPoint> cameras = findCamerasNearRouteLocations(routeLocations, radius);
        return formatRouteCameraSummary(cameras, radius);
    }

    @NonNull
    private String formatRouteCameraSummary(@NonNull List<CameraData.CameraPoint> cameras, int radius) {
        if (cameras.isEmpty()) {
            return app.getString(R.string.flockfree_route_no_cameras_summary, radius);
        }
        int flock = 0, motorola = 0, genetec = 0, other = 0;
        for (CameraData.CameraPoint cam : cameras) {
            String brand = cam.brand != null ? cam.brand.toLowerCase() : "";
            if (brand.contains("flock")) flock++;
            else if (brand.contains("motorola")) motorola++;
            else if (brand.contains("genetec")) genetec++;
            else other++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(app.getString(R.string.flockfree_route_cameras_summary, cameras.size(), radius)).append("\n");
        if (flock > 0) appendCount(sb, R.string.flockfree_route_vendor_flock, flock);
        if (motorola > 0) appendCount(sb, R.string.flockfree_route_vendor_motorola, motorola);
        if (genetec > 0) appendCount(sb, R.string.flockfree_route_vendor_genetec, genetec);
        if (other > 0) appendCount(sb, R.string.flockfree_route_vendor_other, other);
        return sb.toString().trim();
    }

    private void appendCount(@NonNull StringBuilder sb, int labelId, int count) {
        sb.append("  ").append(app.getString(labelId)).append(": ").append(count).append("\n");
    }
}
