package net.osmand.plus.plugins.flockfree.widgets;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.ValueHolder;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.OsmandMapTileView.ElevationListener;
import net.osmand.plus.views.AnimateDraggingMapThread;

/**
 * Automatically tilts the map to a 3D perspective during active turn-by-turn
 * navigation (following mode), mimicking Google Maps behaviour.
 *
 * <ul>
 *   <li>When a route is calculated and the routing helper enters following
 *       mode, the map animates to the configured tilt angle (default 55°).</li>
 *   <li>When the route is cancelled or finished, the map animates back to
 *       90° (flat / top-down).</li>
 *   <li>If the user manually changes the tilt via the 3D button during
 *       navigation, that choice is respected until the next navigation mode
 *       change.</li>
 *   <li>The tilt is only applied at zoom level ≥ 3 (the OsmAnd minimum for
 *       camera tilt adjustment).</li>
 * </ul>
 *
 * The controller registers as an {@link IRouteInformationListener} on the
 * {@link RoutingHelper} and as an {@link ElevationListener} on the map view
 * to detect manual user tilt changes.
 */
public class NavigationTiltController implements IRouteInformationListener, ElevationListener {

	private static final float FLAT_ELEVATION_ANGLE = 90f;
	private static final int MIN_ZOOM_FOR_TILT = 3;
	private static final float TILT_ANIMATION_TIME_SECONDS = 0.6f;

	@NonNull
	private final OsmandApplication app;
	@NonNull
	private final RoutingHelper routingHelper;
	@NonNull
	private final FlockFreePlugin plugin;
	@NonNull
	private final Handler uiHandler;

	@Nullable
	private OsmandMapTileView mapView;

	private boolean registered;
	private boolean elevationListenerAdded;
	private boolean tiltApplied;
	private boolean userOverrodeTilt;

	public NavigationTiltController(@NonNull OsmandApplication app,
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
		ensureMapViewAttached();
	}

	/**
	 * Detach listeners. Called when the plugin is disabled or the
	 * controlling MapActivity is destroyed.
	 */
	public void unregister() {
		if (registered) {
			routingHelper.removeListener(this);
			registered = false;
		}
		if (elevationListenerAdded && mapView != null) {
			mapView.removeElevationListener(this);
			elevationListenerAdded = false;
		}
		mapView = null;
		tiltApplied = false;
		userOverrodeTilt = false;
	}

	private void ensureMapViewAttached() {
		OsmandMapTileView currentMapView = app.getOsmandMap().getMapView();
		if (currentMapView != mapView) {
			if (elevationListenerAdded && mapView != null) {
				mapView.removeElevationListener(this);
				elevationListenerAdded = false;
			}
			mapView = currentMapView;
			if (mapView != null) {
				mapView.addElevationListener(this);
				elevationListenerAdded = true;
			}
		}
	}

	// ---------- IRouteInformationListener ----------

	@Override
	public void newRouteIsCalculated(boolean newRoute, ValueHolder<Boolean> showToast) {
		if (!isTiltEnabled()) {
			return;
		}
		// When a new route is calculated, reset the user-override flag so
		// the tilt gets applied again for the new navigation session.
		userOverrodeTilt = false;
		if (routingHelper.isFollowingMode()) {
			applyTilt();
		}
	}

	@Override
	public void routeWasCancelled() {
		restoreFlat();
		userOverrodeTilt = false;
	}

	@Override
	public void routeWasFinished() {
		restoreFlat();
		userOverrodeTilt = false;
	}

	// ---------- ElevationListener ----------

	@Override
	public void onElevationChanging(float angle) {
		// Not used — we care about the moment the user finishes changing tilt.
	}

	@Override
	public void onStopChangingElevation(float angle) {
		// If the user manually changes the tilt while we have applied our
		// navigation tilt, mark that they overrode it so we don't fight them.
		if (tiltApplied && !userOverrodeTilt && angle != getDesiredTiltAngle()) {
			userOverrodeTilt = true;
		}
	}

	// ---------- Tilt logic ----------

	private boolean isTiltEnabled() {
		return plugin.NAVIGATION_TILT_ENABLED.get();
	}

	private float getDesiredTiltAngle() {
		float angle = plugin.NAVIGATION_TILT_ANGLE.get();
		// Clamp to valid range
		return Math.max(FlockFreePlugin.NAVIGATION_TILT_MIN,
				Math.min(angle, FlockFreePlugin.NAVIGATION_TILT_MAX));
	}

	/**
	 * Animate the map to the configured navigation tilt angle, respecting
	 * zoom level constraints and user override.
	 */
	public void applyTilt() {
		ensureMapViewAttached();
		if (mapView == null || !isTiltEnabled()) {
			return;
		}
		if (userOverrodeTilt) {
			// User explicitly changed tilt during this nav session — respect it.
			return;
		}
		int zoom = mapView.getZoom();
		if (zoom < MIN_ZOOM_FOR_TILT) {
			return;
		}
		float targetAngle = getDesiredTiltAngle();
		float currentAngle = mapView.getElevationAngle();
		if (Math.abs(currentAngle - targetAngle) < 0.5f) {
			tiltApplied = true;
			return;
		}
		animateTilt(targetAngle);
		tiltApplied = true;
	}

	/**
	 * Animate the map back to flat (90°) when navigation ends.
	 */
	public void restoreFlat() {
		ensureMapViewAttached();
		if (mapView == null) {
			return;
		}
		float currentAngle = mapView.getElevationAngle();
		if (Math.abs(currentAngle - FLAT_ELEVATION_ANGLE) < 0.5f) {
			tiltApplied = false;
			return;
		}
		if (!tiltApplied && !userOverrodeTilt) {
			// We never applied the tilt, so don't force the map flat —
			// the user may have set their own tilt independently.
			tiltApplied = false;
			return;
		}
		animateTilt(FLAT_ELEVATION_ANGLE);
		tiltApplied = false;
	}

	private void animateTilt(float targetAngle) {
		OsmandMapTileView view = mapView;
		if (view == null) {
			return;
		}
		// Use the animated dragging thread for smooth tilt transitions.
		AnimateDraggingMapThread thread = view.getAnimatedDraggingThread();
		if (thread != null) {
			uiHandler.post(() -> {
				// Guard: view may have been detached between calls.
				OsmandMapTileView v = app.getOsmandMap().getMapView();
				if (v == null) {
					return;
				}
				v.getAnimatedDraggingThread()
						.startTilting(targetAngle, TILT_ANIMATION_TIME_SECONDS);
			});
		}
	}

	/**
	 * Called by the plugin when the user changes the tilt angle preference.
	 * If currently navigating with tilt applied, re-apply with the new angle.
	 */
	public void onTiltAnglePreferenceChanged() {
		if (tiltApplied && !userOverrodeTilt && routingHelper.isFollowingMode()) {
			applyTilt();
		}
	}
}