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

import java.util.List;

public class FlockFreeLayer extends OsmandMapLayer implements ContextMenuLayer.IContextMenuProvider {

    private static final int MIN_ZOOM_TO_SHOW = 10;

    private final FlockFreePlugin plugin;
    private final Paint markerPaint;
    private final Paint candidatePaint;
    private final Paint textPaint;
    private final Paint coneFillPaint;
    private final Paint coneStrokePaint;

    private static final float CONE_HALF_ANGLE_DEG = 30f; // half-angle of the view cone on each side
    private static final float CONE_LENGTH_DP = 22f;     // screen-space cone length in dp

    private List<CameraData.CameraPoint> visibleCameras = new java.util.ArrayList<>();
    private List<CydDetectionCandidate> visibleDetections = new java.util.ArrayList<>();

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
        if (tileBox.getZoom() >= MIN_ZOOM_TO_SHOW) {
            CameraData cameraData = plugin.getCameraData();
            if (cameraData.isDataLoaded()) {
                List<CameraData.CameraPoint> cameras = cameraData.getCamerasInBoundingBox(
                        screenArea.top, screenArea.left, screenArea.bottom, screenArea.right);
                visibleCameras = cameras;
                for (CameraData.CameraPoint camera : cameras) {
                    drawCamera(canvas, tileBox, camera);
                }
            } else {
                visibleCameras = new java.util.ArrayList<>();
            }
        } else {
            visibleCameras = new java.util.ArrayList<>();
        }

        visibleDetections = getVisibleDetections(screenArea);
        for (CydDetectionCandidate detection : visibleDetections) {
            drawCydDetection(canvas, tileBox, detection);
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
        markerPaint.setColor(color);
        float radius = dpToPx(6);

        // Draw orientation cone when direction is available and zoom is high enough
        if (tileBox.getZoom() >= 15) {
            Float bearing = parseDirection(camera.direction);
            if (bearing != null) {
                drawCameraCone(canvas, tileBox, x, y, bearing, color);
            }
        }

        canvas.drawCircle(x, y, radius, markerPaint);
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setColor(darkenColor(color));
        canvas.drawCircle(x, y, radius, markerPaint);
        markerPaint.setStyle(Paint.Style.FILL);

        if (tileBox.getZoom() >= 15) {
            String label = getShortBrandName(camera.brand);
            canvas.drawText(label, x, y - radius - dpToPx(2), textPaint);
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
        // Convert compass bearing (0=N, 90=E) to screen angle (0=E, 90=S in canvas terms)
        // and account for map rotation so the cone points correctly on a rotated map.
        float mapRotation = tileBox.getRotate(); // radians
        float bearingRad = (float) Math.toRadians(compassBearing);
        // Screen angle: compass bearing - 90 (to convert from north-up to east-right canvas)
        // minus map rotation (to counter-rotate for the map's rotation)
        float screenAngle = bearingRad - (float) (Math.PI / 2) - mapRotation;

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
