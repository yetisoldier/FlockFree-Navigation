package net.osmand.plus.plugins.flockfree.widgets;

import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.data.ValueHolder;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.helpers.DayNightHelper;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.FlockFreeNavigationAssistant;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.plugins.flockfree.TrafficRoutingHelper;
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.util.Algorithms;

import java.util.Calendar;

/**
 * A Google Maps-style bottom navigation bar shown during active navigation.
 * Displays ETA (green), remaining distance, arrival time, optional traffic
 * condition, and a close button to exit navigation.
 *
 * This is a display-only overlay — it does not modify {@code RouteInfoWidget}
 * or stock OsmAnd navigation logic. It registers as an {@link IRouteInformationListener}
 * on the {@link RoutingHelper} and self-manages its lifecycle.
 */
public class FlockFreeNavigationBar implements IRouteInformationListener {

	private static final long UPDATE_INTERVAL_MS = 1000L;
	private static final long REATTACH_INTERVAL_MS = 2000L;
	private static final float ARRIVAL_PREVIEW_DISTANCE_M = 500f;
	private static final float ARRIVAL_HIDE_DISTANCE_M = 600f;
	private static final float ARRIVAL_ARRIVED_DISTANCE_M = 50f;

	@Nullable
	private static FlockFreeNavigationBar instance;

	private final OsmandApplication app;
	private final RoutingHelper routingHelper;
	private final Handler updateHandler = new Handler(Looper.getMainLooper());

	@Nullable
	private MapActivity mapActivity;
	@Nullable
	private View barView;
	@Nullable
	private TextView etaText;
	@Nullable
	private TextView secondaryText;
	@Nullable
	private LinearLayout trafficContainer;
	@Nullable
	private View trafficDot;
	@Nullable
	private TextView trafficText;
	@Nullable
	private ImageButton closeButton;
	@Nullable
	private View accentBar;
	@Nullable
	private LinearLayout arrivalRow;
	@Nullable
	private TextView arrivalNameText;
	@Nullable
	private TextView arrivalSideText;

	private boolean arrivalRowVisible;
	private boolean arrivalToastShown;

	private boolean registered;
	private boolean visible;
	private boolean listenerRegistered;

	private final Runnable updateRunnable = new Runnable() {
		@Override
		public void run() {
			if (!registered) {
				return;
			}
			if (mapActivity == null || mapActivity.isDestroyed()) {
				reattachToMapActivity();
				updateHandler.postDelayed(this, REATTACH_INTERVAL_MS);
				return;
			}
			if (!isVisible()) {
				updateVisibility();
			}
			if (isVisible()) {
				updateInfo();
			}
			updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
		}
	};

	private FlockFreeNavigationBar(@NonNull OsmandApplication app) {
		this.app = app;
		this.routingHelper = app.getRoutingHelper();
	}

	/**
	 * Get or create the singleton instance. Safe to call multiple times.
	 */
	public static void ensureInitialized(@NonNull OsmandApplication app) {
		if (instance == null) {
			instance = new FlockFreeNavigationBar(app);
			instance.start();
		}
	}

	/**
	 * Start listening to routing events and begin periodic updates.
	 */
	private void start() {
		if (!listenerRegistered) {
			routingHelper.addListener(this);
			listenerRegistered = true;
		}
		registered = true;
		updateHandler.post(updateRunnable);
	}

	/**
	 * Find (or re-find) the MapActivity and bind views.
	 */
	private void reattachToMapActivity() {
		OsmandMapTileView mapView = app.getOsmandMap().getMapView();
		MapActivity activity = mapView != null ? mapView.getMapActivity() : null;
		if (activity == null || activity.isDestroyed()) {
			return;
		}
		bindToActivity(activity);
	}

	/**
	 * Bind to a MapActivity and find the bar views.
	 */
	private void bindToActivity(@NonNull MapActivity activity) {
		this.mapActivity = activity;
		barView = activity.findViewById(R.id.flockfree_nav_bar);
		if (barView == null) {
			return;
		}
		etaText = barView.findViewById(R.id.flockfree_nav_bar_eta);
		secondaryText = barView.findViewById(R.id.flockfree_nav_bar_secondary);
		trafficContainer = barView.findViewById(R.id.flockfree_nav_bar_traffic_container);
		trafficDot = barView.findViewById(R.id.flockfree_nav_bar_traffic_dot);
		trafficText = barView.findViewById(R.id.flockfree_nav_bar_traffic_text);
		closeButton = barView.findViewById(R.id.flockfree_nav_bar_close);
		accentBar = barView.findViewById(R.id.flockfree_nav_bar_accent);
		arrivalRow = barView.findViewById(R.id.flockfree_nav_bar_arrival_row);
		arrivalNameText = barView.findViewById(R.id.flockfree_nav_bar_arrival_name);
		arrivalSideText = barView.findViewById(R.id.flockfree_nav_bar_arrival_side);

		if (closeButton != null) {
			closeButton.setOnClickListener(v -> {
				app.stopNavigation();
				activity.refreshMap();
			});
		}
		updateColors();
	}

