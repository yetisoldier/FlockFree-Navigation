package net.osmand.plus.plugins.flockfree;

import android.Manifest;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.app.ActivityCompat;
import androidx.preference.Preference;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.cyd.CydBleService;
import net.osmand.plus.plugins.flockfree.cyd.CydDetectionCandidate;
import net.osmand.plus.plugins.flockfree.cyd.CydHardwareManager;
import net.osmand.plus.plugins.flockfree.widgets.NavigationTiltController;
import net.osmand.plus.plugins.flockfree.wifi.WifiScannerManager;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.preferences.EditTextPreferenceEx;
import net.osmand.plus.settings.preferences.ListPreferenceEx;
import net.osmand.plus.settings.preferences.SwitchPreferenceEx;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.util.Algorithms;

public class FlockFreeSettingsFragment extends BaseSettingsFragment {

	private static final long DYNAMIC_STATUS_REFRESH_MS = 1_000L;
	private static final int MAX_DYNAMIC_STATUS_REFRESH_TICKS = 90;
	private static final int WIFI_LOCATION_REQUEST_CODE = 46;
	private static final String CAMERA_DATA_STATUS_KEY = "flockfree_camera_data_status";
	private static final String CAMERA_DATA_REFRESH_KEY = "flockfree_camera_data_refresh";
	private static final String CAMERA_NEAREST_MAP_CENTER_KEY = "flockfree_camera_nearest_map_center";
	private static final String CAMERA_NEAREST_LAST_CHECK_KEY = "flockfree_camera_nearest_last_check";
	private static final String ROUTE_LAST_CHECK_KEY = "flockfree_route_last_check";
	private static final String ROUTE_TRADEOFF_SUMMARY_KEY = "flockfree_route_tradeoff_summary";
	private static final String TRAFFIC_ROUTE_LAST_CHECK_KEY = "flockfree_traffic_last_check";
	private static final String TOMTOM_API_KEY = "tomtom_api_key";
	private static final String ALERT_LAST_CHECK_KEY = "flockfree_alert_last_check";
	private static final String ALERT_CHECK_MAP_CENTER_KEY = "flockfree_alert_check_map_center";
	private static final String REPORT_LAST_DRAFT_KEY = "flockfree_report_last_draft";
	private static final String REPORT_MAP_CENTER_KEY = "flockfree_report_map_center";
	private static final String CYD_STATUS_KEY = "flockfree_cyd_status";
	private static final String CYD_CONNECT_KEY = "flockfree_cyd_connect";
	private static final String CYD_REQUEST_STATUS_KEY = "flockfree_cyd_request_status";
	private static final String CYD_SIMULATE_DETECTION_KEY = "flockfree_cyd_simulate_detection";
	private static final String CYD_CLEAR_DETECTIONS_KEY = "flockfree_cyd_clear_detections";
	private static final String WIFI_SCAN_STATUS_KEY = "flockfree_wifi_scan_status";
	private static final String WIFI_SCAN_CLEAR_KEY = "flockfree_wifi_scan_clear";
	private static final Integer[] AVOIDANCE_RADIUS_VALUES = {50, 75, 100, 150, 200, 300, 500};
	private static final Integer[] ALERT_DISTANCE_VALUES = {100, 200, 300, 500, 750, 1000};
	private static final Float[] TILT_ANGLE_VALUES = {30f, 35f, 40f, 45f, 50f, 55f, 60f, 65f, 70f, 75f, 80f};

	private final FlockFreePlugin plugin = PluginsHelper.requirePlugin(FlockFreePlugin.class);
	private final Handler statusRefreshHandler = new Handler(Looper.getMainLooper());
	private final Runnable dynamicStatusRefreshRunnable = new Runnable() {
		@Override
		public void run() {
			refreshDynamicStatusPreferences();
			dynamicStatusRefreshTicks++;
			if (dynamicStatusRefreshTicks < MAX_DYNAMIC_STATUS_REFRESH_TICKS
					&& shouldKeepDynamicStatusRefreshing()) {
				statusRefreshHandler.postDelayed(this, DYNAMIC_STATUS_REFRESH_MS);
			}
		}
	};

	private int dynamicStatusRefreshTicks;

