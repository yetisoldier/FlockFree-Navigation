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
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.views.layers.ContextMenuLayer;
import net.osmand.plus.views.layers.MapSelectionResult;
import net.osmand.plus.views.layers.MapSelectionRules;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.util.MapUtils;

import java.util.List;

public class FlockFreeLayer extends OsmandMapLayer implements ContextMenuLayer.IContextMenuProvider {

    private static final int MIN_ZOOM_TO_SHOW = 10;

    private final FlockFreePlugin plugin;
    private final Paint markerPaint;
    private final Paint textPaint;

    private List<CameraData.CameraPoint> visibleCameras = new java.util.ArrayList<>();
    private CameraData.CameraPoint tappedCamera;

    public FlockFreeLayer(@NonNull Context context, @NonNull FlockFreePlugin plugin) {
        super(context);
        this.plugin = plugin;

        markerPaint = new Paint();
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setAntiAlias(true);
        markerPaint.setStrokeWidth(dpToPx(2));

        textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(dpToPx(10));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings drawSettings) {
        if (!plugin.CAMERA_SHOW_LAYER.get()) {
            return;
        }
        if (tileBox.getZoom() < MIN_ZOOM_TO_SHOW) {
            return;
        }
        CameraData cameraData = plugin.getCameraData();
        if (!cameraData.isDataLoaded()) {
            return;
        }

        QuadRect screenArea = tileBox.getLatLonBounds();
        List<CameraData.CameraPoint> cameras = cameraData.getCamerasInBoundingBox(
                screenArea.top, screenArea.left, screenArea.bottom, screenArea.right);

        visibleCameras = cameras;

        for (CameraData.CameraPoint camera : cameras) {
            float x = tileBox.getPixXFromLatLon(camera.lat, camera.lon);
            float y = tileBox.getPixYFromLatLon(camera.lat, camera.lon);
            int color = getBrandColor(camera.brand);
            markerPaint.setColor(color);
            float radius = dpToPx(6);
            canvas.drawCircle(x, y, radius, markerPaint);
            // Draw a darker outline
            markerPaint.setStyle(Paint.Style.STROKE);
            markerPaint.setColor(darkenColor(color));
            canvas.drawCircle(x, y, radius, markerPaint);
            markerPaint.setStyle(Paint.Style.FILL);

            // Draw brand label at higher zoom
            if (tileBox.getZoom() >= 15) {
                String label = getShortBrandName(camera.brand);
                canvas.drawText(label, x, y - radius - dpToPx(2), textPaint);
            }
        }
    }

    @Override
    public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
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
    }

    @Nullable
    @Override
    public LatLon getObjectLocation(Object o) {
        if (o instanceof CameraData.CameraPoint) {
            CameraData.CameraPoint cam = (CameraData.CameraPoint) o;
            return new LatLon(cam.lat, cam.lon);
        }
        return null;
    }

    @Override
    public PointDescription getObjectName(Object o) {
        if (o instanceof CameraData.CameraPoint) {
            CameraData.CameraPoint cam = (CameraData.CameraPoint) o;
            String brand = cam.brand != null ? cam.brand : "ALPR Camera";
            return new PointDescription(PointDescription.POINT_TYPE_POI, brand);
        }
        return null;
    }

    @Override
    public boolean drawInScreenPixels() {
        return false;
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

    private double getTapRadiusMeters(@NonNull RotatedTileBox tileBox) {
        return Math.max(20, tileBox.getDistance(0, 0, 30, 0));
    }

    private int getBrandColor(String brand) {
        if (brand == null) return Color.parseColor("#757575");
        switch (brand.toLowerCase()) {
            case "flock safety":
            case "flock":
                return Color.parseColor("#E53935"); // red
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
