package net.osmand.plus.plugins.flockfree;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.data.QuadPointDouble;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RouteSegmentSearchResult;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.Locale;
import java.util.Collections;
import java.util.Objects;

/**
 * Helps avoid known Flock camera locations during route calculation.
 * Before route calculation, finds Flock cameras within a configurable radius
 * of the route corridor and blocks nearby road segments.
 */
public class CameraAvoidanceHelper {

    private static final Log LOG = PlatformUtil.getLog(CameraAvoidanceHelper.class);
    private static final int UNKNOWN_ROUTE_TIME_SECONDS = -1;
    private static final double ROUTE_INDEX_CELL_DEGREES = 0.01d;
    private static final int MAX_ROUTE_INDEX_ASSIGNMENTS = 200_000;

    /**
     * Half-window in degrees for direction-aware camera filtering.
     * A camera blocks a road segment only if the route bearing is within ±this many degrees
     * of the camera's facing bearing. Set to 60 for a 120° total acceptance window.
     * When camera bearing is unavailable (0), falls back to omnidirectional blocking.
     */
    private static final float DIRECTION_MATCH_WINDOW_DEGREES = 60f;

    public enum AvoidanceStatus {
        NONE,
        APPLIED,
        PARTIAL_APPLIED,
        FALLBACK,
        SKIPPED_PARTIAL,
        SKIPPED_NO_DATA,
        SKIPPED_NO_ROAD_IDS
    }

    /**
     * Pairs a road ID with the count of Flock cameras found on/near that road.
     * Used by RouteProvider for iterative relaxation: unblock roads with
     * the fewest cameras first when full avoidance fails.
     */
    public static class RoadWithCameraCount {
        public final long roadId;
        public final int cameraCount;

        public RoadWithCameraCount(long roadId, int cameraCount) {
            this.roadId = roadId;
            this.cameraCount = cameraCount;
        }
    }

    // ---- Route-association cache ----

    private static final int MAX_CACHE_ENTRIES = 20;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    /** Cache key: route fingerprint + dataset version + avoidance radius. */
    private static final class RouteCacheKey {
        final String routeHash;
        final int datasetVersion;
        final int radiusMeters;

