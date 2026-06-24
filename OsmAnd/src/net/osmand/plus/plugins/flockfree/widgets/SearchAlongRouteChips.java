package net.osmand.plus.plugins.flockfree.widgets;

import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.ValueHolder;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.helpers.DayNightHelper;
import net.osmand.plus.plugins.flockfree.FlockFreeNavigationAssistant;
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.views.OsmandMapTileView;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the row of search-along-route chips (Gas, Food, Coffee, Parking, EV Charging)
 * shown above the {@link FlockFreeNavigationBar} during active navigation.
 *
 * <p>Each chip opens {@link FlockFreeNavigationAssistant#openAddStopSearch} with a
 * predefined query so users can quickly find POIs along their route and add them
 * as intermediate stops.</p>
 *
 * <p>The chips view is inflated from {@code R.layout.flockfree_navigation_actions}
 * which is already included in {@code map_hud_bottom.xml}. This class handles
 * day/night theming and self-manages visibility based on routing state.</p>
 */
public class SearchAlongRouteChips implements IRouteInformationListener {

	private static final long UPDATE_INTERVAL_MS = 1000L;
	private static final long REATTACH_INTERVAL_MS = 2000L;

	@Nullable
	private static SearchAlongRouteChips instance;

	private final OsmandApplication app;
	private final RoutingHelper routingHelper;
	private final Handler updateHandler = new Handler(Looper.getMainLooper());

	@Nullable
	private MapActivity mapActivity;
	@Nullable
	private View chipsView;
	@Nullable
	private final List<TextView> chipTextViews = new ArrayList<>();

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
				updateColors();
			}
			updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
		}
	};

	private SearchAlongRouteChips(@NonNull OsmandApplication app) {
		this.app = app;
		this.routingHelper = app.getRoutingHelper();
	}

	/**
	 * Get or create the singleton instance. Safe to call multiple times.
	 */
	public static void ensureInitialized(@NonNull OsmandApplication app) {
		if (instance == null) {
			instance = new SearchAlongRouteChips(app);
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
	 * Bind to a MapActivity and find the chip views.
	 */
	private void bindToActivity(@NonNull MapActivity activity) {
		this.mapActivity = activity;
		chipsView = activity.findViewById(R.id.flockfree_navigation_actions);
		if (chipsView == null) {
			return;
		}

		// Collect all chip TextViews for theming
		chipTextViews.clear();
		findChipTextViews(chipsView);

		// Wire click listeners
		setChipClick(R.id.flockfree_stop_gas_chip, "gas station");
		setChipClick(R.id.flockfree_stop_food_chip, "restaurant");
		setChipClick(R.id.flockfree_stop_coffee_chip, "coffee");
		setChipClick(R.id.flockfree_stop_parking_chip, "parking");
		setChipClick(R.id.flockfree_stop_ev_chip, "charging station");

		updateColors();
	}

	/**
	 * Recursively find all TextView children of the chips view.
	 */
	private void findChipTextViews(@NonNull View view) {
		if (view instanceof TextView) {
			chipTextViews.add((TextView) view);
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) {
				findChipTextViews(group.getChildAt(i));
			}
		}
	}

	private void setChipClick(int chipId, @NonNull String query) {
		View chip = chipsView != null ? chipsView.findViewById(chipId) : null;
		if (chip != null) {
			chip.setOnClickListener(v -> {
				if (mapActivity != null && !mapActivity.isDestroyed()) {
					FlockFreeNavigationAssistant.openAddStopSearch(mapActivity, query);
				}
			});
		}
	}

	/**
	 * Show or hide the chips based on current routing state.
	 */
	private void updateVisibility() {
		if (chipsView == null) {
			reattachToMapActivity();
			if (chipsView == null) {
				return;
			}
		}
		boolean shouldShow = shouldShowChips();
		if (shouldShow && !visible) {
			show();
		} else if (!shouldShow && visible) {
			hide();
		}
	}

	/**
	 * Chips are visible only during active navigation (following mode) with a
	 * calculated route — same conditions as {@link FlockFreeNavigationBar}.
	 */
	private boolean shouldShowChips() {
		return routingHelper.isFollowingMode()
				&& routingHelper.isRouteCalculated()
				&& routingHelper.getRoute() != null
				&& routingHelper.getRoute().isCalculated();
	}

	private void show() {
		visible = true;
		updateColors();
		if (chipsView != null) {
			chipsView.setVisibility(View.VISIBLE);
		}
	}

	private void hide() {
		visible = false;
		if (chipsView != null) {
			chipsView.setVisibility(View.GONE);
		}
	}

	private boolean isVisible() {
		return visible && chipsView != null && chipsView.getVisibility() == View.VISIBLE;
	}

	/**
	 * Update chip backgrounds and text colors for day/night mode.
	 */
	private void updateColors() {
		if (chipsView == null) {
			return;
		}
		boolean nightMode = app.getDaynightHelper().isNightMode(
				app.getSettings().getApplicationMode(), ThemeUsageContext.MAP);

		int bgRes = nightMode
				? R.drawable.bg_flockfree_chip_night
				: R.drawable.bg_flockfree_chip;
		int textColor = nightMode
				? app.getResources().getColor(R.color.google_maps_text_primary_night)
				: app.getResources().getColor(R.color.google_maps_text_primary);

		chipsView.setBackgroundResource(android.R.color.transparent);

		for (TextView chip : chipTextViews) {
			chip.setBackgroundResource(bgRes);
			chip.setTextColor(textColor);
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
		hide();
	}

	@Override
	public void routeWasFinished() {
		hide();
	}
}