	/**
	 * Show or hide the bar based on current routing state.
	 */
	private void updateVisibility() {
		if (barView == null) {
			reattachToMapActivity();
			if (barView == null) {
				return;
			}
		}
		boolean shouldShow = routingHelper.isFollowingMode()
				&& routingHelper.isRouteCalculated()
				&& routingHelper.getRoute() != null
				&& routingHelper.getRoute().isCalculated();
		if (shouldShow && !visible) {
			show();
		} else if (!shouldShow && visible) {
			hide();
		} else if (shouldShow) {
			updateInfo();
			updateColors();
		}
	}

	private void show() {
		visible = true;
		updateColors();
		updateInfo();
		if (barView != null) {
			barView.setVisibility(View.VISIBLE);
		}
	}

	private void hide() {
		visible = false;
		if (barView != null) {
			barView.setVisibility(View.GONE);
		}
	}

	private boolean isVisible() {
		return visible && barView != null && barView.getVisibility() == View.VISIBLE;
	}

	/**
	 * Refresh ETA time, distance, arrival time, and traffic condition.
	 */
	private void updateInfo() {
		if (barView == null || !isVisible()) {
			return;
		}
		RouteCalculationResult route = routingHelper.getRoute();
		if (route == null || !route.isCalculated()) {
			return;
		}
		Location location = app.getLocationProvider().getLastKnownLocation();
		int leftSeconds = location != null ? route.getLeftTime(location) : 0;
		if (leftSeconds <= 0) {
			leftSeconds = Math.max(0, (int) route.getRoutingTime());
		}
		float leftDistanceMeters = routingHelper.getLeftDistance();

		if (etaText != null) {
			String eta = formatEtaShort(leftSeconds);
			etaText.setText(eta);
		}

		if (secondaryText != null) {
			String distance = OsmAndFormatter.getFormattedDistance(leftDistanceMeters, app);
			String arrival = formatArrivalTime(leftSeconds);
			secondaryText.setText(distance + " · " + arrival);
		}

		updateTrafficInfo();
		updateArrivalPreview();
	}

	@NonNull
	private String formatEtaShort(int seconds) {
		if (seconds <= 0) {
			return "0 min";
		}
		int hours = seconds / 3600;
		int minutes = (seconds / 60) % 60;
		if (hours > 0) {
			return hours + " hr " + minutes + " min";
		}
		return Math.max(1, minutes) + " min";
	}

