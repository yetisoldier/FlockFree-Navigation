package net.osmand.plus.plugins.flockfree;

import androidx.annotation.NonNull;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;

import org.apache.commons.logging.Log;

import java.util.List;

/**
 * Helps avoid known ALPR camera locations during route calculation.
 * Before route calculation, finds cameras within a configurable radius
 * of the route corridor and blocks nearby road segments.
 */
public class CameraAvoidanceHelper {

    private static final Log LOG = PlatformUtil.getLog(CameraAvoidanceHelper.class);

    private final OsmandApplication app;
    private final FlockFreePlugin plugin;

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
        List<CameraData.CameraPoint> result = new java.util.ArrayList<>();
        CameraData cameraData = plugin.getCameraData();
        if (!cameraData.isDataLoaded()) {
            return result;
        }

        for (LatLon point : routePoints) {
            List<CameraData.CameraPoint> nearby = cameraData.getCamerasNear(
                    point.getLatitude(), point.getLongitude(), radiusMeters);
            for (CameraData.CameraPoint cam : nearby) {
                if (!result.contains(cam)) {
                    result.add(cam);
                }
            }
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
        double minLat = Double.MAX_VALUE, maxLat = Double.MIN_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = Double.MIN_VALUE;
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

    /**
     * Returns a human-readable summary of cameras near the route.
     */
    @NonNull
    public String getRouteCameraSummary(@NonNull List<LatLon> routePoints) {
        if (!isAvoidanceEnabled()) {
            return "Camera avoidance disabled";
        }
        int radius = getAvoidanceRadius();
        List<CameraData.CameraPoint> cameras = findCamerasNearRoute(routePoints, radius);
        if (cameras.isEmpty()) {
            return "No cameras detected within " + radius + "m of route";
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
        sb.append(cameras.size()).append(" cameras near route (").append(radius).append("m corridor):\n");
        if (flock > 0) sb.append("  Flock Safety: ").append(flock).append("\n");
        if (motorola > 0) sb.append("  Motorola: ").append(motorola).append("\n");
        if (genetec > 0) sb.append("  Genetec: ").append(genetec).append("\n");
        if (other > 0) sb.append("  Other: ").append(other);
        return sb.toString().trim();
    }
}
