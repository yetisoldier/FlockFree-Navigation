package net.osmand.plus.plugins.flockfree;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.flockfree.TomTomIncidentProvider.TrafficIncident;
import net.osmand.plus.views.layers.ContextMenuLayer;
import net.osmand.plus.views.layers.MapSelectionResult;
import net.osmand.plus.views.layers.MapSelectionRules;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FlockFreeIncidentLayer extends OsmandMapLayer implements ContextMenuLayer.IContextMenuProvider {

    private static final int MIN_ZOOM_TO_SHOW = 10;
    private static final int CLUSTER_MIN_ZOOM = 13;
    private static final float CLUSTER_CELL_SIZE_DP = 40f;
    private static final float CLUSTER_BASE_RADIUS_DP = 10f;
    private static final float CLUSTER_RADIUS_INCREMENT_DP = 1f;
    private static final float CLUSTER_MAX_RADIUS_DP = 20f;
    private static final float MARKER_RADIUS_DP = 20f;
    private static final float MARKER_BORDER_DP = 2f;
    private static final long FETCH_DEBOUNCE_MS = 60_000L;
    private static final double VIEWPORT_CHANGE_THRESHOLD = 0.15; // 15% movement before refetch

    private final FlockFreePlugin plugin;
    private final TomTomIncidentProvider incidentProvider;
    private final Paint markerPaint;
    private final Paint borderPaint;
    private final Paint textPaint;
    private final Paint clusterTextPaint;

    private List<TrafficIncident> visibleIncidents = new ArrayList<>();
    private List<IncidentCluster> visibleClusters = new ArrayList<>();
    private double lastFetchMinLat;
    private double lastFetchMinLon;
    private double lastFetchMaxLat;
    private double lastFetchMaxLon;
    private long lastFetchTimeMs;

    private static class IncidentCluster {
        final float screenX;
        final float screenY;
        final double lat;
        final double lon;
        final int count;
        final int color;
        final List<TrafficIncident> incidents;

        IncidentCluster(float screenX, float screenY, double lat, double lon,
                        int count, int color, List<TrafficIncident> incidents) {
            this.screenX = screenX;
            this.screenY = screenY;
            this.lat = lat;
            this.lon = lon;
            this.count = count;
            this.color = color;
            this.incidents = incidents;
        }
    }

    public FlockFreeIncidentLayer(@NonNull Context context, @NonNull FlockFreePlugin plugin,
                                   @NonNull TomTomIncidentProvider incidentProvider) {
        super(context);
        this.plugin = plugin;
        this.incidentProvider = incidentProvider;

        markerPaint = new Paint();
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setAntiAlias(true);

        borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setAntiAlias(true);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStrokeWidth(dpToPx(MARKER_BORDER_DP));

        textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        clusterTextPaint = new Paint();
        clusterTextPaint.setAntiAlias(true);
        clusterTextPaint.setColor(Color.WHITE);
        clusterTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings drawSettings) {
        if (!plugin.INCIDENTS_SHOW_LAYER.get()) {
            return;
        }
        int zoom = tileBox.getZoom();
        if (zoom < MIN_ZOOM_TO_SHOW) {
            visibleIncidents = new ArrayList<>();
            visibleClusters = new ArrayList<>();
            return;
        }

        QuadRect screenArea = tileBox.getLatLonBounds();
        double minLat = Math.min(screenArea.top, screenArea.bottom);
        double maxLat = Math.max(screenArea.top, screenArea.bottom);
        double minLon = Math.min(screenArea.left, screenArea.right);
        double maxLon = Math.max(screenArea.left, screenArea.right);

        // Fetch incidents if viewport changed significantly or cache is stale
        maybeFetchIncidents(minLat, minLon, maxLat, maxLon);

        // Get incidents from cache
        String apiKey = plugin.TOMTOM_API_KEY.get();
        if (apiKey == null || apiKey.isEmpty()) {
            visibleIncidents = new ArrayList<>();
            visibleClusters = new ArrayList<>();
            return;
        }
        visibleIncidents = incidentProvider.fetchIncidents(minLat, minLon, maxLat, maxLon, apiKey);

        // Filter to viewport
        List<TrafficIncident> inViewport = new ArrayList<>();
        for (TrafficIncident incident : visibleIncidents) {
            if (incident.lat >= minLat && incident.lat <= maxLat
                    && incident.lon >= minLon && incident.lon <= maxLon) {
                inViewport.add(incident);
            }
        }

        visibleClusters = new ArrayList<>();
        if (zoom >= CLUSTER_MIN_ZOOM) {
            for (TrafficIncident incident : inViewport) {
                drawIncidentMarker(canvas, tileBox, incident);
            }
        } else {
            drawClusters(canvas, tileBox, inViewport);
        }
    }

    private void maybeFetchIncidents(double minLat, double minLon, double maxLat, double maxLon) {
        long now = System.currentTimeMillis();
        boolean stale = now - lastFetchTimeMs >= FETCH_DEBOUNCE_MS;
        boolean viewportChanged = isViewportSignificantlyDifferent(minLat, minLon, maxLat, maxLon);
        if (!stale && !viewportChanged) {
            return;
        }
        lastFetchMinLat = minLat;
        lastFetchMinLon = minLon;
        lastFetchMaxLat = maxLat;
        lastFetchMaxLon = maxLon;
        lastFetchTimeMs = now;
        String apiKey = plugin.TOMTOM_API_KEY.get();
        if (apiKey != null && !apiKey.isEmpty()) {
            incidentProvider.prefetchIncidentsAsync(minLat, minLon, maxLat, maxLon, apiKey, null);
        }
    }

    private boolean isViewportSignificantlyDifferent(double minLat, double minLon,
                                                      double maxLat, double maxLon) {
        double latRange = lastFetchMaxLat - lastFetchMinLat;
        double lonRange = lastFetchMaxLon - lastFetchMinLon;
        if (latRange <= 0 || lonRange <= 0) {
            return true;
        }
        double latShift = Math.abs(minLat - lastFetchMinLat) / latRange;
        double lonShift = Math.abs(minLon - lastFetchMinLon) / lonRange;
        return latShift > VIEWPORT_CHANGE_THRESHOLD || lonShift > VIEWPORT_CHANGE_THRESHOLD;
    }

    private void drawClusters(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                              @NonNull List<TrafficIncident> incidents) {
        float cellSize = dpToPx(CLUSTER_CELL_SIZE_DP);
        Map<Long, List<TrafficIncident>> grid = new HashMap<>();
        Map<Long, float[]> cellScreenCenter = new HashMap<>();

        for (TrafficIncident incident : incidents) {
            float x = tileBox.getPixXFromLatLon(incident.lat, incident.lon);
            float y = tileBox.getPixYFromLatLon(incident.lat, incident.lon);
            int cellX = (int) (x / cellSize);
            int cellY = (int) (y / cellSize);
            long key = ((long) cellX << 32) | (cellY & 0xFFFFFFFFL);

            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(incident);
            float[] sums = cellScreenCenter.computeIfAbsent(key, k -> new float[2]);
            sums[0] += x;
            sums[1] += y;
        }

        for (Map.Entry<Long, List<TrafficIncident>> entry : grid.entrySet()) {
            List<TrafficIncident> cellIncidents = entry.getValue();
            int count = cellIncidents.size();
            float[] sums = cellScreenCenter.get(entry.getKey());
            float centerX = sums[0] / count;
            float centerY = sums[1] / count;

            if (count == 1) {
                drawIncidentMarker(canvas, tileBox, cellIncidents.get(0));
                continue;
            }

            int color = getIncidentColor(cellIncidents.get(0).iconCategory);
            LatLon centerLatLon = tileBox.getLatLonFromPixel(centerX, centerY);
            IncidentCluster cluster = new IncidentCluster(
                    centerX, centerY, centerLatLon.getLatitude(), centerLatLon.getLongitude(),
                    count, color, cellIncidents);
            visibleClusters.add(cluster);
            drawClusterBadge(canvas, cluster);
        }
    }

    private void drawClusterBadge(@NonNull Canvas canvas, @NonNull IncidentCluster cluster) {
        float radius = dpToPx(Math.min(CLUSTER_BASE_RADIUS_DP + CLUSTER_RADIUS_INCREMENT_DP * (cluster.count - 1),
                CLUSTER_MAX_RADIUS_DP));
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(cluster.color);
        canvas.drawCircle(cluster.screenX, cluster.screenY, radius, markerPaint);

        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setColor(darkenColor(cluster.color));
        canvas.drawCircle(cluster.screenX, cluster.screenY, radius, markerPaint);
        markerPaint.setStyle(Paint.Style.FILL);

        clusterTextPaint.setTextSize(dpToPx(10));
        float textOffset = clusterTextPaint.getTextSize() / 3f;
        canvas.drawText(String.valueOf(cluster.count), cluster.screenX,
                cluster.screenY + textOffset, clusterTextPaint);
    }

    private void drawIncidentMarker(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                                     @NonNull TrafficIncident incident) {
        float x = tileBox.getPixXFromLatLon(incident.lat, incident.lon);
        float y = tileBox.getPixYFromLatLon(incident.lat, incident.lon);
        float radius = dpToPx(MARKER_RADIUS_DP / 2f);

        // Draw filled circle
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(getIncidentColor(incident.iconCategory));
        canvas.drawCircle(x, y, radius, markerPaint);

        // Draw white border
        borderPaint.setStrokeWidth(dpToPx(MARKER_BORDER_DP));
        canvas.drawCircle(x, y, radius, borderPaint);

        // Draw letter symbol inside
        textPaint.setTextSize(radius * 1.0f);
        String letter = getIncidentLetter(incident.iconCategory);
        float textOffset = textPaint.getTextSize() / 3f;
        canvas.drawText(letter, x, y + textOffset, textPaint);
    }

    @Override
    public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
        // Check cluster tap first
        if (!visibleClusters.isEmpty()) {
            IncidentCluster closestCluster = null;
            float closestClusterDist = Float.MAX_VALUE;
            float tapRadius = dpToPx(CLUSTER_MAX_RADIUS_DP);
            for (IncidentCluster cluster : visibleClusters) {
                float dx = cluster.screenX - point.x;
                float dy = cluster.screenY - point.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < tapRadius && dist < closestClusterDist) {
                    closestClusterDist = dist;
                    closestCluster = cluster;
                }
            }
            if (closestCluster != null) {
                int targetZoom = Math.min(tileBox.getZoom() + 2, 20);
                getMapView().getAnimatedDraggingThread()
                        .startMoving(closestCluster.lat, closestCluster.lon, targetZoom);
                return true;
            }
        }

        // Check incident tap
        LatLon latLon = tileBox.getLatLonFromPixel(point.x, point.y);
        double tapLat = latLon.getLatitude();
        double tapLon = latLon.getLongitude();

        TrafficIncident closest = null;
        double closestDist = Double.MAX_VALUE;
        for (TrafficIncident incident : visibleIncidents) {
            double dist = MapUtils.getDistance(incident.lat, incident.lon, tapLat, tapLon);
            if (dist < getTapRadiusMeters(tileBox) && dist < closestDist) {
                closestDist = dist;
                closest = incident;
            }
        }

        if (closest != null) {
            showIncidentDetails(closest);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(@NonNull android.view.MotionEvent event, @NonNull RotatedTileBox tileBox) {
        return false;
    }

    @Override
    public void collectObjectsFromPoint(@NonNull MapSelectionResult result, @NonNull MapSelectionRules rules) {
        if (rules.isOnlyTouchableObjects()) {
            return;
        }
        PointF point = result.getPoint();
        RotatedTileBox tileBox = result.getTileBox();
        LatLon latLon = tileBox.getLatLonFromPixel(point.x, point.y);
        TrafficIncident closest = null;
        double closestDist = Double.MAX_VALUE;
        for (TrafficIncident incident : visibleIncidents) {
            double dist = MapUtils.getDistance(incident.lat, incident.lon,
                    latLon.getLatitude(), latLon.getLongitude());
            if (dist < getTapRadiusMeters(tileBox) && dist < closestDist) {
                closestDist = dist;
                closest = incident;
            }
        }
        if (closest != null) {
            result.collect(closest, this);
        }
    }

    @Nullable
    @Override
    public LatLon getObjectLocation(Object o) {
        if (o instanceof TrafficIncident) {
            TrafficIncident incident = (TrafficIncident) o;
            return new LatLon(incident.lat, incident.lon);
        }
        return null;
    }

    @Override
    public PointDescription getObjectName(Object o) {
        if (o instanceof TrafficIncident) {
            TrafficIncident incident = (TrafficIncident) o;
            String categoryName = getIncidentCategoryName(incident.iconCategory);
            return new PointDescription(PointDescription.POINT_TYPE_POI, categoryName);
        }
        return null;
    }

    @Override
    public boolean drawInScreenPixels() {
        return true;
    }

    private void showIncidentDetails(@NonNull TrafficIncident incident) {
        MapActivity mapActivity = getMapActivity();
        if (mapActivity == null) {
            return;
        }
        String categoryName = getIncidentCategoryName(incident.iconCategory);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.flockfree_incident_type_label)).append(": ").append(categoryName).append("\n");
        if (incident.description != null && !incident.description.isEmpty()) {
            sb.append(getString(R.string.flockfree_incident_description_label)).append(": ")
                    .append(incident.description).append("\n");
        }
        sb.append(getString(R.string.flockfree_incident_coordinates_label)).append(": ")
                .append(String.format(java.util.Locale.US, "%.5f, %.5f", incident.lat, incident.lon));
        if (incident.roadClosed) {
            sb.append("\n").append(getString(R.string.flockfree_incident_road_closed));
        }
        new android.app.AlertDialog.Builder(mapActivity)
                .setTitle(categoryName)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.shared_string_ok, null)
                .show();
    }

    private int getIncidentColor(int iconCategory) {
        switch (iconCategory) {
            case 1: // Accident
                return 0xFFEA4335;
            case 6: // Jam
                return 0xFFF9AB00;
            case 8: // Road Closed
                return 0xFF8B0000;
            case 9: // Roadworks
                return 0xFFFDD835;
            case 7: // Lane Closed
                return 0xFFFF6D00;
            case 11: // Flooding
                return 0xFF2196F3;
            case 3: // Dangerous Conditions
                return 0xFF9C27B0;
            case 2: // Fog
            case 4: // Rain
            case 5: // Ice
            case 10: // Wind
                return 0xFF00BCD4;
            case 14: // Broken Down Vehicle
                return 0xFF607D8B;
            case 0: // Unknown
            default:
                return 0xFF9E9E9E;
        }
    }

    private String getIncidentLetter(int iconCategory) {
        switch (iconCategory) {
            case 1: return "A";
            case 2: return "F";
            case 3: return "D";
            case 4: return "R";
            case 5: return "I";
            case 6: return "J";
            case 7: return "L";
            case 8: return "C";
            case 9: return "W";
            case 10: return "N";
            case 11: return "FL";
            case 14: return "B";
            case 0: default: return "?";
        }
    }

    @NonNull
    private String getIncidentCategoryName(int iconCategory) {
        switch (iconCategory) {
            case 1: return getString(R.string.flockfree_incident_accident);
            case 2: return getString(R.string.flockfree_incident_fog);
            case 3: return getString(R.string.flockfree_incident_dangerous);
            case 4: return getString(R.string.flockfree_incident_rain);
            case 5: return getString(R.string.flockfree_incident_ice);
            case 6: return getString(R.string.flockfree_incident_jam);
            case 7: return getString(R.string.flockfree_incident_lane_closed);
            case 8: return getString(R.string.flockfree_incident_road_closed);
            case 9: return getString(R.string.flockfree_incident_roadworks);
            case 10: return getString(R.string.flockfree_incident_wind);
            case 11: return getString(R.string.flockfree_incident_flooding);
            case 14: return getString(R.string.flockfree_incident_broken_down);
            case 0: default: return getString(R.string.flockfree_incident_unknown);
        }
    }

    private double getTapRadiusMeters(@NonNull RotatedTileBox tileBox) {
        return Math.max(20, tileBox.getDistance(0, 0, 30, 0));
    }

    private int darkenColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.7f;
        return Color.HSVToColor(hsv);
    }

    private float dpToPx(float dp) {
        return dp * ((android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi
                / (float) android.util.DisplayMetrics.DENSITY_DEFAULT));
    }
}