	@Override
	protected void setupPreferences() {
		setupSwitchPreference(plugin.CAMERA_SHOW_LAYER.getId(),
				R.string.flockfree_show_cameras_on_map_description);
		setupSwitchPreference(plugin.INCIDENTS_SHOW_LAYER.getId(),
				R.string.flockfree_incidents_show_layer_description);
		setupCameraDataStatusPreference();
		setupNearestCameraLastCheckPreference();
		setupDistancePreference(plugin.CAMERA_AVOIDANCE_RADIUS.getId(), AVOIDANCE_RADIUS_VALUES,
				R.string.flockfree_avoidance_radius_description);
		setupRouteLastCheckPreference();
		setupRouteTradeoffSummaryPreference();
		setupSwitchPreference(plugin.TRAFFIC_ROUTING_ENABLED.getId(),
				R.string.flockfree_traffic_routing_enabled_description);
		setupSwitchPreference(plugin.INCIDENTS_ALERTS_ENABLED.getId(),
				R.string.flockfree_incidents_alerts_enabled_description);
		setupTomTomApiKeyPreference();
		setupTrafficRouteLastCheckPreference();
		setupSwitchPreference(plugin.CAMERA_ALERTS_ENABLED.getId(),
				R.string.flockfree_nearby_alerts_enabled_description);
		setupDistancePreference(plugin.CAMERA_ALERT_DISTANCE.getId(), ALERT_DISTANCE_VALUES,
				R.string.flockfree_alert_distance_description);
		setupAlertLastCheckPreference();
		setupReportLastDraftPreference();
		setupSwitchPreference(plugin.CYD_BLE_ENABLED.getId(),
				R.string.flockfree_cyd_ble_description);
		setupSwitchPreference(plugin.WIFI_SCAN_ENABLED.getId(),
				R.string.flockfree_wifi_scan_description);
		setupNavigationTiltEnabledPreference();
		setupNavigationTiltAnglePreference();
		setupWifiScanStatusPreference();
		setupCydStatusPreference();
	}

	@Override
	public void onResume() {
		super.onResume();
		refreshDynamicStatusPreferences();
		startDynamicStatusRefresh();
	}

	@Override
	public void onPause() {
		statusRefreshHandler.removeCallbacks(dynamicStatusRefreshRunnable);
		super.onPause();
	}

	private void setupSwitchPreference(@NonNull String prefId, @StringRes int descriptionId) {
		SwitchPreferenceEx preference = findPreference(prefId);
		if (preference != null) {
			preference.setDescription(descriptionId);
		}
	}

	private void setupCameraDataStatusPreference() {
		Preference preference = findPreference(CAMERA_DATA_STATUS_KEY);
		if (preference != null) {
			CameraData cameraData = plugin.getCameraData();
			if (cameraData.isDataLoaded()) {
				preference.setSummary(getString(R.string.flockfree_camera_data_loaded_source_age_summary,
						cameraData.getCameraCount(), cameraData.getSpatialBucketCount(),
						cameraData.getLastLoadedSourceLabel(), cameraData.getLastLoadedFreshnessLabel()));
			} else if (cameraData.isLoading()) {
				preference.setSummary(R.string.flockfree_camera_data_loading_summary);
			} else {
				preference.setSummary(R.string.flockfree_camera_data_not_loaded_summary);
			}
		}
	}

	private void setupRouteLastCheckPreference() {
		Preference preference = findPreference(ROUTE_LAST_CHECK_KEY);
		if (preference != null) {
			preference.setSummary(plugin.getLastRouteCheckSummary());
		}
	}

	private void setupRouteTradeoffSummaryPreference() {
		Preference preference = findPreference(ROUTE_TRADEOFF_SUMMARY_KEY);
		if (preference != null) {
			preference.setSummary(plugin.getLastRouteTradeoffSummary());
		}
	}

	private void setupTrafficRouteLastCheckPreference() {
		Preference preference = findPreference(TRAFFIC_ROUTE_LAST_CHECK_KEY);
		if (preference != null) {
			preference.setSummary(plugin.getLastTrafficRouteCheckSummary());
		}
	}

	private void setupTomTomApiKeyPreference() {
		EditTextPreferenceEx preference = findPreference(TOMTOM_API_KEY);
		if (preference != null) {
			String apiKey = plugin.TOMTOM_API_KEY.get();
			preference.setPersistent(false);
			preference.setSecret(true);
			preference.setDescription(R.string.flockfree_tomtom_api_key_description);
			preference.setSummary(Algorithms.isEmpty(apiKey)
					? getString(R.string.flockfree_tomtom_api_key_not_configured)
					: getString(R.string.flockfree_tomtom_api_key_configured));
		}
	}

