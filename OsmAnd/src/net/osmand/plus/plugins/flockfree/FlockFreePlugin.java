package net.osmand.plus.plugins.flockfree;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;

import net.osmand.Location;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.plugins.flockfree.cyd.CydBleService;
import net.osmand.plus.plugins.flockfree.cyd.CydDetectionCandidate;
import net.osmand.plus.plugins.flockfree.cyd.CydHardwareManager;
import net.osmand.plus.plugins.flockfree.wifi.WifiScannerManager;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.plugins.flockfree.widgets.CameraProximityWidget;
import net.osmand.plus.quickaction.QuickActionType;
import net.osmand.plus.quickaction.actions.ShowHideCamerasAction;
import net.osmand.plus.quickaction.actions.ToggleCameraAvoidanceAction;
import net.osmand.plus.quickaction.actions.ToggleCameraAlertsAction;
import net.osmand.plus.quickaction.actions.AddCameraAction;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.plus.views.mapwidgets.MapWidgetInfo;
import net.osmand.plus.views.mapwidgets.WidgetInfoCreator;
import net.osmand.plus.views.mapwidgets.WidgetType;
import net.osmand.plus.views.mapwidgets.WidgetsPanel;
import net.osmand.plus.views.mapwidgets.widgets.MapWidget;
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
    public final CommonPreference<Boolean> WIFI_SCAN_ENABLED;
    public final CommonPreference<String> CAMERA_ROUTE_LAST_CHECK_SUMMARY;
    public final CommonPreference<String> CAMERA_ALERT_LAST_CHECK_SUMMARY;
    public final CommonPreference<String> CAMERA_REPORT_LAST_DRAFT_SUMMARY;
    public final CommonPreference<String> CAMERA_NEAREST_LAST_CHECK_SUMMARY;

    // Context menu item order
    private static final int CAMERA_DETAILS_ITEM_ORDER = 7800;
    private static final int CYD_REVIEW_ITEM_ORDER = 7850;
    private static final int CYD_DETAILS_ITEM_ORDER = 7860;
    private static final int ADD_CAMERA_ITEM_ORDER = 7900;
    private static final long CAMERA_ALERT_COOLDOWN_MS = 90_000L;
    private static final String CAMERA_ALERT_CHANNEL_ID = "flockfree_camera_alert";
    private static final int CAMERA_ALERT_NOTIFICATION_ID = 7901;
    private static final long SAME_CAMERA_ALERT_COOLDOWN_MS = 10 * 60_000L;
    private static final float MOVING_ALERT_SPEED_MPS = 2.0f;
    private static final int MAP_CENTER_CAMERA_SEARCH_RADIUS_METERS = 5_000;

    private FlockFreeLayer cameraLayer;
    private CameraData cameraData;
    private CameraAvoidanceHelper avoidanceHelper;
    private CameraReporter cameraReporter;
    private CydHardwareManager cydHardwareManager;
    private WifiScannerManager wifiScannerManager;
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
        CommonPreference<Boolean> cydBleEnabled = registerBooleanPreference(
                FlockFreePreferences.CYD_BLE_ENABLED,
                FlockFreePreferences.DEFAULT_CYD_BLE_ENABLED).makeProfile().cache();
        migrateCydBleEnabledToGlobal(cydBleEnabled);
        CYD_BLE_ENABLED = cydBleEnabled;
        CommonPreference<Boolean> wifiScanEnabled = registerBooleanPreference(
                FlockFreePreferences.WIFI_SCAN_ENABLED,
                FlockFreePreferences.DEFAULT_WIFI_SCAN_ENABLED).makeProfile().cache();
        WIFI_SCAN_ENABLED = wifiScanEnabled;
        CAMERA_ROUTE_LAST_CHECK_SUMMARY = registerStringPreference(
                FlockFreePreferences.CAMERA_ROUTE_LAST_CHECK_SUMMARY,
                FlockFreePreferences.DEFAULT_STATUS_SUMMARY).makeProfile().cache();
        CAMERA_ALERT_LAST_CHECK_SUMMARY = registerStringPreference(
                FlockFreePreferences.CAMERA_ALERT_LAST_CHECK_SUMMARY,
                FlockFreePreferences.DEFAULT_STATUS_SUMMARY).makeProfile().cache();
        CAMERA_REPORT_LAST_DRAFT_SUMMARY = registerStringPreference(
                FlockFreePreferences.CAMERA_REPORT_LAST_DRAFT_SUMMARY,
                FlockFreePreferences.DEFAULT_STATUS_SUMMARY).makeProfile().cache();
        CAMERA_NEAREST_LAST_CHECK_SUMMARY = registerStringPreference(
                FlockFreePreferences.CAMERA_NEAREST_LAST_CHECK_SUMMARY,
                FlockFreePreferences.DEFAULT_STATUS_SUMMARY).makeProfile().cache();
    }

    private void migrateCydBleEnabledToGlobal(@NonNull CommonPreference<Boolean> preference) {
        boolean migrateEnabled = false;
        if (!app.getSettings().isSet(true, preference.getId())) {
            for (ApplicationMode mode : ApplicationMode.allPossibleValues()) {
                if (preference.isSetForMode(mode) && Boolean.TRUE.equals(preference.getModeValue(mode))) {
                    migrateEnabled = true;
                    break;
                }
            }
        }
        preference.makeGlobal();
        if (migrateEnabled) {
            preference.set(true);
        }
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
            cameraReporter = new CameraReporter(app, CAMERA_REPORT_LAST_DRAFT_SUMMARY);
        }
        return cameraReporter;
    }

    @NonNull
    public CydHardwareManager getCydHardwareManager() {
        if (cydHardwareManager == null) {
            cydHardwareManager = new CydHardwareManager(app);
            cydHardwareManager.setConnectionListener(state -> onCydConnectionStateChanged());
        }
        return cydHardwareManager;
    }

    @NonNull
    public WifiScannerManager getWifiScannerManager() {
        if (wifiScannerManager == null) {
            wifiScannerManager = new WifiScannerManager(app);
            wifiScannerManager.setListener(new WifiScannerManager.Listener() {
                @Override
                public void onWifiFlockDetection(@NonNull WifiScannerManager.WifiFlockDetection detection, boolean isNew) {
                    if (isNew) {
                        app.showShortToastMessage(R.string.flockfree_wifi_detection_received);
                    }
                }

                @Override
                public void onWifiScanCycleCompleted(int totalDevicesScanned, int flockMatchesTotal) {
                    // Could log or update UI — for now just let status refresh handle it
                }
            });
        }
        return wifiScannerManager;
    }

    @NonNull
    public synchronized String getLastRouteCheckSummary() {
        String summary = CAMERA_ROUTE_LAST_CHECK_SUMMARY.get();
        return summary != null && summary.length() > 0
                ? summary
                : app.getString(R.string.flockfree_route_last_check_none);
    }

    private synchronized void setLastRouteCheckSummary(@NonNull String summary) {
        CAMERA_ROUTE_LAST_CHECK_SUMMARY.set(summary);
    }

    @NonNull
    public synchronized String getLastCameraAlertCheckSummary() {
        String summary = CAMERA_ALERT_LAST_CHECK_SUMMARY.get();
        return summary != null && summary.length() > 0
                ? summary
                : app.getString(R.string.flockfree_alert_last_check_none);
    }

    private synchronized void setLastCameraAlertCheckSummary(@NonNull String summary) {
        CAMERA_ALERT_LAST_CHECK_SUMMARY.set(summary);
    }

    @NonNull
    public synchronized String getLastNearestCameraSummary() {
        String summary = CAMERA_NEAREST_LAST_CHECK_SUMMARY.get();
        return summary != null && summary.length() > 0
                ? summary
                : app.getString(R.string.flockfree_camera_nearest_last_check_none);
    }

    private synchronized void setLastNearestCameraSummary(@NonNull String summary) {
        CAMERA_NEAREST_LAST_CHECK_SUMMARY.set(summary);
    }

    public void checkCameraAlertAtMapCenter(@Nullable MapActivity mapActivity) {
        if (mapActivity == null || mapActivity.getMapView() == null) {
            setLastCameraAlertCheckSummary(app.getString(R.string.flockfree_alert_last_check_map_unavailable));
            app.showShortToastMessage(R.string.flockfree_alert_last_check_map_unavailable);
            return;
        }
        double latitude = mapActivity.getMapView().getLatitude();
        double longitude = mapActivity.getMapView().getLongitude();
        if (!isValidCoordinate(latitude, longitude)) {
            setLastCameraAlertCheckSummary(app.getString(R.string.flockfree_alert_last_check_map_unavailable));
            app.showShortToastMessage(R.string.flockfree_alert_last_check_map_unavailable);
            return;
        }
        checkCameraAlertAt(latitude, longitude, null, false, false, true);
    }

    public void showNearestCameraAtMapCenter(@Nullable MapActivity mapActivity) {
        if (mapActivity == null || mapActivity.getMapView() == null) {
            String summary = app.getString(R.string.flockfree_camera_nearest_map_unavailable);
            setLastNearestCameraSummary(summary);
            app.showShortToastMessage(summary);
            return;
        }
        double latitude = mapActivity.getMapView().getLatitude();
        double longitude = mapActivity.getMapView().getLongitude();
        if (!isValidCoordinate(latitude, longitude)) {
            String summary = app.getString(R.string.flockfree_camera_nearest_map_unavailable);
            setLastNearestCameraSummary(summary);
            app.showShortToastMessage(summary);
            return;
        }
        CameraData data = getCameraData();
        if (!data.isDataLoaded()) {
            data.ensureDataLoaded();
            String summary = app.getString(R.string.flockfree_camera_nearest_loading);
            setLastNearestCameraSummary(summary);
            app.showShortToastMessage(summary);
            return;
        }
        List<CameraData.CameraPoint> cameras = data.getCamerasNear(
                latitude, longitude, MAP_CENTER_CAMERA_SEARCH_RADIUS_METERS);
        CameraData.CameraPoint closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (CameraData.CameraPoint camera : cameras) {
            double distance = MapUtils.getDistance(latitude, longitude, camera.lat, camera.lon);
            if (distance < closestDistance) {
                closest = camera;
                closestDistance = distance;
            }
        }
        if (closest == null) {
            String summary = app.getString(R.string.flockfree_camera_nearest_none,
                    MAP_CENTER_CAMERA_SEARCH_RADIUS_METERS);
            setLastNearestCameraSummary(summary);
            app.showShortToastMessage(summary);
            return;
        }
        String brand = closest.brand != null ? closest.brand : app.getString(R.string.res_unknown);
        int roundedDistance = Math.max(1, Math.round((float) closestDistance));
        setLastNearestCameraSummary(app.getString(
                R.string.flockfree_camera_nearest_last_check_found, brand, roundedDistance));
        showCameraDetails(mapActivity, closest, closestDistance,
                R.string.flockfree_camera_nearest_details);
    }

    @Override
    protected List<QuickActionType> getQuickActionTypes() {
        List<QuickActionType> actions = new ArrayList<>();
        actions.add(ShowHideCamerasAction.TYPE);
        actions.add(ToggleCameraAvoidanceAction.TYPE);
        actions.add(ToggleCameraAlertsAction.TYPE);
        actions.add(AddCameraAction.TYPE);
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
        WidgetInfoCreator creator = new WidgetInfoCreator(app, appMode, layoutMode);
        MapWidget cameraWidget = createMapWidgetForParams(activity, WidgetType.CAMERA_PROXIMITY);
        if (cameraWidget != null) {
            widgetInfos.add(creator.createWidgetInfo(cameraWidget));
        }
    }

    @Nullable
    @Override
    protected MapWidget createMapWidgetForParams(@NonNull MapActivity mapActivity, @NonNull WidgetType widgetType,
                                                  @Nullable String customId, @Nullable WidgetsPanel widgetsPanel) {
        if (widgetType == WidgetType.CAMERA_PROXIMITY) {
            return new CameraProximityWidget(mapActivity, customId, widgetsPanel);
        }
        return null;
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
        showCameraDetails(mapActivity, camera, null, R.string.flockfree_alpr_camera);
    }

    private void showCameraDetails(@NonNull MapActivity mapActivity,
                                   @NonNull CameraData.CameraPoint camera,
                                   @Nullable Double distanceFromMapCenterMeters,
                                   @StringRes int titleId) {
        StringBuilder sb = new StringBuilder();
        String unknown = app.getString(R.string.res_unknown);
        String na = app.getString(R.string.n_a);
        if (distanceFromMapCenterMeters != null) {
            sb.append(app.getString(R.string.flockfree_detail_distance_from_map_center)).append(": ")
                    .append(Math.max(1, Math.round(distanceFromMapCenterMeters.floatValue())))
                    .append(" ").append(app.getString(R.string.shared_string_meters)).append("\n");
        }
        sb.append(app.getString(R.string.flockfree_detail_brand)).append(": ").append(camera.brand != null ? camera.brand : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_operator)).append(": ").append(camera.operator != null ? camera.operator : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_direction)).append(": ").append(camera.direction != null ? camera.direction : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_mount_type)).append(": ").append(camera.mountType != null ? camera.mountType : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_surveillance_zone)).append(": ").append(camera.surveillanceZone != null ? camera.surveillanceZone : unknown).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_osm_id)).append(": ").append(camera.osmId != null ? camera.osmId : na).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_osm_type)).append(": ").append(camera.osmType != null ? camera.osmType : na).append("\n");
        sb.append(app.getString(R.string.flockfree_detail_last_updated)).append(": ").append(camera.osmTimestamp != null ? camera.osmTimestamp : na);
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(mapActivity);
        builder.setTitle(titleId)
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
        ensureCydScanIfEnabled(activity);
        ensureWifiScanIfEnabled();
    }

    @Override
    public void disable(@NonNull OsmandApplication app) {
        super.disable(app);
        CydBleService.stop(app);
        if (cydHardwareManager != null) {
            cydHardwareManager.close();
            cydHardwareManager = null;
        }
        if (wifiScannerManager != null) {
            wifiScannerManager.stop();
        }
    }

    @Override
    public void updateLocation(Location location) {
        if (location == null) {
            return;
        }
        updateCydPhoneLocation(location);
        Float accuracy = location.hasAccuracy() ? location.getAccuracy() : null;
        boolean moving = location.hasSpeed() && location.getSpeed() >= MOVING_ALERT_SPEED_MPS;
        checkCameraAlertAt(location.getLatitude(), location.getLongitude(), accuracy, true, moving, false);
    }

    private void checkCameraAlertAt(double latitude, double longitude, @Nullable Float accuracy,
                                    boolean requireNavigationOrMovement, boolean moving, boolean forceAlert) {
        String skipReason = getCameraAlertSkipReason(accuracy, requireNavigationOrMovement, moving);
        if (skipReason != null) {
            setLastCameraAlertCheckSummary(skipReason);
            return;
        }
        CameraData data = getCameraData();
        if (!data.isDataLoaded()) {
            setLastCameraAlertCheckSummary(app.getString(R.string.flockfree_alert_last_check_loading));
            data.ensureDataLoaded();
            return;
        }
        int alertDistance = CAMERA_ALERT_DISTANCE.get();
        List<CameraData.CameraPoint> cameras = data.getCamerasNear(
                latitude, longitude, alertDistance);
        CameraData.CameraPoint closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (CameraData.CameraPoint camera : cameras) {
            double distance = MapUtils.getDistance(
                    latitude, longitude, camera.lat, camera.lon);
            if (distance < closestDistance) {
                closest = camera;
                closestDistance = distance;
            }
        }
        if (closest != null) {
            showCameraAlertIfNeeded(closest, closestDistance, forceAlert);
        } else {
            setLastCameraAlertCheckSummary(app.getString(R.string.flockfree_alert_last_check_no_cameras,
                    alertDistance));
        }
    }

    private void updateCydPhoneLocation(@NonNull Location location) {
        if (CYD_BLE_ENABLED.get() && cydHardwareManager != null) {
            cydHardwareManager.updatePhoneLocation(location);
        }
    }

    private void ensureCydScanIfEnabled(@NonNull MapActivity activity) {
        if (!CYD_BLE_ENABLED.get()) {
            return;
        }
        CydBleService.start(activity);
        CydHardwareManager manager = getCydHardwareManager();
        CydHardwareManager.State state = manager.getState();
        if (state == CydHardwareManager.State.IDLE || state == CydHardwareManager.State.ERROR) {
            manager.startScanAndConnect(activity);
        }
    }

    /**
     * Start WiFi scanning if enabled, unless a CYD hardware device is connected.
     * When the CYD is actively scanning (promiscuous WiFi), the phone's WiFi scanner
     * is redundant and wastes battery — so we disable it.
     */
    public boolean ensureWifiScanIfEnabled() {
        if (!WIFI_SCAN_ENABLED.get()) {
            if (wifiScannerManager != null) {
                wifiScannerManager.stop();
            }
            return true;
        }
        // Auto-disable WiFi scan when CYD is connected — CYD does full promiscuous scanning
        if (cydHardwareManager != null && cydHardwareManager.getState() == CydHardwareManager.State.READY) {
            if (wifiScannerManager != null && wifiScannerManager.isScanning()) {
                app.showToastMessage(R.string.flockfree_wifi_scan_paused_cyd_active);
                wifiScannerManager.stop();
            }
            return true;
        }
        WifiScannerManager manager = getWifiScannerManager();
        if (!manager.isScanning()) {
            return manager.start();
        }
        return true;
    }

    /**
     * Called when CYD connection state changes to re-evaluate WiFi scanner.
     * When CYD becomes READY, stop WiFi scan to save battery.
     * When CYD disconnects, resume WiFi scan if it was enabled.
     */
    public void onCydConnectionStateChanged() {
        if (cydHardwareManager != null && cydHardwareManager.getState() == CydHardwareManager.State.READY) {
            if (wifiScannerManager != null && wifiScannerManager.isScanning()) {
                app.showToastMessage(R.string.flockfree_wifi_scan_paused_cyd_active);
                wifiScannerManager.stop();
            }
        } else {
            // CYD not ready — resume WiFi scan if enabled
            if (WIFI_SCAN_ENABLED.get()) {
                ensureWifiScanIfEnabled();
            }
        }
    }

    @Nullable
    private String getCameraAlertSkipReason(@Nullable Float accuracy, boolean requireNavigationOrMovement,
                                            boolean moving) {
        if (!CAMERA_ALERTS_ENABLED.get()) {
            return app.getString(R.string.flockfree_alert_last_check_disabled);
        }
        int alertDistance = CAMERA_ALERT_DISTANCE.get();
        if (alertDistance <= 0) {
            return app.getString(R.string.flockfree_alert_last_check_disabled);
        }
        if (accuracy != null && accuracy > alertDistance) {
            return app.getString(R.string.flockfree_alert_last_check_accuracy,
                    Math.round(accuracy), alertDistance);
        }
        if (!requireNavigationOrMovement) {
            return null;
        }
        boolean shouldCheck = app.getRoutingHelper().isFollowingMode() || moving;
        return shouldCheck ? null : app.getString(R.string.flockfree_alert_last_check_waiting);
    }

    private void showCameraAlertIfNeeded(@NonNull CameraData.CameraPoint camera, double distanceMeters,
                                         boolean forceAlert) {
        long now = System.currentTimeMillis();
        String cameraKey = getCameraAlertKey(camera);
        String brand = camera.brand != null ? camera.brand : app.getString(R.string.res_unknown);
        int roundedDistance = Math.max(1, Math.round((float) distanceMeters));
        long cooldown = cameraKey.equals(lastCameraAlertKey)
                ? SAME_CAMERA_ALERT_COOLDOWN_MS
                : CAMERA_ALERT_COOLDOWN_MS;
        if (!forceAlert && now - lastCameraAlertTimeMs < cooldown) {
            setLastCameraAlertCheckSummary(app.getString(R.string.flockfree_alert_last_check_cooldown,
                    brand, roundedDistance));
            return;
        }
        lastCameraAlertTimeMs = now;
        lastCameraAlertKey = cameraKey;
        setLastCameraAlertCheckSummary(app.getString(R.string.flockfree_alert_last_check_triggered,
                brand, roundedDistance));

        // High-priority heads-up notification with large icon — much more noticeable than a toast
        String title = app.getString(R.string.flockfree_nearby_camera_alert, brand, roundedDistance);
        showCameraAlertNotification(title, brand, roundedDistance);
        // Also show a long-duration toast as a secondary visual cue
        app.showToastMessage(title);
    }

    private void showCameraAlertNotification(@NonNull String title, @NonNull String brand, int distanceM) {
        NotificationManager notificationManager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return;

        // Create high-importance channel for heads-up notifications (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = notificationManager.getNotificationChannel(CAMERA_ALERT_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(
                        CAMERA_ALERT_CHANNEL_ID,
                        "FlockFree Camera Alerts",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Alerts when approaching Flock safety cameras");
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 400, 200, 400});
                notificationManager.createNotificationChannel(channel);
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CAMERA_ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_action_device_camera)
                .setContentTitle("\uD83D\uDEA8 Flock Camera Ahead")
                .setContentText(title)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(title))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 400, 200, 400});

        notificationManager.notify(CAMERA_ALERT_NOTIFICATION_ID, builder.build());
    }

    @NonNull
    private String getCameraAlertKey(@NonNull CameraData.CameraPoint camera) {
        if (camera.osmType != null && camera.osmId != null) {
            return camera.osmType + ":" + camera.osmId;
        }
        return Math.round(camera.lat * 1_000_000d) + ":" + Math.round(camera.lon * 1_000_000d);
    }

    private boolean isValidCoordinate(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && latitude >= -90d && latitude <= 90d
                && longitude >= -180d && longitude <= 180d;
    }

    @Override
    public void newRouteIsCalculated(boolean newRoute) {
        if (!newRoute || !CAMERA_AVOIDANCE_ENABLED.get()) {
            return;
        }
        getCameraData().ensureDataLoaded();
        if (!getCameraData().isDataLoaded()) {
            String loadingSummary = app.getString(R.string.flockfree_route_camera_data_loading);
            setLastRouteCheckSummary(loadingSummary);
            app.showShortToastMessage(loadingSummary);
            return;
        }
        RouteCalculationResult route = app.getRoutingHelper().getRoute();
        if (route == null || !route.isCalculated()) {
            setLastRouteCheckSummary(app.getString(R.string.flockfree_route_last_check_no_route));
            return;
        }
        List<Location> routeLocations = route.getImmutableAllLocations();
        if (routeLocations == null || routeLocations.isEmpty()) {
            setLastRouteCheckSummary(app.getString(R.string.flockfree_route_last_check_no_route));
            return;
        }
        CameraAvoidanceHelper helper = getAvoidanceHelper();
        String routeSummary = helper.getRouteCameraSummaryFromLocations(routeLocations);
        String avoidanceSummary = helper.consumeLastAvoidanceStatusSummary();
        if (!avoidanceSummary.isEmpty()) {
            routeSummary = routeSummary + "\n" + avoidanceSummary;
        }
        setLastRouteCheckSummary(routeSummary);
        app.showToastMessage(routeSummary);
    }
}
