package net.osmand.plus.plugins.flockfree;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.preference.Preference;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.preferences.ListPreferenceEx;
import net.osmand.plus.settings.preferences.SwitchPreferenceEx;

public class FlockFreeSettingsFragment extends BaseSettingsFragment {

	private static final String CAMERA_DATA_STATUS_KEY = "flockfree_camera_data_status";
	private static final Integer[] AVOIDANCE_RADIUS_VALUES = {50, 75, 100, 150, 200, 300, 500};
	private static final Integer[] ALERT_DISTANCE_VALUES = {100, 200, 300, 500, 750, 1000};

	private final FlockFreePlugin plugin = PluginsHelper.requirePlugin(FlockFreePlugin.class);

	@Override
	protected void setupPreferences() {
		setupSwitchPreference(plugin.CAMERA_SHOW_LAYER.getId(),
				R.string.flockfree_show_cameras_on_map_description);
		setupCameraDataStatusPreference();
		setupSwitchPreference(plugin.CAMERA_AVOIDANCE_ENABLED.getId(),
				R.string.flockfree_camera_avoidance_enabled_description);
		setupDistancePreference(plugin.CAMERA_AVOIDANCE_RADIUS.getId(), AVOIDANCE_RADIUS_VALUES,
				R.string.flockfree_avoidance_radius_description);
		setupDistancePreference(plugin.CAMERA_ALERT_DISTANCE.getId(), ALERT_DISTANCE_VALUES,
				R.string.flockfree_alert_distance_description);
		setupSwitchPreference(plugin.CYD_BLE_ENABLED.getId(),
				R.string.flockfree_cyd_ble_description);
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
				preference.setSummary(getString(R.string.flockfree_camera_data_loaded_summary,
						cameraData.getCameraCount(), cameraData.getSpatialBucketCount()));
			} else if (cameraData.isLoading()) {
				preference.setSummary(R.string.flockfree_camera_data_loading_summary);
			} else {
				preference.setSummary(R.string.flockfree_camera_data_not_loaded_summary);
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
		if (accepted && plugin.CAMERA_SHOW_LAYER.getId().equals(preference.getKey())) {
			MapActivity mapActivity = getMapActivity();
			if (mapActivity != null) {
				plugin.updateLayers(mapActivity, mapActivity);
				mapActivity.refreshMap();
			}
		}
		return accepted;
	}
}
