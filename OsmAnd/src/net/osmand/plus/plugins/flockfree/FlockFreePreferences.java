package net.osmand.plus.plugins.flockfree;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;

/**
 * Centralized preference keys for the FlockFree plugin.
 * All preferences are registered through FlockFreePlugin, this class
 * provides convenient constants for key names and defaults.
 */
public final class FlockFreePreferences {

    // Preference keys
    public static final String CAMERA_DATA_LAST_UPDATE = "camera_data_last_update";
    public static final String CAMERA_AVOIDANCE_ENABLED = "camera_avoidance_enabled";
    public static final String CAMERA_AVOIDANCE_RADIUS = "camera_avoidance_radius";
    public static final String CAMERA_SHOW_LAYER = "camera_show_layer";
    public static final String CAMERA_ALERTS_ENABLED = "camera_alerts_enabled";
    public static final String CAMERA_ALERT_DISTANCE = "camera_alert_distance";
    public static final String TRAFFIC_ROUTING_ENABLED = "traffic_routing_enabled";
    public static final String TOMTOM_API_KEY = "tomtom_api_key";
    public static final String CYD_BLE_ENABLED = "cyd_ble_enabled";
    public static final String WIFI_SCAN_ENABLED = "wifi_scan_enabled";
    public static final String CAMERA_ROUTE_LAST_CHECK_SUMMARY = "camera_route_last_check_summary";
    public static final String ROUTE_TRADEOFF_SUMMARY = "route_tradeoff_summary";
    public static final String TRAFFIC_ROUTE_LAST_CHECK_SUMMARY = "traffic_route_last_check_summary";
    public static final String CAMERA_ALERT_LAST_CHECK_SUMMARY = "camera_alert_last_check_summary";
    public static final String CAMERA_REPORT_LAST_DRAFT_SUMMARY = "camera_report_last_draft_summary";
    public static final String CAMERA_NEAREST_LAST_CHECK_SUMMARY = "camera_nearest_last_check_summary";
    public static final String APP_UPDATE_LAST_CHECK_SUMMARY = "app_update_last_check_summary";
    public static final String APP_UPDATE_LAST_CHECK_TIME = "app_update_last_check_time";
    public static final String APP_UPDATE_LAST_NOTIFIED_VERSION = "app_update_last_notified_version";
    public static final String RENDERER_MIGRATION_DONE = "renderer_migration_done";
    public static final String VISUAL_DEFAULTS_MIGRATION_DONE = "visual_defaults_migration_done";
    public static final String CAMERA_AVOIDANCE_DEFAULTS_MIGRATION_DONE = "camera_avoidance_defaults_migration_done";
    public static final String TRAFFIC_DEFAULTS_MIGRATION_DONE = "traffic_defaults_migration_done";
    public static final String INCIDENTS_SHOW_LAYER = "incidents_show_layer";
    public static final String INCIDENTS_ALERTS_ENABLED = "incidents_alerts_enabled";
    public static final String ROUTE_COLOR_MIGRATION_DONE = "route_color_migration_done";
    public static final String FORCE_NIGHT_MAP = "force_night_map";
    public static final String NAVIGATION_TILT_ENABLED = "navigation_tilt_enabled";
    public static final String NAVIGATION_TILT_ANGLE = "navigation_tilt_angle";
    public static final String BUILDING_TRANSPARENCY_ENABLED = "building_transparency_enabled";

    // Default values
    public static final boolean DEFAULT_CAMERA_SHOW_LAYER = true;
    public static final boolean DEFAULT_INCIDENTS_SHOW_LAYER = true;
    public static final boolean DEFAULT_INCIDENTS_ALERTS_ENABLED = true;
    public static final boolean DEFAULT_CAMERA_AVOIDANCE_ENABLED = true;
    public static final boolean DEFAULT_CAMERA_ALERTS_ENABLED = true;
    public static final boolean DEFAULT_TRAFFIC_ROUTING_ENABLED = true;
    public static final String DEFAULT_TOMTOM_API_KEY = "";
    public static final int DEFAULT_CAMERA_AVOIDANCE_RADIUS = 100;     // meters
    public static final int DEFAULT_CAMERA_ALERT_DISTANCE = 300;        // meters
    public static final long DEFAULT_CAMERA_DATA_LAST_UPDATE = 0L;
    public static final boolean DEFAULT_CYD_BLE_ENABLED = false;
    public static final boolean DEFAULT_WIFI_SCAN_ENABLED = false;
    public static final boolean DEFAULT_RENDERER_MIGRATION_DONE = false;
    public static final boolean DEFAULT_VISUAL_DEFAULTS_MIGRATION_DONE = false;
    public static final boolean DEFAULT_CAMERA_AVOIDANCE_DEFAULTS_MIGRATION_DONE = false;
    public static final boolean DEFAULT_TRAFFIC_DEFAULTS_MIGRATION_DONE = false;
    public static final boolean DEFAULT_ROUTE_COLOR_MIGRATION_DONE = false;
    public static final boolean DEFAULT_FORCE_NIGHT_MAP = false;
    public static final boolean DEFAULT_NAVIGATION_TILT_ENABLED = true;
    public static final boolean DEFAULT_BUILDING_TRANSPARENCY_ENABLED = true;
    public static final float DEFAULT_NAVIGATION_TILT_ANGLE = 55f;
    public static final float MIN_NAVIGATION_TILT_ANGLE = 30f;
    public static final float MAX_NAVIGATION_TILT_ANGLE = 80f;
    public static final String DEFAULT_STATUS_SUMMARY = "";
    public static final long DEFAULT_APP_UPDATE_LAST_CHECK_TIME = 0L;
    public static final String DEFAULT_APP_UPDATE_LAST_NOTIFIED_VERSION = "";

    // Bounds
    public static final int MIN_AVOIDANCE_RADIUS = 50;
    public static final int MAX_AVOIDANCE_RADIUS = 500;
    public static final int MIN_ALERT_DISTANCE = 100;
    public static final int MAX_ALERT_DISTANCE = 1000;

    // Data source
    public static final String CAMERA_DATA_URL = "https://data.dontgetflocked.com/cameras.geojson.gz";
    public static final String APP_UPDATE_RELEASES_URL = "https://api.github.com/repos/yetisoldier/FlockFree-Navigation/releases/latest";
    public static final String APP_UPDATE_RELEASES_PAGE_URL = "https://github.com/yetisoldier/FlockFree-Navigation/releases/latest";
    public static final long REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000; // 1 week
    public static final long APP_UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; // 1 day
    public static final String APP_RELEASE_VERSION = "1.8.7";

    private FlockFreePreferences() {
        // Utility class, no instances
    }
}
