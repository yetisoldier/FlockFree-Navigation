package net.osmand.plus.plugins.flockfree;

import android.graphics.Color;

import androidx.annotation.NonNull;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.backend.preferences.CommonPreference;

import org.apache.commons.logging.Log;

/**
 * Lazy migration that sets the car profile's custom route line colors to
 * Google Maps–style navigation purple (#9C4DCC day / #B39DDB night)
 * when the FlockFree plugin is enabled and the migration hasn't run yet.
 *
 * <p>This is invoked from {@link BaseRouteLayer#updateCustomColor(boolean)}
 * on the first route render after the plugin becomes active, avoiding any
 * need to edit {@code FlockFreePlugin.java} (which may be modified by other
 * agents simultaneously).</p>
 */
public final class FlockFreeRouteColors {

	private static final Log log = PlatformUtil.getLog(FlockFreeRouteColors.class);

	/** Google Maps active-navigation purple (day). */
	public static final int NAV_PURPLE_DAY = Color.parseColor("#9C4DCC");
	/** Google Maps active-navigation purple (night) — lighter for contrast. */
	public static final int NAV_PURPLE_NIGHT = Color.parseColor("#B39DDB");

	private FlockFreeRouteColors() {
		// Utility class — no instances
	}

	/**
	 * Runs a lazy, one-time migration that sets the car profile's
	 * {@code CUSTOM_ROUTE_COLOR_DAY} and {@code CUSTOM_ROUTE_COLOR_NIGHT}
	 * to the FlockFree navigation purple values.
	 *
	 * <p>Safe to call on every {@code updateCustomColor()} — it short-circuits
	 * once the migration flag is set.</p>
	 *
	 * @param app the OsmandApplication instance
	 */
	public static void apply(@NonNull OsmandApplication app) {
		try {
			OsmandSettings settings = app.getSettings();

			// Retrieve the migration flag registered by FlockFreePlugin
			CommonPreference<Boolean> migrationDone = settings.registerBooleanPreference(
					FlockFreePreferences.ROUTE_COLOR_MIGRATION_DONE,
					FlockFreePreferences.DEFAULT_ROUTE_COLOR_MIGRATION_DONE).makeGlobal().cache();

			if (Boolean.TRUE.equals(migrationDone.get())) {
				return;
			}

			// Only set the car profile defaults — other profiles are left untouched
			ApplicationMode carMode = ApplicationMode.CAR;

			int currentDay = settings.CUSTOM_ROUTE_COLOR_DAY.getModeValue(carMode);
			int currentNight = settings.CUSTOM_ROUTE_COLOR_NIGHT.getModeValue(carMode);

			// OsmAnd's default route color is the first entry in DefaultColors (0xFF2196F3 blue).
			// If the user has already customised the colour to something other than the
			// OsmAnd default, respect their choice and skip migration.
			int osmandDefaultColor = 0xFF2196F3; // DefaultColors.values()[0].getColor()

			if (currentDay == osmandDefaultColor) {
				settings.CUSTOM_ROUTE_COLOR_DAY.setModeValue(carMode, NAV_PURPLE_DAY);
			}
			if (currentNight == osmandDefaultColor) {
				settings.CUSTOM_ROUTE_COLOR_NIGHT.setModeValue(carMode, NAV_PURPLE_NIGHT);
			}

			migrationDone.set(true);
			log.info("FlockFree route colour migration applied: car profile day=#9C4DCC night=#B39DDB");
		} catch (Exception e) {
			log.error("FlockFree route colour migration failed", e);
		}
	}
}