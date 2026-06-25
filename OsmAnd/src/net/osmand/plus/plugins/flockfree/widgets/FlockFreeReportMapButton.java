package net.osmand.plus.plugins.flockfree.widgets;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.views.controls.maphudbuttons.MapButton;
import net.osmand.plus.views.mapwidgets.configure.buttons.MapButtonState;

public class FlockFreeReportMapButton extends MapButton {

	public FlockFreeReportMapButton(@NonNull Context context) {
		this(context, null);
	}

	public FlockFreeReportMapButton(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public FlockFreeReportMapButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		setOnClickListener(v -> {
			if (mapActivity != null && !mapActivity.isDestroyed()) {
				ReportBottomSheet.showInstance(mapActivity);
			}
		});
	}

	@Nullable
	@Override
	public MapButtonState getButtonState() {
		return null;
	}

	@Override
	protected boolean shouldShow() {
		if (app == null) return false;
		return app.getRoutingHelper().isFollowingMode()
				&& app.getRoutingHelper().isRouteCalculated()
				&& app.getRoutingHelper().getRoute() != null
				&& app.getRoutingHelper().getRoute().isCalculated();
	}
}