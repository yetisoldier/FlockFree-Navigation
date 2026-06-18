package net.osmand.plus.plugins.flockfree;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.quickaction.QuickActionType;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.plus.views.mapwidgets.MapWidgetInfo;
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter;
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem;

import java.util.ArrayList;
import java.util.List;

public class FlockFreePlugin extends OsmandPlugin {

    public static final String PLUGIN_ID = "flockfree";

    // Preferences
    public final OsmandPreference<Boolean> CAMERA_SHOW_LAYER;
    public final CommonPreference<Boolean> CAMERA_AVOIDANCE_ENABLED;
    public final CommonPreference<Integer> CAMERA_AVOIDANCE_RADIUS;
    public final CommonPreference<Integer> CAMERA_ALERT_DISTANCE;
    public final CommonPreference<Long> CAMERA_DATA_LAST_UPDATE;
    public final CommonPreference<Boolean> CYD_BLE_ENABLED;

    // Context menu item order
    private static final int CAMERA_DETAILS_ITEM_ORDER = 7800;
    private static final int ADD_CAMERA_ITEM_ORDER = 7900;

    private FlockFreeLayer cameraLayer;
    private CameraData cameraData;
    private CameraAvoidanceHelper avoidanceHelper;
    private CameraReporter cameraReporter;

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
                    .setTitle("Camera Details")
                    .setIcon(R.drawable.ic_action_info_dark)
                    .setOrder(CAMERA_DETAILS_ITEM_ORDER)
                    .setListener((uiAdapter, view, item, isChecked) -> {
                        showCameraDetails(mapActivity, (CameraData.CameraPoint) selectedObj);
                        return true;
                    }));
        }
        adapter.addItem(new ContextMenuItem(PLUGIN_ID + ".add_camera")
                .setTitle("Add ALPR Camera")
                .setIcon(R.drawable.ic_action_plus_dark)
                .setOrder(ADD_CAMERA_ITEM_ORDER)
                .setListener((uiAdapter, view, item, isChecked) -> {
                    getCameraReporter().showAddCameraDialog(mapActivity, latitude, longitude);
                    return true;
                }));
    }

    public void showCameraDetails(@NonNull MapActivity mapActivity, @NonNull CameraData.CameraPoint camera) {
        StringBuilder sb = new StringBuilder();
        sb.append("Brand: ").append(camera.brand != null ? camera.brand : "Unknown").append("\n");
        sb.append("Operator: ").append(camera.operator != null ? camera.operator : "Unknown").append("\n");
        sb.append("Direction: ").append(camera.direction != null ? camera.direction : "Unknown").append("\n");
        sb.append("Mount Type: ").append(camera.mountType != null ? camera.mountType : "Unknown").append("\n");
        sb.append("Surveillance Zone: ").append(camera.surveillanceZone != null ? camera.surveillanceZone : "Unknown").append("\n");
        sb.append("OSM ID: ").append(camera.osmId != null ? camera.osmId : "N/A").append("\n");
        sb.append("OSM Type: ").append(camera.osmType != null ? camera.osmType : "N/A").append("\n");
        sb.append("Last Updated: ").append(camera.osmTimestamp != null ? camera.osmTimestamp : "N/A");
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(mapActivity);
        builder.setTitle("ALPR Camera")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
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
    public void newRouteIsCalculated(boolean newRoute) {
        if (!newRoute || !CAMERA_AVOIDANCE_ENABLED.get()) {
            return;
        }
        getCameraData().ensureDataLoaded();
        if (!getCameraData().isDataLoaded()) {
            app.showShortToastMessage("FlockFree: camera data is still loading");
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
        app.showToastMessage("FlockFree: " + getAvoidanceHelper().getRouteCameraSummaryFromLocations(routeLocations));
    }
}
