package net.osmand.plus.plugins.flockfree;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.core.jni.MapMarker;
import net.osmand.core.jni.MapMarkerBuilder;
import net.osmand.core.jni.MapMarkersCollection;
import net.osmand.core.jni.PointI;
import net.osmand.core.jni.SWIGTYPE_p_void;
import net.osmand.core.jni.SingleSkImage;
import net.osmand.core.jni.SwigUtilities;
import net.osmand.core.jni.TextRasterizer;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.core.android.MapRendererView;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.flockfree.cyd.CydDetectionCandidate;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.plus.views.layers.ContextMenuLayer;
import net.osmand.plus.views.layers.MapSelectionResult;
import net.osmand.plus.views.layers.MapSelectionRules;
import net.osmand.plus.views.layers.MapTextLayer;
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
    private static final double CAMERA_QUERY_BOUNDS_PADDING_FACTOR = 0.25;

    // Zoom-based scale factors for camera pin/cone icon sizing.
    // At street level (zoom 15+), markers are full size; they shrink at lower zoom levels
    // so the map doesn't get cluttered when viewing a wider area.
    private static final float PIN_SCALE_ZOOM_CLOSE = 1.0f;   // zoomGroup 15+ (street level)
    private static final float PIN_SCALE_ZOOM_CLUSTER = 0.7f; // zoomGroup 13-14 (neighborhood)
    private static final float PIN_SCALE_ZOOM_FAR = 0.5f;     // zoomGroup 10-12 (city overview)

    private final FlockFreePlugin plugin;
    private final Paint markerPaint;
    private final Paint candidatePaint;
    private final Paint textPaint;
    private final Paint coneFillPaint;
    private final Paint coneStrokePaint;

    // Keep the visible field of view consistent with routing and route camera counts.
    private static final float CONE_HALF_ANGLE_DEG = CameraAvoidanceHelper.CAMERA_CONE_HALF_ANGLE_DEGREES;
    // Cone represents a fixed real-world distance so it scales naturally with zoom.
    // At zoom 15, 100m ≈ 44dp on screen; this keeps cones visually proportional.
    // Cone scales with zoom but not as fast as the map itself, so it stays proportional
    // to the road without dominating the screen at high zoom or vanishing at low zoom.
    // At zoom 15: 44dp (reference). Each zoom level changes cone length by ~1.41x (sqrt of map scale).
    private static final float CONE_BASE_LENGTH_DP = 44f;  // reference screen length at zoom 15
    private static final float CONE_ZOOM_POWER = 0.5f;     // sqrt scaling: 2^(zoom-15) * 0.5

    // Cached pixel dimensions — computed once in constructor to avoid per-frame dpToPx() calls
    private final float cameraOuterRadiusPx;
    private final float cameraInnerRadiusPx;
    private final float markerStrokeWidthPx;
    private final float cameraGlyphWidthPx;
    private final float cameraGlyphHeightPx;
    private final float cameraLensRadiusPx;
    private final float cameraLabelOffsetPx;
    private final float coneLengthPx;  // reference length at zoom 15, used for native cone bitmaps
    private final float detectionRadiusPx;
    private final float detectionLabelOffsetPx;
    private final float clusterCellSizePx;
    private final float clusterBaseRadiusPx;
    private final float clusterRadiusIncrementPx;
    private final float clusterMaxRadiusPx;
    private final float textPaintSizePx;
    private final float coneStrokeWidthPx;

    // Reusable Path objects — avoid per-frame allocation
    private final Path conePath = new Path();
    private final Path diamondPath = new Path();

    // Reusable collections for clustering — avoid per-frame allocation
    private final Map<Long, List<CameraData.CameraPoint>> clusterGrid = new HashMap<>();
    private final Map<Long, float[]> clusterScreenCenter = new HashMap<>();
    private final Map<String, Integer> brandCountsMap = new HashMap<>();
    // Cache key: ((long)zoomGroup << 32) | (color & 0xFFFFFFFFL) — so bitmaps vary by both color and zoom level
    private final Map<Long, SingleSkImage> nativePinImages = new HashMap<>();
    private final Map<Long, SingleSkImage> nativeConeImages = new HashMap<>();
    private final SWIGTYPE_p_void nativeConeIconKey = SwigUtilities.getOnSurfaceIconKey(1);

    private List<CameraData.CameraPoint> visibleCameras = new ArrayList<>();
    private List<CydDetectionCandidate> visibleDetections = new ArrayList<>();
    private List<CameraCluster> visibleClusters = new ArrayList<>();
    private final List<CameraData.CameraPoint> cachedCameraQuery = new ArrayList<>();
    @Nullable
    private QuadRect cachedCameraQueryBounds;
    private int cachedCameraQueryZoomGroup = -1;
    private long cachedCameraDataRevision = -1L;
    private long cameraQueryGeneration;
    private long nativeMarkerQueryGeneration = -1L;
    private int nativeMarkerZoomGroup = -1;
    private int nativeMarkerZoom = -1;  // track actual zoom for cone regeneration
    private boolean nativeMarkerNightMode;
    private boolean nativeRenderPending;

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

        // Cache all dp-to-px conversions once — never call dpToPx() in onDraw/draw methods
        cameraOuterRadiusPx = dpToPx(7);
        cameraInnerRadiusPx = dpToPx(5);
        markerStrokeWidthPx = dpToPx(2);
        cameraGlyphWidthPx = dpToPx(3);
        cameraGlyphHeightPx = dpToPx(2);
        cameraLensRadiusPx = dpToPx(0.8f);
        cameraLabelOffsetPx = dpToPx(3);
        coneLengthPx = dpToPx(CONE_BASE_LENGTH_DP);
        detectionRadiusPx = dpToPx(8);
        detectionLabelOffsetPx = dpToPx(2);
        clusterCellSizePx = dpToPx(CLUSTER_CELL_SIZE_DP);
        clusterBaseRadiusPx = dpToPx(CLUSTER_BASE_RADIUS_DP);
        clusterRadiusIncrementPx = dpToPx(CLUSTER_RADIUS_INCREMENT_DP);
        clusterMaxRadiusPx = dpToPx(CLUSTER_MAX_RADIUS_DP);
        textPaintSizePx = dpToPx(10);
        coneStrokeWidthPx = dpToPx(1.5f);

        markerPaint = new Paint();
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setAntiAlias(true);
        markerPaint.setStrokeWidth(markerStrokeWidthPx);

        candidatePaint = new Paint();
        candidatePaint.setAntiAlias(true);
        candidatePaint.setStrokeWidth(markerStrokeWidthPx);

        textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(textPaintSizePx);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        coneFillPaint = new Paint();
        coneFillPaint.setStyle(Paint.Style.FILL);
        coneFillPaint.setAntiAlias(true);

        coneStrokePaint = new Paint();
        coneStrokePaint.setStyle(Paint.Style.STROKE);
        coneStrokePaint.setAntiAlias(true);
        coneStrokePaint.setStrokeWidth(coneStrokeWidthPx);
    }

    @Override
    public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings drawSettings) {
        if (!plugin.CAMERA_SHOW_LAYER.get()) {
            clearNativeCameraMarkers();
            return;
        }

        QuadRect screenArea = tileBox.getLatLonBounds();
        visibleClusters.clear();
        MapRendererView mapRenderer = getMapRenderer();
        if (tileBox.getZoom() >= MIN_ZOOM_TO_SHOW) {
            CameraData cameraData = plugin.getCameraData();
            if (cameraData.isDataLoaded()) {
                List<CameraData.CameraPoint> queryCameras =
                        getCamerasForScreen(cameraData, screenArea, tileBox.getZoom());
                visibleCameras.clear();
                // The query cache deliberately covers a padded area so map movement does not
                // repeatedly hit SQLite. Only project and draw the cameras on the current screen.
                for (CameraData.CameraPoint camera : queryCameras) {
                    if (isInBounds(screenArea, camera.lat, camera.lon)) {
                        visibleCameras.add(camera);
                    }
                }
                if (tileBox.getZoom() >= CLUSTER_MIN_ZOOM) {
                    if (mapRenderer != null) {
                        updateNativeCameraMarkers(mapRenderer, queryCameras, tileBox.getZoom(),
                                drawSettings.isNightMode());
                    } else {
                        clearNativeCameraMarkers();
                        // Legacy renderer: draw individual markers on the shared canvas.
                        for (CameraData.CameraPoint camera : visibleCameras) {
                            drawCamera(canvas, tileBox, camera);
                        }
                    }
                } else {
                    clearNativeCameraMarkers();
                    // Cluster cameras using grid-based spatial clustering
                    drawClusters(canvas, tileBox, visibleCameras);
                }
            } else {
                clearCameraQueryCache();
                clearNativeCameraMarkers();
                visibleCameras.clear();
            }
        } else {
            clearNativeCameraMarkers();
            visibleCameras.clear();
        }

        fillVisibleDetections(screenArea);
        for (CydDetectionCandidate detection : visibleDetections) {
            drawCydDetection(canvas, tileBox, detection);
        }
    }

    @NonNull
    private List<CameraData.CameraPoint> getCamerasForScreen(@NonNull CameraData cameraData,
                                                             @NonNull QuadRect screenArea,
                                                             int zoom) {
        long dataRevision = cameraData.getDataRevision();
        int zoomGroup = getCameraZoomGroup(zoom);
        QuadRect cachedBounds = cachedCameraQueryBounds;
        if (cachedBounds != null
                && cachedCameraDataRevision == dataRevision
                && cachedCameraQueryZoomGroup == zoomGroup
                && containsBounds(cachedBounds, screenArea)) {
            return cachedCameraQuery;
        }

        QuadRect queryBounds = expandBounds(screenArea);
        cachedCameraQuery.clear();
        cachedCameraQuery.addAll(cameraData.getCamerasInBoundingBox(
                queryBounds.top, queryBounds.left, queryBounds.bottom, queryBounds.right));
        cachedCameraQueryBounds = queryBounds;
        cachedCameraQueryZoomGroup = zoomGroup;
        cachedCameraDataRevision = dataRevision;
        cameraQueryGeneration++;
        return cachedCameraQuery;
    }

    private void clearCameraQueryCache() {
        cachedCameraQuery.clear();
        cachedCameraQueryBounds = null;
        cachedCameraQueryZoomGroup = -1;
        cachedCameraDataRevision = -1L;
        cameraQueryGeneration++;
    }

    @NonNull
    private QuadRect expandBounds(@NonNull QuadRect bounds) {
        double top = Math.max(bounds.top, bounds.bottom);
        double bottom = Math.min(bounds.top, bounds.bottom);
        double latPadding = Math.abs(top - bottom) * CAMERA_QUERY_BOUNDS_PADDING_FACTOR;
        if (bounds.left > bounds.right) {
            return new QuadRect(bounds.left, top + latPadding, bounds.right, bottom - latPadding);
        }
        double lonPadding = Math.abs(bounds.right - bounds.left) * CAMERA_QUERY_BOUNDS_PADDING_FACTOR;
        return new QuadRect(bounds.left - lonPadding, top + latPadding,
                bounds.right + lonPadding, bottom - latPadding);
    }

    private boolean containsBounds(@NonNull QuadRect cached, @NonNull QuadRect screen) {
        double cachedTop = Math.max(cached.top, cached.bottom);
        double cachedBottom = Math.min(cached.top, cached.bottom);
        double screenTop = Math.max(screen.top, screen.bottom);
        double screenBottom = Math.min(screen.top, screen.bottom);
        if (screenTop > cachedTop || screenBottom < cachedBottom) {
            return false;
        }
        if (cached.left > cached.right || screen.left > screen.right) {
            return false;
        }
        return screen.left >= cached.left && screen.right <= cached.right;
    }

    /**
     * Grid-based spatial clustering: divide the screen into a grid of cells,
     * group cameras in the same cell, and draw cluster badges.
     */
    private void drawClusters(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                              @NonNull List<CameraData.CameraPoint> cameras) {
        float cellSize = clusterCellSizePx;

        // Reuse class-level maps — clear at start of each call instead of allocating new ones
        clusterGrid.clear();
        clusterScreenCenter.clear();

        for (CameraData.CameraPoint camera : cameras) {
            PointF pos = getPixelFromLatLon(tileBox, camera.lat, camera.lon);
            float x = pos.x;
            float y = pos.y;
            int cellX = (int) (x / cellSize);
            int cellY = (int) (y / cellSize);
            long key = ((long) cellX << 32) | (cellY & 0xFFFFFFFFL);

            List<CameraData.CameraPoint> cellCameras = clusterGrid.get(key);
            if (cellCameras == null) {
                cellCameras = new ArrayList<>();
                clusterGrid.put(key, cellCameras);
            }
            cellCameras.add(camera);
            float[] sums = clusterScreenCenter.get(key);
            if (sums == null) {
                sums = new float[2];
                clusterScreenCenter.put(key, sums);
            }
            sums[0] += x;
            sums[1] += y;
        }

        for (Map.Entry<Long, List<CameraData.CameraPoint>> entry : clusterGrid.entrySet()) {
            List<CameraData.CameraPoint> cellCameras = entry.getValue();
            int count = cellCameras.size();

            float[] sums = clusterScreenCenter.get(entry.getKey());
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
            LatLon centerLatLon = getLatLonFromPixel(tileBox, centerX, centerY);

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
        float radius = Math.min(clusterBaseRadiusPx + clusterRadiusIncrementPx * (cluster.count - 1),
                clusterMaxRadiusPx);

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
        brandCountsMap.clear();
        for (CameraData.CameraPoint cam : cameras) {
            String key = cam.brand != null ? cam.brand.toLowerCase() : "";
            brandCountsMap.merge(key, 1, Integer::sum);
        }
        String dominantBrand = "";
        int maxCount = 0;
        for (Map.Entry<String, Integer> entry : brandCountsMap.entrySet()) {
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
            float tapRadius = clusterMaxRadiusPx;
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
        LatLon latLon = getLatLonFromPixel(tileBox, point.x, point.y);
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
        LatLon latLon = getLatLonFromPixel(tileBox, point.x, point.y);
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
        LatLon latLon = getLatLonFromPixel(tileBox, point.x, point.y);
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

    private void fillVisibleDetections(@NonNull QuadRect screenArea) {
        visibleDetections.clear();
        for (CydDetectionCandidate detection : plugin.getCydHardwareManager().getRecentDetections()) {
            Double lat = detection.getLatitude();
            Double lon = detection.getLongitude();
            if (lat != null && lon != null && isInBounds(screenArea, lat, lon)) {
                visibleDetections.add(detection);
            }
        }
    }

    @NonNull
    private PointF getPixelFromLatLon(@NonNull RotatedTileBox tileBox, double lat, double lon) {
        MapRendererView mapRenderer = getMapRenderer();
        if (mapRenderer != null) {
            return NativeUtilities.getElevatedPixelFromLatLon(mapRenderer, tileBox, lat, lon);
        }
        return new PointF(tileBox.getPixXFromLatLon(lat, lon), tileBox.getPixYFromLatLon(lat, lon));
    }


    @NonNull
    private LatLon getLatLonFromPixel(@NonNull RotatedTileBox tileBox, float x, float y) {
        MapRendererView mapRenderer = getMapRenderer();
        if (mapRenderer != null) {
            return NativeUtilities.getLatLonFromElevatedPixel(mapRenderer, tileBox, x, y);
        }
        return tileBox.getLatLonFromPixel(x, y);
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

    private void updateNativeCameraMarkers(@NonNull MapRendererView mapRenderer,
                                           @NonNull List<CameraData.CameraPoint> cameras,
                                           int zoom, boolean nightMode) {
        int zoomGroup = getCameraZoomGroup(zoom);
        int roundedZoom = Math.round(zoom);
        if (nativeMarkerQueryGeneration == cameraQueryGeneration
                && nativeMarkerZoomGroup == zoomGroup
                && nativeMarkerZoom == roundedZoom
                && nativeMarkerNightMode == nightMode
                && (cameras.isEmpty() || mapMarkersCollection != null)) {
            return;
        }

        nativeMarkerQueryGeneration = cameraQueryGeneration;
        nativeMarkerZoomGroup = zoomGroup;
        nativeMarkerZoom = roundedZoom;
        nativeMarkerNightMode = nightMode;

        if (cameras.isEmpty()) {
            // Nothing to show — just clean up old markers, no flicker risk.
            clearNativeCameraMarkers(false);
            return;
        }

        // Build the new markers collection BEFORE removing the old one from the renderer.
        // This ensures there is never a frame where no markers exist, preventing flicker.
        MapMarkersCollection newCollection = new MapMarkersCollection();
        TextRasterizer.Style captionStyle = zoomGroup >= 15
                ? MapTextLayer.getTextStyle(getContext(), nightMode, getTextScale(), view.getDensity())
                : null;
        int markerId = 1;
        for (CameraData.CameraPoint camera : cameras) {
            int color = getBrandColor(camera.brand);
            MapMarkerBuilder markerBuilder = new MapMarkerBuilder()
                    .setMarkerId(markerId++)
                    .setBaseOrder(getPointsOrder())
                    .setIsHidden(false)
                    .setIsAccuracyCircleSupported(false)
                    .setPosition(new PointI(
                            MapUtils.get31TileNumberX(camera.lon),
                            MapUtils.get31TileNumberY(camera.lat)))
                    .setPinIcon(getNativePinImage(color, zoomGroup))
                    .setPinIconVerticalAlignment(MapMarker.PinIconVerticalAlignment.CenterVertical)
                    .setPinIconHorisontalAlignment(MapMarker.PinIconHorisontalAlignment.CenterHorizontal);

            float bearing = camera.getBearing();
            boolean hasBearing = camera.hasBearing();
            if (captionStyle != null) {
                markerBuilder.setCaptionStyle(captionStyle)
                        .setCaptionTopSpace(-(cameraOuterRadiusPx * getPinScaleForZoomGroup(zoomGroup)) - (cameraLabelOffsetPx * getPinScaleForZoomGroup(zoomGroup)))
                        .setCaption(getShortBrandName(camera.brand));
            }
            if (zoomGroup >= 15 && hasBearing) {
                markerBuilder.addOnMapSurfaceIcon(nativeConeIconKey, getNativeConeImage(color, zoom));
            }
            MapMarker marker = markerBuilder.buildAndAddToCollection(newCollection);
            if (marker != null && zoomGroup >= 15 && hasBearing) {
                float nativeDirection = (bearing + 180f) % 360f;
                marker.setOnMapSurfaceIconDirection(nativeConeIconKey, nativeDirection);
            }
        }

        // Atomic swap: save old collection, set new one, add new to renderer FIRST,
        // then remove old — so the renderer always has markers visible.
        MapMarkersCollection oldCollection = mapMarkersCollection;
        mapMarkersCollection = newCollection;
        mapRenderer.addSymbolsProvider(newCollection);
        if (oldCollection != null) {
            mapRenderer.removeSymbolsProvider(oldCollection);
        }
        requestNativeCameraRender(mapRenderer);
    }

    private int getCameraZoomGroup(int zoom) {
        if (zoom >= 15) {
            return 15;
        }
        if (zoom >= CLUSTER_MIN_ZOOM) {
            return CLUSTER_MIN_ZOOM;
        }
        return MIN_ZOOM_TO_SHOW;
    }

    /**
     * Returns a scale factor for the given zoom group, used to size pin/cone bitmaps.
     * Closer zoom = larger markers; farther zoom = smaller markers to reduce clutter.
     */
    private static float getPinScaleForZoomGroup(int zoomGroup) {
        if (zoomGroup >= 15) return PIN_SCALE_ZOOM_CLOSE;
        if (zoomGroup >= CLUSTER_MIN_ZOOM) return PIN_SCALE_ZOOM_CLUSTER;
        return PIN_SCALE_ZOOM_FAR;
    }

    /** Composite cache key: combines zoomGroup and color into a single long. */
    private static long pinCacheKey(int color, int zoomGroup) {
        return ((long) zoomGroup << 32) | (color & 0xFFFFFFFFL);
    }

    @NonNull
    private SingleSkImage getNativePinImage(int color, int zoomGroup) {
        long key = pinCacheKey(color, zoomGroup);
        SingleSkImage image = nativePinImages.get(key);
        if (image != null) {
            return image;
        }

        float scale = getPinScaleForZoomGroup(zoomGroup);
        // Scale all pixel dimensions for this zoom level so markers shrink at lower zoom
        float outerRadius = cameraOuterRadiusPx * scale;
        float innerRadius = cameraInnerRadiusPx * scale;
        float strokeWidth = markerStrokeWidthPx * scale;
        float glyphWidth = cameraGlyphWidthPx * scale;
        float glyphHeight = cameraGlyphHeightPx * scale;
        float lensRadius = cameraLensRadiusPx * scale;

        int padding = (int) Math.ceil(strokeWidth);
        int size = (int) Math.ceil(outerRadius * 2f) + padding * 2;
        float center = size / 2f;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(color);
        canvas.drawCircle(center, center, outerRadius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(center, center, innerRadius, paint);

        paint.setColor(color);
        float camLeft = center - glyphWidth / 2f;
        float camTop = center - glyphHeight / 2f;
        canvas.drawRect(camLeft, camTop, camLeft + glyphWidth, camTop + glyphHeight, paint);
        canvas.drawCircle(center, center, lensRadius, paint);

        image = NativeUtilities.createSkImageFromBitmap(bitmap);
        nativePinImages.put(key, image);
        return image;
    }

    @NonNull
    private SingleSkImage getNativeConeImage(int color, int zoom) {
        // Cache key includes rounded zoom so cones regenerate when zoom changes significantly.
        // Round to nearest integer zoom to limit cache entries while staying visually accurate.
        int roundedZoom = Math.round(zoom);
        long key = pinCacheKey(color, roundedZoom);
        SingleSkImage image = nativeConeImages.get(key);
        if (image != null) {
            return image;
        }

        // Cone scales with zoom using sqrt of map scale so it grows when zooming in
        // but doesn't dominate the screen. At zoom 15: reference, zoom 17: ~2x, zoom 13: ~0.5x.
        float scale = getPinScaleForZoomGroup(getCameraZoomGroup(zoom));
        float zoomScaleFactor = (float) Math.pow(2, (roundedZoom - 15) * CONE_ZOOM_POWER);
        float coneLength = coneLengthPx * zoomScaleFactor;
        // Clamp to reasonable screen range
        coneLength = Math.max(coneLength, coneLengthPx * 0.3f);
        coneLength = Math.min(coneLength, coneLengthPx * 3f);
        float coneStrokeW = coneStrokeWidthPx * scale;

        int padding = (int) Math.ceil(coneStrokeW);
        int size = (int) Math.ceil(coneLength * 2f) + padding * 2;
        float center = size / 2f;
        float halfAngle = (float) Math.toRadians(CONE_HALF_ANGLE_DEG);
        float leftX = center + (float) (coneLength * Math.sin(-halfAngle));
        float leftY = center - (float) (coneLength * Math.cos(-halfAngle));
        float rightX = center + (float) (coneLength * Math.sin(halfAngle));
        float rightY = center - (float) (coneLength * Math.cos(halfAngle));

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Path path = new Path();
        path.moveTo(center, center);
        path.lineTo(leftX, leftY);
        path.lineTo(rightX, rightY);
        path.close();

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(60);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(coneStrokeW);
        paint.setAlpha(140);
        canvas.drawPath(path, paint);

        image = NativeUtilities.createSkImageFromBitmap(bitmap);
        nativeConeImages.put(key, image);
        return image;
    }

    private void clearNativeCameraMarkers() {
        clearNativeCameraMarkers(true);
    }

    private void clearNativeCameraMarkers(boolean requestRender) {
        if (mapMarkersCollection != null) {
            MapRendererView mapRenderer = getMapRenderer();
            clearMapMarkersCollections();
            if (requestRender && mapRenderer != null) {
                requestNativeCameraRender(mapRenderer);
            }
        }
    }

    private void requestNativeCameraRender(@NonNull MapRendererView mapRenderer) {
        if (nativeRenderPending) {
            return;
        }
        if (view == null) {
            mapRenderer.requestRender();
            return;
        }
        View layersView = view.getView();
        if (layersView == null) {
            mapRenderer.requestRender();
            return;
        }
        nativeRenderPending = true;
        layersView.postOnAnimation(() -> {
            nativeRenderPending = false;
            if (getMapRenderer() == mapRenderer) {
                mapRenderer.requestRender();
            }
        });
    }

    @Override
    protected void clearMapMarkersCollections() {
        super.clearMapMarkersCollections();
        nativeMarkerQueryGeneration = -1L;
        nativeMarkerZoomGroup = -1;
        nativeMarkerZoom = -1;
        // Clear cached pin/cone bitmaps so stale size or night-mode variants don't persist
        nativePinImages.clear();
        nativeConeImages.clear();
    }

    private void drawCamera(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                            @NonNull CameraData.CameraPoint camera) {
        PointF pos = getPixelFromLatLon(tileBox, camera.lat, camera.lon);
        float x = pos.x;
        float y = pos.y;
        int color = getBrandColor(camera.brand);

        // Apply zoom-based scaling so canvas fallback matches native renderer behavior
        int zoomGroup = getCameraZoomGroup(tileBox.getZoom());
        float scale = getPinScaleForZoomGroup(zoomGroup);
        float outerRadius = cameraOuterRadiusPx * scale;
        float innerRadius = cameraInnerRadiusPx * scale;
        float strokeWidth = markerStrokeWidthPx * scale;
        float glyphWidth = cameraGlyphWidthPx * scale;
        float glyphHeight = cameraGlyphHeightPx * scale;
        float lensRadius = cameraLensRadiusPx * scale;
        float labelOffset = cameraLabelOffsetPx * scale;

        // Draw orientation cone when direction is available and zoom is high enough
        if (tileBox.getZoom() >= 15) {
            Float bearing = parseDirection(camera.direction);
            if (bearing != null) {
                drawCameraCone(canvas, tileBox, x, y, bearing, color, scale);
            }
        }

        // Outer ring: brand-color stroked circle (POI pin style)
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setColor(color);
        markerPaint.setStrokeWidth(strokeWidth);
        canvas.drawCircle(x, y, outerRadius, markerPaint);

        // Inner white filled circle (switch to FILL once — stays for rest of method)
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, innerRadius, markerPaint);

        // Tiny camera glyph in brand color: rectangle body + circular lens
        markerPaint.setColor(color);
        float camLeft = x - glyphWidth / 2f;
        float camTop = y - glyphHeight / 2f;
        canvas.drawRect(camLeft, camTop, camLeft + glyphWidth, camTop + glyphHeight, markerPaint);
        canvas.drawCircle(x, y, lensRadius, markerPaint);

        if (tileBox.getZoom() >= 15) {
            String label = getShortBrandName(camera.brand);
            canvas.drawText(label, x, y - outerRadius - labelOffset, textPaint);
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
                                float x, float y, float compassBearing, int color, float scale) {
        // Convert compass bearing (0=N, 90=E) to canvas angle (0=E, 90=S),
        // then apply the live native-renderer rotation. The RotatedTileBox can lag
        // behind the renderer during an animated turn or pan.
        MapRendererView mapRenderer = getMapRenderer();
        float mapRotation = mapRenderer != null
                ? MapUtils.unifyRotationTo360(-mapRenderer.getAzimuth())
                : tileBox.getRotate();
        float screenAngle = (float) Math.toRadians(compassBearing - 90f + mapRotation);

        float halfAngle = (float) Math.toRadians(CONE_HALF_ANGLE_DEG);
        // Cone scales with zoom using sqrt of map scale factor so it grows when zooming in
        // but doesn't dominate the screen. At zoom 15: 44dp, zoom 17: ~88dp, zoom 13: ~22dp.
        float zoomScaleFactor = (float) Math.pow(2, (tileBox.getFullZoom() - 15) * CONE_ZOOM_POWER);
        float scaledConeLength = coneLengthPx * zoomScaleFactor;
        // Clamp to reasonable screen range
        scaledConeLength = Math.max(scaledConeLength, coneLengthPx * 0.3f);
        scaledConeLength = Math.min(scaledConeLength, coneLengthPx * 3f);

        // Reuse class-level conePath — reset before each use instead of allocating
        conePath.reset();
        conePath.moveTo(x, y);
        float endX1 = x + (float) (scaledConeLength * Math.cos(screenAngle - halfAngle));
        float endY1 = y + (float) (scaledConeLength * Math.sin(screenAngle - halfAngle));
        float endX2 = x + (float) (scaledConeLength * Math.cos(screenAngle + halfAngle));
        float endY2 = y + (float) (scaledConeLength * Math.sin(screenAngle + halfAngle));
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
        coneStrokePaint.setStrokeWidth(coneStrokeWidthPx * scale);
        canvas.drawPath(conePath, coneStrokePaint);
    }

    private void drawCydDetection(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                                  @NonNull CydDetectionCandidate detection) {
        Double lat = detection.getLatitude();
        Double lon = detection.getLongitude();
        if (lat == null || lon == null) {
            return;
        }
        PointF pos = getPixelFromLatLon(tileBox, lat, lon);
        float x = pos.x;
        float y = pos.y;
        float radius = detectionRadiusPx;

        // Reuse class-level diamondPath — reset before each use instead of allocating
        diamondPath.reset();
        diamondPath.moveTo(x, y - radius);
        diamondPath.lineTo(x + radius, y);
        diamondPath.lineTo(x, y + radius);
        diamondPath.lineTo(x - radius, y);
        diamondPath.close();

        candidatePaint.setStyle(Paint.Style.FILL);
        candidatePaint.setColor(Color.parseColor("#00ACC1"));
        canvas.drawPath(diamondPath, candidatePaint);
        candidatePaint.setStyle(Paint.Style.STROKE);
        candidatePaint.setColor(Color.parseColor("#004D60"));
        canvas.drawPath(diamondPath, candidatePaint);
        candidatePaint.setStyle(Paint.Style.FILL);

        if (tileBox.getZoom() >= 14) {
            canvas.drawText("CYD", x, y - radius - detectionLabelOffsetPx, textPaint);
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