	@NonNull
	private String formatArrivalTime(int leftSeconds) {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.SECOND, leftSeconds);
		return android.text.format.DateFormat.getTimeFormat(app).format(calendar.getTime());
	}

	private void updateTrafficInfo() {
		if (trafficContainer == null || trafficDot == null || trafficText == null) {
			return;
		}
		FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
		if (plugin == null || !plugin.TRAFFIC_ROUTING_ENABLED.get()) {
			trafficContainer.setVisibility(View.GONE);
			return;
		}
		TrafficRoutingHelper helper = plugin.getTrafficRoutingHelper();
		String label = helper.getRouteTrafficSummaryLabel();
		int color = helper.getRouteTrafficSummaryColor();

		if (Algorithms.isEmpty(label)) {
			trafficContainer.setVisibility(View.GONE);
			return;
		}
		trafficContainer.setVisibility(View.VISIBLE);
		trafficText.setText(label);
		trafficDot.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
	}

	private void updateColors() {
		if (barView == null) {
			return;
		}
		boolean nightMode = app.getDaynightHelper().isNightMode(
				app.getSettings().getApplicationMode(), ThemeUsageContext.MAP);

		int bg = nightMode
				? app.getResources().getColor(R.color.google_maps_nav_bar_bg_night)
				: app.getResources().getColor(R.color.google_maps_nav_bar_bg);
		barView.setBackgroundColor(bg);

		int etaColor = nightMode
				? app.getResources().getColor(R.color.google_maps_eta_green_night)
				: app.getResources().getColor(R.color.google_maps_eta_green);
		if (etaText != null) {
			etaText.setTextColor(etaColor);
		}

		int secondaryColor = nightMode
				? app.getResources().getColor(R.color.google_maps_nav_bar_text_secondary_night)
				: app.getResources().getColor(R.color.google_maps_text_secondary);
		if (secondaryText != null) {
			secondaryText.setTextColor(secondaryColor);
		}

		int closeTint = nightMode
				? app.getResources().getColor(R.color.google_maps_nav_bar_text_night)
				: app.getResources().getColor(R.color.google_maps_text_primary);
		if (closeButton != null) {
			closeButton.setColorFilter(closeTint, PorterDuff.Mode.SRC_ATOP);
		}

		if (trafficText != null) {
			trafficText.setTextColor(secondaryColor);
		}

		// Update accent bar color based on traffic condition
		if (accentBar != null) {
			int accentColor = etaColor; // default: green
			FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
			if (plugin != null && plugin.TRAFFIC_ROUTING_ENABLED.get()) {
				TrafficRoutingHelper helper = plugin.getTrafficRoutingHelper();
				String label = helper.getRouteTrafficSummaryLabel();
				if (!Algorithms.isEmpty(label)) {
					accentColor = helper.getRouteTrafficSummaryColor();
				}
			}
			accentBar.setBackgroundColor(accentColor);
		}

		// Update arrival row colors too
		if (arrivalRowVisible) {
			updateArrivalRowColors();
		}
	}

	// --- Arrival Preview ---

	/**
	 * Updates the arrival preview row based on distance to destination.
	 * Shows the row when within {@link #ARRIVAL_PREVIEW_DISTANCE_M} meters,
	 * hides it when beyond {@link #ARRIVAL_HIDE_DISTANCE_M} meters (hysteresis).
	 */
	private void updateArrivalPreview() {
		if (arrivalRow == null) {
			return;
		}
		float distanceMeters = FlockFreeNavigationAssistant.getDistanceToDestination(app);
		if (distanceMeters < 0) {
			hideArrivalRow();
			return;
		}

		boolean shouldShow;
		if (arrivalRowVisible) {
			// Hysteresis: hide only when beyond 600m
			shouldShow = distanceMeters <= ARRIVAL_HIDE_DISTANCE_M;
		} else {
			// Show when within 500m
			shouldShow = distanceMeters <= ARRIVAL_PREVIEW_DISTANCE_M;
		}

		if (shouldShow) {
			showArrivalRow(distanceMeters);
		} else {
			hideArrivalRow();
		}
	}

	private void showArrivalRow(float distanceMeters) {
		if (!arrivalRowVisible) {
			arrivalRowVisible = true;
			arrivalRow.setVisibility(View.VISIBLE);
			arrivalToastShown = false;
		}

		String destName = FlockFreeNavigationAssistant.getDestinationName(app);
		if (destName == null) {
			destName = app.getString(R.string.flockfree_arrival_preview_arrived_generic);
		}

		if (distanceMeters <= ARRIVAL_ARRIVED_DISTANCE_M) {
			// Very close — show "You have arrived"
			if (arrivalNameText != null) {
				arrivalNameText.setText(app.getString(R.string.flockfree_arrival_preview_arrived, destName));
			}
			if (arrivalSideText != null) {
				arrivalSideText.setText("");
				arrivalSideText.setVisibility(View.GONE);
			}
			// Show a one-time toast
			if (!arrivalToastShown) {
				arrivalToastShown = true;
				app.showToastMessage(app.getString(R.string.flockfree_arrival_preview_arrived, destName));
			}
		} else {
			// Approaching — show destination name + side of street
			if (arrivalNameText != null) {
				arrivalNameText.setText(destName);
			}
			String sideText = FlockFreeNavigationAssistant.getDestinationSideOfStreet(app);
			if (arrivalSideText != null) {
				if (sideText != null) {
					arrivalSideText.setText(sideText);
					arrivalSideText.setVisibility(View.VISIBLE);
				} else {
					arrivalSideText.setText("");
					arrivalSideText.setVisibility(View.GONE);
				}
			}
		}

		updateArrivalRowColors();
	}

	private void hideArrivalRow() {
		if (arrivalRowVisible) {
			arrivalRowVisible = false;
			arrivalRow.setVisibility(View.GONE);
			arrivalToastShown = false;
		}
	}

	private void updateArrivalRowColors() {
		boolean nightMode = app.getDaynightHelper().isNightMode(
				app.getSettings().getApplicationMode(), ThemeUsageContext.MAP);
		int nameColor = nightMode
				? app.getResources().getColor(R.color.google_maps_nav_bar_text_night)
				: app.getResources().getColor(R.color.google_maps_text_primary);
		int sideColor = nightMode
				? app.getResources().getColor(R.color.google_maps_nav_bar_text_secondary_night)
				: app.getResources().getColor(R.color.google_maps_text_secondary);
		if (arrivalNameText != null) {
			arrivalNameText.setTextColor(nameColor);
		}
		if (arrivalSideText != null) {
			arrivalSideText.setTextColor(sideColor);
		}
	}

	/**
	 * Detach listeners and stop periodic updates.
	 * Called when the plugin is disabled.
	 */
	public static void unregister() {
		if (instance != null) {
			instance.doUnregister();
		}
	}

	private void doUnregister() {
		registered = false;
		if (listenerRegistered) {
			routingHelper.removeListener(this);
			listenerRegistered = false;
		}
		updateHandler.removeCallbacksAndMessages(null);
		hide();
		mapActivity = null;
		instance = null;
	}

	// --- IRouteInformationListener ---

	@Override
	public void newRouteIsCalculated(boolean newRoute, ValueHolder<Boolean> showToast) {
		updateVisibility();
	}

	@Override
	public void routeWasCancelled() {
		hideArrivalRow();
		hide();
	}

	@Override
	public void routeWasFinished() {
		hideArrivalRow();
		hide();
	}
}