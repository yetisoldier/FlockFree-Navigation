package net.osmand.plus.plugins.flockfree.widgets;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.ValueHolder;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.plugins.srtm.SRTMPlugin;
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.NextDirectionInfo;
import net.osmand.plus.routing.RoutingHelper;

/**
 * Temporarily hides 3D buildings when approaching a turn so they don't
 * block the driver's view of the route — similar to Google Maps behaviour.
 *
 * <ul>
 *   <li>Registers as an {@link IRouteInformationListener} on the
 *       {@link RoutingHelper}.</li>
 *   <li>When a route is calculated and the routing helper is in following
 *       mode, starts a periodic check (every 2 seconds).</li>
 *   <li>When the distance to the next turn drops below 300 m AND 3D
 *       buildings are currently enabled, buildings are toggled off.</li>
 *   <li>When the distance rises above 500 m (hysteresis), the original
 *       user preference is restored.</li>
 *   <li>On route cancel / finish, the original preference is restored
 *       and monitoring stops.</li>
 * </ul>
 *
 * Hysteresis (300 m off / 500 m on) prevents rapid flickering when the
 * distance hovers near the threshold.
 */
public class BuildingTransparencyController implements IRouteInformationListener {

	private static final int CHECK_INTERVAL_MS = 2000;
	private static final int TURN_APPROACH_THRESHOLD_M = 300;
	private static final int TURN_CLEAR_THRESHOLD_M = 500;

	@NonNull
	private final OsmandApplication app;
	@NonNull
	private final RoutingHelper routingHelper;
	@NonNull
	private final FlockFreePlugin plugin;
	@NonNull
	private final Handler uiHandler;

	private boolean registered;
	private boolean monitoring;
	private boolean buildingsHidden;

	@Nullable
	private Boolean original3DObjectsPreference;

	public BuildingTransparencyController(@NonNull OsmandApplication app,
	                                     @NonNull FlockFreePlugin plugin) {
		this.app = app;
		this.plugin = plugin;
		this.routingHelper = app.getRoutingHelper();
		this.uiHandler = new Handler(Looper.getMainLooper());
	}

	/**
	 * Register as a route information listener on the routing helper.
	 * Safe to call multiple times — duplicate registration is guarded.
	 */
	public void register() {
		if (!registered) {
			routingHelper.addListener(this);
			registered = true;
		}
	}

	/**
	 * Detach listeners, restore buildings, and stop monitoring.
	 * Called when the plugin is disabled or the controlling
	 * MapActivity is destroyed.
	 */
	public void unregister() {
		stopMonitoring();
		if (registered) {
			routingHelper.removeListener(this);
			registered = false;
		}
	}

	// ---------- IRouteInformationListener ----------

	@Override
	public void newRouteIsCalculated(boolean newRoute, ValueHolder<Boolean> showToast) {
		if (!isFeatureEnabled()) {
			return;
		}
		if (routingHelper.isFollowingMode()) {
			startMonitoring();
		}
	}

	@Override
	public void routeWasCancelled() {
		stopMonitoring();
	}

	@Override
	public void routeWasFinished() {
		stopMonitoring();
	}

	// ---------- Monitoring loop ----------

	private boolean isFeatureEnabled() {
		return plugin.BUILDING_TRANSPARENCY_ENABLED.get();
	}

	@Nullable
	private SRTMPlugin getSRTMPlugin() {
		return PluginsHelper.getEnabledPlugin(SRTMPlugin.class);
	}

	private void startMonitoring() {
		if (monitoring) {
			return;
		}
		monitoring = true;
		buildingsHidden = false;
		original3DObjectsPreference = null;
		uiHandler.post(checkRunnable);
	}

	private void stopMonitoring() {
		if (!monitoring) {
			return;
		}
		monitoring = false;
		uiHandler.removeCallbacks(checkRunnable);
		restoreBuildings();
	}

	private final Runnable checkRunnable = new Runnable() {
		@Override
		public void run() {
			if (!monitoring) {
				return;
			}
			checkDistanceToNextTurn();
			uiHandler.postDelayed(this, CHECK_INTERVAL_MS);
		}
	};

	private void checkDistanceToNextTurn() {
		if (!routingHelper.isFollowingMode()) {
			return;
		}
		SRTMPlugin srtmPlugin = getSRTMPlugin();
		if (srtmPlugin == null) {
			return;
		}

		NextDirectionInfo info = routingHelper.getNextRouteDirectionInfo(
				new NextDirectionInfo(), false);
		if (info == null || info.directionInfo == null) {
			// No upcoming turn — restore buildings if they were hidden.
			restoreBuildings();
			return;
		}

		int distanceToTurn = info.distanceTo;
		boolean buildingsEnabled = srtmPlugin.ENABLE_3D_MAP_OBJECTS.get();

		if (distanceToTurn < TURN_APPROACH_THRESHOLD_M && buildingsEnabled) {
			// Approaching a turn — hide buildings.
			original3DObjectsPreference = Boolean.TRUE;
			srtmPlugin.ENABLE_3D_MAP_OBJECTS.set(false);
			buildingsHidden = true;
		} else if (distanceToTurn > TURN_CLEAR_THRESHOLD_M && buildingsHidden) {
			// Past the turn — restore buildings.
			restoreBuildings();
		}
	}

	private void restoreBuildings() {
		if (!buildingsHidden) {
			return;
		}
		SRTMPlugin srtmPlugin = getSRTMPlugin();
		if (srtmPlugin != null && original3DObjectsPreference != null) {
			srtmPlugin.ENABLE_3D_MAP_OBJECTS.set(original3DObjectsPreference);
		}
		buildingsHidden = false;
		original3DObjectsPreference = null;
	}
}