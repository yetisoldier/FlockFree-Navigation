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
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.views.OsmandMapTileView;

/**
 * Manages a floating report button (FAB) that appears on the
 * right side of the screen during active navigation only.
 *
 * Tapping the FAB opens a quick report sheet for cameras, hazards, and
 * traffic incidents.
 * The FAB is injected programmatically into the map's root view so it
 * does not require layout XML changes. It shows when navigation is in
 * following mode with a calculated route, and hides otherwise.
 */
public class NavigationReportButton implements IRouteInformationListener {

	private static final long UPDATE_INTERVAL_MS = 1000L;
	private static final long REATTACH_INTERVAL_MS = 2000L;
	private static final int FAB_ICON_PADDING_DP = 10;
	private static final int FAB_FALLBACK_BOTTOM_MARGIN_DP = 112;

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

		ViewGroup parent = findReportButtonParent(activity);
		if (parent == null) {
			return;
		}

		// Create the FAB ImageView
		fabView = new ImageView(app);
		fabView.setId(R.id.flockfree_report_fab);
		fabView.setImageResource(R.drawable.ic_action_device_camera);
		fabView.setContentDescription(app.getString(R.string.flockfree_report_sheet_title));
		fabView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		int iconPadding = dp(FAB_ICON_PADDING_DP);
		fabView.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
		int fabSize = getMapButtonSize();
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(fabSize, fabSize);
		lp.gravity = Gravity.END | Gravity.BOTTOM;
		lp.setMargins(0, 0, getMapButtonMargin(), getFallbackBottomMargin());

		fabView.setLayoutParams(lp);
		fabView.setElevation(6f);
		fabView.setVisibility(View.GONE);
		fabView.setOnClickListener(v -> {
			if (mapActivity != null && !mapActivity.isDestroyed()) {
				ReportBottomSheet.showInstance(mapActivity);
			}
		});

		parent.addView(fabView);
		updateColors();
	}

	@Nullable
	private ViewGroup findReportButtonParent(@NonNull MapActivity activity) {
		View mapHudLayout = activity.findViewById(R.id.map_hud_layout);
		if (mapHudLayout instanceof ViewGroup) {
			return (ViewGroup) mapHudLayout;
		}
		View root = activity.findViewById(android.R.id.content);
		return root instanceof ViewGroup ? (ViewGroup) root : null;
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
		updatePosition();
		updateColors();
		if (fabView != null) {
			fabView.setVisibility(View.VISIBLE);
			fabView.post(this::updatePosition);
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
				? R.drawable.flockfree_report_button_night
				: R.drawable.flockfree_report_button;
		fabView.setBackgroundResource(bgRes);

		fabView.setColorFilter(ColorUtilities.getMapButtonIconColor(app, nightMode),
				android.graphics.PorterDuff.Mode.SRC_ATOP);
	}

	private void updatePosition() {
		if (fabView == null || mapActivity == null) {
			return;
		}
		View parent = fabView.getParent() instanceof View ? (View) fabView.getParent() : null;
		View zoomInButton = findZoomInButton();
		View navBar = mapActivity.findViewById(R.id.flockfree_nav_bar);
		int bottomMargin = getFallbackBottomMargin();
		int endMargin = getMapButtonMargin();
		if (parent != null && zoomInButton != null && zoomInButton.isShown()
				&& parent.getHeight() > 0 && parent.getWidth() > 0
				&& zoomInButton.getHeight() > 0 && zoomInButton.getWidth() > 0) {
			int[] parentLocation = new int[2];
			int[] zoomLocation = new int[2];
			parent.getLocationOnScreen(parentLocation);
			zoomInButton.getLocationOnScreen(zoomLocation);
			int zoomTop = zoomLocation[1] - parentLocation[1];
			int zoomRight = zoomLocation[0] - parentLocation[0] + zoomInButton.getWidth();
			if (zoomTop > 0 && zoomTop < parent.getHeight()) {
				bottomMargin = parent.getHeight() - zoomTop + getMapButtonSpacing();
			}
			if (zoomRight > 0 && zoomRight <= parent.getWidth()) {
				endMargin = Math.max(0, parent.getWidth() - zoomRight);
			}
		} else if (parent != null && navBar != null && navBar.getVisibility() == View.VISIBLE) {
			int[] parentLocation = new int[2];
			int[] navBarLocation = new int[2];
			parent.getLocationOnScreen(parentLocation);
			navBar.getLocationOnScreen(navBarLocation);
			int navBarTop = navBarLocation[1] - parentLocation[1];
			if (parent.getHeight() > 0 && navBarTop > 0 && navBarTop < parent.getHeight()) {
				bottomMargin = parent.getHeight() - navBarTop + getMapButtonSpacing()
						+ getZoomStackOffset();
			}
		}
		ViewGroup.LayoutParams params = fabView.getLayoutParams();
		if (params instanceof FrameLayout.LayoutParams) {
			FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) params;
			int fabSize = getMapButtonSize();
			lp.width = fabSize;
			lp.height = fabSize;
			lp.setMargins(0, 0, endMargin, bottomMargin);
			fabView.setLayoutParams(lp);
		}
	}

	@Nullable
	private View findZoomInButton() {
		if (mapActivity == null) {
			return null;
		}
		View mapHudLayout = mapActivity.findViewById(R.id.map_hud_layout);
		View zoomInButton = mapHudLayout != null ? mapHudLayout.findViewById(R.id.map_zoom_in_button) : null;
		if (isUsableAnchor(zoomInButton)) {
			return zoomInButton;
		}
		zoomInButton = mapActivity.findViewById(R.id.map_zoom_in_button);
		return isUsableAnchor(zoomInButton) ? zoomInButton : null;
	}

	private boolean isUsableAnchor(@Nullable View view) {
		return view != null && view.isShown() && view.getWidth() > 0 && view.getHeight() > 0;
	}

	private int getMapButtonSize() {
		return app.getResources().getDimensionPixelSize(R.dimen.map_button_size);
	}

	private int getMapButtonSpacing() {
		return app.getResources().getDimensionPixelSize(R.dimen.map_button_spacing);
	}

	private int getMapButtonMargin() {
		return app.getResources().getDimensionPixelSize(R.dimen.map_button_margin);
	}

	private int getFallbackBottomMargin() {
		return dp(FAB_FALLBACK_BOTTOM_MARGIN_DP) + getZoomStackOffset();
	}

	private int getZoomStackOffset() {
		return 2 * (getMapButtonSize() + getMapButtonSpacing());
	}

	private int dp(float value) {
		return (int) TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP, value, app.getResources().getDisplayMetrics());
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
		if (listenerRegistered) {
			routingHelper.removeListener(this);
			listenerRegistered = false;
		}
		updateHandler.removeCallbacksAndMessages(null);
		if (fabView != null && fabView.getParent() instanceof ViewGroup) {
			((ViewGroup) fabView.getParent()).removeView(fabView);
		}
		fabView = null;
		mapActivity = null;
		visible = false;
		instance = null;
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
