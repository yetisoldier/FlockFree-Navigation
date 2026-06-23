package net.osmand.plus.plugins.flockfree;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

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
import net.osmand.plus.plugins.flockfree.widgets.NavigationTiltController;
import net.osmand.plus.plugins.flockfree.widgets.TrafficStatusWidget;
import net.osmand.plus.quickaction.QuickActionType;
import net.osmand.plus.quickaction.actions.ShowHideCamerasAction;
import net.osmand.plus.quickaction.actions.ToggleCameraAvoidanceAction;
import net.osmand.plus.quickaction.actions.ToggleCameraAlertsAction;
import net.osmand.plus.quickaction.actions.AddCameraAction;
import net.osmand.plus.render.RendererRegistry;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;
import net.osmand.plus.helpers.DayNightHelper;
import net.osmand.plus.settings.enums.DayNightMode;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.plus.views.mapwidgets.MapWidgetInfo;
import net.osmand.plus.views.mapwidgets.WidgetType;
import net.osmand.plus.views.mapwidgets.WidgetsPanel;
import net.osmand.plus.views.mapwidgets.widgets.MapWidget;
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter;
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FlockFreePlugin extends OsmandPlugin {

    public static final String PLUGIN_ID = "flockfree";

    // Preferences
    public final OsmandPreference<Boolean> CAMERA_SHOW_LAYER;
    public final CommonPreference<Boolean> CAMERA_AVOIDANCE_ENABLED;
    public final CommonPreference<Integer> CAMERA_AVOIDANCE_RADIUS;
    public final CommonPreference<Boolean> CAMERA_ALERTS_ENABLED;
    public final CommonPreference<Integer> CAMERA_ALERT_DISTANCE;
    public final CommonPreference<Boolean> TRAFFIC_ROUTING_ENABLED;
    public final OsmandPreference<Boolean> INCIDENTS_SHOW_LAYER;
    public final OsmandPreference<Boolean> INCIDENTS_ALERTS_ENABLED;
    public final CommonPreference<String> TOMTOM_API_KEY;
    public final CommonPreference<Long> CAMERA_DATA_LAST_UPDATE;
    public final CommonPreference<Boolean> CYD_BLE_ENABLED;
    public final CommonPreference<Boolean> WIFI_SCAN_ENABLED;
    public final CommonPreference<String> CAMERA_ROUTE_LAST_CHECK_SUMMARY;
    public final CommonPreference<String> TRAFFIC_ROUTE_LAST_CHECK_SUMMARY;
    public final CommonPreference<String> CAMERA_ALERT_LAST_CHECK_SUMMARY;
    public final CommonPreference<String> CAMERA_REPORT_LAST_DRAFT_SUMMARY;
    public final CommonPreference<String> CAMERA_NEAREST_LAST_CHECK_SUMMARY;
    private final CommonPreference<Boolean> RENDERER_MIGRATION_DONE;
    private final CommonPreference<Boolean> VISUAL_DEFAULTS_MIGRATION_DONE;
    public final OsmandPreference<Boolean> FORCE_NIGHT_MAP;

    // Navigation 3D tilt preferences
    public final OsmandPreference<Boolean> NAVIGATION_TILT_ENABLED;
    public final CommonPreference<Float> NAVIGATION_TILT_ANGLE;
    public static final float NAVIGATION_TILT_MIN = FlockFreePreferences.MIN_NAVIGATION_TILT_ANGLE;
    public static final float NAVIGATION_TILT_MAX = FlockFreePreferences.MAX_NAVIGATION_TILT_ANGLE;

    // Context menu item order
    private static final int CAMERA_DETAILS_ITEM_ORDER = 7800;
    private static final int CYD_REVIEW_ITEM_ORDER = 7850;
    private static final int CYD_DETAILS_ITEM_ORDER = 7860;
    private static final int ADD_CAMERA_ITEM_ORDER = 7900;
    private static final long CAMERA_ALERT_COOLDOWN_MS = 90_000L;
    private static final long SAME_CAMERA_ALERT_COOLDOWN_MS = 10 * 60_000L;
    private static final float MOVING_ALERT_SPEED_MPS = 2.0f;
    private static final float FLOCKFREE_DEFAULT_TEXT_SCALE = 1.2f;
    private static final float FLOCKFREE_MAX_LEGACY_TEXT_SCALE = 1.3f;
    private static final int MAP_CENTER_CAMERA_SEARCH_RADIUS_METERS = 5_000;

    private FlockFreeLayer cameraLayer;
    private FlockFreeIncidentLayer incidentLayer;
    private TomTomIncidentProvider incidentProvider;
    private CameraData cameraData;
    private CameraAvoidanceHelper avoidanceHelper;
    private TrafficRoutingHelper trafficRoutingHelper;
    private CameraReporter cameraReporter;
    private CydHardwareManager cydHardwareManager;
    private WifiScannerManager wifiScannerManager;
    private NavigationTiltController navigationTiltController;
    private long lastCameraAlertTimeMs;
    private String lastCameraAlertKey;
    private BroadcastReceiver debugAlertReceiver;
    private final Map<String, Long> alertedIncidentIds = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long INCIDENT_ALERT_COOLDOWN_MS = 60_000L;
    private static final double INCIDENT_ALERT_RADIUS_METERS = 2000.0;
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
        TRAFFIC_ROUTING_ENABLED = registerBooleanPreference(
                FlockFreePreferences.TRAFFIC_ROUTING_ENABLED,
                FlockFreePreferences.DEFAULT_TRAFFIC_ROUTING_ENABLED).makeProfile().cache();
        INCIDENTS_SHOW_LAYER = registerBooleanPreference(
                FlockFreePreferences.INCIDENTS_SHOW_LAYER,
                FlockFreePreferences.DEFAULT_INCIDENTS_SHOW_LAYER).makeProfile().cache();
        INCIDENTS_ALERTS_ENABLED = registerBooleanPreference(
                FlockFreePreferences.INCIDENTS_ALERTS_ENABLED,
                FlockFreePreferences.DEFAULT_INCIDENTS_ALERTS_ENABLED).makeProfile().cache();
        TOMTOM_API_KEY = registerStringPreference(
                FlockFreePreferences.TOMTOM_API_KEY,
                FlockFreePreferences.DEFAULT_TOMTOM_API_KEY).makeGlobal().cache();
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
        TRAFFIC_ROUTE_LAST_CHECK_SUMMARY = registerStringPreference(
                FlockFreePreferences.TRAFFIC_ROUTE_LAST_CHECK_SUMMARY,
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
        RENDERER_MIGRATION_DONE = registerBooleanPreference(
                FlockFreePreferences.RENDERER_MIGRATION_DONE,
                FlockFreePreferences.DEFAULT_RENDERER_MIGRATION_DONE).makeGlobal().cache();
        VISUAL_DEFAULTS_MIGRATION_DONE = registerBooleanPreference(
                FlockFreePreferences.VISUAL_DEFAULTS_MIGRATION_DONE,
                FlockFreePreferences.DEFAULT_VISUAL_DEFAULTS_MIGRATION_DONE).makeGlobal().cache();

        FORCE_NIGHT_MAP = registerBooleanPreference(
                FlockFreePreferences.FORCE_NIGHT_MAP,
                FlockFreePreferences.DEFAULT_FORCE_NIGHT_MAP).makeProfile().cache();
        NAVIGATION_TILT_ENABLED = registerBooleanPreference(
                FlockFreePreferences.NAVIGATION_TILT_ENABLED,
                FlockFreePreferences.DEFAULT_NAVIGATION_TILT_ENABLED).makeProfile().cache();
        NAVIGATION_TILT_ANGLE = registerFloatPreference(
                FlockFreePreferences.NAVIGATION_TILT_ANGLE,
                FlockFreePreferences.DEFAULT_NAVIGATION_TILT_ANGLE).makeProfile().cache();

        migrateDefaultRendererToFlockFree();
        applyFlockFreeVisualDefaults();
        registerForceNightMapThemeProvider();
        registerDebugAlertReceiver();
        incidentProvider = new TomTomIncidentProvider();
    }

    /**
     * Registers a {@link DayNightHelper.MapThemeProvider} that forces the map into
     * night rendering when the FlockFree "Force Night Map" preference is enabled.
     * When the preference is false (default), the theme provider returns null so
     * OsmAnd's normal day/night calculation (AUTO, SYSTEM, SENSOR, etc.) is used.
     */
    private void registerForceNightMapThemeProvider() {
        app.getDaynightHelper().setExternalMapThemeProvider(new DayNightHelper.MapThemeProvider() {
            @Override
            public DayNightMode getMapTheme() {
                if (FORCE_NIGHT_MAP.get()) {
                    return DayNightMode.NIGHT;
                }
                return null;
            }
        });
    }

    private void migrateDefaultRendererToFlockFree() {
        if (Boolean.TRUE.equals(RENDERER_MIGRATION_DONE.get())) {
            return;
        }
        String currentRenderer = app.getSettings().RENDERER.get();
        if (Algorithms.isEmpty(currentRenderer) || RendererRegistry.DEFAULT_RENDER.equals(currentRenderer)) {
            app.getSettings().RENDERER.set(RendererRegistry.FLOCKFREE_RENDER);
        }
        RENDERER_MIGRATION_DONE.set(true);
    }

    private void applyFlockFreeVisualDefaults() {
        app.getSettings().TEXT_SCALE.setDefaultValue(FLOCKFREE_DEFAULT_TEXT_SCALE);
        app.getSettings().TEXT_SCALE.setModeDefaultValue(ApplicationMode.CAR, FLOCKFREE_DEFAULT_TEXT_SCALE);
        app.getSettings().DAYNIGHT_MODE.setModeDefaultValue(ApplicationMode.CAR, DayNightMode.AUTO);
        app.getSettings().ROUTE_SHOW_TURN_ARROWS.setModeDefaultValue(ApplicationMode.CAR, false);
        // Ensure speed limit sign is always shown during navigation for the car profile
        app.getSettings().SHOW_SPEED_LIMIT_WARNING.setModeDefaultValue(ApplicationMode.CAR,
                net.osmand.plus.settings.enums.SpeedLimitWarningState.ALWAYS);
        // Ensure speedometer widget is visible for the car profile
        app.getSettings().SHOW_SPEEDOMETER.setModeDefaultValue(ApplicationMode.CAR, true);

        if (Boolean.TRUE.equals(VISUAL_DEFAULTS_MIGRATION_DONE.get())) {
            return;
        }
        if (app.getSettings().TEXT_SCALE.getModeValue(ApplicationMode.CAR) > FLOCKFREE_MAX_LEGACY_TEXT_SCALE) {
            app.getSettings().TEXT_SCALE.setModeValue(ApplicationMode.CAR, FLOCKFREE_DEFAULT_TEXT_SCALE);
        }
        if (app.getSettings().DAYNIGHT_MODE.getModeValue(ApplicationMode.CAR) == DayNightMode.NIGHT) {
            app.getSettings().DAYNIGHT_MODE.setModeValue(ApplicationMode.CAR, DayNightMode.AUTO);
        }
        if (Boolean.TRUE.equals(app.getSettings().ROUTE_SHOW_TURN_ARROWS.getModeValue(ApplicationMode.CAR))) {
            app.getSettings().ROUTE_SHOW_TURN_ARROWS.setModeValue(ApplicationMode.CAR, false);
        }
        // Ensure speed limit warning is ALWAYS for existing CAR users
        if (app.getSettings().SHOW_SPEED_LIMIT_WARNING.getModeValue(ApplicationMode.CAR) != net.osmand.plus.settings.enums.SpeedLimitWarningState.ALWAYS) {
            app.getSettings().SHOW_SPEED_LIMIT_WARNING.setModeValue(ApplicationMode.CAR, net.osmand.plus.settings.enums.SpeedLimitWarningState.ALWAYS);
        }
        // Ensure speedometer is enabled for existing CAR users
        if (!app.getSettings().SHOW_SPEEDOMETER.getModeValue(ApplicationMode.CAR)) {
            app.getSettings().SHOW_SPEEDOMETER.setModeValue(ApplicationMode.CAR, true);
        }
        VISUAL_DEFAULTS_MIGRATION_DONE.set(true);
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

    private void registerDebugAlertReceiver() {
        debugAlertReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Force-trigger a camera alert toast for UI testing
                String brand = "Flock Safety";
                int distance = 150;
                String title = app.getString(R.string.flockfree_nearby_camera_alert, brand, distance);
                app.showToastMessage(title);
                vibrateForCameraAlert();
            }
        };
        app.registerReceiver(debugAlertReceiver, new IntentFilter("net.osmand.flockfree.TEST_ALERT"),
                Context.RECEIVER_EXPORTED);
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
    public TrafficRoutingHelper getTrafficRoutingHelper() {
        if (trafficRoutingHelper == null) {
            trafficRoutingHelper = new TrafficRoutingHelper(app, this);
        }
        return trafficRoutingHelper;
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

    private void ensureNavigationTiltController() {
        if (navigationTiltController == null) {
            navigationTiltController = new NavigationTiltController(app, this);
        }
        navigationTiltController.register();
    }

    @androidx.annotation.Nullable
    public NavigationTiltController getNavigationTiltController() {
        return navigationTiltController;
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
    public synchronized String getLastTrafficRouteCheckSummary() {
        String summary = TRAFFIC_ROUTE_LAST_CHECK_SUMMARY.get();
        return summary != null && summary.length() > 0
                ? summary
                : app.getString(R.string.flockfree_traffic_last_check_none);
    }

    public synchronized void setLastTrafficRouteCheckSummary(@NonNull String summary) {
        TRAFFIC_ROUTE_LAST_CHECK_SUMMARY.set(summary);
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
        if (incidentLayer != null) {
            app.getOsmandMap().getMapView().removeLayer(incidentLayer);
        }
        incidentLayer = new FlockFreeIncidentLayer(context, this, incidentProvider);
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
            boolean showIncidents = INCIDENTS_SHOW_LAYER.get();
            boolean hasIncidentLayer = incidentLayer != null
                    && app.getOsmandMap().getMapView().getLayers().contains(incidentLayer);
            if (showIncidents != hasIncidentLayer) {
                if (showIncidents && incidentLayer != null) {
                    app.getOsmandMap().getMapView().addLayer(incidentLayer, 3.5f);
                } else if (incidentLayer != null) {
                    app.getOsmandMap().getMapView().removeLayer(incidentLayer);
                }
            }
        } else {
            if (cameraLayer != null) {
                app.getOsmandMap().getMapView().removeLayer(cameraLayer);
            }
            if (incidentLayer != null) {
                app.getOsmandMap().getMapView().removeLayer(incidentLayer);
            }
        }
    }

    public FlockFreeLayer getCameraLayer() {
        return cameraLayer;
    }

    @NonNull
    public TomTomIncidentProvider getIncidentProvider() {
        return incidentProvider;
    }

    @Nullable
    public FlockFreeIncidentLayer getIncidentLayer() {
        return incidentLayer;
    }

    @Override
    public void createWidgets(@NonNull MapActivity activity, @NonNull List<MapWidgetInfo> widgetInfos,
                              @NonNull net.osmand.plus.settings.backend.ApplicationMode appMode,
                              @Nullable net.osmand.plus.settings.enums.ScreenLayoutMode layoutMode) {
        // FlockFree custom widgets (camera proximity, traffic status) are not registered
        // as on-map side-panel widgets to avoid overlapping the search bar in
        // portrait mode. Camera alerts and traffic routing still work via
        // navigation notifications, audio alerts, and the layers sheet.
        //
        // Speed limit display: The SpeedometerWidget (registered by OsmAnd core)
        // includes the speed limit sign as a sub-component. We ensure it is visible
        // for the car profile by setting SHOW_SPEEDOMETER=true and
        // SHOW_SPEED_LIMIT_WARNING=ALWAYS in applyFlockFreeVisualDefaults().
        //
        // Second-next-turn preview chip: The SECOND_NEXT_TURN widget is registered
        // by OsmAnd core (MapWidgetsFactory) and we ensure it is visible by default
        // for CAR mode via WidgetsAvailabilityHelper. The SecondNextTurnWidget class
        // uses a compact chip layout (flockfree_second_next_turn_chip.xml) when
        // FlockFreePlugin is active, styled as a Google Maps-style preview chip.
    }

    @Nullable
    @Override
    protected MapWidget createMapWidgetForParams(@NonNull MapActivity mapActivity, @NonNull WidgetType widgetType,
                                                  @Nullable String customId, @Nullable WidgetsPanel widgetsPanel) {
        if (widgetType == WidgetType.CAMERA_PROXIMITY) {
            return new CameraProximityWidget(mapActivity, customId, widgetsPanel);
        }
        if (widgetType == WidgetType.TRAFFIC_STATUS) {
            return new TrafficStatusWidget(mapActivity, customId, widgetsPanel);
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
        // Initialize Google Maps-style bottom navigation bar
        net.osmand.plus.plugins.flockfree.widgets.FlockFreeNavigationBar.ensureInitialized(app);
        // Initialize Google Maps-style floating report button
        net.osmand.plus.plugins.flockfree.widgets.NavigationReportButton.ensureInitialized(app, activity);
        // Initialize 3D navigation tilt controller
        ensureNavigationTiltController();
    }

    @Override
    public void mapActivityResume(@NonNull MapActivity activity) {
        getCameraData().ensureDataLoaded();
        ensureCydScanIfEnabled(activity);
        ensureWifiScanIfEnabled();
        ensureNavigationTiltController();
    }

    @Override
    public void disable(@NonNull OsmandApplication app) {
        super.disable(app);
        // Remove the FlockFree night mode override so normal day/night calculation is restored
        app.getDaynightHelper().setExternalMapThemeProvider(null);
        CydBleService.stop(app);
        if (cydHardwareManager != null) {
            cydHardwareManager.close();
            cydHardwareManager = null;
        }
        if (trafficRoutingHelper != null) {
            trafficRoutingHelper.close();
            trafficRoutingHelper = null;
        }
        if (wifiScannerManager != null) {
            wifiScannerManager.stop();
        }
        if (incidentProvider != null) {
            incidentProvider.clearCache();
        }
        if (navigationTiltController != null) {
            navigationTiltController.unregister();
            navigationTiltController = null;
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
        // Check for traffic incidents near current location on route
        List<Location> routeLocations = null;
        RouteCalculationResult route = app.getRoutingHelper().getRoute();
        if (route != null && route.isCalculated()) {
            routeLocations = route.getImmutableAllLocations();
        }
        checkIncidentAlerts(location, routeLocations);
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

        // Toast alert with vibration — no persistent notification
        String title = app.getString(R.string.flockfree_nearby_camera_alert, brand, roundedDistance);
        app.showToastMessage(title);
        vibrateForCameraAlert();
    }

    private void vibrateForCameraAlert() {
        android.os.Vibrator vibrator = (android.os.Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 400, 200, 400};
            vibrator.vibrate(pattern, -1);
        }
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
        if (!newRoute || (!CAMERA_AVOIDANCE_ENABLED.get() && !TRAFFIC_ROUTING_ENABLED.get())) {
            return;
        }
        boolean cameraAvoidanceEnabled = CAMERA_AVOIDANCE_ENABLED.get();
        if (cameraAvoidanceEnabled) {
            getCameraData().ensureDataLoaded();
        }
        if (cameraAvoidanceEnabled && !getCameraData().isDataLoaded()) {
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
        String routeSummary = "";
        if (cameraAvoidanceEnabled) {
            CameraAvoidanceHelper helper = getAvoidanceHelper();
            routeSummary = helper.getRouteCameraSummaryFromLocations(routeLocations);
            String avoidanceSummary = helper.consumeLastAvoidanceStatusSummary();
            if (!avoidanceSummary.isEmpty()) {
                routeSummary = routeSummary + "\n" + avoidanceSummary;
            }
        }
        TrafficRoutingHelper trafficHelper = getTrafficRoutingHelper();
        if (TRAFFIC_ROUTING_ENABLED.get() && route.getOriginalRoute() != null && !route.getOriginalRoute().isEmpty()) {
            trafficHelper.getTrafficColorsForRoute(route.getOriginalRoute());
            setLastTrafficRouteCheckSummary(trafficHelper.getTrafficColorLegendSummary());
        }
        String trafficSummary = trafficHelper.consumeLastTrafficStatusSummary();
        if (!trafficSummary.isEmpty()) {
            setLastTrafficRouteCheckSummary(trafficSummary);
            routeSummary = routeSummary.isEmpty() ? trafficSummary : routeSummary + "\n" + trafficSummary;
        }
        if (routeSummary.isEmpty()) {
            return;
        }
        setLastRouteCheckSummary(routeSummary);
        app.showToastMessage(routeSummary);
    }

    /**
     * Checks for traffic incidents near the current location and speaks a TTS alert
     * if a new incident is found on the route. Uses the same TTS pattern as camera alerts.
     *
     * @param currentLocation  Current GPS location
     * @param routeLocations   Route locations (may be null if no active route)
     */
    public void checkIncidentAlerts(@Nullable Location currentLocation,
                                     @Nullable List<Location> routeLocations) {
        if (currentLocation == null) {
            return;
        }
        if (!INCIDENTS_ALERTS_ENABLED.get()) {
            return;
        }
        String apiKey = TOMTOM_API_KEY.get();
        if (Algorithms.isEmpty(apiKey)) {
            return;
        }
        if (!app.getRoutingHelper().isFollowingMode()) {
            return;
        }
        double lat = currentLocation.getLatitude();
        double lon = currentLocation.getLongitude();
        double alertRadius = INCIDENT_ALERT_RADIUS_METERS;
        double minLat = lat - (alertRadius / 111000.0);
        double maxLat = lat + (alertRadius / 111000.0);
        double minLon = lon - (alertRadius / (111000.0 * Math.cos(Math.toRadians(lat))));
        double maxLon = lon + (alertRadius / (111000.0 * Math.cos(Math.toRadians(lat))));

        List<TomTomIncidentProvider.TrafficIncident> incidents =
                incidentProvider.fetchIncidents(minLat, minLon, maxLat, maxLon, apiKey);
        if (incidents.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (TomTomIncidentProvider.TrafficIncident incident : incidents) {
            if (incident.id == null) {
                continue;
            }
            Long lastAlerted = alertedIncidentIds.get(incident.id);
            if (lastAlerted != null && now - lastAlerted < INCIDENT_ALERT_COOLDOWN_MS) {
                continue;
            }
            double distance = MapUtils.getDistance(lat, lon, incident.lat, incident.lon);
            if (distance > INCIDENT_ALERT_RADIUS_METERS) {
                continue;
            }
            // Check if incident is on the route (within 500m of any route point)
            if (routeLocations != null && !routeLocations.isEmpty() && !isIncidentOnRoute(incident, routeLocations)) {
                continue;
            }
            alertedIncidentIds.put(incident.id, now);
            speakIncidentAlert(incident);
        }
        // Clean up old alert IDs to prevent memory growth
        alertedIncidentIds.entrySet().removeIf(entry -> now - entry.getValue() > 10 * INCIDENT_ALERT_COOLDOWN_MS);
    }

    private boolean isIncidentOnRoute(@NonNull TomTomIncidentProvider.TrafficIncident incident,
                                       @NonNull List<Location> routeLocations) {
        double maxRouteDistance = 500.0; // 500m from route line
        for (Location routePoint : routeLocations) {
            double dist = MapUtils.getDistance(incident.lat, incident.lon,
                    routePoint.getLatitude(), routePoint.getLongitude());
            if (dist <= maxRouteDistance) {
                return true;
            }
        }
        return false;
    }

    private void speakIncidentAlert(@NonNull TomTomIncidentProvider.TrafficIncident incident) {
        String alertText = getIncidentAlertText(incident);
        if (alertText == null) {
            return;
        }
        // Show toast
        app.showToastMessage(alertText);
        // Speak via TTS using the command player directly
        try {
            net.osmand.plus.routing.VoiceRouter voiceRouter = app.getRoutingHelper().getVoiceRouter();
            if (voiceRouter != null) {
                net.osmand.plus.voice.CommandPlayer player = voiceRouter.getPlayer();
                if (player != null) {
                    net.osmand.plus.voice.CommandBuilder builder = player.newCommandBuilder();
                    builder.attention(alertText);
                    player.playCommands(builder);
                }
            }
        } catch (Exception e) {
            // TTS not available — toast is still shown
        }
        vibrateForCameraAlert();
    }

    @Nullable
    private String getIncidentAlertText(@NonNull TomTomIncidentProvider.TrafficIncident incident) {
        String prefix = app.getString(R.string.flockfree_incident_alert_ahead);
        switch (incident.iconCategory) {
            case 1:
                return prefix + " " + app.getString(R.string.flockfree_incident_accident);
            case 6:
                return prefix + " " + app.getString(R.string.flockfree_incident_jam);
            case 8:
                return prefix + " " + app.getString(R.string.flockfree_incident_road_closed);
            case 9:
                return prefix + " " + app.getString(R.string.flockfree_incident_roadworks);
            case 7:
                return prefix + " " + app.getString(R.string.flockfree_incident_lane_closed);
            case 11:
                return prefix + " " + app.getString(R.string.flockfree_incident_flooding);
            case 3:
                return prefix + " " + app.getString(R.string.flockfree_incident_dangerous);
            case 2:
                return prefix + " " + app.getString(R.string.flockfree_incident_fog);
            case 4:
                return prefix + " " + app.getString(R.string.flockfree_incident_rain);
            case 5:
                return prefix + " " + app.getString(R.string.flockfree_incident_ice);
            case 10:
                return prefix + " " + app.getString(R.string.flockfree_incident_wind);
            case 14:
                return prefix + " " + app.getString(R.string.flockfree_incident_broken_down);
            default:
                return null;
        }
    }
}
