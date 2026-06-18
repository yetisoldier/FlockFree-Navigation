package net.osmand.plus.plugins.flockfree;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.plugins.flockfree.cyd.CydDetectionCandidate;
import net.osmand.plus.plugins.flockfree.cyd.CydHardwareManager;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.quickaction.QuickActionType;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.plus.views.mapwidgets.MapWidgetInfo;
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter;
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.List;

public class FlockFreePlugin extends OsmandPlugin {

    public static final String PLUGIN_ID = "flockfree";

    // Preferences
    public final OsmandPreference<Boolean> CAMERA_SHOW_LAYER;
    public final CommonPreference<Boolean> CAMERA_AVOIDANCE_ENABLED;
    public final CommonPreference<Integer> CAMERA_AVOIDANCE_RADIUS;
    public final CommonPreference<Boolean> CAMERA_ALERTS_ENABLED;
    public final CommonPreference<Integer> CAMERA_ALERT_DISTANCE;
    public final CommonPreference<Long> CAMERA_DATA_LAST_UPDATE;
    public final CommonPreference<Boolean> CYD_BLE_ENABLED;

    // Context menu item order
    private static final int CAMERA_DETAILS_ITEM_ORDER = 7800;
    private static final int CYD_REVIEW_ITEM_ORDER = 7850;
    private static final int CYD_DETAILS_ITEM_ORDER = 7860;
    private static final int ADD_CAMERA_ITEM_ORDER = 7900;
    private static final long CAMERA_ALERT_COOLDOWN_MS = 90_000L;
    private static final long SAME_CAMERA_ALERT_COOLDOWN_MS = 10 * 60_000L;
    private static final float MOVING_ALERT_SPEED_MPS = 2.0f;

    private FlockFreeLayer cameraLayer;
    private CameraData cameraData;
    private CameraAvoidanceHelper avoidanceHelper;
    private CameraReporter cameraReporter;
    private CydHardwareManager cydHardwareManager;
    private long lastCameraAlertTimeMs;
    private String lastCameraAlertKey;

    public FlockFreePlugin(OsmandApplication app) {
        super(app);

        CAMERA_SHOW_LAYER = registerBooleanPreference(
                FlockFreePreferences.CAMERA_SHOW_LAYER,
                FlockFreePreferences.DEFAULT_CAMERA_SHOW_LAYER).makeProfile().cache();
        CAMERA_AVOIDANCE_ENABLED = registerBooleanPreference(
                FlockFreePreferences.CAMERA_AVOIDANCE_ENABLED,
                FlockFreePreferences.DEFAULT_CAMERA_AVOIDANCE_ENABLED).makeProfile().cache();
        CAMERA_AVOIDANCE_RADIUS = registerIntPreference(
                FlockFreePreferences.CAMERA_AVOIDANCE_RADIUS,
                FlockFreePreferences.DEFAULT_CAMERA_AVOIDANCE_RADIUS).makeProfile().cache();
        CAMERA_ALERTS_ENABLED = registerBooleanPreference(
                FlockFreePreferences.CAMERA_ALERTS_ENABLED,
                FlockFreePreferences.DEFAULT_CAMERA_ALERTS_ENABLED).makeProfile().cache();
        CAMERA_ALERT_DISTANCE = registerIntPreference(
                FlockFreePreferences.CAMERA_ALERT_DISTANCE,
                FlockFreePreferences.DEFAULT_CAMERA_ALERT_DISTANCE).makeProfile().cache();
        CAMERA_DATA_LAST_UPDATE = registerLongPreference(
                FlockFreePreferences.CAMERA_DATA_LAST_UPDATE,
                FlockFreePreferences.DEFAULT_CAMERA_DATA_LAST_UPDATE).makeProfile().cache();
        CYD_BLE_ENABLED = registerBooleanPreference(
                FlockFreePreferences.CYD_BLE_ENABLED,
                FlockFreePreferences.DEFAULT_CYD_BLE_ENABLED).makeProfile().cache();
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return app.getString(R.string.flockfree_plugin_name);
    }

    @Override
    public CharSequence getDescription(boolean linksEnabled) {
        return app.getString(R.string.flockfree_plugin_description);
    }

    @Override
    public boolean isEnableByDefault() {
        return true;
    }

    @Nullable
    @Override
    public SettingsScreenType getSettingsScreenType() {
        return SettingsScreenType.FLOCKFREE_SETTINGS;
    }

