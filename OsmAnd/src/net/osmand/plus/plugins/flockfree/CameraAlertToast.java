package net.osmand.plus.plugins.flockfree;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;

/**
 * Shows a toast-style overlay for camera proximity alerts.
 *
 * The overlay stays visible until explicitly dismissed (or a safety timeout
 * fires as a fallback). This lets the alert persist until the driver has
 * passed the camera, with the distance text updating live.
 *
 * When the app is in the foreground, the overlay is added to the activity's
 * window (no SYSTEM_ALERT_WINDOW permission needed). If no activity is
 * available, falls back to a standard LENGTH_LONG toast.
 */
public class CameraAlertToast {

	/** Safety fallback so the overlay never gets stuck forever if GPS stops. */
	private static final long SAFETY_TIMEOUT_MS = 120_000L;

	private static final int BACKGROUND_COLOR = 0xEE333333; // 93% opacity dark
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final float TEXT_SIZE_SP = 17f;
	private static final float ICON_SIZE_SP = 22f;
	private static final int PADDING_H_DP = 20;
	private static final int PADDING_V_DP = 16;
	private static final int MARGIN_BOTTOM_DP = 80;
	private static final int MARGIN_SIDE_DP = 24;
	private static final int CORNER_RADIUS_DP = 14;
	private static final int ICON_TEXT_GAP_DP = 10;
	private static final int MAX_WIDTH_DP = 400;

	private final OsmandApplication app;
	private final Handler uiHandler = new Handler(Looper.getMainLooper());
	private View overlayView;
	private TextView textView;
	private WindowManager windowManager;
	private boolean isShowing;
	private Runnable hideRunnable;

	public CameraAlertToast(@NonNull OsmandApplication app) {
		this.app = app;
	}

	/**
	 * Show the overlay with no fixed duration — stays until {@link #dismiss()}
	 * is called (or the safety timeout fires after 2 minutes).
	 */
	public void show(@NonNull String text) {
		uiHandler.post(() -> showInternal(text));
	}

	/**
	 * Update the text of a currently-showing overlay without recreating the view.
	 * No-op if the overlay is not showing.
	 */
	public void updateText(@NonNull String text) {
		uiHandler.post(() -> {
			if (isShowing && textView != null) {
				textView.setText(text);
			}
		});
	}

	/**
	 * @return true if the overlay is currently visible.
	 */
	public boolean isShowing() {
		return isShowing;
	}

	private void showInternal(@NonNull String text) {
		// If already showing, just update the text
		if (isShowing && textView != null) {
			textView.setText(text);
			// Reset the safety timer
			resetSafetyTimer();
			return;
		}

		// Clean up any stale state
		dismissInternal();

		MapActivity activity = getMapActivity();
		if (activity == null) {
			Toast.makeText(app, text, Toast.LENGTH_LONG).show();
			return;
		}

		windowManager = activity.getWindowManager();
		Context ctx = activity;

		int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
		int maxWidthPx = dpToPx(ctx, MAX_WIDTH_DP);
		int overlayWidth = Math.min(screenWidth - dpToPx(ctx, MARGIN_SIDE_DP * 2), maxWidthPx);

		// Root container — horizontal: icon | text
		LinearLayout container = new LinearLayout(ctx);
		container.setOrientation(LinearLayout.HORIZONTAL);
		container.setGravity(Gravity.CENTER_VERTICAL);

		int padH = dpToPx(ctx, PADDING_H_DP);
		int padV = dpToPx(ctx, PADDING_V_DP);
		container.setPadding(padH, padV, padH, padV);

		GradientDrawable background = new GradientDrawable();
		background.setColor(BACKGROUND_COLOR);
		background.setCornerRadius(dpToPx(ctx, CORNER_RADIUS_DP));
		container.setBackground(background);

		// Warning icon
		TextView iconView = new TextView(ctx);
		iconView.setText("\u26A0"); // ⚠ warning sign
		iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ICON_SIZE_SP);
		iconView.setTextColor(0xFFFFA500); // orange warning
		LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		iconParams.rightMargin = dpToPx(ctx, ICON_TEXT_GAP_DP);
		container.addView(iconView, iconParams);

		// Alert text
		textView = new TextView(ctx);
		textView.setText(text);
		textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
		textView.setTextColor(TEXT_COLOR);
		textView.setGravity(Gravity.CENTER_VERTICAL);
		textView.setMaxLines(3);
		textView.setLineSpacing(dpToPx(ctx, 2), 1f);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
				0,
				LinearLayout.LayoutParams.WRAP_CONTENT,
				1f);
		container.addView(textView, textParams);

		WindowManager.LayoutParams params = new WindowManager.LayoutParams(
				overlayWidth,
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
						| WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
						| WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
				PixelFormat.TRANSLUCENT
		);
		params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
		params.x = 0;
		params.y = dpToPx(ctx, MARGIN_BOTTOM_DP);

		overlayView = container;

		try {
			windowManager.addView(overlayView, params);
			isShowing = true;
		} catch (Exception e) {
			Toast.makeText(app, text, Toast.LENGTH_LONG).show();
			textView = null;
			isShowing = false;
			return;
		}

		resetSafetyTimer();
	}

	private void resetSafetyTimer() {
		if (hideRunnable != null) {
			uiHandler.removeCallbacks(hideRunnable);
		}
		hideRunnable = this::dismissInternal;
		uiHandler.postDelayed(hideRunnable, SAFETY_TIMEOUT_MS);
	}

	/**
	 * Dismiss the overlay immediately if it is showing.
	 */
	public void dismiss() {
		uiHandler.post(this::dismissInternal);
	}

	private void dismissInternal() {
		if (hideRunnable != null) {
			uiHandler.removeCallbacks(hideRunnable);
			hideRunnable = null;
		}
		if (overlayView != null && isShowing && windowManager != null) {
			try {
				windowManager.removeView(overlayView);
			} catch (Exception ignored) {
			}
		}
		overlayView = null;
		textView = null;
		isShowing = false;
	}

	@Nullable
	private MapActivity getMapActivity() {
		try {
			if (app.getOsmandMap() != null
					&& app.getOsmandMap().getMapView() != null) {
				return app.getOsmandMap().getMapView().getMapActivity();
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private static int dpToPx(@NonNull Context ctx, int dp) {
		float density = ctx.getResources().getDisplayMetrics().density;
		return Math.round(dp * density);
	}
}