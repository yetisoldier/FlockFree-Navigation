package net.osmand.plus.routepreparationmenu.cards;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin.RouteComparisonInfo;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.utils.OsmAndFormatter;

import java.util.ArrayList;
import java.util.List;

public class FlockFreeRouteComparisonCard extends MapBaseCard {

	private final RouteComparisonInfo comparisonInfo;
	private final boolean privacyActive;

	public interface RouteSelectionListener {
		void onRouteSelected(boolean privacyRoute);
	}

	private RouteSelectionListener selectionListener;

	public FlockFreeRouteComparisonCard(@NonNull MapActivity mapActivity,
	                                    @NonNull RouteComparisonInfo comparisonInfo,
	                                    boolean privacyActive) {
		super(mapActivity);
		this.comparisonInfo = comparisonInfo;
		this.privacyActive = privacyActive;
	}

	public void setSelectionListener(@Nullable RouteSelectionListener listener) {
		this.selectionListener = listener;
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.flockfree_route_comparison_card;
	}

	@Override
	protected void updateContent() {
		View fastestCard = view.findViewById(R.id.flockfree_route_fastest_card);
		View privacyCard = view.findViewById(R.id.flockfree_route_privacy_card);

		setupRouteOption(fastestCard, !privacyActive);
		setupRouteOption(privacyCard, privacyActive);
		setupSummaryBackground(view.findViewById(R.id.flockfree_route_comparison_summary));

		fastestCard.setOnClickListener(v -> {
			if (privacyActive && selectionListener != null) {
				selectionListener.onRouteSelected(false);
			}
		});
		privacyCard.setOnClickListener(v -> {
			if (!privacyActive && selectionListener != null) {
				selectionListener.onRouteSelected(true);
			}
		});

		((TextView) view.findViewById(R.id.flockfree_route_fastest_distance_time))
				.setText(formatDistanceTime(comparisonInfo.fastestDistanceMeters, comparisonInfo.fastestTimeSeconds));
		((TextView) view.findViewById(R.id.flockfree_route_privacy_distance_time))
				.setText(formatDistanceTime(comparisonInfo.privacyDistanceMeters, comparisonInfo.privacyTimeSeconds));

		TextView fastestCameras = view.findViewById(R.id.flockfree_route_fastest_cameras);
		fastestCameras.setText(formatCameraCount(comparisonInfo.fastestCameraCount));
		fastestCameras.setTextColor(ContextCompat.getColor(app, R.color.marker_orange));

		TextView privacyCameras = view.findViewById(R.id.flockfree_route_privacy_cameras);
		privacyCameras.setText(formatCameraCount(comparisonInfo.privacyCameraCount));
		privacyCameras.setTextColor(ContextCompat.getColor(app, R.color.text_color_positive));

		TextView privacyActiveLabel = view.findViewById(R.id.flockfree_route_privacy_active);
		privacyActiveLabel.setVisibility(privacyActive ? View.VISIBLE : View.GONE);
		privacyActiveLabel.setTextColor(ContextCompat.getColor(app, ColorUtilities.getActiveColorId(nightMode)));

		TextView summaryText = view.findViewById(R.id.flockfree_route_comparison_summary_text);
		TextView summaryMeta = view.findViewById(R.id.flockfree_route_comparison_summary_meta);
		int avoidedCameras = comparisonInfo.getAvoidedCameraCount();
		summaryText.setText(formatAvoidedCameras(avoidedCameras));
		summaryText.setTextColor(ContextCompat.getColor(app, ColorUtilities.getActiveColorId(nightMode)));
		summaryMeta.setText(formatTradeoffMeta());
		summaryMeta.setTextColor(ColorUtilities.getSecondaryTextColor(app, nightMode));
	}

	@NonNull
	private String formatDistanceTime(int distanceMeters, int timeSeconds) {
		return app.getString(R.string.flockfree_route_comparison_distance_time,
				OsmAndFormatter.getFormattedDistance(distanceMeters, app),
				OsmAndFormatter.getFormattedDuration(timeSeconds, app));
	}

	@NonNull
	private String formatCameraCount(int cameraCount) {
		return app.getResources().getQuantityString(R.plurals.flockfree_route_comparison_cameras,
				cameraCount, cameraCount);
	}

	@NonNull
	private String formatAvoidedCameras(int avoidedCameras) {
		if (avoidedCameras <= 0) {
			return app.getString(R.string.flockfree_route_comparison_active_route);
		}
		String avoided = app.getResources().getQuantityString(R.plurals.flockfree_route_tradeoff_avoids_cameras,
				avoidedCameras, avoidedCameras);
		int fewerPercent = comparisonInfo.fastestCameraCount > 0
				? Math.round(100f * avoidedCameras / comparisonInfo.fastestCameraCount)
				: 0;
		return avoided + " (" + app.getString(R.string.flockfree_route_comparison_fewer, fewerPercent) + ")";
	}

	@NonNull
	private String formatTradeoffMeta() {
		List<String> parts = new ArrayList<>();
		int timeDeltaSeconds = comparisonInfo.privacyTimeSeconds - comparisonInfo.fastestTimeSeconds;
		if (timeDeltaSeconds > 0) {
			int timeDeltaMinutes = Math.max(1, (timeDeltaSeconds + 59) / 60);
			parts.add(app.getString(R.string.flockfree_route_comparison_time_delta, timeDeltaMinutes));
		}
		int distanceDeltaMeters = comparisonInfo.privacyDistanceMeters - comparisonInfo.fastestDistanceMeters;
		if (distanceDeltaMeters > 0 && comparisonInfo.fastestDistanceMeters > 0) {
			int distanceDeltaPercent = Math.max(1,
					Math.round(100f * distanceDeltaMeters / comparisonInfo.fastestDistanceMeters));
			parts.add(app.getString(R.string.flockfree_route_comparison_distance_delta, distanceDeltaPercent));
		}
		return parts.isEmpty() ? app.getString(R.string.flockfree_route_comparison_active_route)
				: android.text.TextUtils.join(" • ", parts);
	}

	private void setupRouteOption(@NonNull View routeOption, boolean active) {
		GradientDrawable background = new GradientDrawable();
		background.setCornerRadius(AndroidUtils.dpToPx(app, 8));
		background.setColor(ContextCompat.getColor(app, active
				? (nightMode ? R.color.active_color_secondary_dark : R.color.active_color_secondary_light)
				: (nightMode ? R.color.inactive_buttons_and_links_bg_dark : R.color.inactive_buttons_and_links_bg_light)));
		background.setStroke(AndroidUtils.dpToPx(app, active ? 2 : 1), ContextCompat.getColor(app, active
				? ColorUtilities.getActiveColorId(nightMode)
				: R.color.icon_color_default_light));
		routeOption.setBackground(background);
	}

	private void setupSummaryBackground(@NonNull View summary) {
		GradientDrawable background = new GradientDrawable();
		background.setCornerRadius(AndroidUtils.dpToPx(app, 8));
		background.setColor(ContextCompat.getColor(app,
				nightMode ? R.color.active_color_secondary_dark : R.color.active_color_secondary_light));
		summary.setBackground(background);
	}
}
