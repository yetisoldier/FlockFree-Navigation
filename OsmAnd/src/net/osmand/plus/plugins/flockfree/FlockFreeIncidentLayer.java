package net.osmand.plus.plugins.flockfree;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import net.osmand.core.android.MapRendererView;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.flockfree.TomTomIncidentProvider.TrafficIncident;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.plus.utils.UiUtilities;
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
    private static final float MARKER_RADIUS_DP = 28f;
    private static final float MARKER_BORDER_DP = 2f;
    private static final long FETCH_DEBOUNCE_MS = 60_000L;
    private static final double VIEWPORT_CHANGE_THRESHOLD = 0.15; // 15% movement before refetch

    private final FlockFreePlugin plugin;
    private final TomTomIncidentProvider incidentProvider;
    private final Paint markerPaint;
    private final Paint borderPaint;
    private final Paint textPaint;
    private final Paint iconPaint;
    private final Paint clusterTextPaint;
    private final Path iconPath = new Path();
    private final Path wavePath = new Path();
    private final Path trianglePath = new Path();
    private final Path swirlPath = new Path();
    private final Path conePath = new Path();
    private final RectF carBodyRect = new RectF();
    private final RectF roadClosedBarRect = new RectF();

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
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        iconPaint = new Paint();
        iconPaint.setAntiAlias(true);
        iconPaint.setColor(Color.WHITE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);

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
            PointF pos = getPixelFromLatLon(tileBox, incident.lat, incident.lon);
            float x = pos.x;
            float y = pos.y;
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
        PointF pos = getPixelFromLatLon(tileBox, incident.lat, incident.lon);
        float x = pos.x;
        float y = pos.y;
        float radius = dpToPx(MARKER_RADIUS_DP / 2f);

        // Draw filled circle
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(getIncidentColor(incident.iconCategory));
        canvas.drawCircle(x, y, radius, markerPaint);

        // Draw white border
        borderPaint.setStrokeWidth(dpToPx(MARKER_BORDER_DP));
        canvas.drawCircle(x, y, radius, borderPaint);

        drawIncidentIcon(canvas, x, y, radius, incident.iconCategory);
    }

    private void drawIncidentIcon(@NonNull Canvas canvas, float x, float y, float radius, int iconCategory) {
        iconPaint.setColor(Color.WHITE);
        iconPaint.setStrokeWidth(Math.max(dpToPx(2f), radius * 0.14f));
        switch (iconCategory) {
            case 1:
                drawCarIcon(canvas, x, y, radius, false);
                break;
            case 6:
                drawJamIcon(canvas, x, y, radius);
                break;
            case 8:
                drawRoadClosedIcon(canvas, x, y, radius);
                break;
            case 9:
                drawRoadworksIcon(canvas, x, y, radius);
                break;
            case 7:
                drawLaneClosedIcon(canvas, x, y, radius);
                break;
            case 11:
                drawFloodingIcon(canvas, x, y, radius);
                break;
            case 3:
                drawDangerIcon(canvas, x, y, radius);
                break;
            case 2:
                drawFogIcon(canvas, x, y, radius);
                break;
            case 4:
                drawRainIcon(canvas, x, y, radius);
                break;
            case 5:
                drawIceIcon(canvas, x, y, radius);
                break;
            case 10:
                drawWindIcon(canvas, x, y, radius);
                break;
            case 14:
                drawCarIcon(canvas, x, y, radius, true);
                break;
            case 0:
            default:
                drawUnknownIcon(canvas, x, y, radius);
                break;
        }
    }

    private void drawCarIcon(@NonNull Canvas canvas, float x, float y, float radius, boolean brokenDown) {
        float size = radius * 1.12f;
        iconPaint.setStyle(Paint.Style.FILL);
        carBodyRect.set(x - size * 0.48f, y - size * 0.12f,
                x + size * 0.48f, y + size * 0.25f);
        canvas.drawRoundRect(carBodyRect, radius * 0.12f, radius * 0.12f, iconPaint);
        canvas.drawCircle(x - size * 0.28f, y + size * 0.31f, radius * 0.12f, iconPaint);
        canvas.drawCircle(x + size * 0.28f, y + size * 0.31f, radius * 0.12f, iconPaint);
        if (brokenDown) {
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(Math.max(dpToPx(2f), radius * 0.12f));
            float slash = size * 0.34f;
            canvas.drawLine(x - slash, y - slash * 0.85f, x + slash, y + slash * 0.85f, iconPaint);
            canvas.drawLine(x + slash, y - slash * 0.85f, x - slash, y + slash * 0.85f, iconPaint);
        }
    }

    private void drawJamIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        float size = radius * 1.05f;
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPath.reset();
        iconPath.moveTo(x - size * 0.38f, y - size * 0.38f);
        iconPath.lineTo(x + size * 0.25f, y - size * 0.38f);
        iconPath.lineTo(x - size * 0.18f, y);
        iconPath.lineTo(x + size * 0.38f, y);
        iconPath.lineTo(x - size * 0.25f, y + size * 0.38f);
        iconPath.lineTo(x + size * 0.38f, y + size * 0.38f);
        canvas.drawPath(iconPath, iconPaint);
    }

    private void drawRoadClosedIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        float width = radius * 1.15f;
        float height = radius * 0.26f;
        iconPaint.setStyle(Paint.Style.FILL);
        roadClosedBarRect.set(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f);
        canvas.drawRoundRect(roadClosedBarRect, height / 2f, height / 2f, iconPaint);
    }

    private void drawRoadworksIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        float size = radius * 1.18f;
        iconPaint.setStyle(Paint.Style.FILL);
        conePath.reset();
        conePath.moveTo(x, y - size * 0.45f);
        conePath.lineTo(x - size * 0.38f, y + size * 0.42f);
        conePath.lineTo(x + size * 0.38f, y + size * 0.42f);
        conePath.close();
        canvas.drawPath(conePath, iconPaint);
    }

    private void drawLaneClosedIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        float size = radius * 1.08f;
        iconPaint.setStyle(Paint.Style.STROKE);
        float left = x - size * 0.24f;
        float right = x + size * 0.24f;
        canvas.drawLine(left, y - size * 0.45f, left, y + size * 0.45f, iconPaint);
        canvas.drawLine(right, y - size * 0.45f, right, y + size * 0.45f, iconPaint);
        canvas.drawLine(x - size * 0.42f, y + size * 0.38f, x + size * 0.42f, y - size * 0.38f, iconPaint);
    }

    private void drawFloodingIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        iconPaint.setStyle(Paint.Style.STROKE);
        drawWave(canvas, x, y - radius * 0.14f, radius);
        drawWave(canvas, x, y + radius * 0.2f, radius);
    }

    private void drawWave(@NonNull Canvas canvas, float x, float y, float radius) {
        float size = radius * 1.12f;
        wavePath.reset();
        wavePath.moveTo(x - size * 0.5f, y);
        wavePath.cubicTo(x - size * 0.25f, y - size * 0.25f,
                x - size * 0.1f, y + size * 0.25f, x + size * 0.1f, y);
        wavePath.cubicTo(x + size * 0.25f, y - size * 0.25f,
                x + size * 0.35f, y + size * 0.25f, x + size * 0.5f, y);
        canvas.drawPath(wavePath, iconPaint);
    }

    private void drawDangerIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        float size = radius * 1.2f;
        iconPaint.setStyle(Paint.Style.STROKE);
        trianglePath.reset();
        trianglePath.moveTo(x, y - size * 0.5f);
        trianglePath.lineTo(x - size * 0.46f, y + size * 0.38f);
        trianglePath.lineTo(x + size * 0.46f, y + size * 0.38f);
        trianglePath.close();
        canvas.drawPath(trianglePath, iconPaint);
    }

    private void drawFogIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        float width = radius * 1.1f;
        iconPaint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(x - width / 2f, y - radius * 0.32f, x + width / 2f, y - radius * 0.32f, iconPaint);
        canvas.drawLine(x - width / 2f, y, x + width / 2f, y, iconPaint);
        canvas.drawLine(x - width / 2f, y + radius * 0.32f, x + width / 2f, y + radius * 0.32f, iconPaint);
    }

    private void drawRainIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        iconPaint.setStyle(Paint.Style.STROKE);
        float top = y - radius * 0.45f;
        for (int i = -1; i <= 2; i++) {
            float startX = x + i * radius * 0.28f - radius * 0.18f;
            canvas.drawLine(startX, top, startX - radius * 0.24f, top + radius * 0.9f, iconPaint);
        }
    }

    private void drawIceIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        iconPaint.setStyle(Paint.Style.STROKE);
        float size = radius * 0.52f;
        canvas.drawLine(x, y - size, x, y + size, iconPaint);
        canvas.drawLine(x - size, y, x + size, y, iconPaint);
        canvas.drawLine(x - size * 0.72f, y - size * 0.72f, x + size * 0.72f, y + size * 0.72f, iconPaint);
        canvas.drawLine(x + size * 0.72f, y - size * 0.72f, x - size * 0.72f, y + size * 0.72f, iconPaint);
    }

    private void drawWindIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        float size = radius * 1.12f;
        iconPaint.setStyle(Paint.Style.STROKE);
        swirlPath.reset();
        swirlPath.moveTo(x - size * 0.5f, y - size * 0.18f);
        swirlPath.cubicTo(x - size * 0.05f, y - size * 0.55f, x + size * 0.48f, y - size * 0.28f,
                x + size * 0.18f, y + size * 0.04f);
        swirlPath.cubicTo(x - size * 0.1f, y + size * 0.34f, x + size * 0.38f, y + size * 0.46f,
                x + size * 0.5f, y + size * 0.18f);
        canvas.drawPath(swirlPath, iconPaint);
    }

    private void drawUnknownIcon(@NonNull Canvas canvas, float x, float y, float radius) {
        textPaint.setTextSize(radius * 1.1f);
        float textOffset = textPaint.getTextSize() / 3f;
        canvas.drawText("?", x, y + textOffset, textPaint);
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

    @NonNull
    private PointF getPixelFromLatLon(@NonNull RotatedTileBox tileBox, double lat, double lon) {
        MapRendererView mapRenderer = getMapRenderer();
        if (mapRenderer != null) {
            return NativeUtilities.getPixelFromLatLon(mapRenderer, tileBox, lat, lon);
        }
        return new PointF(tileBox.getPixXFromLatLon(lat, lon), tileBox.getPixYFromLatLon(lat, lon));
    }

    private void showIncidentDetails(@NonNull TrafficIncident incident) {
        MapActivity mapActivity = getMapActivity();
        if (mapActivity == null) {
            return;
        }
        String categoryName = getIncidentCategoryName(incident.iconCategory);
        boolean nightMode = mapActivity.getApp().getDaynightHelper().isNightMode(ThemeUsageContext.APP);
        Context themedContext = UiUtilities.getThemedContext(mapActivity, nightMode);
        int primaryColor = ColorUtilities.getPrimaryTextColor(themedContext, nightMode);
        int secondaryColor = ColorUtilities.getSecondaryTextColor(themedContext, nightMode);

        BottomSheetDialog dialog = new BottomSheetDialog(themedContext);
        LinearLayout layout = new LinearLayout(themedContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = (int) dpToPx(24f);
        int verticalPadding = (int) dpToPx(20f);
        layout.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        TextView title = createIncidentDetailsText(themedContext, categoryName, primaryColor, 20f, true);
        layout.addView(title);

        if (incident.description != null && !incident.description.isEmpty()) {
            TextView description = createIncidentDetailsText(themedContext,
                    incident.description, primaryColor, 15f, false);
            description.setPadding(0, (int) dpToPx(12f), 0, 0);
            layout.addView(description);
        }

        String coordinates = getString(R.string.flockfree_incident_coordinates_label) + ": "
                + String.format(java.util.Locale.US, "%.5f, %.5f", incident.lat, incident.lon);
        TextView coordinateView = createIncidentDetailsText(themedContext, coordinates, secondaryColor, 14f, false);
        coordinateView.setPadding(0, (int) dpToPx(12f), 0, 0);
        layout.addView(coordinateView);

        if (incident.roadClosed) {
            TextView roadClosed = createIncidentDetailsText(themedContext,
                    getString(R.string.flockfree_incident_road_closed), getIncidentColor(8), 15f, true);
            roadClosed.setPadding(0, (int) dpToPx(12f), 0, 0);
            layout.addView(roadClosed);
        }

        Button closeButton = new Button(themedContext);
        closeButton.setText(R.string.shared_string_close);
        closeButton.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = (int) dpToPx(18f);
        layout.addView(closeButton, buttonParams);

        dialog.setContentView(layout);
        dialog.show();
    }

    @NonNull
    private TextView createIncidentDetailsText(@NonNull Context context, @NonNull CharSequence text,
                                                int textColor, float textSizeSp, boolean bold) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextColor(textColor);
        textView.setTextSize(textSizeSp);
        if (bold) {
            textView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }
        return textView;
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
