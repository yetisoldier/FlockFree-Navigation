package net.osmand.plus.plugins.flockfree;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.flockfree.cyd.CydDetectionCandidate;
import net.osmand.plus.views.layers.ContextMenuLayer;
import net.osmand.plus.views.layers.MapSelectionResult;
import net.osmand.plus.views.layers.MapSelectionRules;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FlockFreeLayer extends OsmandMapLayer implements ContextMenuLayer.IContextMenuProvider {

    private static final int MIN_ZOOM_TO_SHOW = 10;
    private static final int CLUSTER_MIN_ZOOM = 13; // cluster below this zoom, show individual at 13+
    private static final float CLUSTER_CELL_SIZE_DP = 40f; // grid cell size in dp
    private static final float CLUSTER_BASE_RADIUS_DP = 10f;
    private static final float CLUSTER_RADIUS_INCREMENT_DP = 1f;
    private static final float CLUSTER_MAX_RADIUS_DP = 20f;

    private final FlockFreePlugin plugin;
    private final Paint markerPaint;
    private final Paint candidatePaint;
    private final Paint textPaint;
    private final Paint coneFillPaint;
    private final Paint coneStrokePaint;

    private static final float CONE_HALF_ANGLE_DEG = 30f; // half-angle of the view cone on each side
    private static final float CONE_LENGTH_DP = 22f;     // screen-space cone length in dp

    private List<CameraData.CameraPoint> visibleCameras = new ArrayList<>();
    private List<CydDetectionCandidate> visibleDetections = new ArrayList<>();
    private List<CameraCluster> visibleClusters = new ArrayList<>();

    /**
     * A cluster of cameras that fall within the same grid cell.
     */
    private static class CameraCluster {
        final float screenX;
        final float screenY;
        final double lat;
        final double lon;
        final int count;
        final int dominantColor;
        final List<CameraData.CameraPoint> cameras;

        CameraCluster(float screenX, float screenY, double lat, double lon,
                      int count, int dominantColor, List<CameraData.CameraPoint> cameras) {
            this.screenX = screenX;
            this.screenY = screenY;
            this.lat = lat;
            this.lon = lon;
            this.count = count;
            this.dominantColor = dominantColor;
            this.cameras = cameras;
        }
    }

    public FlockFreeLayer(@NonNull Context context, @NonNull FlockFreePlugin plugin) {
        super(context);
        this.plugin = plugin;

        markerPaint = new Paint();
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setAntiAlias(true);
        markerPaint.setStrokeWidth(dpToPx(2));

        candidatePaint = new Paint();
        candidatePaint.setAntiAlias(true);
        candidatePaint.setStrokeWidth(dpToPx(2));

        textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(dpToPx(10));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        coneFillPaint = new Paint();
        coneFillPaint.setStyle(Paint.Style.FILL);
        coneFillPaint.setAntiAlias(true);

        coneStrokePaint = new Paint();
        coneStrokePaint.setStyle(Paint.Style.STROKE);
        coneStrokePaint.setAntiAlias(true);
        coneStrokePaint.setStrokeWidth(dpToPx(1.5f));
    }

    @Override
    public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings drawSettings) {
        if (!plugin.CAMERA_SHOW_LAYER.get()) {
            return;
        }

        QuadRect screenArea = tileBox.getLatLonBounds();
        visibleClusters = new ArrayList<>();
        if (tileBox.getZoom() >= MIN_ZOOM_TO_SHOW) {
            CameraData cameraData = plugin.getCameraData();
            if (cameraData.isDataLoaded()) {
                List<CameraData.CameraPoint> cameras = cameraData.getCamerasInBoundingBox(
                        screenArea.top, screenArea.left, screenArea.bottom, screenArea.right);
                visibleCameras = cameras;
                if (tileBox.getZoom() >= CLUSTER_MIN_ZOOM) {
                    // Show individual markers
                    for (CameraData.CameraPoint camera : cameras) {
                        drawCamera(canvas, tileBox, camera);
                    }
                } else {
                    // Cluster cameras using grid-based spatial clustering
                    drawClusters(canvas, tileBox, cameras);
                }
            } else {
                visibleCameras = new ArrayList<>();
            }
        } else {
            visibleCameras = new ArrayList<>();
        }

        visibleDetections = getVisibleDetections(screenArea);
        for (CydDetectionCandidate detection : visibleDetections) {
            drawCydDetection(canvas, tileBox, detection);
        }
    }

    /**
     * Grid-based spatial clustering: divide the screen into a grid of cells,
     * group cameras in the same cell, and draw cluster badges.
     */
    private void drawClusters(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                              @NonNull List<CameraData.CameraPoint> cameras) {
        float cellSize = dpToPx(CLUSTER_CELL_SIZE_DP);

        // Use a map keyed by grid cell coordinates
        Map<Long, List<CameraData.CameraPoint>> grid = new HashMap<>();
        Map<Long, float[]> cellScreenCenter = new HashMap<>(); // store [sumX, sumY] for averaging

        for (CameraData.CameraPoint camera : cameras) {
            float x = tileBox.getPixXFromLatLon(camera.lat, camera.lon);
            float y = tileBox.getPixYFromLatLon(camera.lat, camera.lon);
            int cellX = (int) (x / cellSize);
            int cellY = (int) (y / cellSize);
            long key = ((long) cellX << 32) | (cellY & 0xFFFFFFFFL);

            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(camera);
            float[] sums = cellScreenCenter.computeIfAbsent(key, k -> new float[2]);
            sums[0] += x;
            sums[1] += y;
        }

        for (Map.Entry<Long, List<CameraData.CameraPoint>> entry : grid.entrySet()) {
            List<CameraData.CameraPoint> cellCameras = entry.getValue();
            int count = cellCameras.size();

            float[] sums = cellScreenCenter.get(entry.getKey());
            float centerX = sums[0] / count;
            float centerY = sums[1] / count;

            if (count == 1) {
                // Single camera — draw normal marker
                drawCamera(canvas, tileBox, cellCameras.get(0));
                continue;
            }

            // Determine dominant brand color
            int dominantColor = getDominantBrandColor(cellCameras);

            // Convert screen center back to lat/lon for tap handling
            LatLon centerLatLon = tileBox.getLatLonFromPixel(centerX, centerY);

            CameraCluster cluster = new CameraCluster(
                    centerX, centerY,
                    centerLatLon.getLatitude(), centerLatLon.getLongitude(),
                    count, dominantColor, cellCameras);
            visibleClusters.add(cluster);
            drawClusterBadge(canvas, cluster);
        }
    }

    /**
     * Draw a cluster badge: filled circle with count text.
     */
    private void drawClusterBadge(@NonNull Canvas canvas, @NonNull CameraCluster cluster) {
        float radius = dpToPx(Math.min(CLUSTER_BASE_RADIUS_DP + CLUSTER_RADIUS_INCREMENT_DP * (cluster.count - 1),
                CLUSTER_MAX_RADIUS_DP));

        // Fill with dominant brand color
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(cluster.dominantColor);
        canvas.drawCircle(cluster.screenX, cluster.screenY, radius, markerPaint);

        // Stroke with darkened color
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setColor(darkenColor(cluster.dominantColor));
        canvas.drawCircle(cluster.screenX, cluster.screenY, radius, markerPaint);
        markerPaint.setStyle(Paint.Style.FILL);

        // Draw count text
        String countText = String.valueOf(cluster.count);
        float textOffset = textPaint.getTextSize() / 3f;
        canvas.drawText(countText, cluster.screenX, cluster.screenY + textOffset, textPaint);
    }

    /**
     * Find the most common brand color among a group of cameras.
     */
    private int getDominantBrandColor(@NonNull List<CameraData.CameraPoint> cameras) {
        Map<String, Integer> brandCounts = new HashMap<>();
        for (CameraData.CameraPoint cam : cameras) {
            String key = cam.brand != null ? cam.brand.toLowerCase() : "";
            brandCounts.merge(key, 1, Integer::sum);
        }
        String dominantBrand = "";
        int maxCount = 0;
        for (Map.Entry<String, Integer> entry : brandCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                dominantBrand = entry.getKey();
            }
        }
        // Reconstruct a brand string for getBrandColor
        for (CameraData.CameraPoint cam : cameras) {
            if (cam.brand != null && cam.brand.toLowerCase().equals(dominantBrand)) {
                return getBrandColor(cam.brand);
            }
        }
        return getBrandColor(null);
    }

    @Override
    public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
        // Check if a cluster was tapped (only relevant when clustering is active)
        if (!visibleClusters.isEmpty()) {
            CameraCluster closestCluster = null;
            float closestClusterDist = Float.MAX_VALUE;
            float tapRadius = dpToPx(CLUSTER_MAX_RADIUS_DP);
            for (CameraCluster cluster : visibleClusters) {
                float dx = cluster.screenX - point.x;
                float dy = cluster.screenY - point.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < tapRadius && dist < closestClusterDist) {
                    closestClusterDist = dist;
                    closestCluster = cluster;
                }
            }
            if (closestCluster != null) {
                // Animate to cluster center at zoom+2
                int targetZoom = Math.min(tileBox.getZoom() + 2, 20);
                getMapView().getAnimatedDraggingThread()
                        .startMoving(closestCluster.lat, closestCluster.lon, targetZoom);
                return true;
            }
        }

        // Check if a camera marker was tapped
        LatLon latLon = tileBox.getLatLonFromPixel(point.x, point.y);
        double tapLat = latLon.getLatitude();
        double tapLon = latLon.getLongitude();

        CameraData.CameraPoint closest = null;
        double closestDist = Double.MAX_VALUE;

        for (CameraData.CameraPoint camera : visibleCameras) {
            double dist = MapUtils.getDistance(camera.lat, camera.lon, tapLat, tapLon);
            if (dist < getTapRadiusMeters(tileBox) && dist < closestDist) {
                closestDist = dist;
                closest = camera;
            }
        }

        if (closest != null) {
            MapActivity mapActivity = getMapActivity();
            if (mapActivity != null) {
                plugin.showCameraDetails(mapActivity, closest);
            }
            return true;
        }
        CydDetectionCandidate detection = findClosestDetection(point, tileBox);
        if (detection != null) {
            MapActivity mapActivity = getMapActivity();
            if (mapActivity != null) {
                plugin.showCydDetectionDetails(mapActivity, detection);
            }
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
        CameraData.CameraPoint camera = findClosestCamera(point, tileBox);
        if (camera != null) {
            result.collect(camera, this);
        }
        CydDetectionCandidate detection = findClosestDetection(point, tileBox);
        if (detection != null) {
            result.collect(detection, this);
        }
    }

    @Nullable
    @Override
    public LatLon getObjectLocation(Object o) {
        if (o instanceof CameraData.CameraPoint) {
            CameraData.CameraPoint cam = (CameraData.CameraPoint) o;
            return new LatLon(cam.lat, cam.lon);
        } else if (o instanceof CydDetectionCandidate) {
            CydDetectionCandidate detection = (CydDetectionCandidate) o;
            Double lat = detection.getLatitude();
            Double lon = detection.getLongitude();
            if (lat != null && lon != null) {
                return new LatLon(lat, lon);
            }
        }
        return null;
    }

    @Override
    public PointDescription getObjectName(Object o) {
        if (o instanceof CameraData.CameraPoint) {
            CameraData.CameraPoint cam = (CameraData.CameraPoint) o;
            String brand = cam.brand != null ? cam.brand : getString(R.string.flockfree_alpr_camera);
            return new PointDescription(PointDescription.POINT_TYPE_POI, brand);
        } else if (o instanceof CydDetectionCandidate) {
            CydDetectionCandidate detection = (CydDetectionCandidate) o;
            return new PointDescription(PointDescription.POINT_TYPE_POI,
                    getString(R.string.flockfree_cyd_detection_name, detection.getDetectionTypeLabel()));
        }
        return null;
    }

    @Override
    public boolean drawInScreenPixels() {
        return true;
    }

    private CameraData.CameraPoint findClosestCamera(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
        LatLon latLon = tileBox.getLatLonFromPixel(point.x, point.y);
        CameraData.CameraPoint closest = null;
        double closestDist = Double.MAX_VALUE;
        for (CameraData.CameraPoint camera : visibleCameras) {
            double dist = MapUtils.getDistance(camera.lat, camera.lon, latLon.getLatitude(), latLon.getLongitude());
            if (dist < getTapRadiusMeters(tileBox) && dist < closestDist) {
                closestDist = dist;
                closest = camera;
            }
        }
        return closest;
    }

    @Nullable
    private CydDetectionCandidate findClosestDetection(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
        LatLon latLon = tileBox.getLatLonFromPixel(point.x, point.y);
        CydDetectionCandidate closest = null;
        double closestDist = Double.MAX_VALUE;
        for (CydDetectionCandidate detection : visibleDetections) {
            Double lat = detection.getLatitude();
            Double lon = detection.getLongitude();
            if (lat == null || lon == null) {
                continue;
            }
            double dist = MapUtils.getDistance(lat, lon, latLon.getLatitude(), latLon.getLongitude());
            if (dist < getTapRadiusMeters(tileBox) && dist < closestDist) {
                closestDist = dist;
                closest = detection;
            }
        }
        return closest;
    }

    @NonNull
    private List<CydDetectionCandidate> getVisibleDetections(@NonNull QuadRect screenArea) {
        List<CydDetectionCandidate> detections = new java.util.ArrayList<>();
        for (CydDetectionCandidate detection : plugin.getCydHardwareManager().getRecentDetections()) {
            Double lat = detection.getLatitude();
            Double lon = detection.getLongitude();
            if (lat != null && lon != null && isInBounds(screenArea, lat, lon)) {
                detections.add(detection);
            }
        }
        return detections;
    }

    private boolean isInBounds(@NonNull QuadRect screenArea, double lat, double lon) {
        double top = Math.max(screenArea.top, screenArea.bottom);
        double bottom = Math.min(screenArea.top, screenArea.bottom);
        boolean latitudeInBounds = lat >= bottom && lat <= top;
        if (!latitudeInBounds) {
            return false;
        }
        if (screenArea.left <= screenArea.right) {
            return lon >= screenArea.left && lon <= screenArea.right;
        }
        return lon >= screenArea.left || lon <= screenArea.right;
    }

    private void drawCamera(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                            @NonNull CameraData.CameraPoint camera) {
        float x = tileBox.getPixXFromLatLon(camera.lat, camera.lon);
        float y = tileBox.getPixYFromLatLon(camera.lat, camera.lon);
        int color = getBrandColor(camera.brand);
        float outerRadius = dpToPx(7);
        float innerRadius = dpToPx(5);

        // Draw orientation cone when direction is available and zoom is high enough
        if (tileBox.getZoom() >= 15) {
            Float bearing = parseDirection(camera.direction);
            if (bearing != null) {
                drawCameraCone(canvas, tileBox, x, y, bearing, color);
            }
        }

        // Outer ring: brand-color stroked circle (POI pin style)
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setColor(color);
        markerPaint.setStrokeWidth(dpToPx(2));
        canvas.drawCircle(x, y, outerRadius, markerPaint);

        // Inner white filled circle
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, innerRadius, markerPaint);

        // Tiny camera glyph in brand color: rectangle body + circular lens
        markerPaint.setColor(color);
        float camW = dpToPx(3);
        float camH = dpToPx(2);
        float camLeft = x - camW / 2f;
        float camTop = y - camH / 2f;
        canvas.drawRect(camLeft, camTop, camLeft + camW, camTop + camH, markerPaint);
        canvas.drawCircle(x, y, dpToPx(0.8f), markerPaint);

        // Restore markerPaint to default FILL state for other callers
        markerPaint.setStyle(Paint.Style.FILL);

        if (tileBox.getZoom() >= 15) {
            String label = getShortBrandName(camera.brand);
            canvas.drawText(label, x, y - outerRadius - dpToPx(3), textPaint);
        }
    }

    @androidx.annotation.Nullable
    private Float parseDirection(@androidx.annotation.Nullable String direction) {
        if (direction == null || direction.isEmpty()) {
            return null;
        }
        try {
            float bearing = Float.parseFloat(direction.trim());
            if (bearing >= 0f && bearing <= 360f) {
                return bearing;
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        return null;
    }

    private void drawCameraCone(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                                float x, float y, float compassBearing, int color) {
        // Convert compass bearing (0=N, 90=E) to canvas angle (0=E, 90=S),
        // then apply the map rotation already used by RotatedTileBox projection.
        float screenAngle = (float) Math.toRadians(compassBearing - 90f + tileBox.getRotate());

        float coneLength = dpToPx(CONE_LENGTH_DP);
        float halfAngle = (float) Math.toRadians(CONE_HALF_ANGLE_DEG);

        // Build the cone as a triangle: camera position -> two points at the wide end
        Path conePath = new Path();
        conePath.moveTo(x, y);
        float endX1 = x + (float) (coneLength * Math.cos(screenAngle - halfAngle));
        float endY1 = y + (float) (coneLength * Math.sin(screenAngle - halfAngle));
        float endX2 = x + (float) (coneLength * Math.cos(screenAngle + halfAngle));
        float endY2 = y + (float) (coneLength * Math.sin(screenAngle + halfAngle));
        conePath.lineTo(endX1, endY1);
        conePath.lineTo(endX2, endY2);
        conePath.close();

        // Fill with semi-transparent brand color
        coneFillPaint.setColor(color);
        coneFillPaint.setAlpha(60);
        canvas.drawPath(conePath, coneFillPaint);

        // Stroke with a more opaque brand color
        coneStrokePaint.setColor(color);
        coneStrokePaint.setAlpha(140);
        canvas.drawPath(conePath, coneStrokePaint);
    }

    private void drawCydDetection(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                                  @NonNull CydDetectionCandidate detection) {
        Double lat = detection.getLatitude();
        Double lon = detection.getLongitude();
        if (lat == null || lon == null) {
            return;
        }
        float x = tileBox.getPixXFromLatLon(lat, lon);
        float y = tileBox.getPixYFromLatLon(lat, lon);
        float radius = dpToPx(8);
        Path diamond = new Path();
        diamond.moveTo(x, y - radius);
        diamond.lineTo(x + radius, y);
        diamond.lineTo(x, y + radius);
        diamond.lineTo(x - radius, y);
        diamond.close();

        candidatePaint.setStyle(Paint.Style.FILL);
        candidatePaint.setColor(Color.parseColor("#00ACC1"));
        canvas.drawPath(diamond, candidatePaint);
        candidatePaint.setStyle(Paint.Style.STROKE);
        candidatePaint.setColor(Color.parseColor("#004D60"));
        canvas.drawPath(diamond, candidatePaint);
        candidatePaint.setStyle(Paint.Style.FILL);

        if (tileBox.getZoom() >= 14) {
            canvas.drawText("CYD", x, y - radius - dpToPx(2), textPaint);
        }
    }

    private double getTapRadiusMeters(@NonNull RotatedTileBox tileBox) {
        return Math.max(20, tileBox.getDistance(0, 0, 30, 0));
    }

    private int getBrandColor(String brand) {
        if (brand == null) return Color.parseColor("#757575");
        switch (brand.toLowerCase()) {
            case "flock safety":
            case "flock":
                return Color.parseColor("#E53935"); // red camera marker
            case "motorola":
            case "motorola solutions":
                return Color.parseColor("#FB8C00"); // orange
            case "genetec":
                return Color.parseColor("#1E88E5"); // blue
            case "leonardo":
                return Color.parseColor("#8E24AA"); // purple
            default:
                return Color.parseColor("#757575"); // gray
        }
    }

    private int darkenColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.7f;
        return Color.HSVToColor(hsv);
    }

    private String getShortBrandName(String brand) {
        if (brand == null) return "?";
        String lower = brand.toLowerCase();
        if (lower.contains("flock")) return "Flock";
        if (lower.contains("motorola")) return "Moto";
        if (lower.contains("genetec")) return "Gen";
        if (lower.contains("leonardo")) return "Leo";
        return "?";
    }

    private float dpToPx(float dp) {
        return dp * ((android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi / (float) android.util.DisplayMetrics.DENSITY_DEFAULT));
    }
}