	private void setupNearestCameraLastCheckPreference() {
		Preference preference = findPreference(CAMERA_NEAREST_LAST_CHECK_KEY);
		if (preference != null) {
			preference.setSummary(plugin.getLastNearestCameraSummary());
		}
	}

	private void setupAlertLastCheckPreference() {
		Preference preference = findPreference(ALERT_LAST_CHECK_KEY);
		if (preference != null) {
			preference.setSummary(plugin.getLastCameraAlertCheckSummary());
		}
	}

	private void setupReportLastDraftPreference() {
		Preference preference = findPreference(REPORT_LAST_DRAFT_KEY);
		if (preference != null) {
			preference.setSummary(plugin.getCameraReporter().getLastReportDraftSummary());
		}
	}

	private void setupCydStatusPreference() {
		Preference preference = findPreference(CYD_STATUS_KEY);
		if (preference != null) {
			CydHardwareManager manager = plugin.getCydHardwareManager();
			CydDetectionCandidate detection = manager.getLastDetection();
			if (detection != null) {
				preference.setSummary(getString(R.string.flockfree_cyd_status_last_detection,
						detection.getStatusSummary(), manager.getRecentDetectionCount())
						+ "\n" + manager.getStatusSummary());
			} else {
				preference.setSummary(manager.getStatusSummary());
			}
		}
	}

	private void setupWifiScanStatusPreference() {
		Preference preference = findPreference(WIFI_SCAN_STATUS_KEY);
		if (preference != null) {
			WifiScannerManager manager = plugin.getWifiScannerManager();
			preference.setSummary(manager.getStatusSummary());
		}
	}

	private void setupDistancePreference(@NonNull String prefId, @NonNull Integer[] values,
	                                     @StringRes int descriptionId) {
		ListPreferenceEx preference = findPreference(prefId);
		if (preference != null) {
			preference.setEntries(formatMeters(values));
			preference.setEntryValues(values);
			preference.setDescription(descriptionId);
		}
	}

