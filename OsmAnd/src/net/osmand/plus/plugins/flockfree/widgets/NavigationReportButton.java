package net.osmand.plus.plugins.flockfree.widgets;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.views.OsmandMapTileView;

/**
 * Wires up the camera report button that lives in the zoom button column
 * (map_hud_controls.xml). The button is inflated via XML; this class
 * finds it, sets up the click listener, manages visibility based on
 * navigation state, and updates day/night colors.
 */
public class NavigationReportButton implements IRouteInformationListener {

	private static final long UPDATE_INTERVAL_MS = 1000L;
	private static final long REATTACH_INTERVAL_MS = 2000L;

	@Nullable
	private static NavigationReportButton instance;

	private final OsmandApplication app;
	private final RoutingHelper routingHelper;
	private final Handler updateHandler = new Handler(Looper.getMainLooper());

	@Nullable
	private MapActivity mapActivity;
	@Nullable
	private ImageView fabView;
	private boolean visible;
	private boolean listenerRegistered;

	private final Runnable updateRunnable = new Runnable() {
		@Override
		public void run() {
			if (!listenerRegistered) {
				return;
			}
			if (mapActivity == null || mapActivity.isDestroyed()) {
				reattachToMapActivity();
				updateHandler.postDelayed(this, REATTACH_INTERVAL_MS);
				return;
			}
			if (fabView == null) {
				findFabView();
			}
			updateVisibility();
			updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
		}
	};

	private NavigationReportButton(@NonNull OsmandApplication app) {
		this.app = app;
		this.routingHelper = app.getRoutingHelper();
	}

	public static void ensureInitialized(@NonNull OsmandApplication app, @NonNull MapActivity activity) {
		if (instance == null) {
			instance = new NavigationReportButton(app);
			instance.start();
		}
		instance.bindToActivity(activity);
	}

	public static void ensureInitialized(@NonNull OsmandApplication app) {
		if (instance == null) {
			instance = new NavigationReportButton(app);
			instance.start();
		}
	}

	private void start() {
		if (!listenerRegistered) {
			routingHelper.addListener(this);
			listenerRegistered = true;
		}
		updateHandler.post(updateRunnable);
	}

	private void reattachToMapActivity() {
		OsmandMapTileView mapView = app.getOsmandMap().getMapView();
		MapActivity activity = mapView != null ? mapView.getMapActivity() : null;
		if (activity == null || activity.isDestroyed()) {
			return;
		}
		bindToActivity(activity);
	}

	private void bindToActivity(@NonNull MapActivity activity) {
		this.mapActivity = activity;
		findFabView();
	}

	private void findFabView() {
		if (mapActivity == null) return;
		View v = mapActivity.findViewById(R.id.flockfree_report_fab);
		if (v instanceof ImageView) {
			fabView = (ImageView) v;
			fabView.setVisibility(View.GONE);
			fabView.setOnClickListener(button -> {
				if (mapActivity != null && !mapActivity.isDestroyed()) {
					ReportBottomSheet.showInstance(mapActivity);
				}
			});
			updateColors();
		}
	}

	private void updateVisibility() {
		if (fabView == null) {
			return;
		}
		boolean shouldShow = routingHelper.isFollowingMode()
				&& routingHelper.isRouteCalculated()
				&& routingHelper.getRoute() != null
				&& routingHelper.getRoute().isCalculated();
		if (shouldShow && !visible) {
			visible = true;
			updateColors();
			fabView.setVisibility(View.VISIBLE);
		} else if (!shouldShow && visible) {
			visible = false;
			fabView.setVisibility(View.GONE);
		}
	}

	private void updateColors() {
		if (fabView == null) return;
		boolean nightMode = app.getDaynightHelper().isNightMode(
				app.getSettings().getApplicationMode(), ThemeUsageContext.MAP);
		int bgRes = nightMode
				? R.drawable.flockfree_report_button_night
				: R.drawable.flockfree_report_button;
		fabView.setBackgroundResource(bgRes);
		fabView.setImageResource(R.drawable.ic_action_device_camera);
		fabView.setColorFilter(ColorUtilities.getMapButtonIconColor(app, nightMode),
				android.graphics.PorterDuff.Mode.SRC_ATOP);
	}

	public static void unregister() {
		if (instance != null) {
			instance.doUnregister();
		}
	}

	private void doUnregister() {
		if (listenerRegistered) {
			routingHelper.removeListener(this);
			listenerRegistered = false;
		}
		updateHandler.removeCallbacksAndMessages(null);
		fabView = null;
		mapActivity = null;
		visible = false;
		instance = null;
	}

	@Override
	public void newRouteIsCalculated(boolean newRoute, net.osmand.data.ValueHolder<Boolean> showToast) {
		updateVisibility();
	}

	@Override
	public void routeWasCancelled() {
		if (fabView != null) fabView.setVisibility(View.GONE);
		visible = false;
	}

	@Override
	public void routeWasFinished() {
		if (fabView != null) fabView.setVisibility(View.GONE);
		visible = false;
	}
}