    @Override
    public String getPrefsDescription() {
        return app.getString(R.string.flockfree_plugin_prefs_description);
    }

    @NonNull
    public CameraData getCameraData() {
        if (cameraData == null) {
            cameraData = new CameraData(app);
        }
        return cameraData;
    }

    @NonNull
    public CameraAvoidanceHelper getAvoidanceHelper() {
        if (avoidanceHelper == null) {
            avoidanceHelper = new CameraAvoidanceHelper(app, this);
        }
        return avoidanceHelper;
    }

    @NonNull
    public CameraReporter getCameraReporter() {
        if (cameraReporter == null) {
            cameraReporter = new CameraReporter(app);
        }
        return cameraReporter;
    }

    @NonNull
    public CydHardwareManager getCydHardwareManager() {
        if (cydHardwareManager == null) {
            cydHardwareManager = new CydHardwareManager(app);
        }
        return cydHardwareManager;
    }

    @Override
    protected List<QuickActionType> getQuickActionTypes() {
        List<QuickActionType> actions = new ArrayList<>();
        // Quick action types would be registered here once the action classes are created
        return actions;
    }

    @Override
    public void registerLayers(@NonNull Context context, @Nullable MapActivity mapActivity) {
        if (cameraLayer != null) {
            app.getOsmandMap().getMapView().removeLayer(cameraLayer);
        }
        cameraLayer = new FlockFreeLayer(context, this);
    }

    @Override
    public void updateLayers(@NonNull Context context, @Nullable MapActivity mapActivity) {
        if (isActive()) {
            if (cameraLayer == null) {
                registerLayers(context, mapActivity);
            }
            boolean showLayer = CAMERA_SHOW_LAYER.get();
            boolean hasLayer = app.getOsmandMap().getMapView().getLayers().contains(cameraLayer);
            if (showLayer != hasLayer) {
                if (showLayer) {
                    app.getOsmandMap().getMapView().addLayer(cameraLayer, 3.0f);
                } else {
                    app.getOsmandMap().getMapView().removeLayer(cameraLayer);
                }
            }
        } else {
            if (cameraLayer != null) {
                app.getOsmandMap().getMapView().removeLayer(cameraLayer);
            }
        }
    }

    public FlockFreeLayer getCameraLayer() {
        return cameraLayer;
    }

    @Override
    public void createWidgets(@NonNull MapActivity activity, @NonNull List<MapWidgetInfo> widgetInfos,
                              @NonNull net.osmand.plus.settings.backend.ApplicationMode appMode,
                              @Nullable net.osmand.plus.settings.enums.ScreenLayoutMode layoutMode) {
        // Widget registration would go here once CameraCountWidget is implemented
    }

    @Override
    protected void registerMapContextMenuActions(@NonNull MapActivity mapActivity,
                                                  double latitude, double longitude,
                                                  @NonNull ContextMenuAdapter adapter,
                                                  Object selectedObj, boolean configureMenu) {
        if (selectedObj instanceof CameraData.CameraPoint) {
            adapter.addItem(new ContextMenuItem(PLUGIN_ID + ".camera_details")
                    .setTitle(app.getString(R.string.flockfree_camera_details))
                    .setIcon(R.drawable.ic_action_info_dark)
                    .setOrder(CAMERA_DETAILS_ITEM_ORDER)
                    .setListener((uiAdapter, view, item, isChecked) -> {
                        showCameraDetails(mapActivity, (CameraData.CameraPoint) selectedObj);
                        return true;
                    }));
        } else if (selectedObj instanceof CydDetectionCandidate) {
            CydDetectionCandidate detection = (CydDetectionCandidate) selectedObj;
            adapter.addItem(new ContextMenuItem(PLUGIN_ID + ".cyd_review")
                    .setTitle(app.getString(R.string.flockfree_cyd_review_detection))
                    .setIcon(R.drawable.ic_action_plus_dark)
                    .setOrder(CYD_REVIEW_ITEM_ORDER)
                    .setListener((uiAdapter, view, item, isChecked) -> {
                        showCydDetectionReport(mapActivity, detection);
                        return true;
                    }));
            adapter.addItem(new ContextMenuItem(PLUGIN_ID + ".cyd_details")
                    .setTitle(app.getString(R.string.flockfree_cyd_detection_details))
                    .setIcon(R.drawable.ic_action_info_dark)
                    .setOrder(CYD_DETAILS_ITEM_ORDER)
                    .setListener((uiAdapter, view, item, isChecked) -> {
                        showCydDetectionDetails(mapActivity, detection);
                        return true;
                    }));
        }
        adapter.addItem(new ContextMenuItem(PLUGIN_ID + ".add_camera")
                .setTitle(app.getString(R.string.flockfree_add_camera))
                .setIcon(R.drawable.ic_action_plus_dark)
                .setOrder(ADD_CAMERA_ITEM_ORDER)
                .setListener((uiAdapter, view, item, isChecked) -> {
                    getCameraReporter().showAddCameraDialog(mapActivity, latitude, longitude);
                    return true;
                }));
    }