	@NonNull
	private String[] formatMeters(@NonNull Integer[] values) {
		String[] entries = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			entries[i] = values[i] + " " + getString(R.string.shared_string_meters);
		}
		return entries;
	}

	private void setupNavigationTiltEnabledPreference() {
		SwitchPreferenceEx preference = findPreference(plugin.NAVIGATION_TILT_ENABLED.getId());
		if (preference != null) {
			preference.setDescription(R.string.flockfree_navigation_tilt_enabled_desc);
		}
	}

	private void setupNavigationTiltAnglePreference() {
		EditTextPreferenceEx preference = findPreference(plugin.NAVIGATION_TILT_ANGLE.getId());
		if (preference != null) {
			preference.setDescription(R.string.flockfree_navigation_tilt_angle_desc);
			float angle = plugin.NAVIGATION_TILT_ANGLE.get();
			preference.setSummary(getString(R.string.flockfree_navigation_tilt_angle_summary, angle));
		}
	}

	@Override
	public boolean onPreferenceChange(Preference preference, Object newValue) {
		if (plugin.CYD_BLE_ENABLED.getId().equals(preference.getKey())) {
			if (Boolean.TRUE.equals(newValue)) {
				MapActivity mapActivity = getMapActivity();
				if (mapActivity == null) {
					app.showShortToastMessage(R.string.flockfree_cyd_status_map_unavailable);
					return false;
				}
				if (!AndroidUtils.requestBLEPermissions(mapActivity)) {
					setupCydStatusPreference();
					startDynamicStatusRefresh();
					return false;
				}
				AndroidUtils.requestNotificationPermissionIfNeeded(mapActivity);
				boolean accepted = super.onPreferenceChange(preference, newValue);
				if (accepted) {
					startCydScan(mapActivity);
				}
				return accepted;
			}
			boolean accepted = super.onPreferenceChange(preference, newValue);
			if (accepted) {
				CydBleService.stop(app);
				plugin.getCydHardwareManager().disconnect();
				setupCydStatusPreference();
				// CYD disconnected - resume WiFi scan if enabled
				plugin.onCydConnectionStateChanged();
			}
			return accepted;
		}
		if (plugin.WIFI_SCAN_ENABLED.getId().equals(preference.getKey())) {
			if (Boolean.TRUE.equals(newValue)) {
				MapActivity mapActivity = getMapActivity();
				if (mapActivity == null) {
					app.showShortToastMessage(R.string.flockfree_cyd_status_map_unavailable);
					setupWifiScanStatusPreference();
					return false;
				}
				if (!ensureWifiScanLocationReady(mapActivity)) {
					setupWifiScanStatusPreference();
					return false;
				}
			}
			boolean accepted = super.onPreferenceChange(preference, newValue);
			if (accepted) {
				if (Boolean.TRUE.equals(newValue)) {
					if (!plugin.ensureWifiScanIfEnabled()) {
						plugin.WIFI_SCAN_ENABLED.set(false);
						app.showShortToastMessage(R.string.flockfree_wifi_scan_start_failed);
					}
				} else {
					plugin.getWifiScannerManager().stop();
				}
				setupWifiScanStatusPreference();
			}
			return accepted;
		}
		if (plugin.TOMTOM_API_KEY.getId().equals(preference.getKey())) {
			String sanitized = newValue != null ? newValue.toString().trim() : "";
			boolean accepted = super.onPreferenceChange(preference, sanitized);
			if (accepted) {
				setupTomTomApiKeyPreference();
			}
			return accepted;
		}
		if (plugin.NAVIGATION_TILT_ANGLE.getId().equals(preference.getKey())) {
			float angle;
			try {
				angle = Float.parseFloat(newValue != null ? newValue.toString().trim() : "55");
			} catch (NumberFormatException e) {
				angle = 55f;
			}
			angle = Math.max(FlockFreePlugin.NAVIGATION_TILT_MIN,
					Math.min(angle, FlockFreePlugin.NAVIGATION_TILT_MAX));
			boolean accepted = super.onPreferenceChange(preference, angle);
			if (accepted) {
				setupNavigationTiltAnglePreference();
				notifyTiltAngleChanged();
			}
			return accepted;
		}
		if (plugin.NAVIGATION_TILT_ENABLED.getId().equals(preference.getKey())) {
			boolean accepted = super.onPreferenceChange(preference, newValue);
			if (accepted) {
				notifyTiltEnabledChanged(Boolean.TRUE.equals(newValue));
			}
			return accepted;
		}
		boolean accepted = super.onPreferenceChange(preference, newValue);
		if (!accepted) {
			return false;
		}
		if (plugin.CAMERA_SHOW_LAYER.getId().equals(preference.getKey())
				|| plugin.INCIDENTS_SHOW_LAYER.getId().equals(preference.getKey())) {
			MapActivity mapActivity = getMapActivity();
			if (mapActivity != null) {
				plugin.updateLayers(mapActivity, mapActivity);
				mapActivity.refreshMap();
			}
		}
		return true;
	}

	private void notifyTiltAngleChanged() {
		NavigationTiltController controller = plugin.getNavigationTiltController();
		if (controller != null) {
			controller.onTiltAnglePreferenceChanged();
		}
	}

	private void notifyTiltEnabledChanged(boolean enabled) {
		NavigationTiltController controller = plugin.getNavigationTiltController();
		if (controller != null) {
			if (enabled) {
				controller.applyTilt();
			} else {
				controller.restoreFlat();
			}
		}
	}

	private boolean ensureWifiScanLocationReady(@NonNull MapActivity mapActivity) {
		if (!AndroidUtils.hasPermission(mapActivity, Manifest.permission.ACCESS_FINE_LOCATION)) {
			ActivityCompat.requestPermissions(mapActivity,
					new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
					WIFI_LOCATION_REQUEST_CODE);
			app.showShortToastMessage(R.string.flockfree_wifi_scan_location_permission_requested);
			return false;
		}
		if (!plugin.getWifiScannerManager().isReadyToScan()) {
			app.showShortToastMessage(R.string.flockfree_wifi_scan_location_disabled);
			return false;
		}
		return true;
	}

	@Override
	public boolean onPreferenceClick(Preference preference) {
		String key = preference.getKey();
		if (CAMERA_DATA_REFRESH_KEY.equals(key)) {
			refreshCameraData();
			startDynamicStatusRefresh();
			return true;
		} else if (CAMERA_NEAREST_MAP_CENTER_KEY.equals(key)) {
			plugin.showNearestCameraAtMapCenter(getMapActivity());
			setupNearestCameraLastCheckPreference();
			return true;
		} else if (CYD_CONNECT_KEY.equals(key)) {
			startCydScan();
			return true;
		} else if (ALERT_CHECK_MAP_CENTER_KEY.equals(key)) {
			plugin.checkCameraAlertAtMapCenter(getMapActivity());
			setupAlertLastCheckPreference();
			startDynamicStatusRefresh();
			return true;
		} else if (REPORT_MAP_CENTER_KEY.equals(key)) {
			plugin.getCameraReporter().showAddCameraDialogAtMapCenter(getMapActivity());
			setupReportLastDraftPreference();
			return true;
		} else if (CYD_REQUEST_STATUS_KEY.equals(key)) {
			plugin.getCydHardwareManager().requestStatus();
			setupCydStatusPreference();
			startDynamicStatusRefresh();
			return true;
		} else if (CYD_SIMULATE_DETECTION_KEY.equals(key)) {
			plugin.getCydHardwareManager().simulateDetection(getMapActivity());
			setupCydStatusPreference();
			startDynamicStatusRefresh();
			return true;
		} else if (CYD_CLEAR_DETECTIONS_KEY.equals(key)) {
			plugin.getCydHardwareManager().clearDetections();
			setupCydStatusPreference();
			MapActivity mapActivity = getMapActivity();
			if (mapActivity != null) {
				mapActivity.refreshMap();
			}
			return true;
		} else if (WIFI_SCAN_CLEAR_KEY.equals(key)) {
			plugin.getWifiScannerManager().clearDetections();
			setupWifiScanStatusPreference();
			return true;
		}
		return super.onPreferenceClick(preference);
	}

	private void refreshCameraData() {
		CameraData cameraData = plugin.getCameraData();
		boolean started = cameraData.refreshData();
		app.showShortToastMessage(started
				? R.string.flockfree_camera_data_refresh_requested
				: R.string.flockfree_camera_data_loading_summary);
		setupCameraDataStatusPreference();
	}

	private void startCydScan() {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null) {
			app.showShortToastMessage(R.string.flockfree_cyd_status_map_unavailable);
			return;
		}
		if (!AndroidUtils.requestBLEPermissions(mapActivity)) {
			setupCydStatusPreference();
			startDynamicStatusRefresh();
			return;
		}
		AndroidUtils.requestNotificationPermissionIfNeeded(mapActivity);
		plugin.CYD_BLE_ENABLED.set(true);
		startCydScan(mapActivity);
	}

	private void startCydScan(@NonNull MapActivity mapActivity) {
		CydBleService.start(mapActivity);
		plugin.getCydHardwareManager().startScanAndConnect(mapActivity);
		setupCydStatusPreference();
		startDynamicStatusRefresh();
	}

	private void refreshDynamicStatusPreferences() {
		setupCameraDataStatusPreference();
		setupNearestCameraLastCheckPreference();
		setupRouteLastCheckPreference();
		setupRouteTradeoffSummaryPreference();
		setupTomTomApiKeyPreference();
		setupTrafficRouteLastCheckPreference();
		setupAlertLastCheckPreference();
		setupReportLastDraftPreference();
		setupCydStatusPreference();
		setupNavigationTiltAnglePreference();
		setupWifiScanStatusPreference();
	}

	private void startDynamicStatusRefresh() {
		statusRefreshHandler.removeCallbacks(dynamicStatusRefreshRunnable);
		dynamicStatusRefreshTicks = 0;
		if (shouldKeepDynamicStatusRefreshing()) {
			statusRefreshHandler.postDelayed(dynamicStatusRefreshRunnable, DYNAMIC_STATUS_REFRESH_MS);
		}
	}

	private boolean shouldKeepDynamicStatusRefreshing() {
		if (plugin.getCameraData().isLoading()) {
			return true;
		}
		CydHardwareManager.State state = plugin.getCydHardwareManager().getState();
		boolean cydActive = state == CydHardwareManager.State.SCANNING
				|| state == CydHardwareManager.State.CONNECTING
				|| state == CydHardwareManager.State.READY;
		boolean wifiActive = plugin.getWifiScannerManager().isScanning();
		return cydActive || wifiActive;
	}
}
