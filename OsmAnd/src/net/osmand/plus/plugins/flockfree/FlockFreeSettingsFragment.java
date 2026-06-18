package net.osmand.plus.plugins.flockfree;

import androidx.annotation.NonNull;
import androidx.preference.Preference;

import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.preferences.ListPreferenceEx;
import net.osmand.plus.settings.preferences.SwitchPreferenceEx;

public class FlockFreeSettingsFragment extends BaseSettingsFragment {

	private static final Integer[] AVOIDANCE_RADIUS_VALUES = {50, 75, 100, 150, 200, 300, 500};
	private static final Integer[] ALERT_DISTANCE_VALUES = {100, 200, 300, 500, 750, 1000};

	private final FlockFreePlugin plugin = PluginsHelper.requirePlugin(FlockFreePlugin.class);

	@Override
	protected void setupPreferences() {
		setupSwitchPreference(plugin.CAMERA_SHOW_LAYER.getId(),
				"Display ALPR camera markers from dontgetflocked.com.");
		setupSwitchPreference(plugin.CAMERA_AVOIDANCE_ENABLED.getId(),
				"Show route camera summaries when a new route is calculated.");
		setupDistancePreference(plugin.CAMERA_AVOIDANCE_RADIUS.getId(), AVOIDANCE_RADIUS_VALUES,
				"Route corridor radius used for camera checks.");
		setupDistancePreference(plugin.CAMERA_ALERT_DISTANCE.getId(), ALERT_DISTANCE_VALUES,
				"Reserved distance for future nearby-camera alerts.");
		setupSwitchPreference(plugin.CYD_BLE_ENABLED.getId(),
				"Reserved for Can You Detect hardware over Bluetooth Low Energy.");
	}

	private void setupSwitchPreference(@NonNull String prefId, @NonNull String description) {
		SwitchPreferenceEx preference = findPreference(prefId);
		if (preference != null) {
			preference.setDescription(description);
		}
	}

	private void setupDistancePreference(@NonNull String prefId, @NonNull Integer[] values,
	                                     @NonNull String description) {
		ListPreferenceEx preference = findPreference(prefId);
		if (preference != null) {
			preference.setEntries(formatMeters(values));
			preference.setEntryValues(values);
			preference.setDescription(description);
		}
	}

	@NonNull
	private String[] formatMeters(@NonNull Integer[] values) {
		String[] entries = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			entries[i] = values[i] + " meters";
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