    public void showCameraDetails(@NonNull MapActivity mapActivity, @NonNull CameraData.CameraPoint camera) {
        StringBuilder sb = new StringBuilder();
        String unknown = app.getString(R.string.res_unknown);
        String na = app.getString(R.string.n_a);
        sb.append(app.getString(R.string.flockfree_detail_brand)).append(": ").append(camera.brand != null ? camera.brand : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_operator)).append(": ").append(camera.operator != null ? camera.operator : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_direction)).append(": ").append(camera.direction != null ? camera.direction : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_mount_type)).append(": ").append(camera.mountType != null ? camera.mountType : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_surveillance_zone)).append(": ").append(camera.surveillanceZone != null ? camera.surveillanceZone : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_osm_id)).append(": ").append(camera.osmId != null ? camera.osmId : na).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_osm_type)).append(": ").append(camera.osmType != null ? camera.osmType : na).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_last_updated)).append(": ").append(camera.osmTimestamp != null ? camera.osmTimestamp : na);
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(mapActivity);
        builder.setTitle(R.string.flockfree_alpr_camera)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.shared_string_ok, null)
                .show();
    }

    public void showCydDetectionDetails(@NonNull MapActivity mapActivity,
                                        @NonNull CydDetectionCandidate detection) {
        StringBuilder sb = new StringBuilder();
        sb.append(app.getString(R.string.flockfree_cyd_detail_type)).append(": ")
                .append(detection.getDetectionTypeLabel()).append("\n");
        sb.append(app.getString(R.string.flockfree_cyd_detail_source)).append(": ")
                .append(detection.getSourceLabel()).append("\n");
        sb.append(app.getString(R.string.flockfree_cyd_detail_signal)).append(": ")
                .append(detection.getSignalStatus()).append("\n");
        sb.append(app.getString(R.string.flockfree_cyd_detail_gps)).append(": ")
                .append(detection.getGpsStatus());
        Integer channel = detection.getChannel();
        Integer frequency = detection.getFrequency();
        if (channel != null || frequency != null) {
            sb.append("\n").append(app.getString(R.string.flockfree_cyd_detail_channel)).append(": ");
            if (channel != null) {
                sb.append(channel);
            }
            if (frequency != null) {
                if (channel != null) {
                    sb.append(" / ");
                }
                sb.append(frequency).append(" MHz");
            }
        }
        long ageMs = detection.getReceivedAgeMs(System.currentTimeMillis());
        sb.append("\n").append(app.getString(R.string.flockfree_cyd_detail_received_age)).append(": ")
                .append(ageMs / 1000L).append(" s");

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(mapActivity);
        builder.setTitle(R.string.flockfree_cyd_detection_details)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.flockfree_cyd_review_detection,
                        (dialog, which) -> showCydDetectionReport(mapActivity, detection))
                .setNegativeButton(R.string.shared_string_close, null)
                .show();
    }

    private void showCydDetectionReport(@NonNull MapActivity mapActivity,
                                        @NonNull CydDetectionCandidate detection) {
        Double lat = detection.getLatitude();
        Double lon = detection.getLongitude();
        if (lat == null || lon == null) {
            app.showShortToastMessage(R.string.flockfree_cyd_detection_no_gps);
            return;
        }
        getCameraReporter().showAddCameraDialog(mapActivity, lat, lon);
    }

    @Override
    public void mapActivityCreate(@NonNull MapActivity activity) {
        // Pre-load camera data on map activity create
        getCameraData().ensureDataLoaded();
    }

    @Override
    public void mapActivityResume(@NonNull MapActivity activity) {
        getCameraData().ensureDataLoaded();
    }

    @Override
    public void disable(@NonNull OsmandApplication app) {
        super.disable(app);
        if (cydHardwareManager != null) {
            cydHardwareManager.close();
            cydHardwareManager = null;
        }
    }

    @Override
    public void updateLocation(Location location) {
        if (location == null) {
            return;
        }
        updateCydPhoneLocation(location);
        if (!shouldCheckCameraAlert(location)) {
            return;
        }
        CameraData data = getCameraData();
        if (!data.isDataLoaded()) {
            data.ensureDataLoaded();
            return;
        }
        int alertDistance = CAMERA_ALERT_DISTANCE.get();
        List<CameraData.CameraPoint> cameras = data.getCamerasNear(
                location.getLatitude(), location.getLongitude(), alertDistance);
        CameraData.CameraPoint closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (CameraData.CameraPoint camera : cameras) {
            double distance = MapUtils.getDistance(
                    location.getLatitude(), location.getLongitude(), camera.lat, camera.lon);
            if (distance < closestDistance) {
                closest = camera;
                closestDistance = distance;
            }
        }
        if (closest != null) {
            showCameraAlertIfNeeded(closest, closestDistance);
        }
    }

    private void updateCydPhoneLocation(@NonNull Location location) {
        if (CYD_BLE_ENABLED.get() && cydHardwareManager != null) {
            cydHardwareManager.updatePhoneLocation(location);
        }
    }

    private boolean shouldCheckCameraAlert(@NonNull Location location) {
        if (!CAMERA_ALERTS_ENABLED.get()) {
            return false;
        }
        int alertDistance = CAMERA_ALERT_DISTANCE.get();
        if (alertDistance <= 0) {
            return false;
        }
        if (location.hasAccuracy() && location.getAccuracy() > alertDistance) {
            return false;
        }
        return app.getRoutingHelper().isFollowingMode()
                || (location.hasSpeed() && location.getSpeed() >= MOVING_ALERT_SPEED_MPS);
    }

    private void showCameraAlertIfNeeded(@NonNull CameraData.CameraPoint camera, double distanceMeters) {
        long now = System.currentTimeMillis();
        String cameraKey = getCameraAlertKey(camera);
        long cooldown = cameraKey.equals(lastCameraAlertKey)
                ? SAME_CAMERA_ALERT_COOLDOWN_MS
                : CAMERA_ALERT_COOLDOWN_MS;
        if (now - lastCameraAlertTimeMs < cooldown) {
            return;
        }
        lastCameraAlertTimeMs = now;
        lastCameraAlertKey = cameraKey;
        String brand = camera.brand != null ? camera.brand : app.getString(R.string.res_unknown);
        app.showShortToastMessage(R.string.flockfree_nearby_camera_alert,
                brand, Math.max(1, Math.round((float) distanceMeters)));
    }

    @NonNull
    private String getCameraAlertKey(@NonNull CameraData.CameraPoint camera) {
        if (camera.osmType != null && camera.osmId != null) {
            return camera.osmType + ":" + camera.osmId;
        }
        return Math.round(camera.lat * 1_000_000d) + ":" + Math.round(camera.lon * 1_000_000d);
    }

    @Override
    public void newRouteIsCalculated(boolean newRoute) {
        if (!newRoute || !CAMERA_AVOIDANCE_ENABLED.get()) {
            return;
        }
        getCameraData().ensureDataLoaded();
        if (!getCameraData().isDataLoaded()) {
            app.showShortToastMessage(R.string.flockfree_route_camera_data_loading);
            return;
        }
        RouteCalculationResult route = app.getRoutingHelper().getRoute();
        if (route == null || !route.isCalculated()) {
            return;
        }
        List<Location> routeLocations = route.getImmutableAllLocations();
        if (routeLocations == null || routeLocations.isEmpty()) {
            return;
        }
        CameraAvoidanceHelper helper = getAvoidanceHelper();
        String routeSummary = helper.getRouteCameraSummaryFromLocations(routeLocations);
        String avoidanceSummary = helper.consumeLastAvoidanceStatusSummary();
        if (!avoidanceSummary.isEmpty()) {
            routeSummary = routeSummary + "\n" + avoidanceSummary;
        }
        app.showToastMessage(routeSummary);
    }
}