        RouteCacheKey(String routeHash, int datasetVersion, int radiusMeters) {
            this.routeHash = routeHash;
            this.datasetVersion = datasetVersion;
            this.radiusMeters = radiusMeters;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RouteCacheKey)) return false;
            RouteCacheKey k = (RouteCacheKey) o;
            return datasetVersion == k.datasetVersion
                    && radiusMeters == k.radiusMeters
                    && Objects.equals(routeHash, k.routeHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(routeHash, datasetVersion, radiusMeters);
        }
    }

    /** Cached camera-to-road associations for a single route. */
    private static final class CameraRoadAssociations {
        final List<RoadWithCameraCount> roadsWithCameras;
        final int cameraCount;
        final long createdAtMs;

        CameraRoadAssociations(List<RoadWithCameraCount> roadsWithCameras, int cameraCount, long createdAtMs) {
            this.roadsWithCameras = roadsWithCameras;
            this.cameraCount = cameraCount;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class RouteEdge {
        final LatLon from;
        final LatLon to;

        RouteEdge(@NonNull LatLon from, @NonNull LatLon to) {
            this.from = from;
            this.to = to;
        }
    }

    /**
     * Exact route-corridor checks still use MapUtils; this index only narrows the
     * edge candidates that need that relatively expensive calculation.
     */
    private static final class RouteEdgeIndex {
        final List<RouteEdge> allEdges = new ArrayList<>();
        final Map<Long, List<RouteEdge>> edgeGrid = new HashMap<>();
        boolean usable = true;

        RouteEdgeIndex(@NonNull List<LatLon> routePoints, int radiusMeters) {
            double latOffset = radiusMeters / 100_000d;
            int assignmentCount = 0;
            for (int i = 1; i < routePoints.size(); i++) {
                LatLon from = routePoints.get(i - 1);
                LatLon to = routePoints.get(i);
                RouteEdge edge = new RouteEdge(from, to);
                allEdges.add(edge);

                if (!usable || Math.abs(from.getLongitude() - to.getLongitude()) > 180d) {
                    usable = false;
                    continue;
                }
                double maxAbsLat = Math.min(89.9d, Math.max(
                        Math.abs(from.getLatitude()), Math.abs(to.getLatitude())) + latOffset);
                double cosLatitude = Math.max(0.01d, Math.cos(Math.toRadians(maxAbsLat)));
                double lonOffset = radiusMeters / (100_000d * cosLatitude);
                int minLatBucket = routeIndexBucket(
                        Math.min(from.getLatitude(), to.getLatitude()) - latOffset, 90d);
                int maxLatBucket = routeIndexBucket(
                        Math.max(from.getLatitude(), to.getLatitude()) + latOffset, 90d);
                int minLonBucket = routeIndexBucket(
                        Math.min(from.getLongitude(), to.getLongitude()) - lonOffset, 180d);
                int maxLonBucket = routeIndexBucket(
                        Math.max(from.getLongitude(), to.getLongitude()) + lonOffset, 180d);
                long edgeAssignments = (long) (maxLatBucket - minLatBucket + 1)
                        * (maxLonBucket - minLonBucket + 1);
                if (edgeAssignments > MAX_ROUTE_INDEX_ASSIGNMENTS
                        || assignmentCount + edgeAssignments > MAX_ROUTE_INDEX_ASSIGNMENTS) {
                    usable = false;
                    edgeGrid.clear();
                    continue;
                }
                assignmentCount += (int) edgeAssignments;
                for (int latBucket = minLatBucket; latBucket <= maxLatBucket; latBucket++) {
                    for (int lonBucket = minLonBucket; lonBucket <= maxLonBucket; lonBucket++) {
                        edgeGrid.computeIfAbsent(routeIndexKey(latBucket, lonBucket), ignored -> new ArrayList<>())
                                .add(edge);
                    }
                }
            }
            if (!usable) {
                edgeGrid.clear();
            }
        }

        @NonNull
        List<RouteEdge> getCandidateEdges(double lat, double lon) {
            if (!usable) {
                return allEdges;
            }
            List<RouteEdge> candidates = edgeGrid.get(routeIndexKey(
                    routeIndexBucket(lat, 90d), routeIndexBucket(lon, 180d)));
            return candidates != null ? candidates : Collections.emptyList();
        }

        private static int routeIndexBucket(double coordinate, double offset) {
            return (int) Math.floor((coordinate + offset) / ROUTE_INDEX_CELL_DEGREES);
        }

        private static long routeIndexKey(int latBucket, int lonBucket) {
            return ((long) latBucket << 32) ^ (lonBucket & 0xffffffffL);
        }
    }

    /** Spatial prefilter for route road geometries; exact 31-bit geometry checks remain authoritative. */
    private static final class RouteRoadIndex {
        final List<Integer> allRoadIndexes = new ArrayList<>();
        final Map<Long, List<Integer>> roadGrid = new HashMap<>();
        boolean usable = true;

        RouteRoadIndex(@NonNull List<RouteSegmentResult> roads, int radiusMeters) {
            int assignmentCount = 0;
            for (int roadIndex = 1; roadIndex < roads.size() - 1; roadIndex++) {
                RouteSegmentResult road = roads.get(roadIndex);
                RouteDataObject object = road.getObject();
                if (object == null) {
                    continue;
                }
                allRoadIndexes.add(roadIndex);
                if (!usable) {
                    continue;
                }

                int start = Math.min(road.getStartPointIndex(), road.getEndPointIndex());
                int end = Math.max(road.getStartPointIndex(), road.getEndPointIndex());
                double minLat = Double.MAX_VALUE;
                double maxLat = -Double.MAX_VALUE;
                double minLon = Double.MAX_VALUE;
                double maxLon = -Double.MAX_VALUE;
                for (int pointIndex = start; pointIndex <= end; pointIndex++) {
                    double lat = MapUtils.get31LatitudeY(object.getPoint31YTile(pointIndex));
                    double lon = MapUtils.get31LongitudeX(object.getPoint31XTile(pointIndex));
                    minLat = Math.min(minLat, lat);
                    maxLat = Math.max(maxLat, lat);
                    minLon = Math.min(minLon, lon);
                    maxLon = Math.max(maxLon, lon);
                }
                if (maxLon - minLon > 180d) {
                    usable = false;
                    roadGrid.clear();
                    continue;
                }

                double latOffset = radiusMeters / 100_000d;
                double maxAbsLat = Math.min(89.9d, Math.max(Math.abs(minLat), Math.abs(maxLat)) + latOffset);
                double cosLatitude = Math.max(0.01d, Math.cos(Math.toRadians(maxAbsLat)));
                double lonOffset = radiusMeters / (100_000d * cosLatitude);
                int minLatBucket = roadIndexBucket(minLat - latOffset, 90d);
                int maxLatBucket = roadIndexBucket(maxLat + latOffset, 90d);
                int minLonBucket = roadIndexBucket(minLon - lonOffset, 180d);
                int maxLonBucket = roadIndexBucket(maxLon + lonOffset, 180d);
                long roadAssignments = (long) (maxLatBucket - minLatBucket + 1)
                        * (maxLonBucket - minLonBucket + 1);
                if (roadAssignments > MAX_ROUTE_INDEX_ASSIGNMENTS
                        || assignmentCount + roadAssignments > MAX_ROUTE_INDEX_ASSIGNMENTS) {
                    usable = false;
                    roadGrid.clear();
                    continue;
                }
                assignmentCount += (int) roadAssignments;
                for (int latBucket = minLatBucket; latBucket <= maxLatBucket; latBucket++) {
                    for (int lonBucket = minLonBucket; lonBucket <= maxLonBucket; lonBucket++) {
                        roadGrid.computeIfAbsent(routeIndexKey(latBucket, lonBucket), ignored -> new ArrayList<>())
                                .add(roadIndex);
                    }
                }
            }
            if (!usable) {
                roadGrid.clear();
            }
        }

        @NonNull
        List<Integer> getCandidateRoadIndexes(double lat, double lon) {
            if (!usable) {
                return allRoadIndexes;
            }
            List<Integer> candidates = roadGrid.get(routeIndexKey(
                    roadIndexBucket(lat, 90d), roadIndexBucket(lon, 180d)));
            return candidates != null ? candidates : Collections.emptyList();
        }

        private static int roadIndexBucket(double coordinate, double offset) {
            return (int) Math.floor((coordinate + offset) / ROUTE_INDEX_CELL_DEGREES);
        }

        private static long routeIndexKey(int latBucket, int lonBucket) {
            return ((long) latBucket << 32) ^ (lonBucket & 0xffffffffL);
        }
    }

    private final LinkedHashMap<RouteCacheKey, CameraRoadAssociations> associationCache =
            new LinkedHashMap<RouteCacheKey, CameraRoadAssociations>(MAX_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RouteCacheKey, CameraRoadAssociations> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };

    // ---- End route-association cache ----

    private final OsmandApplication app;
    private final FlockFreePlugin plugin;
    private AvoidanceStatus lastAvoidanceStatus = AvoidanceStatus.NONE;
    private int lastAvoidanceRoadCount;
    private int lastAvoidanceCameraCount;
    private int lastAvoidanceOriginalTimeSeconds = UNKNOWN_ROUTE_TIME_SECONDS;
    private int lastAvoidanceOriginalDistanceMeters;
    private int lastAvoidanceOriginalCameraCount;
    private int lastPartialBlockedRoadCount;
    private int lastPartialTotalCameraRoadCount;
    private int lastPartialRemainingCameraCount;

    public CameraAvoidanceHelper(@NonNull OsmandApplication app, @NonNull FlockFreePlugin plugin) {
        this.app = app;
        this.plugin = plugin;
    }

    public boolean isAvoidanceEnabled() {
        return plugin.isCameraAvoidanceActive();
    }

    public int getAvoidanceRadius() {
        return plugin.CAMERA_AVOIDANCE_RADIUS.get();
    }

    public synchronized void recordAvoidanceApplied(int roadCount) {
        recordAvoidanceApplied(roadCount, 0, UNKNOWN_ROUTE_TIME_SECONDS);
    }

    public synchronized void recordAvoidanceApplied(int roadCount, int cameraCount) {
        recordAvoidanceApplied(roadCount, cameraCount, UNKNOWN_ROUTE_TIME_SECONDS);
    }

    public synchronized void recordAvoidanceApplied(int roadCount, int cameraCount, int originalRouteTimeSeconds) {
        recordAvoidanceApplied(roadCount, cameraCount, originalRouteTimeSeconds, 0);
    }

    public synchronized void recordAvoidanceApplied(int roadCount, int cameraCount, int originalRouteTimeSeconds,
                                                    int originalRouteDistanceMeters) {
        recordAvoidanceApplied(roadCount, cameraCount, 0, originalRouteTimeSeconds, originalRouteDistanceMeters);
    }

    public synchronized void recordAvoidanceApplied(int roadCount, int originalCameraCount, int routeCameraCount,
                                                    int originalRouteTimeSeconds,
                                                    int originalRouteDistanceMeters) {
        lastAvoidanceStatus = AvoidanceStatus.APPLIED;
        lastAvoidanceRoadCount = roadCount;
        lastAvoidanceCameraCount = Math.max(0, originalCameraCount - routeCameraCount);
        lastAvoidanceOriginalTimeSeconds = originalRouteTimeSeconds;
        lastAvoidanceOriginalDistanceMeters = Math.max(0, originalRouteDistanceMeters);
        lastAvoidanceOriginalCameraCount = Math.max(0, originalCameraCount);
        lastPartialBlockedRoadCount = 0;
        lastPartialTotalCameraRoadCount = 0;
        lastPartialRemainingCameraCount = 0;
    }

    public synchronized void recordAvoidanceFallback(int roadCount, int originalCameraCount,
                                                       int originalRouteTimeSeconds,
                                                       int originalRouteDistanceMeters) {
        lastAvoidanceStatus = AvoidanceStatus.FALLBACK;
        lastAvoidanceRoadCount = roadCount;
        lastAvoidanceCameraCount = 0;
        lastAvoidanceOriginalTimeSeconds = Math.max(0, originalRouteTimeSeconds);
        lastAvoidanceOriginalDistanceMeters = Math.max(0, originalRouteDistanceMeters);
        lastAvoidanceOriginalCameraCount = Math.max(0, originalCameraCount);
        lastPartialBlockedRoadCount = 0;
        lastPartialTotalCameraRoadCount = 0;
        lastPartialRemainingCameraCount = 0;
    }

    public synchronized void recordAvoidanceFallback(int roadCount) {
        recordAvoidanceFallback(roadCount, 0, UNKNOWN_ROUTE_TIME_SECONDS, 0);
    }

    public synchronized void recordAvoidanceSkipped(@NonNull AvoidanceStatus status) {
        lastAvoidanceStatus = status;
        lastAvoidanceRoadCount = 0;
        lastAvoidanceCameraCount = 0;
        lastAvoidanceOriginalTimeSeconds = UNKNOWN_ROUTE_TIME_SECONDS;
        lastAvoidanceOriginalDistanceMeters = 0;
        lastAvoidanceOriginalCameraCount = 0;
        lastPartialBlockedRoadCount = 0;
        lastPartialTotalCameraRoadCount = 0;
        lastPartialRemainingCameraCount = 0;
    }

    /**
     * Records that partial (iterative relaxation) avoidance was applied.
     *
     * @param blockedRoadCount      Number of Flock-camera-adjacent roads that remain blocked
     * @param totalCameraRoadCount  Total number of distinct Flock-camera-adjacent roads originally identified
     * @param remainingCameraCount  Actual Flock camera exposures still on/near the accepted route
     */
    public synchronized void recordAvoidancePartial(int blockedRoadCount, int totalCameraRoadCount, int remainingCameraCount) {
        recordAvoidancePartial(blockedRoadCount, totalCameraRoadCount, remainingCameraCount,
                0, UNKNOWN_ROUTE_TIME_SECONDS);
    }

    public synchronized void recordAvoidancePartial(int blockedRoadCount, int totalCameraRoadCount,
                                                    int remainingCameraCount, int originalCameraCount,
                                                    int originalRouteTimeSeconds) {
        recordAvoidancePartial(blockedRoadCount, totalCameraRoadCount, remainingCameraCount,
                originalCameraCount, originalRouteTimeSeconds, 0);
    }

    public synchronized void recordAvoidancePartial(int blockedRoadCount, int totalCameraRoadCount,
                                                    int remainingCameraCount, int originalCameraCount,
                                                    int originalRouteTimeSeconds, int originalRouteDistanceMeters) {
        lastAvoidanceStatus = AvoidanceStatus.PARTIAL_APPLIED;
        lastAvoidanceRoadCount = blockedRoadCount;
        lastAvoidanceCameraCount = Math.max(0, originalCameraCount - remainingCameraCount);
        lastAvoidanceOriginalTimeSeconds = originalRouteTimeSeconds;
        lastAvoidanceOriginalDistanceMeters = Math.max(0, originalRouteDistanceMeters);
        lastAvoidanceOriginalCameraCount = Math.max(0, originalCameraCount);
        lastPartialBlockedRoadCount = blockedRoadCount;
        lastPartialTotalCameraRoadCount = totalCameraRoadCount;
        lastPartialRemainingCameraCount = Math.max(0, remainingCameraCount);
    }

    @NonNull
    public synchronized String consumeLastAvoidanceStatusSummary() {
        String summary;
        switch (lastAvoidanceStatus) {
            case APPLIED:
                summary = app.getString(R.string.flockfree_route_status_applied, lastAvoidanceRoadCount);
                break;
            case PARTIAL_APPLIED:
                summary = app.getString(R.string.flockfree_route_status_partial_applied,
                        lastPartialBlockedRoadCount, lastPartialTotalCameraRoadCount, lastPartialRemainingCameraCount);
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
        lastAvoidanceCameraCount = 0;
        lastAvoidanceOriginalTimeSeconds = UNKNOWN_ROUTE_TIME_SECONDS;
        lastAvoidanceOriginalDistanceMeters = 0;
        lastAvoidanceOriginalCameraCount = 0;
        lastPartialBlockedRoadCount = 0;
        lastPartialTotalCameraRoadCount = 0;
        lastPartialRemainingCameraCount = 0;
        return summary;
    }

    /**
     * Returns a concise tradeoff string like "Avoids 5 cameras" if avoidance was applied,
     * or null if no avoidance was applied or the camera count is zero.
     */
    @Nullable
    public synchronized String getAvoidanceTradeoffSummary() {
        if ((lastAvoidanceStatus == AvoidanceStatus.APPLIED
                || lastAvoidanceStatus == AvoidanceStatus.PARTIAL_APPLIED)
                && lastAvoidanceCameraCount > 0) {
            return app.getResources().getQuantityString(R.plurals.flockfree_route_tradeoff_avoids_cameras,
                    lastAvoidanceCameraCount, lastAvoidanceCameraCount);
        }
        return null;
    }

    public synchronized int getLastAvoidanceOriginalTimeSeconds() {
        return lastAvoidanceOriginalTimeSeconds;
    }

    public synchronized int getLastAvoidanceOriginalDistanceMeters() {
        return lastAvoidanceOriginalDistanceMeters;
    }

    public synchronized int getLastAvoidanceOriginalCameraCount() {
        return lastAvoidanceOriginalCameraCount;
    }

    public synchronized boolean hasLastAvoidanceComparison() {
        return (lastAvoidanceStatus == AvoidanceStatus.APPLIED
                || lastAvoidanceStatus == AvoidanceStatus.PARTIAL_APPLIED
                || lastAvoidanceStatus == AvoidanceStatus.FALLBACK)
                && lastAvoidanceOriginalCameraCount > 0
                && lastAvoidanceOriginalTimeSeconds > 0
                && lastAvoidanceOriginalDistanceMeters > 0;
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
        if (candidates.isEmpty()) {
            return result;
        }
        RouteEdgeIndex routeEdgeIndex = routePoints.size() > 1
                ? new RouteEdgeIndex(routePoints, radiusMeters) : null;
        for (CameraData.CameraPoint cam : candidates) {
            if (isCameraNearRoute(cam, routePoints, routeEdgeIndex, radiusMeters)) {
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
        RouteRoadIndex roadIndex = cameras.isEmpty() ? null : new RouteRoadIndex(roads, radiusMeters);
        for (CameraData.CameraPoint camera : cameras) {
            int cameraX31 = MapUtils.get31TileNumberX(camera.lon);
            int cameraY31 = MapUtils.get31TileNumberY(camera.lat);
            boolean matchedAny = false;
            for (int i : roadIndex.getCandidateRoadIndexes(camera.lat, camera.lon)) {
                RouteSegmentResult road = roads.get(i);
                RouteDataObject obj = road.getObject();
                if (obj == null) {
                    continue;
                }
                if (isCameraNearRoadGeometry(road, cameraX31, cameraY31, radiusMeters)) {
                    result.add(obj.getId());
                    matchedAny = true;
                }
            }
            if (!matchedAny) {
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
        }
        if (!result.isEmpty()) {
            LOG.info("FlockFree collected " + result.size() + " temporary avoid road ids");
        }
        return result;
    }

    /**
     * Computes a compact hash string from the route's segment road IDs and their order.
     * This creates a unique fingerprint for a route so that identical routes can reuse
     * cached camera-to-road associations without recomputation.
     *
     * @param roads the route segments
     * @return a hash string, or null if roads is null/empty
     */
    @Nullable
    private String hashRouteSegments(@NonNull List<RouteSegmentResult> roads) {
        if (roads.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < roads.size(); i++) {
            RouteSegmentResult road = roads.get(i);
            RouteDataObject obj = road.getObject();
            long roadId = obj != null ? obj.getId() : 0L;
            sb.append(roadId).append('|');
        }
        return Integer.toHexString(sb.hashCode());
    }

    /**
     * Returns a dataset version proxy: camera count + hourly bucket of current time.
     * Since CameraData does not expose a version number, this combination ensures
     * the cache invalidates when camera data changes (count changes) or after one hour
     * (time bucket changes).
     *
     * @param cameraData the camera data source
     * @return an integer representing the current dataset version
     */
    private int getDatasetVersion(@NonNull CameraData cameraData) {
        int cameraCount = cameraData.getCameraCount();
        long hourBucket = System.currentTimeMillis() / (60 * 60 * 1000L);
        return Objects.hash(cameraCount, hourBucket);
    }

    /**
     * Clears the route-association cache. Should be called when camera data is refreshed
     * or when the avoidance radius changes.
     */
    public void clearAssociationCache() {
        synchronized (associationCache) {
            associationCache.clear();
        }
        LOG.info("FlockFree route-association cache cleared");
    }

    /**
     * Collects Flock-camera-adjacent road IDs paired with the number of cameras that mapped to each road.
     * The list is sorted by cameraCount DESCENDING (most Flock cameras first), so the RouteProvider
     * can iteratively unblock the least-impactful roads first when full avoidance fails.
     *
     * Results are cached keyed by route fingerprint + dataset version + radius, with a 5-minute TTL
     * and LRU eviction at 20 entries.
     *
     * @param route        The calculated route
     * @param radiusMeters  Radius in meters to search around the route
     * @return List of RoadWithCameraCount sorted by cameraCount descending
     */
    @NonNull
    public List<RoadWithCameraCount> collectAvoidRoadIdsWithCameraCountForRoute(
            @NonNull RouteCalculationResult route, int radiusMeters) {
        CameraData cameraData = plugin.getCameraData();
        if (!isAvoidanceEnabled() || !cameraData.isDataLoaded()) {
            return new ArrayList<>();
        }

        List<RouteSegmentResult> roads = route.getOriginalRoute();
        List<Location> locations = route.getImmutableAllLocations();
        if (roads == null || roads.size() < 3 || locations == null || locations.isEmpty()) {
            return new ArrayList<>();
        }

        // --- Cache lookup ---
        String routeHash = hashRouteSegments(roads);
        int datasetVersion = getDatasetVersion(cameraData);
        if (routeHash != null) {
            RouteCacheKey cacheKey = new RouteCacheKey(routeHash, datasetVersion, radiusMeters);
            synchronized (associationCache) {
                CameraRoadAssociations cached = associationCache.get(cacheKey);
                if (cached != null) {
                    long age = System.currentTimeMillis() - cached.createdAtMs;
                    if (age < CACHE_TTL_MS) {
                        LOG.info("FlockFree route-association cache HIT (age=" + age
                                + "ms, entries=" + associationCache.size() + ")");
                        return new ArrayList<>(cached.roadsWithCameras);
                    } else {
                        associationCache.remove(cacheKey);
                        LOG.info("FlockFree route-association cache STALE (age=" + age + "ms, evicting)");
                    }
                }
            }
        }
        // --- End cache lookup ---

        List<CameraData.CameraPoint> cameras = findCamerasNearRouteLocations(locations, radiusMeters);

        // Map each road ID to the count of Flock cameras that matched it.
        // For each Flock camera, block ALL route segments within the radius, not just
        // the single nearest one. This prevents the router from simply using
        // an adjacent road in the same corridor and still passing near the camera.
        // Direction-aware filtering: if the camera has a bearing (1-360), only block
        // road segments whose travel direction is within ±DIRECTION_MATCH_WINDOW_DEGREES
        // of the camera's facing bearing. If the camera has no bearing data (0),
        // fall back to omnidirectional blocking (block all segments within radius).
        Map<Long, Integer> roadIdToCameraCount = new HashMap<>();
        int directionFilteredCount = 0;
        int omnidirectionalCount = 0;
        RouteRoadIndex roadIndex = cameras.isEmpty() ? null : new RouteRoadIndex(roads, radiusMeters);
        for (CameraData.CameraPoint camera : cameras) {
            int cameraX31 = MapUtils.get31TileNumberX(camera.lon);
            int cameraY31 = MapUtils.get31TileNumberY(camera.lat);
            float cameraBearing = camera.getBearing();
            boolean hasBearing = cameraBearing > 0f;
            boolean matchedAny = false;
            for (int i : roadIndex.getCandidateRoadIndexes(camera.lat, camera.lon)) {
                RouteSegmentResult road = roads.get(i);
                RouteDataObject obj = road.getObject();
                if (obj == null) {
                    continue;
                }
                if (isCameraNearRoadGeometry(road, cameraX31, cameraY31, radiusMeters)) {
                    // Direction-aware filtering: skip this road segment if the camera has a bearing
                    // and the route travel direction does not match the camera's facing direction.
                    if (hasBearing) {
                        float routeBearing = computeSegmentBearing(road);
                        if (!isCameraFacingRouteSegment(cameraBearing, routeBearing, DIRECTION_MATCH_WINDOW_DEGREES)) {
                            directionFilteredCount++;
                            continue;
                        }
                    }
                    Long roadId = obj.getId();
                    roadIdToCameraCount.merge(roadId, 1, Integer::sum);
                    matchedAny = true;
                }
            }
            // If no route road geometry was within radius, fall back to nearest-segment search.
            if (!matchedAny) {
                RouteSegmentSearchResult searchResult = RouteSegmentSearchResult.searchRouteSegment(
                        camera.lat, camera.lon, radiusMeters, roads);
                if (searchResult == null) {
                    continue;
                }
                int roadIndex = searchResult.getRoadIndex();
                if (roadIndex <= 0 || roadIndex >= roads.size() - 1) {
                    continue;
                }
                RouteSegmentResult nearestRoad = roads.get(roadIndex);
                RouteDataObject object = nearestRoad.getObject();
                if (object != null) {
                    // Apply direction-aware filtering to fallback search as well.
                    if (hasBearing) {
                        float routeBearing = computeSegmentBearing(nearestRoad);
                        if (!isCameraFacingRouteSegment(cameraBearing, routeBearing, DIRECTION_MATCH_WINDOW_DEGREES)) {
                            directionFilteredCount++;
                            continue;
                        }
                    }
                    Long roadId = object.getId();
                    roadIdToCameraCount.merge(roadId, 1, Integer::sum);
                }
            }
            if (!hasBearing) {
                omnidirectionalCount++;
            }
        }

        // Build sorted list: most cameras first (most important to block)
        List<RoadWithCameraCount> result = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : roadIdToCameraCount.entrySet()) {
            result.add(new RoadWithCameraCount(entry.getKey(), entry.getValue()));
        }
        result.sort(Collections.reverseOrder(Comparator.comparingInt(r -> r.cameraCount)));

        if (!result.isEmpty()) {
            LOG.info("FlockFree collected " + result.size() + " Flock-camera-adjacent road ids with camera counts"
                    + " (direction-filtered: " + directionFilteredCount
                    + ", omnidirectional: " + omnidirectionalCount
                    + ")");
        }

        // --- Cache store ---
        if (routeHash != null) {
            RouteCacheKey cacheKey = new RouteCacheKey(routeHash, datasetVersion, radiusMeters);
            CameraRoadAssociations associations = new CameraRoadAssociations(
                    new ArrayList<>(result), cameras.size(), System.currentTimeMillis());
            synchronized (associationCache) {
                associationCache.put(cacheKey, associations);
                LOG.info("FlockFree route-association cache STORE (entries=" + associationCache.size() + ")");
            }
        }
        // --- End cache store ---

        return result;
    }

    /**
     * Computes the compass bearing (0-360°) of travel along a route segment.
     * Uses the segment's start and end coordinates to determine the direction of travel.
     *
     * @param road the route segment
     * @return compass bearing in degrees (0-360), or 0 if bearing cannot be determined
     */
    private static float computeSegmentBearing(@NonNull RouteSegmentResult road) {
        RouteDataObject obj = road.getObject();
        if (obj == null) {
            return 0f;
        }
        int startX = obj.getPoint31XTile(road.getStartPointIndex());
        int startY = obj.getPoint31YTile(road.getStartPointIndex());
        int endX = obj.getPoint31XTile(road.getEndPointIndex());
        int endY = obj.getPoint31YTile(road.getEndPointIndex());
        if (startX == endX && startY == endY) {
            return 0f;
        }
        double startLat = MapUtils.get31LatitudeY(startY);
        double startLon = MapUtils.get31LongitudeX(startX);
        double endLat = MapUtils.get31LatitudeY(endY);
        double endLon = MapUtils.get31LongitudeX(endX);
        double dLon = endLon - startLon;
        double y = Math.sin(Math.toRadians(dLon)) * Math.cos(Math.toRadians(endLat));
        double x = Math.cos(Math.toRadians(startLat)) * Math.sin(Math.toRadians(endLat))
                - Math.sin(Math.toRadians(startLat)) * Math.cos(Math.toRadians(endLat)) * Math.cos(Math.toRadians(dLon));
        double bearing = Math.toDegrees(Math.atan2(y, x));
        // Normalize to 0-360
        bearing = (bearing + 360) % 360;
        return (float) bearing;
    }

    /**
     * Checks if a camera bearing matches the route segment's travel direction within a tolerance window.
     * Handles 0/360° wraparound (e.g., 350° and 10° should match within a 60° window).
     *
     * @param cameraBearing  the camera's facing bearing in degrees (1-360)
     * @param routeBearing   the route segment's travel bearing in degrees (0-360)
     * @param windowDegrees  half-window in degrees (e.g., 60 means ±60° tolerance)
     * @return true if the route bearing is within ±windowDegrees of the camera bearing
     */
    private static boolean isCameraFacingRouteSegment(float cameraBearing, float routeBearing, float windowDegrees) {
        float diff = Math.abs(cameraBearing - routeBearing);
        // Handle wraparound: 350° vs 10° should have diff=20, not 340
        if (diff > 180f) {
            diff = 360f - diff;
        }
        return diff <= windowDegrees;
    }

    private boolean isCameraNearRoadGeometry(@NonNull RouteSegmentResult road,
                                             int cameraX31, int cameraY31, int radiusMeters) {
        RouteDataObject obj = road.getObject();
        if (obj == null) {
            return false;
        }
        int startPointIndex = Math.min(road.getStartPointIndex(), road.getEndPointIndex());
        int endPointIndex = Math.max(road.getEndPointIndex(), road.getStartPointIndex());
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
        // Use conservative offsets so the bounding-box prefilter cannot exclude a
        // camera that the precise corridor check would accept.
        double latOffset = radiusMeters / 100_000d;
        double maxAbsLat = Math.min(89.9d, Math.max(Math.abs(minLat), Math.abs(maxLat)) + latOffset);
        double cosLatitude = Math.max(0.01d, Math.cos(Math.toRadians(maxAbsLat)));
        double lonOffset = radiusMeters / (100_000d * cosLatitude);
        return new double[]{
                maxLat + latOffset,  // top
                minLon - lonOffset,  // left
                minLat - latOffset,   // bottom
                maxLon + lonOffset    // right
        };
    }

    private boolean isCameraNearRoute(@NonNull CameraData.CameraPoint cam,
                                      @NonNull List<LatLon> routePoints,
                                      @Nullable RouteEdgeIndex routeEdgeIndex,
                                      int radiusMeters) {
        if (routePoints.size() == 1) {
            LatLon point = routePoints.get(0);
            return MapUtils.getDistance(point.getLatitude(), point.getLongitude(), cam.lat, cam.lon) <= radiusMeters;
        }
        List<RouteEdge> candidateEdges = routeEdgeIndex != null
                ? routeEdgeIndex.getCandidateEdges(cam.lat, cam.lon) : Collections.emptyList();
        for (RouteEdge edge : candidateEdges) {
            double distance = MapUtils.getOrthogonalDistance(
                    cam.lat, cam.lon,
                    edge.from.getLatitude(), edge.from.getLongitude(),
                    edge.to.getLatitude(), edge.to.getLongitude());
            if (distance <= radiusMeters) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a human-readable summary of Flock cameras near the route.
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
            String brand = cam.brand != null ? cam.brand.toLowerCase(Locale.US) : "";
            String operator = cam.operator != null ? cam.operator.toLowerCase(Locale.US) : "";
            if (brand.contains("flock") || operator.contains("flock")) flock++;
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
