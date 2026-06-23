package net.osmand.plus.plugins.flockfree.widgets;

import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.helpers.DayNightHelper;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.views.OsmandMapTileView;

/**
 * Manages a floating "Add Camera" button (FAB) that appears on the
 * right side of the screen during active navigation only.
 *
 * Tapping the FAB opens the existing CameraReporter dialog directly,
 * letting users quickly add a new ALPR camera at the current map center.
 * The FAB is injected programmatically into the map's root view so it
 * does not require layout XML changes. It shows when navigation is in
 * following mode with a calculated route, and hides otherwise.
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
			updateVisibility();
			updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
		}
	};

	private NavigationReportButton(@NonNull OsmandApplication app) {
		this.app = app;
		this.routingHelper = app.getRoutingHelper();
	}

	/**
	 * Get or create the singleton instance and bind to the given activity.
	 */
	public static void ensureInitialized(@NonNull OsmandApplication app, @NonNull MapActivity activity) {
		if (instance == null) {
			instance = new NavigationReportButton(app);
			instance.start();
		}
		instance.bindToActivity(activity);
	}

	/**
	 * Get or create the singleton instance (without binding to an activity).
	 */
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

		// Remove old FAB if present
		if (fabView != null && fabView.getParent() instanceof ViewGroup) {
			((ViewGroup) fabView.getParent()).removeView(fabView);
		}

		// Find a suitable parent — the root content view
		View root = activity.findViewById(android.R.id.content);
		if (root == null) {
			return;
		}
		ViewGroup rootGroup = root instanceof ViewGroup ? (ViewGroup) root : null;
		if (rootGroup == null) {
			return;
		}

		// Create the FAB ImageView
		fabView = new ImageView(app);
		fabView.setId(R.id.flockfree_report_fab);
		fabView.setImageResource(R.drawable.ic_action_device_camera);
		fabView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		int fabSize = (int) TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP, 48, app.getResources().getDisplayMetrics());
		int margin16 = (int) TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP, 16, app.getResources().getDisplayMetrics());
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(fabSize, fabSize);
		lp.gravity = Gravity.END | Gravity.BOTTOM;
		lp.setMargins(0, 0, margin16, margin16 * 4); // 64dp from bottom (above nav bar)

		fabView.setLayoutParams(lp);
		fabView.setElevation(6f);
		fabView.setVisibility(View.GONE);
		fabView.setOnClickListener(v -> {
			if (mapActivity != null && !mapActivity.isDestroyed()) {
				FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
				if (plugin != null) {
					plugin.getCameraReporter().showAddCameraDialogAtMapCenter(mapActivity);
				}
			}
		});

		rootGroup.addView(fabView);
		updateColors();
	}

	private void updateVisibility() {
		if (fabView == null) {
			reattachToMapActivity();
			if (fabView == null) {
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
		}
	}

	private void show() {
		visible = true;
		updateColors();
		if (fabView != null) {
			fabView.setVisibility(View.VISIBLE);
		}
	}

	private void hide() {
		visible = false;
		if (fabView != null) {
			fabView.setVisibility(View.GONE);
		}
	}

	private void updateColors() {
		if (fabView == null) {
			return;
		}
		boolean nightMode = app.getDaynightHelper().isNightMode(
				app.getSettings().getApplicationMode(), ThemeUsageContext.MAP);

		int bgRes = nightMode
				? R.drawable.flockfree_report_fab_bg_night
				: R.drawable.flockfree_report_fab_bg;
		fabView.setBackgroundResource(bgRes);

		// Tint the icon white
		fabView.setColorFilter(
				app.getResources().getColor(R.color.google_maps_text_primary),
				android.graphics.PorterDuff.Mode.SRC_ATOP);
		// In night mode the icon should still be white for contrast
		if (nightMode) {
			fabView.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_ATOP);
		}
	}

	// --- IRouteInformationListener ---

	@Override
	public void newRouteIsCalculated(boolean newRoute, net.osmand.data.ValueHolder<Boolean> showToast) {
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