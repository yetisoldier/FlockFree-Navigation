package net.osmand.plus.plugins.flockfree;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.preference.Preference;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.cyd.CydDetectionCandidate;
import net.osmand.plus.plugins.flockfree.cyd.CydHardwareManager;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.preferences.ListPreferenceEx;
import net.osmand.plus.settings.preferences.SwitchPreferenceEx;

public class FlockFreeSettingsFragment extends BaseSettingsFragment {

	private static final long DYNAMIC_STATUS_REFRESH_MS = 1_000L;
	private static final int MAX_DYNAMIC_STATUS_REFRESH_TICKS = 90;
	private static final String CAMERA_DATA_STATUS_KEY = "flockfree_camera_data_status";
	private static final String CAMERA_DATA_REFRESH_KEY = "flockfree_camera_data_refresh";
	private static final String ROUTE_LAST_CHECK_KEY = "flockfree_route_last_check";
	private static final String ALERT_LAST_CHECK_KEY = "flockfree_alert_last_check";
	private static final String REPORT_LAST_DRAFT_KEY = "flockfree_report_last_draft";
	private static final String CYD_STATUS_KEY = "flockfree_cyd_status";
	private static final String CYD_CONNECT_KEY = "flockfree_cyd_connect";
	private static final String CYD_REQUEST_STATUS_KEY = "flockfree_cyd_request_status";
	private static final String CYD_SIMULATE_DETECTION_KEY = "flockfree_cyd_simulate_detection";
	private static final String CYD_CLEAR_DETECTIONS_KEY = "flockfree_cyd_clear_detections";
	private static final Integer[] AVOIDANCE_RADIUS_VALUES = {50, 75, 100, 150, 200, 300, 500};
	private static final Integer[] ALERT_DISTANCE_VALUES = {100, 200, 300, 500, 750, 1000};

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
		setupCameraDataStatusPreference();
		setupSwitchPreference(plugin.CAMERA_AVOIDANCE_ENABLED.getId(),
				R.string.flockfree_camera_avoidance_enabled_description);
		setupDistancePreference(plugin.CAMERA_AVOIDANCE_RADIUS.getId(), AVOIDANCE_RADIUS_VALUES,
				R.string.flockfree_avoidance_radius_description);
		setupRouteLastCheckPreference();
		setupSwitchPreference(plugin.CAMERA_ALERTS_ENABLED.getId(),
				R.string.flockfree_nearby_alerts_enabled_description);
		setupDistancePreference(plugin.CAMERA_ALERT_DISTANCE.getId(), ALERT_DISTANCE_VALUES,
				R.string.flockfree_alert_distance_description);
		setupAlertLastCheckPreference();
		setupReportLastDraftPreference();
		setupSwitchPreference(plugin.CYD_BLE_ENABLED.getId(),
				R.string.flockfree_cyd_ble_description);
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

	@Override
	public boolean onPreferenceChange(Preference preference, Object newValue) {
		boolean accepted = super.onPreferenceChange(preference, newValue);
		if (!accepted) {
			return false;
		}
		if (plugin.CAMERA_SHOW_LAYER.getId().equals(preference.getKey())) {
			MapActivity mapActivity = getMapActivity();
			if (mapActivity != null) {
				plugin.updateLayers(mapActivity, mapActivity);
				mapActivity.refreshMap();
			}
		} else if (plugin.CYD_BLE_ENABLED.getId().equals(preference.getKey())) {
			if (Boolean.TRUE.equals(newValue)) {
				startCydScan();
			} else {
				plugin.getCydHardwareManager().disconnect();
				setupCydStatusPreference();
			}
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
		} else if (CYD_CONNECT_KEY.equals(key)) {
			startCydScan();
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
		plugin.CYD_BLE_ENABLED.set(true);
		plugin.getCydHardwareManager().startScanAndConnect(mapActivity);
		setupCydStatusPreference();
		startDynamicStatusRefresh();
	}

	private void refreshDynamicStatusPreferences() {
		setupCameraDataStatusPreference();
		setupRouteLastCheckPreference();
		setupAlertLastCheckPreference();
		setupReportLastDraftPreference();
		setupCydStatusPreference();
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
		return state == CydHardwareManager.State.SCANNING
				|| state == CydHardwareManager.State.CONNECTING
				|| state == CydHardwareManager.State.READY;
	}
}
